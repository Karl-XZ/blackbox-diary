package com.blackbox.diary

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.blackbox.diary.inference.SparkSmeBridge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File

/** Only compares synthetic inference requests. Never configures PIN or writes source fixtures. */
class OwnerRuntimeComparisonTest {
    private var foreground:DeviceActivity?=null
    @Before fun keepForeground(){foreground=DeviceActivity.launch();foreground!!.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
    @After fun leaveForeground(){foreground?.close()}
    @Test fun nativeCancellationClearsCacheAndRecovers():Unit=runBlocking {
        check(InstrumentationRegistry.getArguments().getString("ownerDiagnostic")=="authorized-runtime-comparison")
        val g=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
        g.pipeline.mutex.withLock {
            g.ai.clearCache()
            val path=g.models.smeFile.path
            assertTrue(JSONObject(SparkSmeBridge.generate(path,"只输出JSON：{\"ok\":true}",64)).getBoolean("ok"))
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val thread=Thread {try {SparkSmeBridge.generate(path,"只输出一个JSON对象，包含完整的从1到200的数组 {\"numbers\":[1,2,3,...]}，不要省略任何数字。",512)}catch(t:Throwable){failure.set(t)}}
            thread.start();Thread.sleep(400)
            val start=android.os.SystemClock.elapsedRealtime();SparkSmeBridge.cancel();thread.join(15000)
            assertFalse("Native cancellation did not finish",thread.isAlive)
            assertNotNull("Cancellation request did not interrupt generation",failure.get())
            val cancelMs=android.os.SystemClock.elapsedRealtime()-start
            SparkSmeBridge.release()
            assertFalse(SparkSmeBridge.resident)
            assertTrue(JSONObject(SparkSmeBridge.generate(path,"只输出JSON：{\"ok\":true}",64)).getBoolean("ok"))
            val report=JSONObject().put("cancelMs",cancelMs).put("cancelled",true).put("cacheReleased",true).put("subsequentGenerationValid",true)
            File(g.app.getExternalFilesDir(null),"native-cancellation.json").writeText(report.toString(2))
            SparkSmeBridge.release()
        }
    }
    @Test fun compareSamePromptsWithoutEditingOwnerSources():Unit=runBlocking {
        check(InstrumentationRegistry.getArguments().getString("ownerDiagnostic")=="authorized-runtime-comparison")
        val g=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
        assertTrue(g.models.smeFile.exists());assertTrue(SparkSmeBridge.available)
        val rows=JSONArray()
        val before=g.store.read {rows("SELECT artifact_id,text_sha256 FROM source_event").associate {it[0]!! to it[1]!!}}
        g.pipeline.mutex.withLock {
            val prompts=listOf(
                "只输出 JSON，不要解释：{\"ok\":true}" to "short",
                "你是语音纠错器。主动修正同音错字，不要照抄。原始ASR：地帖晚点了，还没买手机。上下文：有人在等地铁。近音候选：地帖→地铁。根据上下文判断候选合理则修正，保留否定；不能默认说话者是机主。只输出 JSON {\"meaningful\":true,\"corrected_text\":\"纠正后的完整文字\",\"speaker\":\"UNKNOWN\"}。" to "correction",
                "判断这段原始ASR是否有可恢复的语义：啊呃滋滋滋嗯嗯。无其他上下文。杂音、无意义填充语不进入日记。只输出JSON {\"meaningful\":false,\"corrected_text\":\"\"}，不要编造场景。" to "noise"
            )
            val backends=if(InstrumentationRegistry.getArguments().getString("backend")=="sme2-only")listOf("sme2") else listOf("litert","sme2")
            for(backend in backends) {
                g.ai.clearCache()
                for((prompt,name) in prompts) {
                    val start=android.os.SystemClock.elapsedRealtime()
                    val raw=g.ai.json(prompt,g.store.epoch,"INTERPRET",256,backend)
                    val parsed=raw?.let {runCatching {JSONObject(it)}.getOrNull()}
                    val valid=when(name){
                        "short"->parsed?.optBoolean("ok")==true
                        "correction"->parsed?.optBoolean("meaningful")==true&&parsed.optString("corrected_text").contains("地铁")&&parsed.optString("corrected_text").contains("没")&&parsed.optString("speaker")=="UNKNOWN"
                        else->parsed?.optBoolean("meaningful",true)==false
                    }
                    val row=JSONObject().put("backend",backend).put("case",name).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start).put("valid",valid).put("issue",g.ai.lastIssue.value?:JSONObject.NULL).put("output",raw?:JSONObject.NULL)
                    if(backend=="sme2"&&SparkSmeBridge.resident)row.put("metrics",JSONObject(SparkSmeBridge.metrics()))
                    rows.put(row)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("runtime_case",row.toString())})
                }
            }
            g.ai.clearCache()
        }
        val after=g.store.read {rows("SELECT artifact_id,text_sha256 FROM source_event").associate {it[0]!! to it[1]!!}}
        val preserved=before.all {(id,hash)->after[id]==hash}
        val report=JSONObject().put("device",android.os.Build.MODEL).put("cases",rows).put("originalSourcesPreserved",preserved)
        File(g.app.getExternalFilesDir(null),"runtime-comparison.json").writeText(report.toString(2))
        assertTrue(preserved)
        assertTrue("SME2 semantic cases failed",(0 until rows.length()).map {rows.getJSONObject(it)}.filter {it.getString("backend")=="sme2"}.all {it.getBoolean("valid")})
    }
}
