package com.blackbox.diary.export

import android.net.Uri
import com.blackbox.diary.AppGraph
import java.io.OutputStreamWriter

object DiaryExporter {
    /** The monitor is a short export lease: deletion cannot interleave with a plaintext write. */
    fun write(graph:AppGraph,day:String,uri:Uri,markdown:Boolean,evidence:Boolean=false)=graph.store.read {
        check(!maintenance&&graph.lock.unlocked.value)
        val diary=diary(day)?:error("DIARY_UNAVAILABLE")
        val body=buildString {
            append(if(markdown)"# " else "").append(diary.title).append("\n\n").append(day).append("\n\n")
            diary.blocks.forEach { b->append(b.text).append("\n\n");if(evidence&&b.eventIds.isNotEmpty())append("来源：").append(b.eventIds.joinToString()).append("\n\n") }
            if(diary.fallback)append("使用基础模板整理。\n")
            if(diary.hasGaps)append("当天存在采集缺口，以上内容仅代表已记录片段。\n")
        }
        graph.app.contentResolver.openOutputStream(uri,"wt")!!.use { stream->OutputStreamWriter(stream,Charsets.UTF_8).use { it.write(body);it.flush() } }
    }
}
