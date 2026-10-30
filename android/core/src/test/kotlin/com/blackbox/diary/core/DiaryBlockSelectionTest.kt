package com.blackbox.diary.core

import org.junit.Test
import org.junit.Assert.*

class DiaryBlockSelectionTest {
    private fun audio(i:Int)=i.toLong() to DiaryBlock("$i","原文 $i","AI_TEMPLATE",listOf("source$i"),"audio.inferred.v1")
    @Test fun interpretationsSpanTheWholeDayRatherThanOnlyTheEarliestRecords() {
        val input=(0 until 80).map(::audio)
        val result=DiarySelection.blocks(input,24)
        assertEquals(24,result.size);assertEquals("0",result.first().id);assertEquals("79",result.last().id)
        assertTrue(result.all {b->input.any {it.second===b}})
        assertTrue(result.any {it.id.toInt() in 30..50})
    }
    @Test fun handwrittenNotesRemainAlongsideDistributedAmbientSpeech() {
        val note=100L to DiaryBlock("note","自己的补充","AI_TEMPLATE",listOf("note-source"),"note.quote.v1")
        val result=DiarySelection.blocks((0 until 80).map(::audio)+note,4)
        assertEquals(4,result.size);assertTrue(result.any {it.id=="note"});assertTrue(result.any {it.id=="0"});assertTrue(result.any {it.id=="79"})
    }
}
