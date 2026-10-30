package com.blackbox.diary.core

import kotlinx.serialization.Serializable

@Serializable data class NarrativePoint(val text:String,val citations:List<String>,val personal:Boolean=false,val uncertain:Boolean=false)
@Serializable data class NarrativeDigest(val points:List<NarrativePoint>)
@Serializable data class DailyNarrative(val title:String,val paragraphs:List<NarrativePoint>)
data class NarrativeEvidence(val text:String,val personal:Boolean,val uncertain:Boolean)

object NarrativeWriting {
    /** Remove generated outcome/purpose codas unless that exact statement came from a source. */
    fun clean(text:String,originals:List<String>):String {
        val source=originals.joinToString("\n")
        val coda=Regex("[，；]((?:让|使|从而|为后续|为当天|为这一天|为一天)[^。！？\\n]{3,80})([。！？])")
        return coda.replace(text){m->if(m.groupValues[1] in source)m.value else m.groupValues[2]}.trim()
    }
    fun title(text:String):String=text.replace(Regex("\\d{4}(?:年|[-/])\\d{1,2}(?:月|[-/])\\d{1,2}日?"),"").replace(Regex("\\d{1,2}月\\d{1,2}日"),"").trim().takeIf {it.length>=2}?:"这一天的回顾"
}

/** Structural/attribution checks, complemented by a separate model grounding review. */
object NarrativeGuard {
    private val owner=Regex("(?<![自无忘物本超真])我")
    fun inheritScope(p:NarrativePoint,evidence:Map<String,NarrativeEvidence>):NarrativePoint {
        if(p.citations.isEmpty()||p.citations.any {it !in evidence})return p
        val refs=p.citations.map(evidence::getValue)
        return p.copy(personal=refs.all {it.personal},uncertain=p.uncertain||refs.any {it.uncertain})
    }
    fun pointIssue(p:NarrativePoint,evidence:Map<String,NarrativeEvidence>,maxChars:Int=180):String? {
        if(p.text.isBlank()||TextRules.codePointLength(p.text)>maxChars)return "TEXT_LENGTH"
        if(p.citations.isEmpty()||p.citations.distinct().size!=p.citations.size)return "CITATION_SHAPE"
        if(p.citations.any {it !in evidence})return "UNKNOWN_CITATION"
        val refs=p.citations.map(evidence::getValue)
        val sourceText=refs.joinToString(" "){it.text}
        if((p.personal||owner.containsMatchIn(p.text))&&refs.any {!it.personal})return "OWNER_ATTRIBUTION"
        if(refs.any {it.uncertain}&&!p.uncertain)return "UNCERTAINTY_LOST"
        if(p.text.contains("[AI推断")||p.text.contains("一段环境语音表达")||p.text.contains("自动修正]"))return "CONVERSION_INVENTORY"
        val embellishment=Regex("为.{0,18}(?:打下|奠定|筑牢).{0,10}基础|画上.{0,12}句号|让.{0,15}(?:更顺畅|更充实|更有意义)|充实而有意义|整体保持.{0,6}状态")
        if(embellishment.findAll(p.text).any {it.value !in sourceText})return "UNSUPPORTED_EFFECT"
        val allowed=Regex("\\d+(?:\\.\\d+)?").findAll(sourceText).map {it.value}.toSet()
        if(Regex("\\d+(?:\\.\\d+)?").findAll(p.text).any {it.value !in allowed})return "NEW_NUMBER"
        return if(p.text==TextRules.sanitize(p.text))null else "UNSANITIZED_TEXT"
    }
    fun validPoint(p:NarrativePoint,evidence:Map<String,NarrativeEvidence>,maxChars:Int=180)=pointIssue(p,evidence,maxChars)==null
    fun digestIssue(p:NarrativeDigest,evidence:Map<String,NarrativeEvidence>):String?=if(p.points.size>3)"TOO_MANY_POINTS" else p.points.firstNotNullOfOrNull {pointIssue(it,evidence,100)}
    fun validDigest(p:NarrativeDigest,evidence:Map<String,NarrativeEvidence>)=digestIssue(p,evidence)==null
    fun validDiary(p:DailyNarrative,evidence:Map<String,NarrativeEvidence>):Boolean =
        TextRules.codePointLength(p.title) in 2..24&&p.title==TextRules.sanitize(p.title)&&p.paragraphs.size in 1..4&&
        p.paragraphs.all {validPoint(it,evidence,200)}&&p.paragraphs.sumOf {TextRules.codePointLength(it.text)} in 40..600&&
        p.paragraphs.map {it.text}.distinct().size==p.paragraphs.size
}
