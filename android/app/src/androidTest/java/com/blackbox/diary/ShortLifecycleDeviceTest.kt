package com.blackbox.diary

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.blackbox.diary.core.*
import com.blackbox.diary.inference.LocalModels
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class ShortLifecycleDeviceTest {
    private val g get()=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
    private fun save(name:String,value:JSONObject){File(g.app.getExternalFilesDir(null),name).writeText(value.toString(2))}
    @Test fun concurrentDeletionIsIdempotent():Unit=runBlocking {
        DeviceActivity.launch().use {
            val day="2024-07-21";g.deletion.deleteDays(setOf(day))
            val event=Event(g.store.id(),day,Source.USER_NOTE,"并发删除合成素材",1,2,"Asia/Shanghai",Quality.HIGH)
            g.store.commitEvent(event,g.store.epoch)
            val before=g.store.read {scalar("SELECT count(*) FROM deletion_operation").toLong()};val generation=g.store.descriptor.getLong("generation")
            coroutineScope {List(10){async(Dispatchers.Default){g.deletion.deleteEvents(setOf(event.id))}}.awaitAll()}
            val operations=g.store.read {scalar("SELECT count(*) FROM deletion_operation").toLong()}-before
            val generations=g.store.descriptor.getLong("generation")-generation
            assertEquals(1L,operations);assertEquals(1L,generations);assertTrue(g.store.read {search("并发删除合成素材")}.isEmpty())
            save("concurrent-delete-probe.json",JSONObject().put("requests",10).put("operationsCreated",operations).put("databaseGenerations",generations).put("passed",true))
            g.deletion.deleteDays(setOf(day))
        }
    }
    @Test fun rejectCorruptModelWithoutReplacingValidImport():Unit=runBlocking {
        DeviceActivity.launch().use {
            val source=File(g.app.getExternalFilesDir(null),"spark-device-source.litertlm");assertEquals(LocalModels.SPARK_BYTES,source.length())
            var original=0;RandomAccessFile(source,"rw").use {original=it.read();it.seek(0);it.write(original xor 1)}
            var rejected=false
            try {g.models.importSpark(Uri.fromFile(source))}catch(t:IllegalArgumentException){rejected=t.message=="MODEL_CHECKSUM"}
            finally {RandomAccessFile(source,"rw").use {it.seek(0);it.write(original)}}
            assertTrue(rejected)
            val hash=MessageDigest.getInstance("SHA-256");g.models.sparkFile.inputStream().use {input->val bytes=ByteArray(1024*1024);while(true){val n=input.read(bytes);if(n<0)break;hash.update(bytes,0,n)}}
            assertEquals(LocalModels.SPARK_SHA,hash.digest().joinToString(""){"%02x".format(it)})
            assertFalse(File(g.models.root,"spark-import.part").exists())
            save("model-import-negative.json",JSONObject().put("corruptSameSizeFileRejected",true).put("installedModelUnchanged",true).put("temporaryFileRemoved",true).put("passed",true))
        }
    }
    @Test fun recordUntilHostStopsProcess():Unit=runBlocking {
        val arguments=InstrumentationRegistry.getArguments();val run=arguments.getString("run")!!;val mode=arguments.getString("mode")!!
        DeviceActivity.launch().use {scene->
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.grantRuntimePermission(g.app.packageName,Manifest.permission.RECORD_AUDIO);automation.grantRuntimePermission(g.app.packageName,Manifest.permission.POST_NOTIFICATIONS)
            scene.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);g.store.savePreferences(g.store.preferences().copy(sleepEnabled=false));g.capture.startAudio()}
            withTimeout(10000){while(g.capture.audio.value.state!=CaptureState.RECORDING)delay(100)};delay(2500)
            val session=g.capture.session(Channel.MICROPHONE)!!
            val sessionCaptured=g.store.read {scalar("SELECT coalesce(sum(valid_ms),0) FROM capture_tick WHERE session_id=?",session).toLong()}
            assertTrue("The live session did not capture valid PCM",sessionCaptured>=1000)
            g.store.transaction {setting("lifecycle_test_session",session);setting("lifecycle_test_mode",mode)}
            save("lifecycle-ready.json",JSONObject().put("run",run).put("mode",mode).put("recording",true).put("capturedMs",sessionCaptured))
            try {delay(60000);fail("Host did not perform the requested lifecycle interruption")}
            finally {g.capture.stop(Channel.MICROPHONE)}
        }
    }
    @Test fun verifyRecoveryAfterHostStop():Unit=runBlocking {
        DeviceActivity.launch().use {
            val session=g.store.read {setting("lifecycle_test_session")!!};val mode=g.store.read {setting("lifecycle_test_mode")!!}
            withTimeout(30000){while(g.store.read {scalar("SELECT state FROM capture_session WHERE id=?",session)}=="RECORDING")delay(100)}
            val state=g.store.read {scalar("SELECT state FROM capture_session WHERE id=?",session)}
            val reason=g.store.read {scalar("SELECT stop_reason FROM capture_session WHERE id=?",session)}
            assertEquals("INTERRUPTED",state);assertEquals("PROCESS_RESTART",reason)
            assertNotEquals(CaptureState.RECORDING,g.capture.audio.value.state)
            if(mode=="permission")assertEquals(PackageManager.PERMISSION_DENIED,g.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertEquals("0",g.store.read {scalar("SELECT count(*) FROM media_chunk WHERE state='WRITING'")})
            save("lifecycle-$mode-recovery.json",JSONObject().put("mode",mode).put("sessionState",state).put("stopReason",reason).put("noAutomaticRecording",true).put("writingChunksRecovered",true).put("passed",true))
        }
    }
}
