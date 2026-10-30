package com.blackbox.diary.privacy

import com.blackbox.diary.AppGraph
import com.blackbox.diary.core.DeleteProgress
import com.blackbox.diary.core.TextRules
import com.blackbox.diary.security.ChunkStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File

class DeletionCoordinator(private val graph:AppGraph) {
    val progress=MutableStateFlow<DeleteProgress?>(null)
    private val mutex=Mutex()
    suspend fun deleteDays(days:Set<String>)=deleteScope(days,emptySet())
    suspend fun deleteEvents(events:Set<String>)=deleteScope(emptySet(),events)
    suspend fun deleteVoiceprint()=deleteScope(emptySet(),emptySet(),setOf("voiceprint_profile"))
    private suspend fun deleteScope(days:Set<String>,roots:Set<String>,settings:Set<String> = emptySet())=mutex.withLock {
        if(days.isEmpty()&&roots.isEmpty()&&settings.isEmpty())return@withLock
        val store=graph.store;val operation=store.id()
        val scopeKey=TextRules.sha256("days:"+days.sorted().joinToString(",")+";roots:"+roots.sorted().joinToString(",")+if(settings.isEmpty())"" else ";settings:"+settings.sorted().joinToString(","))
        val active=store.read { days.any {scalar("SELECT count(*) FROM diary_day WHERE id=?",it)!="0"}||roots.any {scalar("SELECT count(*) FROM artifact WHERE id=? AND lifecycle='ACTIVE'",it)!="0"}||settings.any {setting(it)!=null} }
        if(!active) {
            val previous=store.read {rows("SELECT id,state FROM deletion_operation WHERE idempotency_key LIKE ? ORDER BY requested_utc_ms DESC LIMIT 1","$scopeKey:%").firstOrNull()}
            if(previous!=null){progress.value=DeleteProgress(previous[0]!!,previous[1]!!,"这项删除已完成",previous[1]=="COMPLETED");return@withLock}
        }
        graph.capture.maintenance();graph.pipeline.planner.cancel();graph.ai.cancel();graph.assistant.invalidate();graph.voice.cancel()
        val scheduledJobs=store.read {rows("SELECT id FROM processing_job WHERE kind='GENERATE_DAY' AND state IN ('QUEUED','RUNNING','FAILED_RETRYABLE')").map {it[0]!!}}
        val key="$scopeKey:${store.epoch}"
        store.transaction {
            check(!maintenance) { "MAINTENANCE_PENDING" }
            val next=epoch+1
            exec("UPDATE app_meta SET content_epoch=?,maintenance_state='DELETE_PENDING'",next)
            exec("DELETE FROM assistant_turn")
            settings.forEach {name->exec("DELETE FROM private_setting WHERE name=?",name)}
            if("voiceprint_profile" in settings)savePreferences(preferences().copy(voiceprintEnabled=false))
            exec("INSERT INTO deletion_operation(id,idempotency_key,requested_utc_ms,scope_json,cutoff_ingest_seq,new_epoch,old_generation,state) SELECT ?,?,?,?,ingest_seq,?,db_generation,'HIDDEN' FROM app_meta",operation,key,System.currentTimeMillis(),json.encodeToString(days.toList()),next)
            exec("CREATE TEMP TABLE IF NOT EXISTS delete_roots(id TEXT PRIMARY KEY)");exec("DELETE FROM delete_roots")
            days.forEach { day->exec("INSERT OR IGNORE INTO delete_roots SELECT id FROM artifact WHERE owner_day_id=?",day) }
            roots.forEach {root->exec("INSERT OR IGNORE INTO delete_roots SELECT artifact_id FROM source_event WHERE artifact_id=?",root)}
            exec("WITH RECURSIVE affected(id) AS (SELECT id FROM delete_roots UNION SELECT e.child_id FROM lineage_edge e JOIN affected a ON e.parent_id=a.id) UPDATE artifact SET lifecycle='TOMBSTONED' WHERE id IN (SELECT id FROM affected)")
            exec("INSERT OR IGNORE INTO privacy_tombstone SELECT s.origin_key,?,?,s.artifact_id,? FROM source_event s JOIN artifact a ON a.id=s.artifact_id WHERE a.lifecycle='TOMBSTONED'",operation,next,System.currentTimeMillis())
            exec("UPDATE processing_job SET state='CANCELLED',lease_until_utc_ms=NULL WHERE state NOT IN ('SUCCEEDED','FAILED_FINAL','CANCELLED','STALE','EXPIRED')")
            searchChanges.value++
            exec("DELETE FROM private_setting WHERE name LIKE 'search_%' OR name LIKE 'draft_%'")
            exec("DELETE FROM private_setting WHERE name LIKE 'generation_target_%' OR name='scheduled_generation_job'")
        }
        val manager=androidx.work.WorkManager.getInstance(graph.app)
        scheduledJobs.forEach {manager.cancelUniqueWork("generate-$it")}
        manager.cancelUniqueWork("offline-processing");manager.cancelUniqueWork("processing-retry")
        com.blackbox.diary.security.FaultGate.hit(graph.app,"FP07")
        finish(operation,days)
    }
    suspend fun resumePending()=mutex.withLock {
        if(graph.vault.read("retire.json")!=null)graph.store.recoverSwitch()
        val pending=graph.store.read { rows("SELECT id,scope_json FROM deletion_operation WHERE state!='COMPLETED' ORDER BY requested_utc_ms") }
        pending.forEach { finish(it[0]!!,graph.store.json.decodeFromString<List<String>>(it[1]!!).toSet()) }
    }
    private suspend fun finish(operation:String,days:Set<String>) {
        progress.value=DeleteProgress(operation,"QUIESCING","正在停止采集与处理")
        graph.capture.maintenance();graph.pipeline.planner.cancel();graph.ai.cancel();graph.assistant.invalidate();graph.voice.cancel()
        try {
            withTimeout(30_000) { while(graph.capture.activeTasks.value>0)delay(100) }
            graph.pipeline.mutex.withLock {
                graph.ai.clearCache()
                withContext(Dispatchers.IO) {
                    val store=graph.store;val chunks=ChunkStore(graph)
                    val media=store.read { (days.flatMap { day->rows("SELECT id FROM media_chunk WHERE day_id=?",day).map { it[0]!! } }+rows("SELECT DISTINCT origin_chunk_id FROM source_event s JOIN artifact a ON a.id=s.artifact_id WHERE a.lifecycle='TOMBSTONED' AND origin_chunk_id IS NOT NULL").map {it[0]!!}).toSet() }
                    chunks.purge(media);chunks.retireOldKeys()
                    progress.value=DeleteProgress(operation,"PURGING","正在清理正文、历史版本与关联索引")
                    // No writes outside this monitor can observe a partly purged database.
                    store.read {
                        db.execSQL("PRAGMA foreign_keys=OFF")
                        try { transaction {
                            exec("UPDATE deletion_operation SET state='PURGING' WHERE id=?",operation)
                            exec("CREATE TEMP TABLE IF NOT EXISTS purge_ids(id TEXT PRIMARY KEY)");exec("DELETE FROM purge_ids")
                            exec("INSERT INTO purge_ids SELECT id FROM artifact WHERE lifecycle!='ACTIVE'")
                            exec("DELETE FROM evidence_ref WHERE claim_id IN (SELECT id FROM purge_ids) OR event_id IN (SELECT id FROM purge_ids)")
                            exec("DELETE FROM event_link WHERE event_id IN (SELECT id FROM purge_ids) OR related_event_id IN (SELECT id FROM purge_ids)")
                            exec("DELETE FROM job_input WHERE artifact_id IN (SELECT id FROM purge_ids)")
                            exec("DELETE FROM memory_vector WHERE block_id IN (SELECT id FROM purge_ids) OR revision_id IN (SELECT id FROM purge_ids)")
                            exec("DELETE FROM daily_composition WHERE revision_id IN (SELECT id FROM purge_ids) OR revision_id NOT IN (SELECT artifact_id FROM diary_revision)")
                            exec("CREATE TEMP TABLE IF NOT EXISTS purge_meanings(event_id TEXT PRIMARY KEY)");exec("DELETE FROM purge_meanings")
                            exec("INSERT OR IGNORE INTO purge_meanings SELECT event_id FROM audio_meaning_dependency WHERE source_event_id IN (SELECT id FROM purge_ids) OR event_id IN (SELECT id FROM purge_ids)")
                            exec("INSERT OR IGNORE INTO purge_meanings SELECT event_id FROM audio_meaning WHERE event_id IN (SELECT id FROM purge_ids)")
                            exec("DELETE FROM audio_meaning_dependency WHERE event_id IN (SELECT event_id FROM purge_meanings)")
                            exec("DELETE FROM audio_meaning WHERE event_id IN (SELECT event_id FROM purge_meanings)")
                            exec("DELETE FROM inferred_block_source WHERE block_id IN (SELECT id FROM purge_ids) OR event_id IN (SELECT id FROM purge_ids)")
                            exec("DELETE FROM assistant_turn")
                            exec("DELETE FROM search_document WHERE artifact_id IN (SELECT id FROM purge_ids) OR source_artifact_id IN (SELECT id FROM purge_ids)")
                            exec("DELETE FROM lineage_edge WHERE parent_id IN (SELECT id FROM purge_ids) OR child_id IN (SELECT id FROM purge_ids)")
                            exec("UPDATE diary_revision SET parent_revision_id=NULL WHERE parent_revision_id IN (SELECT id FROM purge_ids)")
                            listOf("diary_block","diary_revision","summary_chunk","fact_claim","source_event").forEach { table->exec("DELETE FROM $table WHERE artifact_id IN (SELECT id FROM purge_ids)") }
                            exec("DELETE FROM artifact WHERE id IN (SELECT id FROM purge_ids)")
                            days.forEach { day->
                                // Active capture intents can straddle days. Split at the deleted civil-day
                                // interval, preserving both surviving sides and their sessions/exclusions.
                                val zoneCuts=rows("SELECT start_ms,coalesce(end_ms,?),zone_id FROM capture_zone",System.currentTimeMillis().toString()).flatMap {z->
                                    val start=z[0]!!.toLong();val end=z[1]!!.toLong()
                                    if(end<=start)emptyList() else com.blackbox.diary.core.Intervals.intersect(listOf(com.blackbox.diary.core.Interval(start,end)),listOf(com.blackbox.diary.core.DayRules.range(day,z[2]!!)))
                                }
                                val cuts=if(zoneCuts.isNotEmpty())com.blackbox.diary.core.Intervals.union(zoneCuts) else rows("SELECT start_utc_ms,end_utc_ms FROM day_slice WHERE day_id=?",day).map {com.blackbox.diary.core.Interval(it[0]!!.toLong(),it[1]!!.toLong())}
                                cuts.forEach {range->splitCapture(range.start,range.end)}
                                exec("DELETE FROM capture_tick WHERE slice_id IN (SELECT id FROM day_slice WHERE day_id=?)",day)
                                exec("DELETE FROM job_input WHERE chunk_id IN (SELECT id FROM media_chunk WHERE day_id=?)",day)
                                exec("UPDATE source_event SET origin_chunk_id=NULL WHERE origin_chunk_id IN (SELECT id FROM media_chunk WHERE day_id=?)",day)
                                exec("DELETE FROM media_chunk WHERE day_id=?",day)
                                exec("DELETE FROM day_slice WHERE day_id=?",day);exec("DELETE FROM diary_day WHERE id=?",day)
                                exec("DELETE FROM recognition_work WHERE day_id=?",day)
                                exec("DELETE FROM day_label WHERE day=?",day);exec("DELETE FROM inference_budget WHERE day=?",day)
                            }
                            exec("DELETE FROM processing_job WHERE state IN ('CANCELLED','STALE','EXPIRED')")
                            exec("DELETE FROM job_input WHERE job_id NOT IN (SELECT id FROM processing_job)")
                            exec("DELETE FROM clock_anchor WHERE id NOT IN (SELECT anchor_id FROM day_slice UNION SELECT anchor_id FROM capture_session)")
                            // Tombstones retain only opaque origin keys; no human text/date scope survives.
                            exec("UPDATE privacy_tombstone SET root_artifact_id=NULL WHERE operation_id=?",operation)
                            exec("UPDATE deletion_operation SET state='REBUILDING',scope_json='[]',checkpoint_json='{}' WHERE id=?",operation)
                            exec("UPDATE app_meta SET maintenance_state='REBUILDING'")
                        } } finally { db.execSQL("PRAGMA foreign_keys=ON") }
                        check(rows("PRAGMA foreign_key_check").isEmpty()) { "PURGE_FOREIGN_KEY_CHECK" }
                    }
                    progress.value=DeleteProgress(operation,"REBUILDING","正在重建加密存储并销毁旧密钥")
                    store.compactAndRetire(operation)
                    check(!store.maintenance)
                    graph.capture.finishMaintenance()
                    progress.value=DeleteProgress(operation,"COMPLETED","已删除，旧加密密钥已销毁。重新记录时请再次授权。",true)
                    runCatching {com.blackbox.diary.processing.Schedule.install(graph.app,store.read {preferences().generationTime})}
                }
            }
        } catch(t:Throwable) {
            val reason=when {
                t is android.database.sqlite.SQLiteFullException||t.message.orEmpty().contains("disk is full",true)||t.message.orEmpty().contains("No space",true)->"设备存储空间不足，内容已隐藏。请释放一些空间后重新打开应用，继续本次清理。"
                graph.vault.read("retire.json")!=null->"加密密钥清理尚未完成，内容仍然隐藏。请重新打开应用继续，暂不标记删除完成。"
                else->"关联数据清理或校验尚未完成，内容已隐藏。重新打开应用会继续本次操作。"
            }
            progress.value=DeleteProgress(operation,"BLOCKED",reason)
            throw t
        }
    }
    private fun splitCapture(start:Long,end:Long)=with(graph.store) {
        val intents=rows("SELECT id,channel,start_utc_ms,end_utc_ms,enabled_by FROM capture_intent WHERE start_utc_ms<? AND coalesce(end_utc_ms,?)>?",end.toString(),System.currentTimeMillis().toString(),start.toString())
        intents.forEach { i->
            val intent=i[0]!!;val a=i[2]!!.toLong();val b=i[3]?.toLong()?:System.currentTimeMillis()
            val segments=listOf(a to minOf(b,start),maxOf(a,end) to b).filter { it.second>it.first }
            val sessions=rows("SELECT id,anchor_id,start_utc_ms,end_utc_ms,state,stop_reason,last_confirmed_elapsed_ns FROM capture_session WHERE intent_id=?",intent)
            val exclusions=rows("SELECT start_utc_ms,end_utc_ms,reason FROM capture_exclusion WHERE intent_id=?",intent)
            val zoneSegments=rows("SELECT start_ms,coalesce(end_ms,?),zone_id FROM capture_zone WHERE intent_id=?",b.toString(),intent)
            segments.forEach { (lo,hi)->
                val next=id();exec("INSERT INTO capture_intent VALUES(?,?,?,?,?)",next,i[1],lo,hi,i[4])
                zoneSegments.forEach {z->val x=maxOf(lo,z[0]!!.toLong());val y=minOf(hi,z[1]!!.toLong());if(y>x)exec("INSERT INTO capture_zone VALUES(?,?,?,?,?)",id(),next,x,y,z[2])}
                exclusions.forEach { e->val x=maxOf(lo,e[0]!!.toLong());val y=minOf(hi,e[1]!!.toLong());if(y>x)exec("INSERT INTO capture_exclusion VALUES(?,?,?,?,?)",id(),next,x,y,e[2]) }
                sessions.forEach { s->val x=maxOf(lo,s[2]!!.toLong());val y=minOf(hi,s[3]?.toLong()?:hi)
                    if(y>x) { val ns=id();exec("INSERT INTO capture_session VALUES(?,?,?,?,?,?,?,?)",ns,next,s[1],x,y,s[4],s[5],s[6]!!.toLong());exec("UPDATE capture_tick SET session_id=? WHERE session_id=? AND start_utc_ms>=? AND end_utc_ms<=?",ns,s[0],x,y);exec("UPDATE media_chunk SET session_id=? WHERE session_id=? AND first_utc_ms>=? AND first_utc_ms<?",ns,s[0],x,y) }
                }
            }
            sessions.forEach { s->exec("DELETE FROM capture_tick WHERE session_id=?",s[0]);exec("UPDATE media_chunk SET session_id=NULL WHERE session_id=?",s[0]);exec("DELETE FROM capture_session WHERE id=?",s[0]) }
            exec("DELETE FROM capture_zone WHERE intent_id=?",intent);exec("DELETE FROM capture_exclusion WHERE intent_id=?",intent);exec("DELETE FROM capture_intent WHERE id=?",intent)
        }
    }
}
