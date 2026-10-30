package com.blackbox.diary.processing

import android.content.*
import android.os.PowerManager
import androidx.work.*
import com.blackbox.diary.DiaryApplication
import com.blackbox.diary.core.DayRules
import com.blackbox.diary.core.TextRules
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

private fun processingForeground(context:Context,id:Int,label:String):ForegroundInfo {
    val manager=context.getSystemService(android.app.NotificationManager::class.java)
    manager.createNotificationChannel(android.app.NotificationChannel("processing","本机离线整理",android.app.NotificationManager.IMPORTANCE_LOW).apply {lockscreenVisibility=android.app.Notification.VISIBILITY_PRIVATE})
    val open=android.app.PendingIntent.getActivity(context,0,Intent(context,com.blackbox.diary.MainActivity::class.java),android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    val notification=android.app.Notification.Builder(context,"processing").setSmallIcon(com.blackbox.diary.R.drawable.ic_launcher).setContentTitle(label).setContentText("仅在本机处理，原始转写保留").setOngoing(true).setVisibility(android.app.Notification.VISIBILITY_PRIVATE).setContentIntent(open).build()
    return ForegroundInfo(id,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}

object Schedule {
    private fun graph(context:Context)=(context.applicationContext as DiaryApplication).graph
    fun install(context:Context,clock:String) {
        val g=graph(context);if(g.store.maintenance)return
        val zone=ZoneId.systemDefault();val due=DayRules.nextGeneration(Instant.now(),zone,clock)
        val day=due.atZone(zone).toLocalDate().minusDays(1).toString()
        val id=queueGeneration(context,day,due.toEpochMilli(),"scheduled:${due.toEpochMilli()}:$zone")?:return
        val previous=g.store.read {setting("scheduled_generation_job")}
        if(previous!=null&&previous!=id) {
            val future=g.store.read {scalar("SELECT count(*) FROM processing_job WHERE id=? AND state='QUEUED' AND next_due_utc_ms>?",previous,System.currentTimeMillis().toString())=="1"}
            if(future){g.store.transaction {exec("UPDATE processing_job SET state='CANCELLED' WHERE id=?",previous);exec("DELETE FROM private_setting WHERE name=?","generation_target_$previous")};WorkManager.getInstance(context).cancelUniqueWork("generate-$previous")}
        }
        g.store.transaction {setting("scheduled_generation_job",id)}
        WorkManager.getInstance(context).cancelUniqueWork("daily-diary")
        g.store.read {rows("SELECT id,next_due_utc_ms FROM processing_job WHERE kind='GENERATE_DAY' AND expected_epoch=? AND state IN ('QUEUED','FAILED_RETRYABLE')",epoch.toString())}.forEach {enqueueGeneration(context,it[0]!!,it[1]?.toLong()?:System.currentTimeMillis())}
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("ttl-cleanup",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<CleanupWorker>(15,TimeUnit.MINUTES).build())
        // An app update, a force-stop or a missed nightly run must not strand past days.
        val today=LocalDate.now().toString()
        val missing=g.store.read {rows("SELECT a.owner_day_id,max(a.ingest_seq) FROM artifact a JOIN source_event s ON s.artifact_id=a.id WHERE a.lifecycle='ACTIVE' AND a.owner_day_id<? GROUP BY a.owner_day_id ORDER BY a.owner_day_id DESC LIMIT 7",today)}.filter {r->g.composer.needs(r[0]!!)}
        missing.forEach {r->
            val keep=queueGeneration(context,r[0]!!,System.currentTimeMillis(),"catchup-v13:${com.blackbox.diary.BuildConfig.VERSION_CODE}:${DailyComposer.VERSION}:${r[0]}:${g.composer.fingerprint(r[0]!!)}")?:return@forEach
            val superseded=g.store.transaction {
                val ids=rows("SELECT j.id FROM processing_job j JOIN private_setting s ON s.name='generation_target_'||j.id WHERE j.kind='GENERATE_DAY' AND s.value=? AND j.id!=? AND j.state IN ('QUEUED','FAILED_RETRYABLE')",r[0]!!,keep).map {it[0]!!}
                ids.forEach {old->exec("UPDATE processing_job SET state='CANCELLED',error_code='SUPERSEDED' WHERE id=?",old);exec("DELETE FROM private_setting WHERE name=?","generation_target_$old")}
                ids
            }
            superseded.forEach {WorkManager.getInstance(context).cancelUniqueWork("generate-$it")}
        }
        process(context)
    }
    fun requestDay(context:Context,day:String,expectedEpoch:Long) {
        val g=graph(context)
        if(g.store.maintenance||g.store.epoch!=expectedEpoch)return
        val watermark=g.store.read {scalar("SELECT ingest_seq FROM app_meta")}
        queueGeneration(context,day,System.currentTimeMillis(),"manual:$day:$watermark:${System.currentTimeMillis()/60000}")
        g.message.value="已开始自动整理，原始 ASR 会保留"
    }
    private fun queueGeneration(context:Context,day:String,due:Long,fingerprint:String):String? {
        val g=graph(context)
        val id=g.store.transaction {
            if(maintenance)return@transaction null
            val hash=TextRules.sha256(fingerprint)
            val previous=rows("SELECT id FROM processing_job WHERE kind='GENERATE_DAY' AND input_fingerprint=? AND expected_epoch=?",hash,epoch.toString()).firstOrNull()?.firstOrNull()
            val key=previous?:id().also {exec("INSERT INTO processing_job(id,kind,expected_epoch,input_fingerprint,pipeline_version,state,next_due_utc_ms) VALUES(?,'GENERATE_DAY',?,?,'1.1','QUEUED',?)",it,epoch,hash,due)}
            if(previous==null)setting("generation_target_$key",day)
            key
        }
        if(id!=null)enqueueGeneration(context,id,due);return id
    }
    private fun enqueueGeneration(context:Context,id:String,due:Long,append:Boolean=false) {
        // Only this opaque identifier reaches WorkManager's unencrypted metadata.
        val request=OneTimeWorkRequestBuilder<DiaryWorker>().setInputData(workDataOf("job_id" to id)).setInitialDelay((due-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("generate-$id",if(append)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,request)
    }
    fun lateResult(context:Context,day:String) {
        val g=graph(context)
        if(day>=LocalDate.now().toString())return
        val watermark=g.store.read {scalar("SELECT ingest_seq FROM app_meta")}
        queueGeneration(context,day,System.currentTimeMillis(),"late:$day:$watermark")
    }
    fun generationRetry(context:Context,id:String,due:Long)=enqueueGeneration(context,id,due,true)
    fun process(context:Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("offline-processing",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<ProcessingWorker>().setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build())
    }
    fun retry(context:Context,delayMs:Long) {
        WorkManager.getInstance(context).enqueueUniqueWork("processing-retry",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<ProcessingWorker>().setInitialDelay(delayMs.coerceAtLeast(1000),TimeUnit.MILLISECONDS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build())
    }
}
class ProcessingWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val g=(applicationContext as DiaryApplication).graph
        if(applicationContext.getSystemService(PowerManager::class.java).currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE)return Result.retry()
        if(g.store.read {scalar("SELECT count(*) FROM media_chunk WHERE state IN ('QUEUED','FAILED_RETRYABLE') AND wrapped_dek IS NOT NULL")}!="0")setForeground(processingForeground(applicationContext,102,"正在本机识别语音"))
        g.pipeline.process()
        val due=g.store.read {rows("SELECT min(next_due_utc_ms) FROM processing_job WHERE kind='ASR' AND state='FAILED_RETRYABLE'").firstOrNull()?.firstOrNull()?.toLongOrNull()}
        val queued=g.store.read {scalar("SELECT count(*) FROM media_chunk WHERE state='QUEUED'").toInt()}
        if(queued>0)Schedule.retry(applicationContext,1000) else if(due!=null)Schedule.retry(applicationContext,due-System.currentTimeMillis())
        return Result.success()
    }
}
class CleanupWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val g=(applicationContext as DiaryApplication).graph
        com.blackbox.diary.security.ChunkStore(g).sweep()
        val days=g.store.read {preferences().retentionDays?.let {keep->days().filter {LocalDate.parse(it)<LocalDate.now().minusDays(keep.toLong())}}?:emptyList()}
        if(days.isNotEmpty())g.deletion.deleteDays(days.toSet())
        Schedule.process(applicationContext);return Result.success()
    }
}
class DiaryWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val g=(applicationContext as DiaryApplication).graph;val id=inputData.getString("job_id")?:return Result.success()
        val target=g.store.transaction {
            val row=rows("SELECT expected_epoch,state FROM processing_job WHERE id=? AND kind='GENERATE_DAY'",id).firstOrNull()?:return@transaction null
            if(row[0]!!.toLong()!=epoch||maintenance||row[1] in listOf("CANCELLED","STALE","EXPIRED","SUCCEEDED","FAILED_FINAL")){exec("UPDATE processing_job SET state='STALE' WHERE id=? AND state NOT IN ('SUCCEEDED','CANCELLED')",id);return@transaction null}
            val day=setting("generation_target_$id")?:return@transaction null
            exec("UPDATE processing_job SET state='RUNNING',attempts=attempts+1,lease_until_utc_ms=? WHERE id=?",System.currentTimeMillis()+10*60_000,id)
            day to epoch
        }?:return Result.success()
        try {
            setForeground(processingForeground(applicationContext,103,"正在自动整理日记"))
            g.pipeline.process()
            if(g.store.read {scalar("SELECT count(*) FROM diary_day WHERE id=?",target.first)}!="0")g.pipeline.generate(target.first,target.second)
            val pending=(if(target.first<LocalDate.now().toString())g.store.read {audioPending(target.first)} else 0)+(if(g.composer.needs(target.first))1 else 0)
            val due=System.currentTimeMillis()+if(g.ai.lastIssue.value==null)1_000L else 15*60_000L
            val resume=g.store.transaction {
                val valid=epoch==target.second&&!maintenance
                val state=if(!valid)"STALE" else if(pending>0)"FAILED_RETRYABLE" else if(diary(target.first)?.blocks.isNullOrEmpty())"FAILED_FINAL" else "SUCCEEDED"
                exec("UPDATE processing_job SET state=?,lease_until_utc_ms=NULL,next_due_utc_ms=?,error_code=? WHERE id=?",state,due,if(state=="SUCCEEDED")null else if(pending>0)g.ai.lastIssue.value?:"ANALYSIS_PENDING" else "NO_MEANINGFUL_CONTENT",id)
                if(state=="SUCCEEDED"||!valid)exec("DELETE FROM private_setting WHERE name=?","generation_target_$id")
                valid&&pending>0
            }
            if(resume)Schedule.generationRetry(applicationContext,id,due)
        } catch(t:Throwable) {
            if(t is CancellationException)throw t
            val retry=g.store.transaction {
                val attempts=scalar("SELECT attempts FROM processing_job WHERE id=?",id).toInt()
                val allowed=attempts<4&&epoch==target.second&&!maintenance
                val due=System.currentTimeMillis()+listOf(30_000L,120_000L,600_000L)[(attempts-1).coerceIn(0,2)]
                exec("UPDATE processing_job SET state=?,lease_until_utc_ms=NULL,next_due_utc_ms=?,error_code='GENERATION_FAILED' WHERE id=?",if(allowed)"FAILED_RETRYABLE" else "FAILED_FINAL",due,id)
                if(allowed)due else null
            }
            if(retry!=null)Schedule.generationRetry(applicationContext,id,retry)
        } finally {if(!g.store.maintenance)Schedule.install(applicationContext,g.store.read {preferences().generationTime})}
        return Result.success()
    }
}
class BootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){val g=(context.applicationContext as DiaryApplication).graph;Schedule.install(context,g.store.read {preferences().generationTime})}
}
