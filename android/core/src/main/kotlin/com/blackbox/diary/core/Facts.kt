package com.blackbox.diary.core

import kotlinx.serialization.json.Json

sealed interface FactResult {data class Approved(val fact: Fact):FactResult;data class Rejected(val code:String):FactResult}
object FactValidator {
    val json=Json {ignoreUnknownKeys=false;isLenient=false;encodeDefaults=true}
    private val templateTypes=mapOf("screen.quote.v1" to ClaimType.SCREEN_TEXT_OBSERVED,"audio.quote.v1" to ClaimType.AUDIO_TEXT_OBSERVED,"note.quote.v1" to ClaimType.USER_NOTE_REPORTED,"system.gap.v1" to ClaimType.SYSTEM_GAP_RECORDED,"system.processing.v1" to ClaimType.PROCESSING_STATUS)
    private val sources=mapOf(ClaimType.SCREEN_TEXT_OBSERVED to Source.SCREEN,ClaimType.AUDIO_TEXT_OBSERVED to Source.AUDIO,ClaimType.USER_NOTE_REPORTED to Source.USER_NOTE,ClaimType.SYSTEM_GAP_RECORDED to Source.SYSTEM,ClaimType.PROCESSING_STATUS to Source.SYSTEM)
    fun parse(input: String): CandidateEnvelope? = runCatching { json.decodeFromString<CandidateEnvelope>(input).also { require(it.version=="1.1"&&it.epoch>0&&it.candidates.size<=64);require(it.candidates.map {c->c.id}.distinct().size==it.candidates.size) } }.getOrNull()
    fun validate(c: ClaimCandidate,events: Map<String,Event>,expectedEpoch: Long,currentEpoch: Long,includeReview: Boolean=false):FactResult {
        fun reject(code:String)=FactResult.Rejected(code)
        if(expectedEpoch!=currentEpoch)return reject("EPOCH_STALE")
        if(!Regex("^[A-Za-z0-9_-]{1,64}$").matches(c.id))return reject("CANDIDATE_ID_INVALID")
        if(templateTypes[c.templateId]!=c.type)return reject("TYPE_TEMPLATE_MISMATCH")
        if(c.evidence.size !in 1..8)return reject("EVIDENCE_COUNT_INVALID")
        var low=false
        for(e in c.evidence){
            val source=events[e.eventId]?:return reject("SOURCE_UNAVAILABLE")
            if(source.lifecycle!=Life.ACTIVE)return reject("SOURCE_UNAVAILABLE")
            if(source.revision!=e.eventRevision)return reject("REVISION_MISMATCH")
            if(source.digest!=e.textSha256||TextRules.sha256(source.text)!=source.digest)return reject("SOURCE_DIGEST_MISMATCH")
            if(source.source!=sources[c.type])return reject("SOURCE_TYPE_MISMATCH")
            if(e.startCp<0||e.endCp<=e.startCp||e.endCp>TextRules.codePointLength(source.text)||TextRules.codePointLength(e.quote)>300)return reject("SPAN_INVALID")
            if(TextRules.slice(source.text,e.startCp,e.endCp)!=e.quote)return reject("QUOTE_MISMATCH")
            if(TextRules.containsSensitive(e.quote))return reject("SENSITIVE_QUOTE")
            if(source.quality==Quality.UNUSABLE)return reject("QUALITY_UNUSABLE")
            if(source.source in listOf(Source.SCREEN,Source.AUDIO)){
                if(source.quality!=Quality.HIGH||source.calibrationProfileId.isNullOrBlank()){
                    if(!includeReview)return reject("QUALITY_REVIEW_REQUIRED")
                    low=true
                }
            }
        }
        val source=events.getValue(c.evidence.first().eventId)
        val system=c.type in listOf(ClaimType.SYSTEM_GAP_RECORDED,ClaimType.PROCESSING_STATUS)
        if(system&&c.slots.isNotEmpty())return reject("SLOT_NOT_ALLOWED")
        if(!system&&(c.slots.keys!=setOf("quoted_text")))return reject("SLOT_INVALID")
        val q=if(system)"" else c.evidence.getOrNull(c.slots.getValue("quoted_text").evidenceIndex)?.quote?:return reject("BINDING_OUT_OF_RANGE")
        val period=DayRules.period(source.startMs,source.zoneId)
        val text=when(c.type){
            ClaimType.SCREEN_TEXT_OBSERVED -> "$period，我的屏幕记录中出现过“$q”这段文字。"
            ClaimType.AUDIO_TEXT_OBSERVED -> "$period，一段语音记录里提到了“$q”。"
            ClaimType.USER_NOTE_REPORTED -> "我在手记里写道：“$q”。"
            ClaimType.SYSTEM_GAP_RECORDED -> {if(source.metadata["kind"]!="CAPTURE_GAP")return reject("SYSTEM_METADATA_INVALID");source.text}
            ClaimType.PROCESSING_STATUS -> {if(source.metadata["kind"]!="PROCESSING_STATUS")return reject("SYSTEM_METADATA_INVALID");source.text}
        }
        return FactResult.Approved(Fact(c.id,c.type,c.templateId,if(low)"[低可信识别] $text" else text,c.evidence.map {it.eventId}.distinct(),source.startMs,c.evidence,low))
    }
    fun fromEvent(e: Event,maxQuoteCp:Int=300): ClaimCandidate? {
        if(e.text.isBlank())return null
        val type=when(e.source){Source.SCREEN->ClaimType.SCREEN_TEXT_OBSERVED;Source.AUDIO->ClaimType.AUDIO_TEXT_OBSERVED;Source.USER_NOTE->ClaimType.USER_NOTE_REPORTED;Source.SYSTEM->when(e.metadata["kind"]){"CAPTURE_GAP"->ClaimType.SYSTEM_GAP_RECORDED;"PROCESSING_STATUS"->ClaimType.PROCESSING_STATUS;else->return null}}
        val template=templateTypes.entries.first {it.value==type}.key;val quote=TextRules.takeCp(e.text,maxQuoteCp.coerceIn(1,300))
        return ClaimCandidate("c_"+TextRules.sha256("${e.id}|${e.revision}|${TextRules.codePointLength(quote)}|${e.digest}").take(40),type,template,listOf(Evidence(e.id,e.revision,0,TextRules.codePointLength(quote),quote,e.digest)),if(e.source==Source.SYSTEM)emptyMap() else mapOf("quoted_text" to SlotBinding(0)))
    }
    fun planOrder(plan: NarrativePlan,facts: List<Fact>):List<Fact>? {
        val byId=facts.associateBy {it.id};val ids=plan.orderedClaimIds
        if(ids.size>64||ids.distinct().size!=ids.size||ids.any {it !in byId})return null
        return ids.map(byId::getValue)
    }
}

object Lineage {
    fun closure(roots: Set<String>,edges: Collection<Pair<String,String>>): Set<String> {
        val children=edges.groupBy({it.first},{it.second});val todo=ArrayDeque(roots);val seen=linkedSetOf<String>()
        while(todo.isNotEmpty()){val id=todo.removeFirst();if(seen.add(id))children[id]?.forEach(todo::addLast)};return seen
    }
    fun canAdd(parent:String,child:String,edges:Collection<Pair<String,String>>)=parent!=child&&parent !in closure(setOf(child),edges)
}
