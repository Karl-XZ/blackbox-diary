package com.blackbox.diary

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.blackbox.diary.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import android.Manifest
import android.media.AudioManager
import android.media.MediaPlayer
import org.json.JSONObject

class MemoryUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var scene:DeviceActivity
    private val g get()=scene.activity.graph
    @Before fun start(){check(BuildConfig.FAULT_TESTING);scene=DeviceActivity.launch();scene.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);g.lock.configure("592618")};g.store.savePreferences(g.store.preferences().copy(recognitionLanguages=setOf("zh")));g.deletion.progress.value=null}
    @After fun end(){scene.close()}
    private fun snapshot(name:String){compose.waitForIdle();assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(g.app.getExternalFilesDir(null),"v12-ui-$name.png")))}
    @Test fun languagesAssistantAndOptionalVoiceprintAreAccessible(){
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("语音识别语言").performScrollTo().performClick()
        snapshot("languages-default")
        compose.onAllNodesWithText("中文（普通话）").onLast().performClick();compose.onNodeWithText("至少保留一种语言").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("中英文").performScrollTo().performClick();compose.onNodeWithText("听懂哪些语言。").performScrollTo();snapshot("languages-bilingual")
        compose.onNodeWithText("保存语言选择").performScrollTo().performClick();compose.waitUntil(5000){g.store.preferences().recognitionLanguages==setOf("zh","en")}
        compose.onNodeWithText("助手").performClick();compose.onNodeWithText("与日记聊聊。").assertIsDisplayed();compose.onNodeWithText("问问你的日记").assertIsDisplayed();snapshot("assistant")
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("我的声纹").performScrollTo().performClick()
        compose.onNodeWithText("开始录制声纹").assertIsNotEnabled();compose.onNodeWithText("我同意在本机录制并分析我的声纹").assertIsDisplayed();snapshot("voiceprint")
        assertFalse(g.voice.enrolled());assertEquals(CaptureState.OFF,g.capture.audio.value.state)
        File(g.app.getExternalFilesDir(null),"v12-ui.json").writeText("{\"languageSelectionPersisted\":true,\"lastLanguageProtected\":true,\"assistantAccessible\":true,\"voiceprintRequiresConsent\":true,\"ownerEnrollmentPerformed\":false,\"passed\":true}")
    }
}
