package com.blackbox.diary
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.blackbox.diary.capture.CaptureController
import com.blackbox.diary.data.DiaryStore
import com.blackbox.diary.security.CryptoVault
import com.blackbox.diary.security.AppLock
import com.blackbox.diary.inference.LocalModels
import com.blackbox.diary.processing.Pipeline
import com.blackbox.diary.processing.Schedule
import com.blackbox.diary.privacy.DeletionCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect

@OptIn(kotlinx.coroutines.FlowPreview::class)
class DiaryApplication:Application() {
    lateinit var graph:AppGraph;private set
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("capture","正在记录",NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility=android.app.Notification.VISIBILITY_PRIVATE })
        graph=AppGraph(this)
        graph.work { graph.pipeline.recover();Schedule.install(this@DiaryApplication,graph.store.preferences().generationTime) }
        graph.work {while(currentCoroutineContext().isActive){delay(60_000);com.blackbox.diary.security.ChunkStore(graph).sweep()}}
        graph.work {graph.store.memoryChanges.debounce(1200).collect {runCatching {graph.memory.sync()}}}
        graph.work {graph.store.changes.debounce(300).collect {graph.assistant.refresh()}}
        graph.work {graph.lock.unlocked.collect {if(!it)graph.assistant.cancel();graph.assistant.refresh()}}
    }
}
class AppGraph(val app:Application) {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val vault=CryptoVault(app)
    val store=DiaryStore(app,vault)
    val lock=AppLock(store)
    val visible=MutableStateFlow(false)
    @Volatile var backgroundAt=0L
    val message=MutableStateFlow<String?>(null)
    val models=LocalModels(app)
    val capture=CaptureController(this)
    val ai=com.blackbox.diary.inference.AiRuntime(this)
    val voice=com.blackbox.diary.inference.Voiceprint(this)
    val memory=com.blackbox.diary.processing.MemoryIndex(this)
    val assistant=com.blackbox.diary.processing.DiaryAssistant(this)
    val interpretations=com.blackbox.diary.processing.AudioInterpreter(this)
    val composer=com.blackbox.diary.processing.DailyComposer(this)
    val pipeline=Pipeline(this)
    val deletion=DeletionCoordinator(this)
    fun work(block:suspend ()->Unit) { scope.launch { try { block() } catch(t:Throwable) { if(t is CancellationException)throw t;message.value=when(t.message) { "EDIT_CONFLICT"->"内容已变化，请重新打开后编辑";"KEY_UNAVAILABLE"->"加密密钥不可用，已停止读取";"MODEL_CHECKSUM","MODEL_SIZE"->"模型文件校验未通过，请选择完整的 Spark-X2.5-4B_int4-bbd1.litertlm 文件";"SOURCE_UNAVAILABLE","VERSION_UNAVAILABLE"->"相关来源或版本已失效，无法恢复";"MAINTENANCE_PENDING"->"正在完成隐私清理，请稍后继续";else->"操作未完成，请稍后重试" } } } }
}
