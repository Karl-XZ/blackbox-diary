package com.blackbox.diary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Debug-only owner-UID metadata diagnostics. No PIN, audio or diary text export. */
class HealthSnapshotReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!="com.blackbox.diary.HEALTH_SNAPSHOT")return
        val g=(context.applicationContext as DiaryApplication).graph
        val result=JSONObject().put("at",System.currentTimeMillis()).put("audio",g.capture.audio.value.state.name)
        g.store.read {
            fun q(key:String,sql:String){result.put(key,JSONArray(rows(sql).map {JSONArray(it)}))}
            q("sources","SELECT a.owner_day_id,s.source,count(*) FROM source_event s JOIN artifact a ON a.id=s.artifact_id WHERE a.lifecycle='ACTIVE' GROUP BY 1,2")
            q("analysis","SELECT a.owner_day_id,m.meaningful,count(*) FROM audio_meaning m JOIN artifact a ON a.id=m.event_id GROUP BY 1,2")
            q("diaries","SELECT r.day_id,r.state,r.revision_no,count(b.artifact_id) FROM diary_revision r LEFT JOIN diary_block b ON b.revision_id=r.artifact_id WHERE r.state IN ('PUBLISHED','CANDIDATE') GROUP BY r.artifact_id")
            q("dailySummary","SELECT r.day_id,r.state,r.revision_no,count(b.artifact_id),sum(length(b.body_text)),sum(CASE WHEN b.template_id LIKE 'diary.summary.%' THEN 1 ELSE 0 END) FROM daily_composition c JOIN diary_revision r ON r.artifact_id=c.revision_id JOIN diary_block b ON b.revision_id=r.artifact_id WHERE r.state IN ('PUBLISHED','CANDIDATE') GROUP BY r.artifact_id")
            q("summaryCache","SELECT model_fingerprint,count(*) FROM summary_chunk GROUP BY model_fingerprint")
            result.put("dailySummaryPending",g.composer.needs(java.time.LocalDate.now().minusDays(1).toString()))
            q("generation","SELECT j.state,j.error_code,j.attempts,s.value,j.next_due_utc_ms FROM processing_job j LEFT JOIN private_setting s ON s.name='generation_target_'||j.id WHERE j.kind='GENERATE_DAY' ORDER BY j.rowid DESC LIMIT 8")
            q("issues","SELECT name,value FROM private_setting WHERE name LIKE 'ai_last_issue_%'")
            q("budget","SELECT * FROM ai_budget")
            result.put("yesterdayPending",g.interpretations.pending(java.time.LocalDate.now().minusDays(1).toString()))
        }
        result.put("nativeResident",com.blackbox.diary.inference.SparkSmeBridge.resident)
        if(com.blackbox.diary.inference.SparkSmeBridge.resident)result.put("nativeMetrics",JSONObject(com.blackbox.diary.inference.SparkSmeBridge.metrics()))
        File(context.filesDir,"health-snapshot.json").writeText(result.toString(2))
    }
}
