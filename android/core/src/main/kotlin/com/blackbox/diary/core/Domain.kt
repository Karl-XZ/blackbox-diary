package com.blackbox.diary.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.*

@Serializable enum class Channel { MICROPHONE, SCREEN }
@Serializable enum class Source { SCREEN, AUDIO, USER_NOTE, SYSTEM }
@Serializable enum class Quality { HIGH, REVIEW, UNUSABLE }
@Serializable enum class CaptureState { OFF, REQUESTING_PERMISSION, RECORDING, PAUSED_USER, PAUSED_RULE, NEEDS_PERMISSION, INTERRUPTED, PAUSED_MAINTENANCE }
@Serializable enum class Life { ACTIVE, TOMBSTONED, INVALIDATED }
@Serializable data class Event(
    val id: String, val day: String, val source: Source, val text: String,
    val startMs: Long, val endMs: Long, val zoneId: String,
    val quality: Quality = Quality.REVIEW, val revision: Int = 1,
    val digest: String = TextRules.sha256(text), val calibrationProfileId: String? = null,
    val lifecycle: Life = Life.ACTIVE, val metadata: Map<String,String> = emptyMap(),
)
@Serializable data class Evidence(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_revision") val eventRevision: Int,
    @SerialName("start_cp") val startCp: Int,
    @SerialName("end_cp") val endCp: Int,
    val quote: String,
    @SerialName("text_sha256") val textSha256: String,
)
@Serializable enum class ClaimType { SCREEN_TEXT_OBSERVED, AUDIO_TEXT_OBSERVED, USER_NOTE_REPORTED, SYSTEM_GAP_RECORDED, PROCESSING_STATUS }
@Serializable data class SlotBinding(@SerialName("evidence_index") val evidenceIndex: Int)
@Serializable data class ClaimCandidate(
    @SerialName("candidate_id") val id: String,
    @SerialName("claim_type") val type: ClaimType,
    @SerialName("template_id") val templateId: String,
    val evidence: List<Evidence>,
    @SerialName("slot_bindings") val slots: Map<String,SlotBinding> = emptyMap(),
)
@Serializable data class CandidateEnvelope(
    @SerialName("schema_version") val version: String,
    @SerialName("content_epoch") val epoch: Long,
    val candidates: List<ClaimCandidate>,
)
@Serializable data class Fact(val id: String, val type: ClaimType, val templateId: String, val text: String, val eventIds: List<String>, val startMs: Long, val evidence: List<Evidence>, val lowConfidence: Boolean = false)
@Serializable data class NarrativePlan(@SerialName("ordered_claim_ids") val orderedClaimIds: List<String>)
@Serializable data class DiaryBlock(val id: String, val text: String, val origin: String, val eventIds: List<String>, val templateId: String? = null, val userNoteRootId: String? = null,val primarySourceId:String?=null)
@Serializable data class Diary(val id: String, val day: String, val title: String, val blocks: List<DiaryBlock>, val revision: Int, val editor: String, val state: String, val fallback: Boolean, val pending: Int = 0, val hasGaps: Boolean = false)
@Serializable data class Preference(
    val sleepEnabled: Boolean = true, val sleepStart: String = "23:00", val sleepEnd: String = "07:00",
    val lowPower: Boolean = true, val maskSensitive: Boolean = true,
    val biometric: Boolean = false, val lockTimeoutSeconds: Int = 30,
    val generationTime: String = "02:00", val length: String = "标准", val tone: String = "生活化",
    val includeReview: Boolean = false, val retentionDays: Int? = null, val generationConfirmation: Boolean = false,
    val recognitionLanguages: Set<String> = setOf("zh"), val interpretAudio: Boolean = true,
    val includeAudioInterpretations: Boolean = true, val voiceprintEnabled: Boolean = false,
)
data class CaptureStatus(val channel: Channel, val state: CaptureState = CaptureState.OFF, val reason: String? = null, val pauseUntilMs: Long? = null)
data class SearchHit(val artifactId: String, val sourceId: String, val day: String, val source: String, val text: String, val epoch: Long)
data class QueueStatus(val audioPending: Int = 0, val audioFailed: Int = 0, val oldestExpiryMs: Long? = null, val ocrBusy: Boolean = false)
data class DeleteProgress(val operationId: String, val state: String, val message: String, val complete: Boolean = false)
data class DeviceCapability(val abi: String, val ramBytes: Long, val freeBytes: Long, val keystoreSecurity: String, val sdk: Int)
data class Recognition(val text: String, val score: Float? = null, val flags: Set<String> = emptySet(), val modelFingerprint: String, val segments:List<SpeechSegment> = emptyList())
data class SpeechSegment(val text:String,val startSample:Int,val endSample:Int,val language:String,val speaker:String="UNKNOWN",val similarity:Float?=null)

object DayRules {
    fun localDay(ms: Long, zone: String) = Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone)).toLocalDate().toString()
    fun range(day: String, zone: String): Interval {
        val d=LocalDate.parse(day);val z=ZoneId.of(zone)
        return Interval(d.atStartOfDay(z).toInstant().toEpochMilli(),d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli())
    }
    fun period(ms: Long, zone: String): String = when(Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone)).hour) { in 5..8->"清晨"; in 9..11->"上午"; in 12..13->"中午"; in 14..17->"下午"; in 18..22->"晚上"; else->"夜间" }
    fun inSleep(ms: Long, zone: String, p: Preference): Boolean {
        if(!p.sleepEnabled)return false
        val t=Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone)).toLocalTime();val a=LocalTime.parse(p.sleepStart);val b=LocalTime.parse(p.sleepEnd)
        return if(a==b)false else if(a<b)t>=a&&t<b else t>=a||t<b
    }
    fun nextGeneration(now: Instant, zone: ZoneId, clock: String): Instant {
        val here=now.atZone(zone);val t=LocalTime.parse(clock)
        val next=here.toLocalDate().atTime(t).atZone(zone)
        return if(next.toInstant()>now)next.toInstant() else next.plusDays(1).toInstant()
    }
}
object ClockRules {
    fun discontinuity(previousUtc:Long,previousElapsedNs:Long,utc:Long,elapsedNs:Long):Boolean =
        elapsedNs<previousElapsedNs||kotlin.math.abs((utc-previousUtc)-(elapsedNs-previousElapsedNs)/1_000_000)>2000
}
