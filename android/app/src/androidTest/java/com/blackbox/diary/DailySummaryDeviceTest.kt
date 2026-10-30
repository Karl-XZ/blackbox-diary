package com.blackbox.diary

import androidx.test.core.app.ApplicationProvider
import com.blackbox.diary.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.time.*

/** Synthetic content exclusively in the disposable installation. */
class DailySummaryDeviceTest {
    private val g get()=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
    private var foreground:DeviceActivity?=null
    @Before fun start(){check(BuildConfig.FAULT_TESTING);foreground=DeviceActivity.launch();foreground!!.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);g.lock.configure("592618")}}
    @After fun finish(){foreground?.close()}
    @Test fun aWholeDayBecomesNarrativeAndPreservesEditsAndDeletion():Unit=runBlocking {
        val day=LocalDate.now().minusDays(1).toString();g.deletion.deleteDays(setOf(day));g.deletion.progress.value=null
        val texts=listOf("早上吃了燕麦和牛奶，列出今天的工作计划。","上午阅读论文，把还没有理解的问题记在纸上。","上午修复了本地数据库的重复索引问题。","中午给自己做了一碗番茄鸡蛋面。","午饭后休息了一会儿，整理上午留下的笔记。","下午没有去公园，留在书桌前修改项目方案。","下午重新组织了文档结构，删掉重复的描述。","下午完成了一版摘要，发给同学讨论。","傍晚下楼取了快递，回家收拾桌面。","晚上做了拉伸，没有出门跑步。","晚上整理书架，把几本书放回原位。","睡前读了一会儿小说，心情平静。")
        val start=LocalDate.parse(day).atTime(8,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val events=texts.mapIndexed {i,text->Event(g.store.id(),day,Source.USER_NOTE,text,start+i*3600_000L,start+i*3600_000L,"Asia/Shanghai",Quality.HIGH).also {check(g.store.commitEvent(it,g.store.epoch))}}
        val hashes=g.store.read {events(day).associate {it.id to it.digest}}
        repeat(5){if(g.composer.needs(day))g.pipeline.generate(day)}
        val diary=g.store.read {diary(day)}
        assertNotNull("No real daily narrative was published; issue=${g.ai.lastIssue.value}",diary)
        val composed=diary!!
        assertTrue(composed.blocks.size in 1..4)
        assertTrue(composed.blocks.all {it.templateId?.startsWith("diary.summary.")==true})
        assertFalse(composed.blocks.any {it.text.contains("[AI推断")||it.text.contains("一段环境语音表达")})
        assertTrue(composed.blocks.joinToString {it.text}.contains("我"))
        assertFalse(composed.blocks.any {it.text.contains("筑牢基础")||it.text.contains("画上")})
        assertFalse(g.composer.needs(day))
        foreground!!.onActivity {it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}
        val device=androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        val yesterday=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("昨天的总结")),10000)
        assertNotNull("Yesterday summary is not accessible on the home page",yesterday)
        yesterday.click()
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("每日总结")),10000))
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(composed.title)),10000))
        device.waitForIdle(1000)
        device.takeScreenshot(File(g.app.getExternalFilesDir(null),"daily-summary-screen.png"))
        assertEquals(hashes,g.store.read {events(day).associate {it.id to it.digest}})
        val deleteRoot=composed.blocks.first().eventIds.first()
        val user=g.store.publish(day,"我修改的标题",listOf(DiaryBlock(g.store.id(),"我保留自己的修改。","USER_AUTHORED",listOf(deleteRoot),userNoteRootId=deleteRoot)),"USER",composed.revision,g.store.epoch,false)!!
        g.pipeline.generate(day)
        assertEquals(user.id,g.store.diary(day)!!.id)
        assertNotNull(g.store.diary(day,"CANDIDATE"))
        val report=JSONObject().put("paragraphs",composed.blocks.size).put("characters",composed.blocks.sumOf {it.text.length}).put("title",composed.title).put("body",composed.blocks.joinToString("\n\n"){it.text}).put("originalNotesPreserved",true).put("manualEditsProtected",true)
        File(g.app.getExternalFilesDir(null),"daily-summary-test.json").writeText(report.toString(2))
        g.deletion.deleteEvents(setOf(deleteRoot));g.deletion.progress.value=null
        assertNull(g.store.diary(day));assertEquals("0",g.store.read {scalar("SELECT count(*) FROM daily_composition c JOIN diary_revision r ON r.artifact_id=c.revision_id WHERE r.day_id=?",day)})
        assertTrue(g.store.read {rows("PRAGMA foreign_key_check")}.isEmpty())
        report.put("deletionCleansSummaryAndVersions",true)
        File(g.app.getExternalFilesDir(null),"daily-summary-test.json").writeText(report.toString(2))
        g.deletion.deleteDays(setOf(day));g.deletion.progress.value=null
    }
}
