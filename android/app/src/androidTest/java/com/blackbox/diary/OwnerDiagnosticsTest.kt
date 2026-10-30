package com.blackbox.diary

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import android.util.Log

/** Owner-safe metadata only. No PIN, raw text, fixtures, deletion or setting writes. */
class OwnerDiagnosticsTest {
    @Test fun inspectMetadata() {
        val g=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
        val report=JSONObject()
        g.store.read {
            fun q(name:String,sql:String) { report.put(name,JSONArray(rows(sql).map {JSONArray(it)})) }
            val p=preferences()
            report.put("preferences",JSONObject().put("generationTime",p.generationTime).put("includeReview",p.includeReview).put("interpretAudio",p.interpretAudio).put("includeAudioInterpretations",p.includeAudioInterpretations).put("languages",p.recognitionLanguages.joinToString(",")))
            q("days","SELECT * FROM diary_day ORDER BY id DESC LIMIT 7")
            q("captures","SELECT i.channel,datetime(s.start_utc_ms/1000,'unixepoch','+8 hours'),datetime(s.end_utc_ms/1000,'unixepoch','+8 hours'),s.state,s.stop_reason FROM capture_session s JOIN capture_intent i ON i.id=s.intent_id ORDER BY s.start_utc_ms DESC LIMIT 12")
            q("ticks","SELECT d.day_id,i.channel,t.outcome,count(*),sum(t.valid_ms) FROM capture_tick t JOIN day_slice d ON d.id=t.slice_id JOIN capture_session s ON s.id=t.session_id JOIN capture_intent i ON i.id=s.intent_id GROUP BY 1,2,3 ORDER BY 1 DESC LIMIT 30")
            q("media","SELECT day_id,state,count(*),sum(byte_count),sum(wrapped_dek IS NOT NULL) FROM media_chunk GROUP BY 1,2 ORDER BY 1 DESC LIMIT 30")
            q("events","SELECT a.owner_day_id,s.source,s.quality_tier,count(*) FROM source_event s JOIN artifact a ON a.id=s.artifact_id GROUP BY 1,2,3 ORDER BY 1 DESC LIMIT 30")
            q("recognition","SELECT day_id,state,detail,count(*) FROM recognition_work GROUP BY 1,2,3 ORDER BY 1 DESC LIMIT 30")
            q("revisions","SELECT r.day_id,r.revision_no,r.editor,r.state,count(b.artifact_id),sum(length(b.body_text)) FROM diary_revision r LEFT JOIN diary_block b ON b.revision_id=r.artifact_id GROUP BY r.artifact_id ORDER BY r.day_id DESC,r.revision_no DESC LIMIT 15")
            q("jobs","SELECT j.kind,j.state,j.attempts,j.error_code,datetime(j.next_due_utc_ms/1000,'unixepoch','+8 hours'),s.value FROM processing_job j LEFT JOIN private_setting s ON s.name='generation_target_'||j.id ORDER BY j.rowid DESC LIMIT 30")
            q("meanings","SELECT a.owner_day_id,count(*) FROM audio_meaning m JOIN artifact a ON a.id=m.event_id GROUP BY 1")
            q("budgets","SELECT * FROM ai_budget")
            q("aiIssues","SELECT name,value FROM private_setting WHERE name IN ('ai_last_issue_INTERPRET','ai_last_issue_CHAT')")
            report.put("analysisPendingYesterday",g.interpretations.pending("2026-10-28"))
        }
        Log.i("OwnerDiagnostics",report.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("owner_metadata",report.toString())})
    }
}
