package com.blackbox.diary.processing

import com.blackbox.diary.AppGraph
import com.blackbox.diary.core.*
import com.blackbox.diary.inference.LocalAsr
import com.blackbox.diary.inference.SparkPlanner
import com.blackbox.diary.security.ChunkStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.*
import kotlinx.serialization.encodeToString

class Pipeline(val graph:AppGraph) {
    val busy=MutableStateFlow(false)
    val planner=SparkPlanner(graph)
    val mutex=Mutex()
    suspend fun recover()=withContext(Dispatchers.IO) {
        val store=graph.store
        store.transaction {
            if(scalar("SELECT count(*) FROM capture_intent WHERE end_utc_ms IS NULL")!="0") {
                val last=rows("SELECT t.end_utc_ms,t.elapsed_end_ns,a.boot_id,d.zone_id FROM capture_tick t JOIN capture_session s ON s.id=t.session_id JOIN clock_anchor a ON a.id=s.anchor_id JOIN day_slice d ON d.id=t.slice_id ORDER BY t.elapsed_end_ns DESC LIMIT 1").firstOrNull()
                if(last!=null&&(last[2]!=bootId()||ClockRules.discontinuity(last[0]!!.toLong(),last[1]!!.toLong(),System.currentTimeMillis(),android.os.SystemClock.elapsedRealtimeNanos()))) {
                    val zone=last[3]!!;val now=System.currentTimeMillis();val text="记录中断期间的计时依据无法确认，本日覆盖率暂不计算。"
                    listOf(last[0]!!.toLong(),now).distinctBy {DayRules.localDay(it,zone)}.forEach {at->commitEvent(Event(id(),DayRules.localDay(at,zone),Source.SYSTEM,text,at,at,zone,Quality.HIGH,metadata=mapOf("kind" to "CAPTURE_GAP","clock_changed" to "true")),epoch)}
                }
            }
            Channel.entries.forEach {c->
                val rule=setting("rule_pause_${c.name}")?.toLongOrNull()
                if(rule!=null) {
                    val intent=rows("SELECT id FROM capture_intent WHERE channel=? AND end_utc_ms IS NULL ORDER BY start_utc_ms DESC LIMIT 1",c.name).firstOrNull()?.firstOrNull()
                    val zone=intent?.let {rows("SELECT zone_id FROM capture_zone WHERE intent_id=? ORDER BY start_ms DESC LIMIT 1",it).firstOrNull()?.firstOrNull()}?:ZoneId.systemDefault().id
                    val start=Instant.ofEpochMilli(rule).atZone(ZoneId.of(zone));var wake=start.toLocalDate().atTime(LocalTime.parse(preferences().sleepEnd)).atZone(start.zone)
                    if(wake<=start)wake=wake.plusDays(1)
                    val end=minOf(System.currentTimeMillis(),wake.toInstant().toEpochMilli())
                    if(intent!=null&&end>rule)exec("INSERT INTO capture_exclusion VALUES(?,?,?,?,?)",id(),intent,rule,end,"SCHEDULED_EXCLUSION")
                    exec("DELETE FROM private_setting WHERE name=?","rule_pause_${c.name}")
                }
                val pause=setting("pause_${c.name}")?.split('|')
                if(pause!=null) {
                    val intent=rows("SELECT id FROM capture_intent WHERE channel=? AND end_utc_ms IS NULL ORDER BY start_utc_ms DESC LIMIT 1",c.name).firstOrNull()?.firstOrNull()
                    val end=if(pause[1]=="0")System.currentTimeMillis() else minOf(System.currentTimeMillis(),pause[1].toLong())
                    if(intent!=null&&end>pause[0].toLong())exec("INSERT INTO capture_exclusion VALUES(?,?,?,?,?)",id(),intent,pause[0].toLong(),end,if(pause[1]=="0")"USER_PAUSE" else "TIMED_PAUSE")
                    exec("DELETE FROM private_setting WHERE name=?","pause_${c.name}")
                }
            }
            exec("UPDATE capture_session SET end_utc_ms=coalesce(end_utc_ms,?),state='INTERRUPTED',stop_reason='PROCESS_RESTART' WHERE end_utc_ms IS NULL",System.currentTimeMillis())
            exec("UPDATE capture_intent SET end_utc_ms=? WHERE end_utc_ms IS NULL",System.currentTimeMillis())
            exec("UPDATE capture_zone SET end_ms=max(start_ms,?) WHERE end_ms IS NULL",System.currentTimeMillis())
            exec("UPDATE processing_job SET state='FAILED_RETRYABLE',lease_until_utc_ms=NULL WHERE state='RUNNING'")
            exec("UPDATE media_chunk SET state='QUEUED' WHERE state IN ('WRITING','PROCESSING') AND wrapped_dek IS NOT NULL")
            exec("UPDATE recognition_work SET state='FAILED',detail='PROCESS_INTERRUPTED' WHERE state='RUNNING'")
        }
        ChunkStore(graph).sweep()
        if(store.maintenance)graph.deletion.resumePending()
    }
    suspend fun process()=mutex.withLock {
        if(graph.store.maintenance)return@withLock
        busy.value=true
        try {
            withContext(Dispatchers.IO) {
                val store=graph.store;val chunks=ChunkStore(graph);chunks.sweep()
                val candidates=store.read { rows("SELECT m.id,m.first_utc_ms,m.expires_utc_ms,m.day_id,a.zone_id,m.created_elapsed_ns,a.boot_id FROM media_chunk m LEFT JOIN capture_session s ON s.id=m.session_id LEFT JOIN clock_anchor a ON a.id=s.anchor_id WHERE m.state IN ('QUEUED','FAILED_RETRYABLE') ORDER BY m.expires_utc_ms LIMIT 8") }
                val recognizer=LocalAsr(graph.models,store.read {preferences().recognitionLanguages},graph.voice::match)
                try { for(r in candidates) {
                    currentCoroutineContext().ensureActive();val id=r[0]!!;val first=r[1]!!.toLong();val expires=r[2]!!.toLong();val epoch=store.epoch
                    fun expiredNow()=MediaTtl.expired(first,expires,r[5]!!.toLong()/1_000_000,System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime(),r[6]==store.bootId())
                    if(expiredNow())continue
                    val job=store.transaction {
                        if(maintenance||this.epoch!=epoch)return@transaction null
                        val existing=rows("SELECT id,state,attempts,coalesce(next_due_utc_ms,0) FROM processing_job WHERE kind='ASR' AND input_fingerprint=? AND expected_epoch=?",id,epoch.toString()).firstOrNull()
                        if(existing!=null&&(existing[1] in listOf("SUCCEEDED","FAILED_FINAL","CANCELLED","STALE","EXPIRED")||existing[3]!!.toLong()>System.currentTimeMillis()))return@transaction null
                        val key=existing?.get(0)?:id()
                        if(existing==null) { exec("INSERT INTO processing_job(id,kind,expected_epoch,input_fingerprint,pipeline_version,state) VALUES(?,'ASR',?,?,'1.1','QUEUED')",key,epoch,id);exec("INSERT INTO job_input VALUES(?,0,NULL,?,NULL)",key,id) }
                        exec("UPDATE processing_job SET state='RUNNING',attempts=attempts+1,lease_until_utc_ms=? WHERE id=?",System.currentTimeMillis()+10*60_000,key)
                        exec("UPDATE media_chunk SET state='PROCESSING' WHERE id=?",id);key
                    }?:continue
                    val samples=ArrayList<Float>(960000);var end=first
                    try {
                        val count=chunks.read(id) { record->val b=ByteBuffer.wrap(record.pcm).order(ByteOrder.LITTLE_ENDIAN);while(b.remaining()>=2)samples.add(b.short/32768f);end=record.utcMs+record.pcm.size/32 }
                        check(count>0) { "EMPTY_OR_CORRUPT_MEDIA" }
                        store.transaction {if(this.epoch==epoch&&!maintenance)exec("UPDATE media_chunk SET sealed_record_count=?,end_utc_ms=? WHERE id=?",count,end,id)}
                        val array=samples.toFloatArray();val recognition=try { recognizer.transcribe(array) } finally { array.fill(0f);samples.clear() }
                        com.blackbox.diary.security.FaultGate.hit(graph.app,"FP03")
                        if(expiredNow())error("MEDIA_EXPIRED")
                        val text=TextRules.sanitize(recognition.text)
                        val zone=r[4]?:ZoneId.systemDefault().id
                        val legacy=store.read {scalar("SELECT count(*) FROM source_event WHERE origin_key=?","asr:$id")!="0"}
                        check(store.read {scalar("SELECT count(*) FROM privacy_tombstone WHERE origin_key=?","asr:$id")}=="0") {"SOURCE_DELETED"}
                        val parts=recognition.segments.ifEmpty {if(text.isBlank())emptyList() else listOf(SpeechSegment(text,0,((end-first)*16).toInt(),"unknown"))}
                        if(!legacy)parts.forEach {part->
                            val clean=TextRules.sanitize(part.text);if(clean.isNotBlank())check(store.commitEvent(Event(store.id(),r[3]!!,Source.AUDIO,clean,first+part.startSample/16,first+part.endSample/16,zone,metadata=mapOf("model" to recognition.modelFingerprint,"language" to part.language,"speaker" to part.speaker,"speaker_similarity" to (part.similarity?.toString()?:""),"attribution" to "AMBIENT_AUDIO_NOT_ASSUMED_OWNER")),epoch,"asr:$id:${part.startSample}:${part.endSample}",id)) {"EPOCH_STALE"}
                        }
                        com.blackbox.diary.security.FaultGate.hit(graph.app,"FP04")
                        store.transaction {
                            check(this.epoch==epoch&&!maintenance)
                            val day=scalar("SELECT day_id FROM media_chunk WHERE id=?",id)
                            val outcome=when {legacy->"LEGACY_TEXT_PRESERVED";"LANGUAGE_EXCLUDED" in recognition.flags->"LANGUAGE_EXCLUDED";"NO_SPEECH" in recognition.flags->"NO_SPEECH";text.isBlank()&&recognition.text.isNotBlank()->"MASKED_NO_TEXT";text.isBlank()->"ASR_DONE_NO_TEXT";else->"TEXT"}
                            exec("INSERT OR REPLACE INTO recognition_work VALUES(?,?,'MICROPHONE','SUCCEEDED',?,?)",id,day,if(text.isNotBlank()||legacy)1 else 0,outcome)
                            exec("UPDATE processing_job SET state='SUCCEEDED',lease_until_utc_ms=NULL WHERE id=?",job)
                            exec("UPDATE media_chunk SET state='COMMITTED' WHERE id=?",id)
                        }
                        chunks.purge(setOf(id));chunks.retireOldKeys()
                        Schedule.lateResult(graph.app,r[3]!!)
                    } catch(t:Throwable) {
                        if(t is CancellationException)throw t
                        store.transaction {
                            val attempts=scalar("SELECT attempts FROM processing_job WHERE id=?",job).toInt()
                            val stale=this.epoch!=epoch||maintenance;val expired=expiredNow()
                            val state=if(stale)"STALE" else if(expired)"EXPIRED" else if(attempts>=4)"FAILED_FINAL" else "FAILED_RETRYABLE"
                            val due=System.currentTimeMillis()+listOf(30_000L,120_000L,600_000L)[(attempts-1).coerceIn(0,2)]
                            exec("UPDATE processing_job SET state=?,next_due_utc_ms=?,lease_until_utc_ms=NULL,error_code=? WHERE id=?",state,due,t.message?.takeIf { it.matches(Regex("[A-Z_]+")) }?:"RECOGNIZER_FAILURE",job)
                            exec("UPDATE media_chunk SET state='FAILED_RETRYABLE' WHERE id=? AND wrapped_dek IS NOT NULL",id)
                            exec("UPDATE diary_day SET has_processing_failure=1 WHERE id=(SELECT day_id FROM media_chunk WHERE id=?)",id)
                        }
                    }
                } } finally { recognizer.close();chunks.sweep() }
            }
        } finally { busy.value=false }
    }
    suspend fun generate(day:String,requiredEpoch:Long?=null)=mutex.withLock {
        val snapshot=graph.store.read { Triple(epoch,diary(day),events(day)) }
        if(graph.store.maintenance||(requiredEpoch!=null&&snapshot.first!=requiredEpoch))return@withLock
        busy.value=true
        try {
            val preferences=graph.store.read { preferences() }
            if(graph.store.maintenance||graph.store.epoch!=snapshot.first)return@withLock
            val events=snapshot.third;val byId=events.associateBy { it.id }
            val facts=events.mapNotNull { FactValidator.fromEvent(it,if(preferences.length=="详细")180 else 100) }.mapNotNull { c->(FactValidator.validate(c,byId,snapshot.first,graph.store.epoch,preferences.includeReview) as? FactResult.Approved)?.fact }.sortedBy { it.startMs }
            graph.store.saveFacts(day,facts,snapshot.first)
            val remaining=if(day<java.time.LocalDate.now().toString())graph.store.read {audioPending(day)} else 0
            if(remaining>0) {
                graph.store.transaction {if(epoch==snapshot.first&&!maintenance)exec("UPDATE diary_day SET has_pending_processing=1 WHERE id=?",day)}
                graph.message.value="正在完成录音转写，随后生成每日总结"
                return@withLock
            }
            val composed=graph.composer.advance(day,events,snapshot.first)
            if(composed==null) {
                graph.store.transaction {if(epoch==snapshot.first&&!maintenance)exec("UPDATE diary_day SET has_pending_processing=1 WHERE id=?",day)}
                graph.message.value="每日总结尚未完成，提炼进度已保存"
                return@withLock
            }
            graph.store.transaction {
                if(epoch!=snapshot.first||maintenance)return@transaction
                ensureDay(day)
                exec("UPDATE diary_day SET has_pending_processing=?,used_fallback=? WHERE id=?",if(audioPending(day)>0)1 else 0,if(composed.fallback)1 else 0,day)
                val gaps=Channel.entries.any { graph.capture.coverage(it,day).gapMs>5000 }
                exec("UPDATE diary_day SET has_capture_gap=? WHERE id=?",if(gaps)1 else 0,day)
                val published=publish(day,composed.title,composed.blocks,"AI",snapshot.second?.revision,snapshot.first,composed.fallback)
                if(published!=null)exec("INSERT OR REPLACE INTO daily_composition VALUES(?,?,?)",published.id,composed.fingerprint,DailyComposer.VERSION)
            }
            graph.message.value="每日总结已完成"
        } finally { busy.value=false }
    }
}
