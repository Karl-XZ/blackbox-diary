package com.blackbox.diary

import android.Manifest
import android.media.*
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File

/** Real-time UI Automator avoids virtual Compose frame clocks during live microphone updates. */
class VoiceEnrollmentDeviceTest {
    @Test fun syntheticAcousticEnrollmentAndDeletion(){
        check(BuildConfig.FAULT_TESTING)
        val instrumentation=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(instrumentation)
        DeviceActivity.launch().use {scene->val g=scene.activity.graph
            scene.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);g.lock.configure("592618")}
            g.deletion.progress.value=null
            instrumentation.uiAutomation.grantRuntimePermission(g.app.packageName,Manifest.permission.RECORD_AUDIO)
            fun find(text:String):UiObject2 {
                repeat(7){
                    val node=device.wait(Until.findObject(By.text(text).pkg(g.app.packageName)),500)
                    if(node!=null&&!node.visibleBounds.isEmpty)return node
                    device.findObjects(By.scrollable(true).pkg(g.app.packageName)).lastOrNull()?.scroll(Direction.DOWN,.6f)
                };error("UI control missing: $text")
            }
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MODIFY_AUDIO_SETTINGS);val audio=g.app.getSystemService(AudioManager::class.java);val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);val muted=audio.isStreamMute(AudioManager.STREAM_MUSIC)
            val fixture=File(g.app.cacheDir,"enrollment-public-zh.wav")
            instrumentation.context.assets.open("zh.wav").use {input->fixture.outputStream().use {input.copyTo(it)}}
            val player=MediaPlayer();val before=g.store.read {rows("SELECT id FROM media_chunk").map {it[0]!!}.toSet()}
            try {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0);audio.setStreamVolume(AudioManager.STREAM_MUSIC,(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*.35f).toInt().coerceAtLeast(1),0)
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                val speaker=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
                assertNotNull(speaker)
                player.setDataSource(fixture.path);player.isLooping=true;player.prepare();assertTrue("Preferred output rejected after prepare",player.setPreferredDevice(speaker))
                find("设置").click();find("我的声纹").click();assertFalse(find("开始录制声纹").parent.isEnabled)
                val consent=device.wait(Until.findObject(By.clazz("android.widget.CheckBox").pkg(g.app.packageName)),5000)
                assertNotNull("Consent checkbox missing",consent);consent.click();assertTrue(find("开始录制声纹").parent.isEnabled)
                player.start();LiveTestAudio.volume(audio,(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*.35f).toInt());find("开始录制声纹").click();val started=System.currentTimeMillis()
                while(!g.voice.enrolled()&&System.currentTimeMillis()-started<60000){
                    if(System.currentTimeMillis()-started>4000&&g.voice.progress.value==null)error("Enrollment stopped: ${g.message.value}")
                    Thread.sleep(100)
                }
                assertTrue("Acoustic enrollment did not complete",g.voice.enrolled());player.stop()
                assertTrue(g.store.preferences().voiceprintEnabled)
                assertTrue(g.store.read {rows("SELECT id FROM media_chunk").map {it[0]!!}.none {it !in before}})
                device.waitForIdle();assertTrue(device.takeScreenshot(File(g.app.getExternalFilesDir(null),"v12-ui-voiceprint-enrolled.png")))
                val generation=g.store.descriptor.getLong("generation")
                find("永久删除我的声纹").click();find("永久删除").click()
                val until=System.currentTimeMillis()+30000;while((g.voice.enrolled()||g.store.maintenance)&&System.currentTimeMillis()<until)Thread.sleep(100)
                assertFalse(g.voice.enrolled());assertFalse(g.store.maintenance);assertFalse(g.vault.exists("db",generation));assertFalse(g.store.preferences().voiceprintEnabled)
                File(g.app.getExternalFilesDir(null),"v12-voice-enrollment-ui.json").writeText(JSONObject().put("physicalMicrophone",true).put("publicAudioUsedAsTestStimulus",true).put("requestedBuiltInSpeaker",true).put("optionalUiConsentRequired",true).put("enrollmentCompleted",true).put("recordedPcmStored",false).put("profileDeleted",true).put("oldKeyRetired",true).put("ownerEnrollmentPerformed",false).put("identityAccuracyMeasured",false).put("passed",true).toString(2))
            }finally {g.voice.cancel();runCatching {player.stop()};player.release();LiveTestAudio.volume(audio,volume);audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);if(muted)audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0);instrumentation.uiAutomation.dropShellPermissionIdentity();fixture.delete()}
        }
    }
}
