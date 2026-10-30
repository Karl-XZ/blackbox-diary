package com.blackbox.diary.core

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object RecognitionLanguages {
    val available=linkedMapOf("zh" to "中文（普通话）","en" to "英语","yue" to "粤语","ja" to "日语","ko" to "韩语")
    fun normalize(selected:Set<String>)=selected.intersect(available.keys).ifEmpty {setOf("zh")}
    fun label(selected:Set<String>)=normalize(selected).joinToString("、") {available.getValue(it)}
    fun allows(selected:Set<String>,detected:String)=detected.removePrefix("<|").removeSuffix("|>") in normalize(selected)
}
object Vectors {
    fun normalized(a:FloatArray):FloatArray {require(a.isNotEmpty()&&a.all {it.isFinite()});val n=sqrt(a.sumOf {it.toDouble()*it});require(n>1e-9);return FloatArray(a.size){(a[it]/n).toFloat()}}
    fun cosine(a:FloatArray,b:FloatArray):Float {require(a.size==b.size);var ab=0.0;var aa=0.0;var bb=0.0;for(i in a.indices){ab+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i]};return if(aa*bb<1e-18)0f else (ab/sqrt(aa*bb)).toFloat().coerceIn(-1f,1f)}
    fun bytes(a:FloatArray)=ByteBuffer.allocate(a.size*4).order(ByteOrder.LITTLE_ENDIAN).apply {a.forEach {putFloat(it)}}.array()
    fun floats(b:ByteArray):FloatArray {require(b.size%4==0);val buffer=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);return FloatArray(b.size/4){buffer.float}}
}
object SpeakerPolicy {
    const val UNKNOWN="UNKNOWN"
    const val POSSIBLE_SELF="POSSIBLE_SELF"
    // This is a conservative operating threshold, not a calibrated identity probability.
    const val THRESHOLD=.75f
    fun classify(scores:List<Float>,speechSeconds:Double):String = if(speechSeconds>=3.0&&scores.size>=2&&scores.all {it.isFinite()&&it>=THRESHOLD})POSSIBLE_SELF else UNKNOWN
    fun label(speaker:String)=if(speaker==POSSIBLE_SELF)"疑似本人（声纹辅助，未确认）" else "说话人未知，可能来自旁人或设备播放"
}
@Serializable data class AudioMeaning(val meaning:String,val uncertain:Boolean=true,val reason:String="",val meaningful:Boolean=true)
@Serializable data class MemoryHit(val blockId:String,val revisionId:String,val day:String,val text:String,val origin:String,val score:Float,val epoch:Long)
@Serializable data class AssistantPart(val text:String,val citations:List<String>)
@Serializable data class AssistantPlan(val parts:List<AssistantPart> = emptyList(),val insufficient:Boolean=false)
object AssistantGrounding {
    private const val BOUNDARY="。！？!?；;\n"
    /** Restore the cited sentence around an exact substring, retaining negation and attribution. */
    fun completeSentences(plan:AssistantPlan,excerpts:Map<String,String>):AssistantPlan? {
        if(plan.insufficient)return plan.takeIf {valid(it,excerpts)}
        if(plan.parts.size !in 1..6)return null
        val parts=plan.parts.map {part->
            if(TextRules.codePointLength(part.text) !in 2..350||part.citations.size !in 1..6)return null
            val sentences=part.citations.map {id->
                val source=excerpts[id]?:return null
                val at=source.indexOf(part.text);if(at<0)return null
                var start=at;var end=at+part.text.length
                while(start>0&&source[start-1] !in BOUNDARY)start--
                while(end<source.length&&source[end-1] !in BOUNDARY)end++
                source.substring(start,end)
            }.distinct()
            if(sentences.size!=1)return null
            part.copy(text=sentences.single())
        }.distinct()
        return plan.copy(parts=parts).takeIf {valid(it,excerpts)}
    }
    private fun wholeSentences(source:String,quote:String):Boolean {
        var at=source.indexOf(quote)
        while(at>=0){val end=at+quote.length
            if((at==0||source[at-1] in BOUNDARY)&&(end==source.length||quote.last() in BOUNDARY))return true
            at=source.indexOf(quote,at+1)
        };return false
    }
    fun valid(plan:AssistantPlan,excerpts:Map<String,String>):Boolean {
        if(plan.insufficient)return plan.parts.isEmpty()
        return plan.parts.size in 1..6&&plan.parts.all {p->
            TextRules.codePointLength(p.text) in 2..350&&p.citations.size in 1..6&&p.citations.distinct().size==p.citations.size&&p.citations.all {id->excerpts[id]?.let {wholeSentences(it,p.text)}==true}
        }
    }
}
@Serializable data class AssistantAnswer(val question:String,val text:String,val sources:List<MemoryHit>,val epoch:Long,val generated:Boolean,val createdMs:Long=System.currentTimeMillis())
