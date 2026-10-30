package com.blackbox.diary

import android.Manifest
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.blackbox.diary.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.time.*

/** Fresh competition capture: synthetic fixtures, genuine local inference, disposable app only. */
class CompetitionVideoTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(ins)
    private lateinit var g:AppGraph
    private val dir get()=g.app.getExternalFilesDir(null)!!
    private val marks=JSONArray()
    private var begin=0L
    private fun state(s:String){File(dir,"video-state.json").writeText(JSONObject().put("state",s).toString())}
    private fun mark(s:String){marks.put(JSONObject().put("label",s).put("seconds",(SystemClock.elapsedRealtime()-begin)/1000.0));File(dir,"video-timeline.json").writeText(JSONObject().put("marks",marks).toString(2))}
    private fun find(s:String):UiObject2 {
        device.wait(Until.findObject(By.text(s).pkg(g.app.packageName)),350)?.let {if(!it.visibleBounds.isEmpty)return it}
        top()
        repeat(14){device.wait(Until.findObject(By.text(s).pkg(g.app.packageName)),350)?.let {if(!it.visibleBounds.isEmpty)return it};device.findObjects(By.scrollable(true).pkg(g.app.packageName)).lastOrNull()?.scroll(Direction.DOWN,.5f)}
        error("Missing UI: $s")
    }
    private fun click(s:String){find(s).click();Thread.sleep(1000)}
    private fun back(){device.pressBack();Thread.sleep(1000)}
    private fun top(){device.findObjects(By.scrollable(true).pkg(g.app.packageName)).lastOrNull()?.fling(Direction.UP);Thread.sleep(1200)}
    private fun note(day:String,text:String,hour:Int):Event {
        val time=LocalDate.parse(day).atTime(hour,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Event(g.store.id(),day,Source.USER_NOTE,text,time,time,ZoneId.systemDefault().id,Quality.HIGH).also {check(g.store.commitEvent(it,g.store.epoch))}
    }
    @Test fun inspectAssistantInput(){
        check(BuildConfig.FAULT_TESTING)
        DeviceActivity.launch().use {scene->
            g=scene.activity.graph;scene.onActivity {g.lock.configure("592618");it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
            click("助手");find("问问你的日记").click();Thread.sleep(1000)
            device.dumpWindowHierarchy(File(dir,"video-input-before.xml"))
            val field=device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(g.app.packageName)),3000)?:find("问问你的日记").parent
            field.text="昨天我去了哪个公园，喝了什么？";Thread.sleep(1000)
            device.dumpWindowHierarchy(File(dir,"video-input-after.xml"))
            check(device.hasObject(By.text("昨天我去了哪个公园，喝了什么？")))
        }
    }
    @Test fun inspectAssistantAnswer():Unit=runBlocking {
        check(BuildConfig.FAULT_TESTING)
        DeviceActivity.launch().use {scene->
            g=scene.activity.graph;scene.onActivity {g.lock.configure("592618");it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            g.assistant.refresh();val previous=g.assistant.history.value.lastOrNull();g.assistant.clear()
            val answer=g.assistant.answer("我记录过去哪个公园散步？")
            File(dir,"video-assistant-check.json").writeText(JSONObject().put("previousAnswer",previous?.text).put("answer",answer?.text).put("generated",answer?.generated).put("issue",g.ai.lastIssue.value).put("chatIssue",g.store.read {setting("ai_last_issue_CHAT")}).toString(2))
        }
    }
    @Test fun capture():Unit=runBlocking {
        check(BuildConfig.FAULT_TESTING) {"Never run video fixtures on the owner's installation"}
        DeviceActivity.launch().use {scene->
            g=scene.activity.graph
            scene.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);g.lock.configure("592618")}
            check(g.models.useSme()) {"Verified SME2 model required"}
            ins.uiAutomation.grantRuntimePermission(g.app.packageName,Manifest.permission.POST_NOTIFICATIONS)
            g.assistant.clear();g.lock.unlocked.value=true
            g.store.savePreferences(g.store.preferences().copy(sleepEnabled=false,recognitionLanguages=setOf("zh"),interpretAudio=true,includeAudioInterpretations=true,includeReview=false))
            val today=LocalDate.now().toString();val yesterday=LocalDate.now().minusDays(1).toString()
            state("preparing")
            g.deletion.deleteDays(setOf(today));g.deletion.progress.value=null;g.lock.unlocked.value=true
            if(g.store.read {events(yesterday).isEmpty()}) {
            note(yesterday,"早上吃了燕麦和牛奶，列出今天的工作计划。",8)
            note(yesterday,"上午修复本地数据库的重复索引问题，整理了项目文档。",10)
            note(yesterday,"中午给自己做了一碗番茄鸡蛋面。",12)
            val context=note(yesterday,"傍晚我和朋友在紫竹院公园散步，喝了一杯桂花拿铁。今天没有买手机。",18)
            note(yesterday,"晚上整理书架，睡前读了一会儿小说，心情平静。",21)
            val raw=Event(g.store.id(),yesterday,Source.AUDIO,"今天去攻元散不。没有买手机。",context.startMs-3000,context.startMs,"Asia/Shanghai",Quality.REVIEW,metadata=mapOf("speaker" to "UNKNOWN","language" to "zh","competition_fixture" to "disclosed_asr_error"))
            check(g.store.commitEvent(raw,g.store.epoch))
            g.interpretations.analyze(g.store.read {events(yesterday)},g.store.epoch,setOf(raw.id))
            check(g.interpretations.meaning(raw)?.meaningful==true)
            }
            val raw=g.store.read {events(yesterday).first {it.metadata["competition_fixture"]=="disclosed_asr_error"}}
            if(g.store.diary(yesterday)==null)repeat(5){if(g.composer.needs(yesterday))g.pipeline.generate(yesterday)}
            check(g.store.diary(yesterday)?.blocks?.any {it.templateId?.startsWith("diary.summary.")==true}==true) {"Narrative generation failed: ${g.ai.lastIssue.value}"}
            if(g.store.read {events(today).isEmpty()}) {
            note(today,"上午完成黑匣子日记的真机检查，修复了每日总结的入口。",9)
            note(today,"中午吃了番茄鸡蛋面，下午整理比赛演示材料。",13)
            }
            File(dir,"video-go.txt").delete();state("ready")
            withTimeout(240000){while(!File(dir,"video-go.txt").exists())delay(200)}
            begin=SystemClock.elapsedRealtime();mark("home");Thread.sleep(8000)
            click("昨天的总结");mark("yesterday-summary");Thread.sleep(12000)
            click("来源");mark("sources");Thread.sleep(5000);find(raw.text).click();Thread.sleep(1500);mark("raw-and-meaning");Thread.sleep(10000)
            device.findObjects(By.scrollable(true).pkg(g.app.packageName)).lastOrNull()?.scroll(Direction.DOWN,.55f);Thread.sleep(6500);mark("meaning-end");back();top()
            click("每日总结");find("编辑日记").click();Thread.sleep(1500);mark("edit")
            device.findObjects(By.clazz("android.widget.EditText").pkg(g.app.packageName)).first().text="散步之后，留一点给自己";Thread.sleep(5000);click("保存");mark("edit-saved");Thread.sleep(5000);top();Thread.sleep(4000);back()
            click("今日");find("补写一笔").click();Thread.sleep(1000)
            device.findObjects(By.clazz("android.widget.EditText").pkg(g.app.packageName)).last().text="傍晚到楼下散步，想到一个更清楚的演示顺序：先看每日总结，再追溯来源。";mark("new-note");Thread.sleep(6000);click("保存手记");click("生成每日总结");mark("generation-start")
            withTimeout(360000){while(g.pipeline.busy.value||g.store.diary(today)==null)delay(300)}
            mark("generation-ready");Thread.sleep(3000);find("今天的总结").click();Thread.sleep(1200);top();mark("today-summary");Thread.sleep(11000);back()
            click("助手");mark("assistant-home");Thread.sleep(5000)
            find("问问你的日记").click();Thread.sleep(1000)
            val questionInput=device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(g.app.packageName)),3000)
                ?:find("问问你的日记").parent
            questionInput.text="我记录过去哪个公园散步？";Thread.sleep(4000)
            device.findObject(By.desc("发送问题").pkg(g.app.packageName)).click()
            if(device.hasObject(By.pkg("com.vivo.ai.ime.nex")))device.pressBack()
            mark("assistant-start")
            withTimeout(240000){while(g.assistant.busy.value||g.assistant.history.value.isEmpty())delay(300)}
            check(g.assistant.history.value.last().generated);mark("assistant-answer");Thread.sleep(12000)
            var source=device.findObjects(By.textContains("查看日记").pkg(g.app.packageName)).firstOrNull()
            repeat(5){if(source==null){device.findObjects(By.scrollable(true).pkg(g.app.packageName)).lastOrNull()?.scroll(Direction.DOWN,.45f);Thread.sleep(700);source=device.findObjects(By.textContains("查看日记").pkg(g.app.packageName)).firstOrNull()}}
            check(source!=null);source!!.click();Thread.sleep(1500);top();mark("assistant-source");Thread.sleep(7000);back()
            click("设置");click("语音识别语言");mark("languages");Thread.sleep(6500);click("中英文");Thread.sleep(3000);click("保存语言选择")
            click("我的声纹");mark("voiceprint");Thread.sleep(8500);back();click("离线与设备检查");mark("offline");Thread.sleep(10500);back();click("今日");top();mark("finish");Thread.sleep(6000)
            File(dir,"video-proof.json").writeText(JSONObject().put("device",android.os.Build.MODEL).put("model","Spark-X2.5-4B-Q4_K_M / SME2").put("sampleData",true).put("personalData",false).put("genuineLocalGeneration",true).put("assistantGenerated",true).put("originalAsrPreserved",g.store.read {events(yesterday).first {it.id==raw.id}.text}==raw.text).toString(2))
            state("complete")
        }
    }
}
