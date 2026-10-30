package com.blackbox.diary.core

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.time.*
import kotlinx.serialization.encodeToString

class DomainTest {
    @Test fun boundedDiarySelectionPreservesTheWholeDay(){val facts=(0..99).map {i->Fact("f$i",if(i==50)ClaimType.USER_NOTE_REPORTED else ClaimType.SCREEN_TEXT_OBSERVED,"screen.quote.v1","观察$i",listOf("e$i"),i.toLong(),emptyList())};val selected=DiarySelection.distributed(facts,10);assertEquals(10,selected.size);assertTrue(selected.any {it.id=="f50"});assertEquals("f0",selected.first().id);assertEquals("f99",selected.last().id)}
    @Test fun clockJumpsAreDetectedWithoutConfusingTimezoneWithElapsedTime(){assertTrue(ClockRules.discontinuity(1000,0,7_202_000,1_000_000_000));assertTrue(ClockRules.discontinuity(1000,0,-7_198_000,1_000_000_000));assertFalse(ClockRules.discontinuity(1000,0,2000,1_000_000_000))}
    private fun event(text:String="今天完成了一本书")=Event("event-1","2026-10-27",Source.USER_NOTE,text,100,200,"Asia/Shanghai",Quality.HIGH)
    @Test fun exclusionsReduceDenominatorButFailuresDoNot() {
        val r=Intervals.report(listOf(Interval(0,100)),listOf(Interval(10,30)),listOf(Interval(0,20),Interval(50,70)),listOf(Interval(0,100)))
        assertEquals(80,r.expectedMs);assertEquals(30,r.capturedMs);assertEquals(50,r.gapMs);assertEquals(.375,r.ratio!!,0.0)
    }
    @Test fun zeroDenominatorIsUnknown(){assertNull(Intervals.report(emptyList(),emptyList(),emptyList(),listOf(Interval(0,10))).ratio)}
    @Test fun overlappingIntervalsDoNotDoubleCount(){assertEquals(20,Intervals.duration(listOf(Interval(0,10),Interval(5,20),Interval(8,12))))}
    @Test fun intervalAlgebraClipsOutsideDay(){assertEquals(5,Intervals.report(listOf(Interval(0,100)),emptyList(),listOf(Interval(10,90)),listOf(Interval(20,25))).capturedMs)}
    @Test fun daylightSavingsDaysAreNotAlways24Hours(){assertEquals(23*3_600_000L,DayRules.range("2026-03-08","America/New_York").duration);assertEquals(25*3_600_000L,DayRules.range("2026-11-01","America/New_York").duration)}
    @Test fun scheduledClockWrapsMidnight(){val p=Preference();assertTrue(DayRules.inSleep(Instant.parse("2026-10-27T16:00:00Z").toEpochMilli(),"Asia/Shanghai",p));assertFalse(DayRules.inSleep(Instant.parse("2026-10-27T05:00:00Z").toEpochMilli(),"Asia/Shanghai",p))}
    @Test fun ttlDoesNotExtendWhenWallClockMovesBack(){assertTrue(MediaTtl.expired(100,1100,100,50,1100,true));assertFalse(MediaTtl.expired(100,1100,100,50,1099,true))}
    @Test fun newBootDoesNotCompareElapsedClocks(){assertFalse(MediaTtl.expired(100,1100,100,200,999999,false))}
    @Test fun privacyMaskRunsBeforeHash(){val s=TextRules.sanitize("手机号 13812345678，验证码: 123456，卡 4111111111111111")
        assertFalse(s.contains("13812345678"));assertFalse(s.contains("123456"));assertFalse(s.contains("4111111111111111"));assertEquals(s,TextRules.sanitize(s))}
    @Test fun privacyAlsoCoversGroupedAndFullWidthPhones(){val s=TextRules.sanitize("138 1234 5678；１３８１２３４５６７８；密码：春天的一杯茶");assertFalse(s.contains("138"));assertFalse(s.contains("春天的一杯茶"));assertEquals(s,TextRules.sanitize(s))}
    @Test fun normalizationIsStable(){assertEquals("é",TextRules.nfc("e\u0301"));assertEquals(64,TextRules.sha256("a").length)}
    @Test fun codePointOffsetsHandleEmoji(){assertEquals("😀",TextRules.slice("甲😀乙",1,2));assertEquals(3,TextRules.codePointLength("甲😀乙"))}
    @Test fun exactObservationAcceptsAQuotedSource(){val e=event();val c=FactValidator.fromEvent(e)!!;assertTrue(FactValidator.validate(c,mapOf(e.id to e),1,1) is FactResult.Approved)}
    @Test fun changedQuoteIsRejected(){val e=event();val c=FactValidator.fromEvent(e)!!;val fake=c.copy(evidence=c.evidence.map {it.copy(quote="今天赚了一百万")});assertEquals("QUOTE_MISMATCH",(FactValidator.validate(fake,mapOf(e.id to e),1,1) as FactResult.Rejected).code)}
    @Test fun staleEpochCannotCommitFacts(){val e=event();assertEquals("EPOCH_STALE",(FactValidator.validate(FactValidator.fromEvent(e)!!,mapOf(e.id to e),1,2) as FactResult.Rejected).code)}
    @Test fun wrongRevisionCannotPassEvidence(){val e=event();val c=FactValidator.fromEvent(e)!!;assertEquals("REVISION_MISMATCH",(FactValidator.validate(c,mapOf(e.id to e.copy(revision=2)),1,1) as FactResult.Rejected).code)}
    @Test fun digestCannotBeForged(){val e=event();val c=FactValidator.fromEvent(e)!!;assertEquals("SOURCE_DIGEST_MISMATCH",(FactValidator.validate(c,mapOf(e.id to e.copy(text="替换内容")),1,1) as FactResult.Rejected).code)}
    @Test fun uncalibratedHighScoreRemainsReview(){val e=event().copy(source=Source.SCREEN,quality=Quality.HIGH);val c=FactValidator.fromEvent(e)!!;assertEquals("QUALITY_REVIEW_REQUIRED",(FactValidator.validate(c,mapOf(e.id to e),1,1) as FactResult.Rejected).code);assertTrue((FactValidator.validate(c,mapOf(e.id to e),1,1,true) as FactResult.Approved).fact.lowConfidence)}
    @Test fun sourceTypesCannotBeRelabeled(){val e=event();val c=FactValidator.fromEvent(e)!!.copy(type=ClaimType.AUDIO_TEXT_OBSERVED,templateId="audio.quote.v1");assertEquals("SOURCE_TYPE_MISMATCH",(FactValidator.validate(c,mapOf(e.id to e),1,1) as FactResult.Rejected).code)}
    @Test fun schemaRejectsUnknownFieldsAndVersions(){val env=CandidateEnvelope("1.1",1,listOf(FactValidator.fromEvent(event())!!));val encoded=FactValidator.json.encodeToString(env);assertNotNull(FactValidator.parse(encoded));assertNull(FactValidator.parse(encoded.dropLast(1)+",\"hack\":true}"));assertNull(FactValidator.parse(encoded.replace("1.1","9.9")))}
    @Test fun dependencyDeletionIncludesCrossDayAndHistoricalVersions(){val edges=listOf("event" to "summary","summary" to "fact","fact" to "version","version" to "block","block" to "index","own-note" to "version");val closure=Lineage.closure(setOf("event"),edges);assertTrue(closure.containsAll(listOf("summary","version","index")));assertFalse("own-note" in closure);assertFalse(Lineage.canAdd("index","event",edges))}
    @Test fun duplicatePlansAndUnknownIdsAreRejected(){val e=event();val f=(FactValidator.validate(FactValidator.fromEvent(e)!!,mapOf(e.id to e),1,1) as FactResult.Approved).fact;assertNull(FactValidator.planOrder(NarrativePlan(listOf(f.id,f.id)),listOf(f)));assertNull(FactValidator.planOrder(NarrativePlan(listOf("unknown")),listOf(f)))}
    @Test fun cjkSearchPreservesPhrasesAndQuotes(){assertTrue(SearchTokens.tokens("今天散步").contains("散步"));assertTrue(SearchTokens.literalMatch("今天去公园散步","公园 散步"));assertFalse(SearchTokens.literalMatch("今天去散公步园","公园"));assertFalse(SearchTokens.ftsQuery("\" OR *").contains(" OR "))}
    @Test fun highlightHandlesNormalizationAndSupplementaryCharacters(){assertEquals(listOf(1..1),SearchTokens.highlightRanges("甲Ａ乙","a"));assertEquals(listOf(1..2),SearchTokens.highlightRanges("甲😀乙","😀"));assertEquals(listOf(0..1),SearchTokens.highlightRanges("e\u0301","é"))}
    @Test fun authenticatedChunksRecoverOnlySealedRecords(){val key=ChunkCodec.randomKey();val output=ByteArrayOutputStream();val out=DataOutputStream(output);val h=ChunkCodec.writeHeader(out,"opaque")
        repeat(3){ChunkCodec.write(out,key,h,it.toLong(),ChunkCodec.Record(it.toLong(),it.toLong(),ByteArray(320){it.toByte()}))};val sealed=output.toByteArray()
        for(cut in 1..330 step 23){val bytes=sealed.copyOf(sealed.size-cut);DataInputStream(ByteArrayInputStream(bytes)).use {input->val head=ChunkCodec.readHeader(input);var n=0;val records=ChunkCodec.read(input,key,head){n++;assertEquals(320,it.pcm.size)};assertEquals(n,records);assertTrue(records<3)}}
    }
    @Test fun wrongKeyOrChangedHeaderCannotRevealPcm(){val key=ChunkCodec.randomKey();val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes);val h=ChunkCodec.writeHeader(out,"one");ChunkCodec.write(out,key,h,0,ChunkCodec.Record(1,1,ByteArray(100)));DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {i->val header=ChunkCodec.readHeader(i);assertEquals(0,ChunkCodec.read(i,ChunkCodec.randomKey(),header){fail("revealed")})}}
}
