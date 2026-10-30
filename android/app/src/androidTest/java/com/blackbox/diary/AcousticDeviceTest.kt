package com.blackbox.diary

import android.Manifest
import android.media.*
import android.os.*
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.*
import androidx.test.platform.app.InstrumentationRegistry
import com.blackbox.diary.core.*
import com.blackbox.diary.inference.LocalAsr
import com.blackbox.diary.security.ChunkStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.nio.*
import kotlin.math.sqrt

/** Physical speaker -> air -> microphone -> encrypted chunks -> VAD/ASR. */
class AcousticDeviceTest {
    @Test fun physicalAcousticRoundTrip():Unit=runBlocking {
        val g=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(g.app.packageName,Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(g.app.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val result=JSONObject().put("device",Build.MODEL).put("startedUtcMs",System.currentTimeMillis())
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MODIFY_AUDIO_SETTINGS);val audio=g.app.getSystemService(AudioManager::class.java);val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);val muted=audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val file=File(g.app.cacheDir,"public-acoustic-fixture.wav")
        file.writeBytes(instrumentation.context.assets.open("zh.wav").use {it.readBytes()})
        val wav=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN);wav.position(12);var reference=FloatArray(0)
        while(wav.remaining()>=8){val label=ByteArray(4);wav.get(label);val size=wav.int;if(String(label)=="data"){reference=FloatArray(size/2){wav.short/32768f};break}else wav.position(wav.position()+size+size%2)}
        val asr=LocalAsr(g.models);var energy=0.0;var samples=0L;var pcm=FloatArray(0)
        try {DeviceActivity.launch().use {scene->
            scene.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);g.lock.configure("592618");g.store.savePreferences(g.store.preferences().copy(sleepEnabled=false))}
            val expected=asr.transcribe(reference).text;assertTrue(expected.isNotBlank())
            val before=g.store.read {rows("SELECT id FROM media_chunk").map {it[0]!!}.toSet()}
            g.pipeline.mutex.withLock {
                scene.onActivity {g.capture.startAudio()}
                withTimeout(10000){while(g.capture.audio.value.state!=CaptureState.RECORDING)delay(100)}
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);audio.setStreamVolume(AudioManager.STREAM_MUSIC,(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*.35f).toInt().coerceAtLeast(1),0)
                MediaPlayer().usePlayer {player->
                    player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    val speaker=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
                    assertNotNull("Built-in speaker unavailable for acoustic test",speaker)
                    player.setDataSource(file.path);player.prepare();assertTrue("Preferred output rejected after prepare",player.setPreferredDevice(speaker));delay(1000);player.start();LiveTestAudio.volume(audio,(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*.35f).toInt())
                    withTimeout(30000){while(player.isPlaying)delay(100)};delay(1200)
                }
                g.capture.pause(Channel.MICROPHONE,null);delay(1200);assertEquals(CaptureState.PAUSED_USER,g.capture.audio.value.state)
                g.capture.resume(Channel.MICROPHONE);delay(1500);g.capture.stop(Channel.MICROPHONE)
                withTimeout(10000){while(g.capture.activeTasks.value>0)delay(100)}
                val ids=g.store.read {rows("SELECT id FROM media_chunk").map {it[0]!!}.filter {it !in before}}
                val floats=ArrayList<Float>();val chunks=ChunkStore(g)
                ids.forEach {id->chunks.read(id){record->val bytes=ByteBuffer.wrap(record.pcm).order(ByteOrder.LITTLE_ENDIAN);while(bytes.remaining()>=2){val value=bytes.short/32768f;floats.add(value);energy+=value*value;samples++}}}
                pcm=floats.toFloatArray();result.put("chunks",ids.size).put("capturedSamples",samples).put("rms",if(samples>0)sqrt(energy/samples)else 0)
                assertTrue("Microphone contained only silence",samples>16000&&energy/samples>0.0000001)
            }
            val begin=SystemClock.elapsedRealtime();val recognized=asr.transcribe(pcm)
            val cleanExpected=expected.filter {it.isLetterOrDigit()};val cleanActual=recognized.text.filter {it.isLetterOrDigit()}
            val distance=editDistance(cleanExpected,cleanActual);val cer=distance.toDouble()/cleanExpected.length.coerceAtLeast(1)
            result.put("asrMs",SystemClock.elapsedRealtime()-begin).put("expectedCharacters",cleanExpected.length).put("recognizedCharacters",cleanActual.length).put("characterErrorRate",cer).put("hasChinese",recognized.text.any {it in '\u4e00'..'\u9fff'})
            assertTrue("Acoustic sample was not recognized",cleanActual.isNotEmpty()&&cer<=0.5)
            g.pipeline.process();result.put("pipelineProcessed",true).put("passed",true)
        }} catch(t:Throwable){result.put("passed",false).put("error",t.javaClass.simpleName+":"+t.message);throw t}
        finally {g.capture.stop(Channel.MICROPHONE);asr.close();pcm.fill(0f);reference.fill(0f);file.delete();LiveTestAudio.volume(audio,volume);audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);if(muted)audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0);instrumentation.uiAutomation.dropShellPermissionIdentity();result.put("finishedUtcMs",System.currentTimeMillis());File(g.app.getExternalFilesDir(null),"acoustic-probe.json").writeText(result.toString(2))}
    }
    private fun editDistance(a:String,b:String):Int {var row=IntArray(b.length+1){it};for(i in a.indices){val next=IntArray(b.length+1);next[0]=i+1;for(j in b.indices)next[j+1]=minOf(next[j]+1,row[j+1]+1,row[j]+if(a[i]==b[j])0 else 1);row=next};return row.last()}
    private inline fun <T> MediaPlayer.usePlayer(block:(MediaPlayer)->T):T=try {block(this)}finally {runCatching {stop()};release()}
}
