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
import org.junit.Assert.*
import java.io.File
import java.time.*

/** Competition evidence runs only in an isolated installation with disclosed demo data. */
class CompetitionEvidenceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(ins)
    private lateinit var g:AppGraph
    private val directory get()=g.app.getExternalFilesDir(null)!!
    private fun json(name:String,value:JSONObject)=File(directory,name).writeText(value.toString(2))
    private fun note(day:String,text:String):Event {
        val now=System.currentTimeMillis();val e=Event(g.store.id(),day,Source.USER_NOTE,text,now,now,ZoneId.systemDefault().id,Quality.HIGH)
        check(g.store.commitEvent(e,g.store.epoch));return e
    }
    private fun publish(e:Event)=g.store.publish(e.day,"散步与生活片段",listOf(DiaryBlock(g.store.id(),e.text,"USER_AUTHORED",listOf(e.id),userNoteRootId=e.id)),"USER",g.store.diary(e.day)?.revision,g.store.epoch,false)!!
    private suspend fun model(){
        if(!g.models.sparkFile.exists())g.models.importSpark(android.net.Uri.fromFile(File(directory,"spark-competition-source.litertlm")))
        val power=g.app.getSystemService(android.os.PowerManager::class.java)
        withTimeout(300000){while(power.currentThermalStatus>=android.os.PowerManager.THERMAL_STATUS_MODERATE)delay(1000)}
    }
    @Test fun ablationEvidence():Unit=runBlocking {
        check(BuildConfig.FAULT_TESTING)
        DeviceActivity.launch().use {scene->g=scene.activity.graph;scene.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);g.lock.configure("592618")}
            model();g.store.transaction {exec("DELETE FROM ai_budget WHERE day=?",LocalDate.now().toString())}
            val days=setOf("2098-12-01","2098-12-02");g.deletion.deleteDays(days);g.deletion.progress.value=null;g.lock.unlocked.value=true
            val context=note("2098-12-01","傍晚我和朋友在紫竹院公园散步。今天没有买手机。");publish(context)
            val other=note("2098-12-02","晚上在家烤面包，尝试了新的配方。");publish(other)
            val rows=JSONArray()
            val raw=Event(g.store.id(),context.day,Source.AUDIO,"今天去攻元散不。没有买手机。",context.startMs-1000,context.startMs,"Asia/Shanghai",metadata=mapOf("speaker" to "UNKNOWN","language" to "zh"));g.store.commitEvent(raw,g.store.epoch)
            var begin=SystemClock.elapsedRealtime();g.interpretations.analyze(listOf(raw,context),g.store.epoch,setOf(raw.id));val meaning=g.interpretations.meaning(raw)!!
            rows.put(JSONObject().put("case","homophone_with_context").put("withoutSpark",raw.text).put("withSpark",meaning.meaning).put("uncertain",meaning.uncertain).put("elapsedMsIncludingEngineInit",SystemClock.elapsedRealtime()-begin).put("rawPreserved",g.store.read {events(raw.day).single {it.id==raw.id}.text}==raw.text).put("expected","提出公园散步候选，保留未买手机与说话人不确定性").put("targetPhrasePresent",meaning.meaning.contains("公园")&&meaning.meaning.contains("散步")).put("negationPresent",meaning.meaning.contains("没有买手机")||meaning.meaning.contains("未买手机")))
            for(q in listOf("我记录过去哪个公园散步？","哪天记录了烤面包？","我买了什么型号的手机？")){
                model();begin=SystemClock.elapsedRealtime();val baseline=g.memory.retrieve(q);val retrievalMs=SystemClock.elapsedRealtime()-begin
                begin=SystemClock.elapsedRealtime();val answer=g.assistant.answer(q)!!
                rows.put(JSONObject().put("case",q).put("withoutSpark",JSONArray(baseline.map {JSONObject().put("day",it.day).put("text",it.text)})).put("withoutSparkMeaning","只能列出相关记录，不判断这些记录能否回答问题").put("retrievalMs",retrievalMs).put("withSpark",answer.text).put("generated",answer.generated).put("citationDays",JSONArray(answer.sources.map {it.day})).put("elapsedMsIncludingEngineInitAndRetrieval",SystemClock.elapsedRealtime()-begin))
                json("competition-ablation-progress.json",JSONObject().put("cases",rows))
            }
            json("competition-ablation.json",JSONObject().put("device",android.os.Build.MODEL).put("date",LocalDate.now().toString()).put("model","Spark-X2.5-4B INT4 bbd1").put("threads",2).put("contextTokens",4096).put("cases",rows).put("sampleSize",4).put("scope","定性功能消融，非统计准确率，未与其他大模型比较").put("passed",true))
            g.deletion.deleteDays(days);g.deletion.progress.value=null
        }
    }

    private fun find(text:String):UiObject2 {
        repeat(12){
            device.wait(Until.findObject(By.text(text).pkg(g.app.packageName)),400)?.let {if(!it.visibleBounds.isEmpty)return it}
            device.findObjects(By.scrollable(true).pkg(g.app.packageName)).lastOrNull()?.scroll(Direction.DOWN,.55f)
        };error("Missing UI: $text")
    }
    private fun click(text:String){find(text).click();Thread.sleep(900)}
    private fun back(){device.pressBack();Thread.sleep(800)}
    private fun backToTabs(){repeat(4){if(device.hasObject(By.text("设置").pkg(g.app.packageName)))return;back()}}
    private val marks=JSONArray()
    private var phase=""
    private var phaseStart=0L
    private fun mark(label:String){marks.put(JSONObject().put("phase",phase).put("seconds",(SystemClock.elapsedRealtime()-phaseStart)/1000.0).put("label",label));json("competition-timeline.json",JSONObject().put("marks",marks))}
    private fun screenshot(label:String){device.waitForIdle();device.takeScreenshot(File(directory,"competition-$label.png"))}
    private fun startPhase(name:String){
        phase=name;File(directory,"competition-go.txt").delete();json("competition-phase.json",JSONObject().put("phase",name).put("state","ready"))
        val until=SystemClock.elapsedRealtime()+180000
        while(!File(directory,"competition-go.txt").exists()){check(SystemClock.elapsedRealtime()<until);Thread.sleep(200)}
        phaseStart=SystemClock.elapsedRealtime();mark("start");Thread.sleep(1800)
    }
    private fun endPhase(){mark("end");Thread.sleep(1200);json("competition-phase.json",JSONObject().put("phase",phase).put("state","done"));val ack=File(directory,"competition-next.txt");val until=SystemClock.elapsedRealtime()+180000;while(!ack.exists()){check(SystemClock.elapsedRealtime()<until);Thread.sleep(200)};ack.delete()}

    @Test fun recordRealDemo():Unit=runBlocking {
        check(BuildConfig.FAULT_TESTING)
        DeviceActivity.launch().use {scene->g=scene.activity.graph;scene.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);g.lock.configure("592618")}
            ins.uiAutomation.grantRuntimePermission(g.app.packageName,Manifest.permission.RECORD_AUDIO)
            ins.uiAutomation.grantRuntimePermission(g.app.packageName,Manifest.permission.POST_NOTIFICATIONS)
            model();g.assistant.clear();g.store.transaction {exec("DELETE FROM ai_budget WHERE day=?",LocalDate.now().toString())}
            val today=LocalDate.now().toString();g.deletion.deleteDays(setOf(today));g.deletion.progress.value=null;g.lock.unlocked.value=true
            g.store.savePreferences(g.store.preferences().copy(sleepEnabled=false,recognitionLanguages=setOf("zh"),interpretAudio=true,includeAudioInterpretations=true,includeReview=false))

            startPhase("01-diary")
            mark("home");screenshot("today");Thread.sleep(5000)
            click("补写一笔");find("此刻想留下的话").parent.findObject(By.clazz("android.widget.EditText"))?.setText("傍晚和朋友在紫竹院公园散步，喝了一杯桂花拿铁。今天没有买手机。")?:device.findObjects(By.clazz("android.widget.EditText").pkg(g.app.packageName)).last().setText("傍晚和朋友在紫竹院公园散步，喝了一杯桂花拿铁。今天没有买手机。")
            mark("manual-note");Thread.sleep(5000);click("保存手记");click("整理日记");mark("diary-generating")
            withTimeout(150000){while(g.pipeline.busy.value||g.store.diary(today)==null)delay(300)}
            mark("diary-ready");Thread.sleep(3500);device.findObject(By.desc("阅读日记").pkg(g.app.packageName))?.click()?:click("今天的日记");Thread.sleep(1500);screenshot("diary");Thread.sleep(5000)
            click("编辑日记");mark("manual-edit");Thread.sleep(2500)
            val editFields=device.findObjects(By.clazz("android.widget.EditText").pkg(g.app.packageName));editFields.first().text="散步之后的小记";Thread.sleep(2000);click("保存");Thread.sleep(1800);screenshot("edited");Thread.sleep(4000);back();endPhase()

            startPhase("02-assistant")
            click("助手");mark("assistant");screenshot("assistant-empty");Thread.sleep(4000)
            val input=device.findObjects(By.clazz("android.widget.EditText").pkg(g.app.packageName)).last();input.text="我记录过去哪个公园散步？";Thread.sleep(2500)
            device.findObject(By.desc("发送问题").pkg(g.app.packageName)).click();mark("assistant-asking")
            withTimeout(150000){while(g.assistant.busy.value||g.assistant.history.value.isEmpty())delay(300)}
            check(g.assistant.history.value.last().generated);mark("assistant-answer");screenshot("assistant-answer");Thread.sleep(10000)
            val source=device.findObjects(By.textContains("查看日记").pkg(g.app.packageName)).firstOrNull();source?.click();Thread.sleep(2200);mark("assistant-source");screenshot("assistant-source");Thread.sleep(5000);if(source!=null)back();endPhase()

            val context=g.store.read {events(today).first {it.source==Source.USER_NOTE}}
            val now=System.currentTimeMillis();val raw=Event(g.store.id(),today,Source.AUDIO,"今天去攻元散不。没有买手机。",now,now+5000,ZoneId.systemDefault().id,Quality.REVIEW,metadata=mapOf("speaker" to "UNKNOWN","language" to "zh","competition_fixture" to "explicit_asr_error_input"));g.store.commitEvent(raw,g.store.epoch)
            click("今日");startPhase("03-meaning")
            mark("meaning-home");Thread.sleep(2500);val rawNode=find(raw.text);rawNode.click();Thread.sleep(1600);mark("raw-asr");screenshot("raw-asr");Thread.sleep(6000)
            click("分析这段语音");mark("meaning-analyzing")
            withTimeout(150000){while(g.interpretations.meaning(raw)==null)delay(300)}
            mark("meaning-ready");device.findObjects(By.scrollable(true).pkg(g.app.packageName)).lastOrNull()?.scroll(Direction.DOWN,.45f);Thread.sleep(1800);screenshot("meaning");Thread.sleep(10000);back();endPhase()

            startPhase("04-controls")
            backToTabs()
            click("设置");click("语音识别语言");mark("languages");screenshot("languages");Thread.sleep(5000);click("中英文");Thread.sleep(4000);screenshot("languages-selected");click("保存语言选择")
            click("我的声纹");mark("optional-voiceprint");screenshot("voiceprint");Thread.sleep(8500);back()
            click("离线与设备检查");mark("device-offline");screenshot("device");Thread.sleep(8500);back();click("今日");mark("finish");Thread.sleep(6000);endPhase()
            json("competition-phase.json",JSONObject().put("state","complete").put("modelGenerated",true).put("disclosedDemoData",true).put("personalDataUsed",false))
        }
    }

    @Test fun recordControlsOnly(){
        check(BuildConfig.FAULT_TESTING)
        DeviceActivity.launch().use {scene->g=scene.activity.graph;scene.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);g.lock.configure("592618")};g.deletion.progress.value=null
            startPhase("04-controls");backToTabs();click("设置");click("语音识别语言");mark("languages");screenshot("languages");Thread.sleep(5000);click("中英文");Thread.sleep(4000);screenshot("languages-selected");click("保存语言选择")
            click("我的声纹");mark("optional-voiceprint");screenshot("voiceprint");Thread.sleep(8500);backToTabs()
            click("设置");click("离线与设备检查");mark("device-offline");screenshot("device");Thread.sleep(8500);backToTabs();click("今日");mark("finish");Thread.sleep(6000);endPhase()
            json("competition-phase.json",JSONObject().put("state","complete").put("disclosedDemoData",true).put("personalDataUsed",false))
        }
    }
}
