package com.blackbox.diary

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.*
import java.io.File

class MobileUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var scene:DeviceActivity
    @Before fun wakeTestDevice(){val device=androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation());device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");scene=DeviceActivity.launch()}
    @After fun releaseScene(){if(::scene.isInitialized)scene.close()}
    private fun snapshot(name:String) {compose.waitForIdle();val file=File(scene.activity.getExternalFilesDir(null),"native-$name.png");file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun nativeNavigationAndHandwrittenDiaryFlow() {
        val graph=scene.activity.graph
        kotlinx.coroutines.runBlocking {graph.deletion.deleteDays(setOf(java.time.LocalDate.now().toString()))}
        graph.deletion.progress.value=null
        compose.runOnUiThread {graph.lock.configure("592618");scene.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        compose.onNodeWithText("今天，也值得记住。").assertIsDisplayed()
        compose.onNodeWithText("补写一笔").performScrollTo().performClick()
        compose.onNodeWithText("此刻想留下的话").performTextInput("午后在窗边读书，喝了一杯茶。")
        compose.onNodeWithText("保存手记").performClick()
        compose.waitUntil(10000) {graph.store.read {events(java.time.LocalDate.now().toString()).any {it.text.contains("窗边读书")}}}
        compose.onNodeWithText("整理日记").performScrollTo().performClick()
        compose.waitUntil(180000) {graph.store.read {diary(java.time.LocalDate.now().toString())!=null}}
        compose.runOnUiThread {graph.message.value=null}
        compose.onNodeWithText("日历").performClick();compose.onNodeWithText("日子，慢慢成册。").assertIsDisplayed()
        snapshot("calendar")
        compose.onNodeWithText("查找").performClick();compose.onNodeWithText("搜索日记与来源").performTextInput("窗边读书")
        compose.waitUntil(10000) {compose.onAllNodesWithText("找到 ",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("没有找到相关片段").assertCountEquals(0)
        snapshot("search")
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("按自己的节奏。").assertIsDisplayed()
        snapshot("settings")
        compose.onNodeWithText("今日").performClick();compose.waitForIdle()
        snapshot("today")
        compose.onNodeWithText("今天的日记").performScrollTo().performClick()
        compose.onNodeWithText("日记",useUnmergedTree=true).assertExists()
        snapshot("diary")
        compose.onNodeWithText("查看来源 · 1").performScrollTo().performClick()
        compose.onNodeWithText("午后在窗边读书，喝了一杯茶。").assertIsDisplayed()
        kotlinx.coroutines.runBlocking {graph.deletion.deleteDays(setOf(java.time.LocalDate.now().toString()))}
        compose.waitForIdle()
        compose.onAllNodesWithText("午后在窗边读书",substring=true).assertCountEquals(0)
    }
}
