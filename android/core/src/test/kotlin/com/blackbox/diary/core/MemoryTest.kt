package com.blackbox.diary.core

import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*
import java.io.File

class MemoryTest {
    @Test fun tokenizerMatchesOfficialAcrossLanguagesAndNormalization(){
        val tokenizer=UnigramTokenizer(File("../app/src/main/assets/models/e5-tokenizer.json").readText())
        val examples=Json.parseToJsonElement(File("../models/tokenizer-golden.json").readText()).jsonArray
        examples.forEach {val e=it.jsonObject;val text=e.getValue("text").jsonPrimitive.content
            assertEquals(text,e.getValue("normalized").jsonPrimitive.content,tokenizer.normalize(text))
            assertArrayEquals(text,e.getValue("ids").jsonArray.map {x->x.jsonPrimitive.long}.toLongArray(),tokenizer.encode(text,512))}
        assertEquals(2L,tokenizer.encode("今天 ".repeat(1000),32).last());assertEquals(32,tokenizer.encode("今天 ".repeat(1000),32).size)
    }
    @Test fun voiceprintRequiresEnoughSpeechAndConsistentWindows(){
        assertEquals(SpeakerPolicy.UNKNOWN,SpeakerPolicy.classify(listOf(.99f),5.0))
        assertEquals(SpeakerPolicy.UNKNOWN,SpeakerPolicy.classify(listOf(.99f,.5f),5.0))
        assertEquals(SpeakerPolicy.UNKNOWN,SpeakerPolicy.classify(listOf(.99f,.99f),1.0))
        assertEquals(SpeakerPolicy.POSSIBLE_SELF,SpeakerPolicy.classify(listOf(.9f,.9f),5.0))
    }
    @Test fun languagesDefaultChineseAndRejectUnselected(){
        assertEquals(setOf("zh"),Preference().recognitionLanguages)
        assertFalse(RecognitionLanguages.allows(setOf("zh"),"<|en|>"))
        assertTrue(RecognitionLanguages.allows(setOf("zh","en"),"<|en|>"))
        assertEquals(setOf("zh"),RecognitionLanguages.normalize(setOf("de")))
    }
    @Test fun vectorPersistenceKeepsDirection(){val a=Vectors.normalized(floatArrayOf(3f,4f));assertArrayEquals(a,Vectors.floats(Vectors.bytes(a)),0f);assertEquals(1f,Vectors.cosine(a,a),1e-6f)}
    @Test fun citationIdAloneCannotSupportInventedFacts(){
        val sources=mapOf("d0" to "今天没有买手机。")
        assertTrue(AssistantGrounding.valid(AssistantPlan(listOf(AssistantPart("今天没有买手机。",listOf("d0")))),sources))
        assertFalse(AssistantGrounding.valid(AssistantPlan(listOf(AssistantPart("买手机",listOf("d0")))),sources))
        assertFalse(AssistantGrounding.valid(AssistantPlan(listOf(AssistantPart("今天",listOf("d0")))),sources))
        assertFalse(AssistantGrounding.valid(AssistantPlan(listOf(AssistantPart("今天买了手机",listOf("d0")))),sources))
        assertFalse(AssistantGrounding.valid(AssistantPlan(listOf(AssistantPart("没有买手机",listOf("d9")))),sources))
        assertFalse(AssistantGrounding.valid(AssistantPlan(listOf(AssistantPart("没有买手机",listOf("d0"))),true),sources))
    }
    @Test fun partialAssistantQuotesRestoreNegationAndSpeakerContext(){
        val source="这是旁人的环境语音，不能确认本人买手机。后来整理书架。"
        val plan=AssistantPlan(listOf(AssistantPart("本人买手机",listOf("d0"))))
        val restored=AssistantGrounding.completeSentences(plan,mapOf("d0" to source))!!
        assertEquals("这是旁人的环境语音，不能确认本人买手机。",restored.parts.single().text)
        assertTrue(AssistantGrounding.valid(restored,mapOf("d0" to source)))
        val park="中午吃面，傍晚在紫竹院公园散步。"
        assertEquals(park,AssistantGrounding.completeSentences(AssistantPlan(listOf(AssistantPart("傍晚在紫竹院公园散步。",listOf("d0")))),mapOf("d0" to park))!!.parts.single().text)
    }
    @Test fun completingQuotesCannotInventOrCombineDifferentSources(){
        val plan=AssistantPlan(listOf(AssistantPart("去公园",listOf("d0","d1"))))
        assertNull(AssistantGrounding.completeSentences(plan,mapOf("d0" to "我没有去公园。","d1" to "我想去公园。")))
        assertNull(AssistantGrounding.completeSentences(AssistantPlan(listOf(AssistantPart("买了手机",listOf("d0")))),mapOf("d0" to "今天没有买手机。")))
    }
}
