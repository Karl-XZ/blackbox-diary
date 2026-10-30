package com.blackbox.diary

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blackbox.diary.core.*
import com.blackbox.diary.inference.*
import com.blackbox.diary.security.ChunkStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class NativeIntegrationTest {
    private var foreground:DeviceActivity?=null
    private val g get()=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
    private val day="2025-01-16"
    private val hiddenModels=mutableListOf<File>()
    @Before fun cleanPriorSyntheticData()=runBlocking {
        check(BuildConfig.FAULT_TESTING) {"Synthetic integration tests must use the disposable application"}
        if(android.os.Build.MANUFACTURER.equals("vivo",true)) {
            foreground=DeviceActivity.launch()
            foreground!!.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        }
        g.deletion.deleteDays(setOf(day,"2025-01-17","2025-03-01","2025-03-02","2025-05-11"))
        // These cases deliberately verify the deterministic fallback; Spark has its own device suite.
        g.ai.clearCache()
        listOf(g.models.sparkFile,g.models.smeFile).filter {it.exists()}.forEach {model->check(model.renameTo(File(model.parentFile,model.name+".fallback-test")));hiddenModels+=model}
    }
    @After fun releaseForeground(){hiddenModels.forEach {model->check(File(model.parentFile,model.name+".fallback-test").renameTo(model))};hiddenModels.clear();foreground?.close();foreground=null}
    @Test fun mUpgradePreservesAlreadyCommittedLegacyAsrWithoutDuplicating()=runBlocking {
        val writer=ChunkStore(g).create(null);val wav=InstrumentationRegistry.getInstrumentation().context.assets.open("zh.wav").use {it.readBytes()}
        val bytes=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);bytes.position(12);var pcm=ByteArray(0)
        while(bytes.remaining()>=8){val label=ByteArray(4);bytes.get(label);val n=bytes.int;if(String(label)=="data"){pcm=ByteArray(n);bytes.get(pcm);break};bytes.position(bytes.position()+n+n%2)}
        var at=0;while(at<pcm.size){val end=minOf(at+32000,pcm.size);writer.append(pcm.copyOfRange(at,end),System.currentTimeMillis(),android.os.SystemClock.elapsedRealtimeNanos());at=end};writer.seal();pcm.fill(0)
        val now=System.currentTimeMillis();val event=Event(g.store.id(),java.time.LocalDate.now().toString(),Source.AUDIO,"旧版已经提交的语音转写",now,now,"Asia/Shanghai")
        assertTrue(g.store.commitEvent(event,g.store.epoch,"asr:${writer.id}",writer.id))
        g.pipeline.process()
        assertEquals("1",g.store.read {scalar("SELECT count(*) FROM source_event WHERE origin_chunk_id=?",writer.id)})
        assertEquals(event.text,g.store.read {events(event.day).single {it.id==event.id}.text})
        g.deletion.deleteEvents(setOf(event.id));g.deletion.progress.value=null
    }
    @Test fun aEncryptedDatabaseAndFtsSupportRealChineseSearch()=runBlocking {
        val text=TextRules.sanitize("今天去了公园散步，电话 13812345678")
        val e=Event(g.store.id(),day,Source.USER_NOTE,text,100,200,"Asia/Shanghai",Quality.HIGH)
        assertTrue(g.store.commitEvent(e,g.store.epoch))
        assertEquals(e.id,g.store.read {search("公园 散步",from=day,to=day,source="USER_NOTE").first().sourceId})
        assertTrue(g.store.read {search("散步",favorites=true)}.isEmpty())
        g.store.label(day,true,"周末,散步")
        assertEquals(e.id,g.store.read {search("散步",from=day,to=day,source="USER_NOTE",favorites=true,tag="周末").single().sourceId})
        g.store.setFavorite(day,false);assertEquals("周末,散步",g.store.read {scalar("SELECT tags FROM day_label WHERE day=?",day)})
        assertTrue(g.store.read {search("13812345678")}.isEmpty())
        val dbFile=File(g.vault.root,"diary-${g.store.descriptor.getLong("generation")}.db")
        assertFalse(dbFile.inputStream().use {input->val header=ByteArray(16);input.read(header);String(header)}.startsWith("SQLite format"))
        g.deletion.deleteDays(setOf(day));assertTrue(g.store.read {search("公园")}.isEmpty())
    }
    @Test fun bGenerationPreservesUserEditsAndUsesCandidates()=runBlocking {
        val id=g.store.id();val event=Event(id,day,Source.USER_NOTE,"下午在书店读了半本书",100,200,"Asia/Shanghai",Quality.HIGH)
        g.store.commitEvent(event,g.store.epoch)
        g.pipeline.generate(day)
        val original=g.store.read {diary(day)}!!;assertTrue(original.blocks.isNotEmpty());assertTrue(original.fallback)
        val edited=g.store.publish(day,"我的标题",original.blocks.map {it.copy(text="我修改过这句话",origin="USER_EDITED_AI")},"USER",original.revision,g.store.epoch,true)!!
        g.pipeline.generate(day)
        assertEquals(edited.id,g.store.read {diary(day)}!!.id)
        assertNotNull(g.store.read {diary(day,"CANDIDATE")})
        g.deletion.deleteDays(setOf(day));assertNull(g.store.read {diary(day)})
    }
    @Test fun cDeletionInvalidatesCrossDayVersionsWithoutDeletingIndependentNotes()=runBlocking {
        val other="2025-01-17";val root=Event(g.store.id(),day,Source.USER_NOTE,"被删除的来源文字",1,2,"Asia/Shanghai",Quality.HIGH)
        val own=Event(g.store.id(),other,Source.USER_NOTE,"仍然保留的独立手记",3,4,"Asia/Shanghai",Quality.HIGH)
        g.store.commitEvent(root,g.store.epoch);g.store.commitEvent(own,g.store.epoch)
        g.store.publish(other,"跨日引用",listOf(DiaryBlock("ignored","被删除内容与独立手记混合","USER_EDITED_AI",listOf(root.id,own.id))),"USER",null,g.store.epoch,true)
        val oldEpoch=g.store.epoch;val oldGen=g.store.descriptor.getLong("generation")
        g.deletion.deleteDays(setOf(day))
        assertTrue(g.store.epoch>oldEpoch);assertFalse(g.vault.exists("db",oldGen))
        assertEquals(CaptureState.OFF,g.capture.audio.value.state);assertEquals(CaptureState.OFF,g.capture.screen.value.state)
        assertNull(g.store.read {diary(other)});assertEquals(own.id,g.store.read {events(other).single().id})
        assertFalse(g.store.commitEvent(root.copy(id=g.store.id()),oldEpoch))
        assertTrue(g.store.read {search("被删除")}.isEmpty());assertTrue(g.store.read {rows("PRAGMA foreign_key_check")}.isEmpty())
        g.deletion.deleteDays(setOf(other))
    }
    @Test fun dEncryptedChunkSurvivesTruncatedTailAndCacheKeyRotation() {
        val chunks=ChunkStore(g);val writer=chunks.create(null);val id=writer.id
        repeat(3) {writer.append(ByteArray(32000){42},System.currentTimeMillis(),android.os.SystemClock.elapsedRealtimeNanos())};writer.seal()
        assertEquals(3,chunks.read(id){assertEquals(42,it.pcm[0].toInt())})
        val old=g.store.read {scalar("SELECT cache_generation FROM app_meta").toLong()}
        chunks.purge(setOf(id));chunks.retireOldKeys();assertFalse(g.vault.exists("cache",old));assertFalse(File(chunks.directory,"$id.bbd").exists())
    }
    @Test fun eActualOfflineOcrRecognizesSyntheticChineseBitmap() {
        val bitmap=Bitmap.createBitmap(720,360,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=48f;typeface=Typeface.DEFAULT}
        canvas.drawText("今天去公园散步",40f,100f,paint);canvas.drawText("BlackBox diary 2026",40f,210f,paint)
        try {val result=OfflineOcr(g.models).use {it.recognize(bitmap)};assertTrue("OCR=$result",result.text.contains("今天"));assertTrue(result.flags.contains("UNCALIBRATED"))}finally {bitmap.recycle()}
    }
    @Test fun fActualOfflineAsrDecodesPublicSampleAndKeepsSilenceEmpty() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val bytes=context.assets.open("zh.wav").use {it.readBytes()};val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes,0,4)=="RIFF");buffer.position(12);var samples=FloatArray(0)
        while(buffer.remaining()>=8) {val label=ByteArray(4);buffer.get(label);val size=buffer.int;if(String(label)=="data") {samples=FloatArray(size/2){buffer.short/32768f};break}else buffer.position(buffer.position()+size+(size%2))}
        val asr=LocalAsr(g.models)
        try {val result=asr.transcribe(samples);assertTrue("ASR=$result",result.text.any {it in '\u4e00'..'\u9fff'});assertEquals("",asr.transcribe(FloatArray(16000)).text)} finally {asr.close();samples.fill(0f)}
    }
    @Test fun gPackageHasNoNetworkingOrBackupPermission() {
        val p=g.app.packageManager.getPackageInfo(g.app.packageName,android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_PERMISSIONS.toLong()))
        assertFalse(p.requestedPermissions.orEmpty().contains("android.permission.INTERNET"));assertEquals(0,p.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
    @Test fun hExportUsesCurrentRevisionAndPinLockBlocksReads()=runBlocking {
        g.lock.configure("592618")
        val event=Event(g.store.id(),day,Source.USER_NOTE,"公开的导出验证片段",1,2,"Asia/Shanghai",Quality.HIGH)
        g.store.commitEvent(event,g.store.epoch);g.pipeline.generate(day)
        val file=File(g.app.getExternalFilesDir(null),"export-test.md")
        com.blackbox.diary.export.DiaryExporter.write(g,day,android.net.Uri.fromFile(file),true)
        val text=file.readText();assertTrue(text.startsWith("# "));assertTrue(text.contains("公开的导出验证片段"));assertFalse(text.contains(event.id));assertTrue(text.contains("基础模板"))
        com.blackbox.diary.export.DiaryExporter.write(g,day,android.net.Uri.fromFile(file),false)
        assertFalse(file.readText().startsWith("# "));assertTrue(file.readText().contains("公开的导出验证片段"))
        g.lock.lock();var blocked=false
        try {com.blackbox.diary.export.DiaryExporter.write(g,day,android.net.Uri.fromFile(file),true)}catch(_:IllegalStateException){blocked=true}
        assertTrue(blocked);assertTrue(g.lock.verify("592618"));assertFalse(g.lock.verify("000000"));file.delete();g.deletion.deleteDays(setOf(day))
    }
    @Test fun iTravelDateCoverageUsesRecordedZoneSegmentsAndDeletionSplitsSurvivors()=runBlocking {
        val start=java.time.Instant.parse("2025-03-01T00:00:00Z").toEpochMilli();val hour=3600000L
        g.store.transaction {
            val intent=id();exec("INSERT INTO capture_intent VALUES(?,'MICROPHONE',?,?,'USER')",intent,start,start+36*hour)
            exec("INSERT INTO capture_zone VALUES(?,?,?,?,?)",id(),intent,start,start+12*hour,"Asia/Shanghai")
            exec("INSERT INTO capture_zone VALUES(?,?,?,?,?)",id(),intent,start+12*hour,start+36*hour,"America/Los_Angeles")
            ensureDay("2025-03-01","Asia/Shanghai");ensureDay("2025-03-02","America/Los_Angeles")
        }
        assertEquals(32*hour,g.capture.coverage(Channel.MICROPHONE,"2025-03-01").expectedMs)
        assertEquals(4*hour,g.capture.coverage(Channel.MICROPHONE,"2025-03-02").expectedMs)
        g.deletion.deleteDays(setOf("2025-03-01"))
        assertEquals(4*hour,g.capture.coverage(Channel.MICROPHONE,"2025-03-02").expectedMs)
        assertTrue(g.store.read {rows("PRAGMA foreign_key_check")}.isEmpty());g.deletion.deleteDays(setOf("2025-03-02"))
    }
    @Test fun jDelayedGenerationKeepsItsOriginalTargetDay()=runBlocking {
        val target="2025-05-11";val id=g.store.id();val epoch=g.store.epoch
        g.store.commitEvent(Event(g.store.id(),target,Source.USER_NOTE,"这条手记属于原定日期",1,2,"Asia/Shanghai",Quality.HIGH),epoch)
        g.store.transaction {exec("INSERT INTO processing_job(id,kind,expected_epoch,input_fingerprint,pipeline_version,state,next_due_utc_ms) VALUES(?,'GENERATE_DAY',?,?,'1.1','QUEUED',?)",id,epoch,TextRules.sha256(id),System.currentTimeMillis());setting("generation_target_$id",target)}
        val request=androidx.work.OneTimeWorkRequestBuilder<com.blackbox.diary.processing.DiaryWorker>().setInputData(androidx.work.workDataOf("job_id" to id)).build()
        androidx.work.WorkManager.getInstance(g.app).enqueue(request)
        withTimeout(60000){while(g.store.read {scalar("SELECT state FROM processing_job WHERE id=?",id)} !in listOf("SUCCEEDED","FAILED_FINAL","STALE"))delay(100)}
        assertEquals("SUCCEEDED",g.store.read {scalar("SELECT state FROM processing_job WHERE id=?",id)})
        assertNotNull(g.store.read {diary(target)});assertNull(g.store.read {setting("generation_target_$id")});g.deletion.deleteDays(setOf(target))
    }
    @Test fun kExpiredAudioIsOnlyReportedClearedAfterKeyRetirement()=runBlocking {
        g.pipeline.mutex.withLock {
            val chunks=ChunkStore(g);val writer=chunks.create(null);val id=writer.id
            writer.append(ByteArray(32000),System.currentTimeMillis(),android.os.SystemClock.elapsedRealtimeNanos());writer.seal()
            val generation=g.store.read {scalar("SELECT key_generation FROM media_chunk WHERE id=?",id).toLong()}
            g.store.transaction {exec("UPDATE media_chunk SET expires_utc_ms=first_utc_ms+1 WHERE id=?",id)}
            delay(10);chunks.sweep()
            assertFalse(g.vault.exists("cache",generation));assertNull(g.store.read {blob("SELECT wrapped_dek FROM media_chunk WHERE id=?",id)})
            assertEquals("EXPIRED_UNPROCESSED",g.store.read {scalar("SELECT state FROM media_chunk WHERE id=?",id)})
            assertEquals("EXPIRED",g.store.read {scalar("SELECT state FROM recognition_work WHERE id=?",id)})
            assertFalse(File(chunks.directory,"$id.bbd").exists())
            assertEquals("1",g.store.read {scalar("SELECT count(*) FROM source_event WHERE origin_key=?","expiry:$id")})
        }
    }
    @Test fun lSourceDeletionClosesCollectionIntentAndLeavesChannelsOff()=runBlocking {
        val root=Event(g.store.id(),day,Source.USER_NOTE,"单条来源删除关闭采集意图",1,2,"Asia/Shanghai",Quality.HIGH)
        g.store.commitEvent(root,g.store.epoch);g.capture.begin(Channel.MICROPHONE)
        assertEquals("1",g.store.read {scalar("SELECT count(*) FROM capture_intent WHERE channel='MICROPHONE' AND end_utc_ms IS NULL")})
        g.deletion.deleteEvents(setOf(root.id))
        assertEquals("0",g.store.read {scalar("SELECT count(*) FROM capture_intent WHERE end_utc_ms IS NULL")})
        assertEquals(CaptureState.OFF,g.capture.audio.value.state);assertEquals(CaptureState.OFF,g.capture.screen.value.state)
    }
}
