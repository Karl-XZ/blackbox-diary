package com.blackbox.diary.inference

import com.blackbox.diary.AppGraph
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicReference
import java.time.LocalDate

/** One large-model operation at a time, shared with the diary planner. No tools/network. */
@OptIn(ExperimentalApi::class)
class AiRuntime(private val graph:AppGraph) {
    val mutex=Mutex()
    val lastIssue=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun issue(code:String,purpose:String="INTERPRET") {
        require(code.matches(Regex("[A-Z_]+")))
        lastIssue.value=code
        graph.store.transaction {setting("ai_last_issue_$purpose",code)}
    }
    private val active=AtomicReference<Conversation?>(null)
    private val activePurpose=AtomicReference<String?>(null)
    private var idleRelease:Job?=null
    fun cancel(purpose:String?=null){if(purpose==null||activePurpose.get()==purpose){active.get()?.cancelProcess();SparkSmeBridge.cancel()}}
    suspend fun clearCache()=mutex.withLock {idleRelease?.cancel();withContext(Dispatchers.IO){SparkSmeBridge.release()}}
    suspend fun json(prompt:String,expectedEpoch:Long,purpose:String,cap:Int=512,backendOverride:String?=null):String?=mutex.withLock {withContext(Dispatchers.Default){
        require(purpose in setOf("CHAT","INTERPRET","PLAN","SUMMARY")&&cap in 64..1024)
        val sme=backendOverride!="litert"&&(backendOverride=="sme2"||graph.models.useSme())
        idleRelease?.cancel()
        val memory=android.app.ActivityManager.MemoryInfo().also {graph.app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)}
        val power=graph.app.getSystemService(android.os.PowerManager::class.java)
        if(!(if(sme)graph.models.smeFile.exists() else graph.models.sparkFile.exists())){issue("MODEL_MISSING",purpose);return@withContext null}
        if(memory.totalMem<8L*1024*1024*1024||memory.availMem<(if(sme&&SparkSmeBridge.resident)512L*1024*1024 else SparkPlanner.MIN_AVAILABLE_MEMORY_BYTES)){issue("MEMORY_LOW",purpose);return@withContext null}
        val thermal=power.currentThermalStatus
        if(thermal>=(if(sme)android.os.PowerManager.THERMAL_STATUS_SEVERE else android.os.PowerManager.THERMAL_STATUS_MODERATE)){issue("THERMAL_PAUSED",purpose);return@withContext null}
        val day=LocalDate.now().toString();val store=graph.store
        // A full/charge-limited vivo may report isCharging=false while still on USB power.
        val plugged=graph.app.registerReceiver(null,android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0)?:0
        val charging=plugged!=0||graph.app.getSystemService(android.os.BatteryManager::class.java).isCharging
        val callLimit=if(purpose=="CHAT")24 else if(purpose=="PLAN")8 else if(purpose=="SUMMARY")if(charging)96 else 48 else if(charging)96 else 8
        val tokenLimit=if(purpose=="CHAT")12288 else if(purpose=="PLAN")6144 else if(purpose=="SUMMARY")if(charging)65536 else 32768 else if(charging)49152 else 4096
        val allowed=store.transaction {
            if(maintenance||epoch!=expectedEpoch)return@transaction false
            exec("INSERT OR IGNORE INTO ai_budget(day,purpose) VALUES(?,?)",day,purpose)
            val row=rows("SELECT calls,tokens FROM ai_budget WHERE day=? AND purpose=?",day,purpose).single()
            if(row[0]!!.toInt()>=callLimit||row[1]!!.toInt()+cap>tokenLimit)return@transaction false
            exec("UPDATE ai_budget SET calls=calls+1,tokens=tokens+? WHERE day=? AND purpose=?",cap,day,purpose);true
        }
        if(!allowed){issue("BUDGET_OR_STATE_LIMIT",purpose);return@withContext null}
        if(sme) {
            val caller=currentCoroutineContext()[Job]
            val watcher=graph.scope.launch {
                val deadline=android.os.SystemClock.elapsedRealtime()+(if(purpose=="SUMMARY")180_000 else 120_000)
                while(caller?.isActive==true&&android.os.SystemClock.elapsedRealtime()<deadline)delay(100)
                SparkSmeBridge.cancel()
            }
            activePurpose.set(purpose);lastIssue.value=null
            try {
                SparkSmeBridge.setThreads(if(thermal>=android.os.PowerManager.THERMAL_STATUS_MODERATE)2 else 4)
                val raw=SparkSmeBridge.generate(graph.models.smeFile.path,prompt,cap)
                currentCoroutineContext().ensureActive()
                val metrics=org.json.JSONObject(SparkSmeBridge.metrics())
                check(metrics.getInt("prompt_tokens")<=2944&&metrics.getBoolean("finished")){"RUNTIME_TOKEN_LIMIT"}
                if(com.blackbox.diary.BuildConfig.FAULT_TESTING)java.io.File(graph.app.getExternalFilesDir(null),"ai-probe-$purpose.json").writeText(org.json.JSONObject().put("result",raw).put("metrics",metrics).toString(2))
                if(store.maintenance||store.epoch!=expectedEpoch)return@withContext null
                store.transaction {exec("DELETE FROM private_setting WHERE name=?","ai_last_issue_$purpose")}
                graph.models.status.value="Spark-X2.5-4B · SME2 CPU · 离线"
                return@withContext raw
            } catch(t:Exception) {
                if(t is CancellationException)throw t
                issue(t.message?.takeIf {it.matches(Regex("[A-Z_]+"))}?:"SME_GENERATION_FAILED",purpose)
                return@withContext null
            } finally {
                watcher.cancel();activePurpose.set(null)
                idleRelease=graph.scope.launch {delay(60_000);mutex.withLock {SparkSmeBridge.release()}}
            }
        }
        var engine:Engine?=null;var conversation:Conversation?=null
        var stage="ENGINE_INITIALIZE"
        try {
            lastIssue.value=null
            ExperimentalFlags.enableBenchmark=true
            engine=Engine(EngineConfig(modelPath=graph.models.sparkFile.path,backend=Backend.CPU(threadCount=2),maxNumTokens=4096,cacheDir=java.io.File(graph.app.codeCacheDir,"litert").apply {mkdirs()}.path));engine.initialize()
            stage="CONVERSATION_INITIALIZE"
            conversation=engine.createConversation(ConversationConfig(systemInstruction=Contents.of(prompt),automaticToolCalling=false,extraContext=mapOf("enable_thinking" to false),prefillPrefaceOnInit=true,maxOutputToken=cap,thinkingConfig=ThinkingConfig(false,0)))
            activePurpose.set(purpose);active.set(conversation);val prefill=conversation.getTokenCount();check(prefill in 1..2880)
            val message=Message.user("Return only the requested JSON now.")
            check(conversation.renderMessageIntoString(message,mapOf("enable_thinking" to false)).trimEnd().endsWith("<|Bot|></think>"))
            if(store.maintenance||store.epoch!=expectedEpoch)return@withContext null
            stage="MODEL_DECODE"
            var text="";val timer=launch {delay(if(purpose=="SUMMARY")180000 else 120000);active.get()?.cancelProcess()}
            try {conversation.sendMessageAsync(message,extraContext=mapOf("enable_thinking" to false),maxOutputToken=cap,thinkingConfig=ThinkingConfig(false,0)).collect {text+=it.toString()}}
            finally {timer.cancel()}
            val stats=conversation.getBenchmarkInfo();check(prefill+stats.lastPrefillTokenCount<=2944&&stats.lastDecodeTokenCount<=cap)
            if(com.blackbox.diary.BuildConfig.FAULT_TESTING)java.io.File(graph.app.getExternalFilesDir(null),"ai-probe-$purpose.json").writeText(org.json.JSONObject().put("result",text).put("inputTokens",prefill+stats.lastPrefillTokenCount).put("outputTokens",stats.lastDecodeTokenCount).toString(2))
            stage="OUTPUT_VALIDATE"
            if(store.maintenance||store.epoch!=expectedEpoch)null else text.trim().also {store.transaction {exec("DELETE FROM private_setting WHERE name=?","ai_last_issue_$purpose")}}
        } catch(t:Exception){if(t is CancellationException)throw t;issue(stage+"_FAILED",purpose);android.util.Log.w("DiaryAi",stage+" "+t.javaClass.simpleName);null}
        finally {active.set(null);activePurpose.set(null);runCatching {conversation?.close()};runCatching {engine?.close()}}
    }}
}
