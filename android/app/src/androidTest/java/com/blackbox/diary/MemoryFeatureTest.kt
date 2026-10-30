package com.blackbox.diary

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.blackbox.diary.core.*
import com.blackbox.diary.inference.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Only the disposable .fault installation, never the owner's personal diary installation. */
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class MemoryFeatureTest {
    private val g get()=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
    private var activity:DeviceActivity?=null
    @Before fun foreground(){check(BuildConfig.FAULT_TESTING);activity=DeviceActivity.launch();activity!!.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);g.lock.configure("592618")}}
    @After fun finish(){activity?.close()}
    private fun report(name:String,data:JSONObject){File(g.app.getExternalFilesDir(null),"$name.json").writeText(data.toString(2))}
    private fun samples(name:String):FloatArray {
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open(name).use {it.readBytes()};val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.position(12)
        while(b.remaining()>=8){val label=ByteArray(4);b.get(label);val size=b.int;if(String(label)=="data"){return FloatArray(size/2){b.short/32768f}};b.position(b.position()+size+size%2)};error("WAV_DATA")
    }
    private fun note(day:String,text:String):Event {val now=System.currentTimeMillis();val e=Event(g.store.id(),day,Source.USER_NOTE,text,now,now,"Asia/Shanghai",Quality.HIGH);check(g.store.commitEvent(e,g.store.epoch));return e}
    private fun publish(e:Event,text:String=e.text)=g.store.publish(e.day,"语义测试",listOf(DiaryBlock(g.store.id(),text,"USER_AUTHORED",listOf(e.id),userNoteRootId=e.id)),"USER",g.store.diary(e.day)?.revision,g.store.epoch,false)!!
    @Test fun aLanguageSelectionActuallyFiltersRecognizedAudio():Unit=runBlocking {
        assertEquals(setOf("zh"),Preference().recognitionLanguages)
        val en=samples("en.wav");val zh=samples("zh.wav")
        try {
            val blocked=LocalAsr(g.models,setOf("zh")).use {it.transcribe(en)}
            assertTrue(blocked.text.isBlank());assertTrue("LANGUAGE_EXCLUDED" in blocked.flags)
            val english=LocalAsr(g.models,setOf("zh","en")).use {it.transcribe(en)}
            assertTrue(english.text.isNotBlank());assertTrue(english.segments.all {it.language=="en"&&it.speaker=="UNKNOWN"})
            val chinese=LocalAsr(g.models,setOf("zh")).use {it.transcribe(zh)}
            assertTrue(chinese.text.isNotBlank());assertTrue(chinese.segments.all {it.language=="zh"})
            report("v12-languages",JSONObject().put("englishExcludedInChineseOnly",true).put("englishAllowedInBilingual",true).put("chineseAllowed",true).put("ambientSpeakerUnknown",true).put("passed",true))
        }finally {en.fill(0f);zh.fill(0f)}
    }
    @Test fun bActualVectorsAreMultilingualAndTrackEditsAndDeletion():Unit=runBlocking {
        val day="2098-11-01";val other="2098-11-02";g.deletion.deleteDays(setOf(day,other));g.deletion.progress.value=null
        val park=note(day,"傍晚我和朋友在紫竹院公园散步，喝了一杯桂花拿铁。");val books=note(other,"我在家整理书架，把历史书放进抽屉。");val original=publish(park)
        val longText="我在家整理书架，把历史书放进抽屉。".repeat(35)+"最后整理好了书房。";val longDiary=publish(books,longText)
        g.memory.sync();val zh=g.memory.retrieve("我上次去哪儿溜达了？");val en=g.memory.retrieve("Where did I take a walk with my friend?")
        assertEquals(day,zh.first().day);assertEquals(day,en.first().day)
        val count=g.store.read {scalar("SELECT count(*) FROM memory_vector").toInt()};assertTrue(count>=2)
        val ranges=g.store.read {rows("SELECT start_cp,end_cp FROM memory_vector WHERE revision_id=? ORDER BY start_cp",longDiary.id)}.map {it[0]!!.toInt() to it[1]!!.toInt()}
        assertEquals(0,ranges.first().first);assertEquals(TextRules.codePointLength(longText),ranges.last().second);assertTrue(ranges.zipWithNext().all {(a,b)->b.first<=a.second})
        SemanticEncoder(g.models).use {encoder->ranges.forEach {(start,end)->assertTrue(encoder.inputLength(TextRules.slice(longText,start,end))<=256)}}
        val replaced=publish(park,"我没去公园，留在家里整理书架。");g.memory.sync()
        assertFalse(g.memory.live(zh.first()));assertNotEquals(original.id,replaced.id)
        assertEquals("0",g.store.read {scalar("SELECT count(*) FROM memory_vector WHERE revision_id=?",original.id)})
        g.deletion.deleteEvents(setOf(park.id));g.deletion.progress.value=null
        assertTrue(g.memory.retrieve("紫竹院公园的桂花拿铁").none {it.day==day})
        assertEquals("0",g.store.read {scalar("SELECT count(*) FROM memory_vector WHERE revision_id=?",replaced.id)})
        assertTrue(g.store.read {rows("PRAGMA foreign_key_check")}.isEmpty())
        report("v12-vectors",JSONObject().put("dimensions",384).put("indexedChunks",count).put("synonymTopHit",true).put("crossLanguageTopHit",true).put("longTextCoveredWithoutTokenTruncation",true).put("editedVersionEvicted",true).put("deletedContentNotRetrieved",true).put("foreignKeysValid",true).put("passed",true))
        g.deletion.deleteDays(setOf(day,other));g.deletion.progress.value=null
    }
    @Test fun cSpeakerEmbeddingsAreOptInAndDeletionRetiresOldKey():Unit=runBlocking {
        if(g.voice.enrolled()){g.deletion.deleteVoiceprint();g.deletion.progress.value=null}
        val zh=samples("zh.wav");val en=samples("en.wav")
        try {
            assertEquals(SpeakerPolicy.UNKNOWN,g.voice.match(zh).first)
            val v=g.voice.vector(zh);val v2=g.voice.vector(zh);val other=g.voice.vector(en)
            val same=Vectors.cosine(v,v2);val different=Vectors.cosine(v,other);assertTrue(same>.99f);assertTrue(different<same)
            val day="2098-11-03";val surviving=note(day,"声纹删除时保留这条独立手记。")
            g.store.transaction {setting("voiceprint_profile",JSONObject().put("model","campplus-zh-en-aa3cfc16").put("embedding",Base64.getEncoder().encodeToString(Vectors.bytes(v))).toString());savePreferences(preferences().copy(voiceprintEnabled=true))}
            val tentative=g.voice.match(zh);g.store.savePreferences(g.store.preferences().copy(voiceprintEnabled=false));assertEquals(SpeakerPolicy.UNKNOWN,g.voice.match(zh).first)
            val old=g.store.descriptor.getLong("generation");g.deletion.deleteVoiceprint();g.deletion.progress.value=null
            assertFalse(g.voice.enrolled());assertFalse(g.vault.exists("db",old));assertTrue(g.store.read {events(day).any {it.id==surviving.id}})
            g.store.transaction {setting("voiceprint_profile",JSONObject().put("model","campplus-zh-en-aa3cfc16").put("embedding",Base64.getEncoder().encodeToString(Vectors.bytes(v))).toString())}
            val secondOld=g.store.descriptor.getLong("generation");g.deletion.deleteVoiceprint();g.deletion.progress.value=null
            assertFalse(g.voice.enrolled());assertFalse(g.vault.exists("db",secondOld))
            report("v12-speaker",JSONObject().put("embeddingDimensions",v.size).put("sameFixtureCosine",same.toDouble()).put("differentFixtureCosine",different.toDouble()).put("tentativeFixtureMatch",tentative.first).put("disabledReturnsUnknown",true).put("profileDeleted",true).put("reEnrolledProfileDeletedAgain",true).put("oldKeyRetired",true).put("diaryPreserved",true).put("ownerEnrollmentPerformed",false).put("identityAccuracyMeasured",false).put("passed",true))
            v.fill(0f);v2.fill(0f);other.fill(0f);g.deletion.deleteDays(setOf(day));g.deletion.progress.value=null
        }finally {zh.fill(0f);en.fill(0f)}
    }
    private suspend fun readyModel(){
        if(!g.models.sparkFile.exists()){
            val source=File(g.app.getExternalFilesDir(null),"spark-v12-test-source.litertlm");assertTrue("Push the fixed model into the disposable test app first",source.exists());g.models.importSpark(android.net.Uri.fromFile(source))
        }
        val power=g.app.getSystemService(android.os.PowerManager::class.java)
        withTimeout(300000){while(power.currentThermalStatus>=android.os.PowerManager.THERMAL_STATUS_MODERATE)delay(1000)}
    }
    @Test fun dRealAssistantAndAudioMeaningKeepTheirSources():Unit=runBlocking {
        readyModel();g.store.transaction {exec("DELETE FROM ai_budget WHERE day=?",java.time.LocalDate.now().toString())};val day="2098-11-04";val otherDay="2098-11-05";g.deletion.deleteDays(setOf(day,otherDay));g.deletion.progress.value=null
        val e=note(day,"傍晚我和朋友在紫竹院公园散步。今天没有买手机。");publish(e);g.memory.sync();g.lock.unlocked.value=true
        val answer=g.assistant.answer("我记录过去哪个公园散步？")!!
        assertTrue("The real model fell back instead of answering",answer.generated);assertTrue(answer.sources.any {it.day==day});assertTrue(answer.text.contains("紫竹院"))
        assertTrue(g.store.read {scalar("SELECT count(*) FROM assistant_turn").toInt()}>0)
        val other=note(otherDay,"晚上在家烤面包，尝试了新的配方。");publish(other);g.assistant.refresh()
        assertTrue("An unrelated diary must not erase the conversation",g.assistant.history.value.any {it.question==answer.question})
        readyModel();val second=g.assistant.answer("哪天记录了烤面包？")!!;assertTrue(second.generated);assertTrue(second.sources.all {it.day==otherDay})
        publish(e);g.assistant.refresh()
        assertFalse(g.assistant.history.value.any {it.question==answer.question});assertTrue(g.assistant.history.value.any {it.question==second.question})
        val raw=Event(g.store.id(),day,Source.AUDIO,"今天去攻元散不。没有买手机。",1000,6000,"Asia/Shanghai",metadata=mapOf("speaker" to "UNKNOWN","language" to "zh"));g.store.commitEvent(raw,g.store.epoch)
        assertTrue(PhoneticHints.suggest(raw.text,listOf(e.text)).any {it.raw=="攻元散不"&&it.candidate=="公园散步"})
        readyModel();g.interpretations.analyze(listOf(raw,e),g.store.epoch)
        val meaning=g.interpretations.meaning(raw);assertNotNull("The model did not finish audio analysis",meaning)
        assertEquals(raw.text,g.store.read {events(day).first {it.id==raw.id}.text})
        assertTrue(e.id in g.interpretations.dependencies(raw))
        g.store.savePreferences(g.store.preferences().copy(includeAudioInterpretations=true))
        // Per-record correction remains in Sources. DailySummaryDeviceTest verifies
        // the separate whole-day narrative rather than a conversion inventory.
        assertNotNull(g.interpretations.meaning(raw))
        assertTrue(e.id in g.interpretations.dependencies(raw))
        g.lock.unlocked.value=true;g.interpretations.confirm(raw,meaning!!.meaning,g.store.epoch)
        val confirmed=g.store.read {events(day).single {it.metadata["confirmed_from_audio"]==raw.id}}
        g.deletion.deleteEvents(setOf(e.id));g.deletion.progress.value=null
        assertNull(g.interpretations.meaning(raw));assertTrue(g.store.read {events(day).none {it.id==confirmed.id}});assertEquals("0",g.store.read {scalar("SELECT count(*) FROM assistant_turn")})
        assertTrue(g.store.read {events(day).any {it.id==raw.id}});assertTrue(g.store.read {events(otherDay).any {it.id==other.id}})
        assertNull(g.store.diary(day));assertNull(g.store.diary(day,"CANDIDATE"));assertTrue(g.memory.retrieve("公园散步").none {it.day==day})
        report("v12-ai",JSONObject().put("assistantGenerated",answer.generated).put("answerCitesRealDiary",true).put("unrelatedPublishPreservesConversation",true).put("editingPrunesOnlyAffectedAnswers",true).put("rawAsrPreserved",true).put("correctionStoredSeparately",true).put("contextDependenciesTracked",true).put("deletingContextRemovesInterpretationAndConfirmedNote",true).put("unknownSpeakerNotPromoted",raw.metadata["speaker"]=="UNKNOWN").put("conversationClearedOnDeletion",true).put("passed",true))
        g.deletion.deleteDays(setOf(day,otherDay));g.deletion.progress.value=null
    }
}
