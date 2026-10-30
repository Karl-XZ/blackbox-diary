package com.blackbox.diary

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.blackbox.diary.core.*
import com.blackbox.diary.security.ChunkStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Host invokes one case, expects process death, then invokes recover in a fresh process. */
class FaultRecoveryTest {
    private var foreground:DeviceActivity?=null
    @org.junit.Before fun keepPhysicalTestRunnable(){if(android.os.Build.MANUFACTURER.equals("vivo",true)){foreground=DeviceActivity.launch();foreground!!.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}}
    @org.junit.After fun releaseTestScene(){foreground?.close();foreground=null}
    private val g get()=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
    @Test fun exercise()=runBlocking {
        check(BuildConfig.FAULT_TESTING)
        val point=InstrumentationRegistry.getArguments().getString("point")!!
        val day="2024-02-18";val survivorDay="2024-02-19"
        g.deletion.deleteDays(setOf(day,survivorDay))
        File(g.app.noBackupFilesDir,"fault-hit.txt").delete()
        g.store.transaction {setting("fault_expectations",JSONObject().put("point",point).put("old_generation",descriptor.getLong("generation")).toString())}
        if(point.substring(2).toInt()<=6) {
            val chunks=ChunkStore(g);val writer=chunks.create(null)
            val wav=InstrumentationRegistry.getInstrumentation().context.assets.open("zh.wav").use {it.readBytes()}
            val b=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);b.position(12);var pcm=ByteArray(0)
            while(b.remaining()>=8){val label=ByteArray(4);b.get(label);val size=b.int;if(String(label)=="data"){pcm=ByteArray(size);b.get(pcm);break}else b.position(b.position()+size+size%2)}
            g.store.transaction {setting("fault_chunk",writer.id)}
            if(point in listOf("FP01","FP02"))arm(point)
            var index=0;while(index<pcm.size){val n=minOf(32000,pcm.size-index);writer.append(pcm.copyOfRange(index,index+n),System.currentTimeMillis(),android.os.SystemClock.elapsedRealtimeNanos());index+=n};writer.seal();pcm.fill(0)
            if(point in listOf("FP03","FP04")){arm(point);g.pipeline.process()}
            if(point in listOf("FP05","FP06")){arm(point);chunks.purge(setOf(writer.id));chunks.retireOldKeys()}
        } else {
            val root=Event(g.store.id(),day,Source.USER_NOTE,"故障测试禁止词 $point",1,2,"Asia/Shanghai",Quality.HIGH)
            val own=Event(g.store.id(),survivorDay,Source.USER_NOTE,"独立手记保留词",3,4,"Asia/Shanghai",Quality.HIGH)
            g.store.commitEvent(root,g.store.epoch);g.store.commitEvent(own,g.store.epoch)
            g.store.publish(survivorDay,"混合版本",listOf(DiaryBlock("ignore","混合故障测试禁止词 $point","USER_EDITED_AI",listOf(root.id,own.id))),"USER",null,g.store.epoch,true)
            arm(point);g.deletion.deleteDays(setOf(day))
        }
        fail("Requested checkpoint did not terminate the process: $point")
    }
    private fun arm(point:String){File(g.app.noBackupFilesDir,"fault-arm.json").writeText(JSONObject().put("point",point).toString())}
    @Test fun recover()=runBlocking {
        check(BuildConfig.FAULT_TESTING)
        val expected=g.store.read {JSONObject(setting("fault_expectations")!!)}
        val point=expected.getString("point")
        assertEquals(point,File(g.app.noBackupFilesDir,"fault-hit.txt").readText())
        withTimeout(30_000){while(g.store.maintenance)delay(100)}
        assertTrue(g.store.read {rows("PRAGMA foreign_key_check")}.isEmpty())
        assertEquals("0",g.store.read {scalar("SELECT count(*) FROM (SELECT origin_key,count(*) n FROM source_event GROUP BY origin_key HAVING n>1)")})
        if(point.substring(2).toInt()>=7) {
            assertTrue(g.store.read {search("故障测试禁止词")}.isEmpty())
            assertEquals(1,g.store.read {events("2024-02-19").size})
            assertNull(g.store.read {diary("2024-02-19")})
            assertFalse(g.vault.exists("db",expected.getLong("old_generation")))
        } else {
            // Recovered complete blocks may already have been processed and purged.
            val id=g.store.read {setting("fault_chunk")!!}
            val state=g.store.read {scalar("SELECT state FROM media_chunk WHERE id=?",id)}
            assertNotEquals("WRITING",state)
            if(g.store.read {blob("SELECT wrapped_dek FROM media_chunk WHERE id=?",id)}!=null)assertTrue(ChunkStore(g).read(id){}>=1)
            ChunkStore(g).sweep()
        }
    }
}
