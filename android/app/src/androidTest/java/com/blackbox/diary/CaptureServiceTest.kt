package com.blackbox.diary

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.blackbox.diary.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.regex.Pattern

@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class CaptureServiceTest {
    @get:Rule val permissions=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @Before fun wakeTestDevice(){val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");device.waitForIdle()}
    @After fun stopServices() {val g=androidx.test.core.app.ApplicationProvider.getApplicationContext<DiaryApplication>().graph;Channel.entries.forEach {g.capture.stop(it)}}
    @Test fun bAudioForegroundServiceCapturesSealedPcmPausesAndResumes()=runBlocking {
        val app=androidx.test.core.app.ApplicationProvider.getApplicationContext<DiaryApplication>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName,Manifest.permission.RECORD_AUDIO)
        DeviceActivity.launch().use {scenario->
            lateinit var g:AppGraph
            scenario.onActivity {g=it.graph;g.lock.configure("592618");g.store.savePreferences(g.store.preferences().copy(sleepEnabled=false));g.capture.startAudio()}
            withTimeout(10_000){while(g.capture.audio.value.state!=CaptureState.RECORDING)delay(100)}
            delay(2500)
            assertTrue(g.capture.coverage(Channel.MICROPHONE,java.time.LocalDate.now().toString()).capturedMs>1000)
            g.capture.pause(Channel.MICROPHONE,null);delay(1200)
            val paused=g.capture.coverage(Channel.MICROPHONE,java.time.LocalDate.now().toString())
            assertTrue(paused.excludedMs>=1000);assertEquals(CaptureState.PAUSED_USER,g.capture.audio.value.state)
            g.capture.resume(Channel.MICROPHONE);delay(2200);g.capture.stop(Channel.MICROPHONE)
            withTimeout(10_000){while(g.capture.activeTasks.value>0)delay(100)}
            assertEquals(CaptureState.OFF,g.capture.audio.value.state)
            assertTrue(g.store.read {scalar("SELECT coalesce(sum(sealed_record_count),0) FROM media_chunk").toLong()}>0)
        }
    }
    @Test fun aScreenProjectionWorksWithoutMicPermissionAndEndsAtSystemLock()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(instrumentation)
        DeviceActivity.launch().use {scenario->
            lateinit var g:AppGraph
            scenario.onActivity {activity->g=activity.graph;check(!g.lock.configured||g.lock.verify("592618")){"A user PIN is configured; preserve this installation and use an isolated test package"};g.lock.configure("592618");g.store.savePreferences(g.store.preferences().copy(sleepEnabled=false));g.capture.stop(Channel.MICROPHONE)}
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,g.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            val day=java.time.LocalDate.now().toString();val previousSources=g.store.read {events(day).filter {it.source==Source.SCREEN}.map {it.id}.toSet()}
            val open=device.wait(Until.findObjects(By.text("开启")),5000)
            assertNotNull("Capture controls were not displayed",open);assertTrue(open.size>=2);open.last().click()
            val spinner=device.wait(Until.findObject(By.res("com.android.systemui","screen_share_mode_options")),1500)
                ?:device.wait(Until.findObject(By.res("com.android.systemui","real_screen_share_mode_spinner")),1500)
            spinner?.click()
            val entire=device.wait(Until.findObject(By.text(Pattern.compile("(?i).*entire screen.*|.*整个屏幕.*|.*全屏.*"))),5000)
            if(entire==null)device.dumpWindowHierarchy(File(g.app.getExternalFilesDir(null),"projection-dialog.xml"))
            assertNotNull("Entire-screen option is missing",entire);entire.click()
            val start=device.wait(Until.findObject(By.res("android","button1")),5000)
            if(start==null){device.dumpWindowHierarchy(File(g.app.getExternalFilesDir(null),"projection-dialog.xml"));fail("System share dialog did not expose the expected confirmation")}
            start.click()
            // vivo adds a second privacy-protection confirmation. Keep every protection switch unchanged.
            val privacyStart=device.wait(Until.findObject(By.res("com.android.systemui","yes")),2000)
            if(privacyStart!=null&&device.hasObject(By.text("屏幕共享保护")))privacyStart.click()
            withTimeout(10_000){while(g.capture.screen.value.state!=CaptureState.RECORDING)delay(100)}
            assertEquals(CaptureState.OFF,g.capture.audio.value.state)
            val testContext=instrumentation.context
            device.executeShellCommand("am start -W -n ${testContext.packageName}/${TestSceneActivity::class.java.name}")
            assertNotNull(device.wait(Until.findObject(By.textContains("这是专用测试画面")),5000));delay(11_000)
            val coverage=g.capture.coverage(Channel.SCREEN,java.time.LocalDate.now().toString())
            assertTrue("Screen has no actual captured interval",coverage.capturedMs>0)
            withTimeout(45000){while(g.store.read {events(day).none {it.source==Source.SCREEN&&it.id !in previousSources&&it.text.contains("公园")}})delay(100)}
            val captured=g.store.read {events(day).filter {it.source==Source.SCREEN&&it.id !in previousSources}}
            assertTrue(captured.isNotEmpty());assertTrue(captured.none {it.text.contains("13812345678")||TextRules.containsSensitive(it.text)})
            assertTrue("The synthetic number was not proven to pass through redaction",captured.any {it.text.contains("[号码已遮蔽]")})
            assertTrue(captured.all {it.quality==Quality.REVIEW})
            assertTrue(g.app.noBackupFilesDir.walkTopDown().none {it.isFile&&it.extension.lowercase() in listOf("png","jpg","jpeg","webp","bmp")})
            device.executeShellCommand("am start -W -n ${g.app.packageName}/com.blackbox.diary.MainActivity")
            withTimeout(10000){while(!g.visible.value||g.capture.activeTasks.value>0)delay(100)}
            val excludedBefore=g.capture.coverage(Channel.SCREEN,day).excludedMs
            val sourcesBefore=g.store.read {events(day).count {it.source==Source.SCREEN}}
            delay(6500)
            val excludedAfter=g.capture.coverage(Channel.SCREEN,day).excludedMs
            assertTrue("Own app was not excluded",excludedAfter>excludedBefore)
            assertEquals(sourcesBefore,g.store.read {events(day).count {it.source==Source.SCREEN}})
            val offAt=android.os.SystemClock.elapsedRealtime()
            device.sleep()
            try {withTimeout(10000){while(g.capture.screen.value.state!=CaptureState.INTERRUPTED)delay(100)}}
            finally {device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")}
            val stoppedAfter=android.os.SystemClock.elapsedRealtime()-offAt
            g.capture.stop(Channel.SCREEN)
            withTimeout(10_000){while(g.capture.activeTasks.value>0)delay(100)}
            assertEquals(CaptureState.OFF,g.capture.screen.value.state)
            File(g.app.getExternalFilesDir(null),"screen-probe.json").writeText(org.json.JSONObject().put("device",android.os.Build.MODEL).put("capturedMs",coverage.capturedMs).put("ocrSources",captured.size).put("syntheticPhoneRedacted",true).put("redactionMarkerObserved",captured.any {it.text.contains("[号码已遮蔽]")}).put("uncalibratedQualityKeptReview",true).put("framesNotStored",true).put("ownAppExcludedMs",excludedAfter-excludedBefore).put("screenOffStopMs",stoppedAfter).put("micPermissionDenied",true).put("passed",true).toString(2))
        }
    }
}
