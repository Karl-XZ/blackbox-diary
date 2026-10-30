package com.blackbox.diary.core

import org.junit.Test
import org.junit.Assert.*

class DailyNarrativeTest {
    private val evidence=mapOf("n0" to NarrativeEvidence("我上午整理了项目文档，下午没有去公园。",true,false),"n1" to NarrativeEvidence("环境谈话中可能在讨论地铁晚点。",false,true))
    @Test fun unknownSpeakerCannotBecomeFirstPerson() {
        assertFalse(NarrativeGuard.validPoint(NarrativePoint("我坐地铁去上班。",listOf("n1"),true,true),evidence))
        assertTrue(NarrativeGuard.validPoint(NarrativePoint("记录中的交流可能涉及地铁晚点。",listOf("n1"),false,true),evidence))
    }
    @Test fun unsupportedReferencesNumbersAndCertaintyAreRejected() {
        assertFalse(NarrativeGuard.validPoint(NarrativePoint("整理了材料。",listOf("invented")),evidence))
        assertFalse(NarrativeGuard.validPoint(NarrativePoint("我完成了12项工作。",listOf("n0"),true),evidence))
        assertFalse(NarrativeGuard.validPoint(NarrativePoint("地铁已经晚点。",listOf("n1")),evidence))
    }
    @Test fun aConversionInventoryIsNotADailyNarrative() {
        assertFalse(NarrativeGuard.validPoint(NarrativePoint("[AI推断 · 自动修正] 一段环境语音表达了：地铁晚点。",listOf("n1"),false,true),evidence))
        val text="今天把时间留给了项目资料，上午整理了文档，让已有的内容有了更清楚的脉络。下午没有去公园，这部分也如实保留在记录里。"
        assertTrue(NarrativeGuard.validDiary(DailyNarrative("整理资料的一天",listOf(NarrativePoint(text,listOf("n0"),true))),evidence))
    }
    @Test fun digestMayDiscardIrrelevantMediaAndKeepsEvidenceIds() {
        assertTrue(NarrativeGuard.validDigest(NarrativeDigest(emptyList()),evidence))
        assertFalse(NarrativeGuard.validDigest(NarrativeDigest(listOf(NarrativePoint("记录中的话题可能涉及地铁。",listOf("n1","n1"),false,true))),evidence))
    }
    @Test fun inventedBenefitsAndCannedEndingsAreNotSourceFacts() {
        assertFalse(NarrativeGuard.validPoint(NarrativePoint("我整理了文档，为后续工作筑牢基础。",listOf("n0"),true),evidence))
        assertFalse(NarrativeGuard.validPoint(NarrativePoint("这为一天画上平稳句号。",listOf("n0"),true),evidence))
    }
    @Test fun unsupportedOutcomeCodaIsRemovedWithoutChangingTheEventOrNegation() {
        assertEquals("我没有去公园，留在家里改文档。",NarrativeWriting.clean("我没有去公园，留在家里改文档，让当天的事务有序收尾。",listOf("下午没有去公园，留在家里修改文档。")))
        val source="我关掉电视，让房间安静下来。"
        assertEquals(source,NarrativeWriting.clean(source,listOf(source)))
    }
    @Test fun titleDoesNotRepeatTheDateAlreadyShownInTheHeader() {
        assertEquals("日常记录",NarrativeWriting.title("2026年10月28日日常记录"))
        assertEquals("阅读与整理",NarrativeWriting.title("阅读与整理"))
    }
    @Test fun abstractSelfConceptIsNotOwnerAttributionAndUncertaintyIsInherited() {
        val point=NarrativePoint("讨论可能涉及自我认知。",listOf("n1"),false,false)
        val inherited=NarrativeGuard.inheritScope(point,evidence)
        assertTrue(inherited.uncertain);assertFalse(inherited.personal)
        assertTrue(NarrativeGuard.validPoint(inherited,evidence))
        assertFalse(NarrativeGuard.validPoint(inherited.copy(text="我去了公园。"),evidence))
    }
}
