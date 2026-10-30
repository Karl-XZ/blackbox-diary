package com.blackbox.diary.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.blackbox.diary.core.*
import com.blackbox.diary.security.CryptoVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

class DiaryStore(val context:Context,val vault:CryptoVault) {
    val changes=MutableStateFlow(0L)
    val searchChanges=MutableStateFlow(0L)
    val memoryChanges=MutableStateFlow(0L)
    val json=Json { encodeDefaults=true }
    var descriptor:JSONObject;private set
    var db:SQLiteDatabase;private set
    val epoch get()=synchronized(this){scalar("SELECT content_epoch FROM app_meta").toLong()}
    val maintenance get()=synchronized(this){scalar("SELECT maintenance_state FROM app_meta")!="NORMAL"}
    init {
        System.loadLibrary("sqlcipher")
        descriptor=vault.read("active.json") ?: vault.newDescriptor(1).also { vault.atomic("active.json",it) }
        db=open(descriptor)
        runCatching { recoverSwitch() } // Pending retirement stays fenced and is retried by recovery.
    }
    private fun open(d:JSONObject):SQLiteDatabase {
        val f=File(vault.root,"diary-${d.getLong("generation")}.db")
        val key=vault.dbKey(d)
        val result=try { SQLiteDatabase.openOrCreateDatabase(f,key,null,null) } finally { key.fill(0) }
        result.rawQuery("PRAGMA cipher_version",emptyArray<String>()).use { check(it.moveToFirst()&&it.getString(0).isNotEmpty()) }
        result.execSQL("PRAGMA foreign_keys=ON");result.execSQL("PRAGMA temp_store=MEMORY")
        result.rawQuery("PRAGMA secure_delete=ON",emptyArray<String>()).use {it.moveToFirst()};result.rawQuery("PRAGMA journal_mode=DELETE",emptyArray<String>()).use {it.moveToFirst()}
        result.rawQuery("PRAGMA user_version",emptyArray<String>()).use { c-> c.moveToFirst();if(c.getInt(0)==0) initialize(result) }
        result.execSQL("CREATE TABLE IF NOT EXISTS recognition_work(id TEXT PRIMARY KEY, day_id TEXT NOT NULL REFERENCES diary_day(id) ON DELETE CASCADE, channel TEXT NOT NULL, state TEXT NOT NULL, has_text INTEGER NOT NULL DEFAULT 0, detail TEXT NOT NULL DEFAULT '')")
        result.execSQL("CREATE TABLE IF NOT EXISTS capture_zone(id TEXT PRIMARY KEY,intent_id TEXT NOT NULL REFERENCES capture_intent(id) ON DELETE CASCADE,start_ms INTEGER NOT NULL,end_ms INTEGER,zone_id TEXT NOT NULL)")
        result.execSQL("CREATE INDEX IF NOT EXISTS active_media_keys ON media_chunk(path_token) WHERE wrapped_dek IS NOT NULL")
        result.execSQL("CREATE INDEX IF NOT EXISTS search_order ON search_document(day_id DESC,id DESC)")
        result.execSQL("CREATE TABLE IF NOT EXISTS memory_vector(chunk_key TEXT PRIMARY KEY,block_id TEXT NOT NULL REFERENCES diary_block(artifact_id) ON DELETE CASCADE,revision_id TEXT NOT NULL REFERENCES diary_revision(artifact_id) ON DELETE CASCADE,text_sha256 TEXT NOT NULL,start_cp INTEGER NOT NULL,end_cp INTEGER NOT NULL,model TEXT NOT NULL,vector BLOB NOT NULL)")
        result.execSQL("CREATE INDEX IF NOT EXISTS memory_revision ON memory_vector(revision_id)")
        result.execSQL("CREATE TABLE IF NOT EXISTS audio_meaning(event_id TEXT PRIMARY KEY REFERENCES source_event(artifact_id) ON DELETE CASCADE,source_sha256 TEXT NOT NULL,meaning_text TEXT NOT NULL,uncertain INTEGER NOT NULL,reason TEXT NOT NULL,model TEXT NOT NULL,created_ms INTEGER NOT NULL)")
        val hasMeaningful=result.rawQuery("PRAGMA table_info(audio_meaning)",emptyArray<String>()).use {c->var found=false;while(c.moveToNext())if(c.getString(1)=="meaningful")found=true;found}
        if(!hasMeaningful)result.execSQL("ALTER TABLE audio_meaning ADD COLUMN meaningful INTEGER NOT NULL DEFAULT 1")
        result.execSQL("CREATE TABLE IF NOT EXISTS audio_meaning_dependency(event_id TEXT NOT NULL REFERENCES audio_meaning(event_id) ON DELETE CASCADE,source_event_id TEXT NOT NULL REFERENCES source_event(artifact_id) ON DELETE CASCADE,PRIMARY KEY(event_id,source_event_id))")
        result.execSQL("CREATE TABLE IF NOT EXISTS inferred_block_source(block_id TEXT PRIMARY KEY REFERENCES diary_block(artifact_id) ON DELETE CASCADE,event_id TEXT NOT NULL REFERENCES source_event(artifact_id) ON DELETE CASCADE)")
        result.execSQL("CREATE TABLE IF NOT EXISTS daily_composition(revision_id TEXT PRIMARY KEY REFERENCES diary_revision(artifact_id) ON DELETE CASCADE,input_fingerprint TEXT NOT NULL,composer_version TEXT NOT NULL)")
        result.execSQL("CREATE TABLE IF NOT EXISTS assistant_turn(id TEXT PRIMARY KEY,question TEXT NOT NULL,answer TEXT NOT NULL,citations_json TEXT NOT NULL,epoch INTEGER NOT NULL,generated INTEGER NOT NULL,created_ms INTEGER NOT NULL)")
        result.execSQL("CREATE TABLE IF NOT EXISTS ai_budget(day TEXT NOT NULL,purpose TEXT NOT NULL,calls INTEGER NOT NULL DEFAULT 0,tokens INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(day,purpose))")
        return result
    }
    private fun initialize(target:SQLiteDatabase) {
        target.beginTransaction()
        try {
        // A trigger's internal semicolons are not statement boundaries.
        val buffer=StringBuilder();var trigger=false
        context.assets.open("contracts/schema.sql").bufferedReader().useLines { lines->lines.forEach { line->
            val t=line.trim();if(t.startsWith("--")||t.isBlank())return@forEach
            if(t.startsWith("CREATE TRIGGER"))trigger=true
            buffer.append(line).append('\n')
            if((!trigger&&t.endsWith(';'))||(trigger&&t=="END;")) {
                val statement=buffer.toString()
                if(statement.trimStart().startsWith("PRAGMA"))target.rawQuery(statement,emptyArray<String>()).use {it.moveToFirst()} else target.execSQL(statement)
                buffer.clear();trigger=false
            }
        } }
        target.execSQL("CREATE TABLE private_setting (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        target.execSQL("CREATE TABLE day_label (day TEXT PRIMARY KEY, favorite INTEGER NOT NULL DEFAULT 0, tags TEXT NOT NULL DEFAULT '')")
        target.execSQL("CREATE TABLE inference_budget (day TEXT PRIMARY KEY, calls INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, active_ms INTEGER NOT NULL DEFAULT 0)")
        target.setTransactionSuccessful()
        } finally {target.endTransaction()}
    }
    @Synchronized fun <T> read(block:DiaryStore.()->T):T=block()
    @Synchronized fun <T> transaction(block:DiaryStore.()->T):T {
        db.beginTransaction();return try { val result=block();db.setTransactionSuccessful();changes.value++;result } finally { db.endTransaction() }
    }
    fun exec(sql:String,vararg args:Any?) { db.execSQL(sql,args) }
    @Synchronized fun rows(sql:String,vararg args:String):List<List<String?>> = db.rawQuery(sql,args).use { c->buildList { while(c.moveToNext())add((0 until c.columnCount).map { if(c.isNull(it))null else c.getString(it) }) } }
    fun scalar(sql:String,vararg args:String)=rows(sql,*args).firstOrNull()?.firstOrNull()?:"0"
    fun blob(sql:String,vararg args:String):ByteArray?=db.rawQuery(sql,args).use { if(it.moveToFirst()&&!it.isNull(0))it.getBlob(0) else null }
    fun setting(name:String)=rows("SELECT value FROM private_setting WHERE name=?",name).firstOrNull()?.get(0)
    fun setting(name:String,value:String) { exec("INSERT OR REPLACE INTO private_setting VALUES(?,?)",name,value) }
    fun preferences()=setting("preferences")?.let { json.decodeFromString<Preference>(it) }?:Preference()
    fun savePreferences(p:Preference)=transaction { setting("preferences",json.encodeToString(p)) }
    fun id()=UUID.randomUUID().toString()
    fun ensureDay(day:String,zone:String=ZoneId.systemDefault().id):String {
        exec("INSERT OR IGNORE INTO diary_day(id,local_date) VALUES(?,?)",day,day)
        val range=DayRules.range(day,zone);val slice="$day@$zone"
        if(scalar("SELECT count(*) FROM day_slice WHERE id=?",slice)=="0") {
            val anchor=id();exec("INSERT INTO clock_anchor VALUES(?,?,?,?,?,?)",anchor,bootId(),android.os.SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis(),zone,"VALID")
            exec("INSERT INTO day_slice VALUES(?,?,?,?,?,?)",slice,day,zone,range.start,range.end,anchor)
        };return slice
    }
    private fun insertArtifact(key:String,kind:String,day:String,revision:Int=1) {
        exec("UPDATE app_meta SET ingest_seq=ingest_seq+1")
        exec("INSERT INTO artifact(id,kind,owner_day_id,revision,created_epoch,ingest_seq,lifecycle,created_utc_ms) SELECT ?,?,?,?,?,ingest_seq,'ACTIVE',? FROM app_meta",key,kind,day,revision,epoch,System.currentTimeMillis())
    }
    fun link(parent:String,child:String,relation:String="DERIVED_FROM") {
        require(parent!=child)
        check(scalar("WITH RECURSIVE a(id) AS (SELECT ? UNION SELECT child_id FROM lineage_edge JOIN a ON parent_id=a.id) SELECT count(*) FROM a WHERE id=?",child,parent)=="0")
        exec("INSERT OR IGNORE INTO lineage_edge VALUES(?,?,?)",parent,child,relation)
    }
    fun commitEvent(event:Event,expectedEpoch:Long,origin:String=event.id,chunkId:String?=null):Boolean=transaction {
        if(maintenance||epoch!=expectedEpoch||scalar("SELECT count(*) FROM privacy_tombstone WHERE origin_key=?",origin)!="0")return@transaction false
        if(scalar("SELECT count(*) FROM source_event WHERE origin_key=?",origin)!="0")return@transaction true
        require(event.text==TextRules.sanitize(event.text));require(event.digest==TextRules.sha256(event.text))
        val slice=ensureDay(event.day,event.zoneId);insertArtifact(event.id,"SOURCE_EVENT",event.day)
        exec("INSERT INTO source_event(artifact_id,source,slice_id,origin_chunk_id,origin_key,observed_start_ms,observed_end_ms,text_nfc,text_sha256,quality_tier,calibration_profile_id,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",event.id,event.source.name,slice,chunkId,origin,event.startMs,event.endMs,event.text,event.digest,event.quality.name,event.calibrationProfileId,json.encodeToString(event.metadata))
        index(event.id,event.day,event.source.name,event.text)
        true
    }
    fun saveFacts(day:String,facts:List<Fact>,expectedEpoch:Long)=transaction {
        check(epoch==expectedEpoch&&!maintenance)
        facts.forEach { fact->
            if(scalar("SELECT count(*) FROM artifact WHERE id=?",fact.id)!="0")return@forEach
            insertArtifact(fact.id,"FACT_CLAIM",day)
            val bindings=if(fact.type in listOf(ClaimType.PROCESSING_STATUS,ClaimType.SYSTEM_GAP_RECORDED))"{}" else "{\"quoted_text\":{\"evidence_index\":0}}"
            exec("INSERT INTO fact_claim VALUES(?,?,?,?, 'APPROVED',NULL,'1.1',?,?)",fact.id,fact.type.name,fact.templateId,bindings,fact.startMs,fact.startMs)
            fact.evidence.forEach {e->exec("INSERT INTO evidence_ref VALUES(?,?,?,?,?,?,?,?)",id(),fact.id,e.eventId,e.eventRevision,e.startCp,e.endCp,e.quote,e.textSha256);link(e.eventId,fact.id)}
        }
        // Summaries are controlled observation text, retaining every underlying root.
        // They serve as bounded windows for model planning; they never invent prose.
        val windows=facts.groupBy {it.startMs/900_000}
        windows.values.forEach { group->
            val fingerprint=TextRules.sha256(group.joinToString("|"){it.id}+"|1.1")
            if(scalar("SELECT count(*) FROM summary_chunk WHERE input_fingerprint=?",fingerprint)!="0")return@forEach
            val key=id();insertArtifact(key,"SUMMARY",day)
            val roots=group.flatMap {it.eventIds}.distinct()
            exec("INSERT INTO summary_chunk VALUES(?,'WINDOW',?,?,?,?,?,?)",key,group.minOf {it.startMs},group.maxOf {it.startMs}+1,group.joinToString("\n"){it.text},json.encodeToString(roots),"controlled-template-1.1",fingerprint)
            group.forEach {link(it.id,key)}
        }
    }
    private fun index(root:String,day:String,source:String,text:String) {
        val key=id();insertArtifact(key,"SEARCH_DOCUMENT",day)
        exec("INSERT INTO search_document(artifact_id,source_artifact_id,day_id,source,body_text,folded_text,tokens,offset_map_json,indexed_epoch) VALUES(?,?,?,?,?,?,?,?,?)",key,root,day,source,text,SearchTokens.fold(text),SearchTokens.tokens(text).joinToString(" "),json.encodeToString(SearchTokens.mapping(text)),epoch)
        searchChanges.value++
    }
    fun narrativeCache(fingerprint:String)=read {rows("SELECT s.body_text FROM summary_chunk s JOIN artifact a ON a.id=s.artifact_id WHERE s.input_fingerprint=? AND a.lifecycle='ACTIVE'",fingerprint).firstOrNull()?.firstOrNull()}
    fun saveNarrativeCache(day:String,fingerprint:String,body:String,roots:List<String>,start:Long,end:Long,version:String,expectedEpoch:Long,level:String)=transaction {
        if(maintenance||epoch!=expectedEpoch)return@transaction
        check(roots.isNotEmpty()&&roots.distinct().all {scalar("SELECT count(*) FROM source_event s JOIN artifact a ON a.id=s.artifact_id WHERE a.id=? AND a.lifecycle='ACTIVE'",it)=="1"})
        if(narrativeCache(fingerprint)!=null)return@transaction
        val key=id();insertArtifact(key,"SUMMARY",day)
        exec("INSERT INTO summary_chunk VALUES(?,?,?,?,?,?,?,?)",key,level,start,maxOf(end,start+1),body,json.encodeToString(roots.distinct()),version,fingerprint)
        roots.distinct().forEach {link(it,key)}
    }
    fun events(day:String?=null):List<Event> {
        val condition=if(day==null)"" else " AND a.owner_day_id=?"
        return rows("SELECT a.id,a.owner_day_id,s.source,s.text_nfc,s.observed_start_ms,s.observed_end_ms,d.zone_id,s.quality_tier,a.revision,s.text_sha256,s.calibration_profile_id,s.metadata_json FROM source_event s JOIN artifact a ON a.id=s.artifact_id JOIN day_slice d ON d.id=s.slice_id WHERE a.lifecycle='ACTIVE'$condition ORDER BY s.observed_start_ms",*if(day==null)emptyArray() else arrayOf(day)).map { r-> Event(r[0]!!,r[1]!!,Source.valueOf(r[2]!!),r[3]!!,r[4]!!.toLong(),r[5]!!.toLong(),r[6]!!,Quality.valueOf(r[7]!!),r[8]!!.toInt(),r[9]!!,r[10],metadata=json.decodeFromString(r[11]!!)) }
    }
    fun processingReport(day:String):List<String> {
        val r=rows("SELECT coalesce(sum(max(0,coalesce(m.end_utc_ms,m.first_utc_ms)-m.first_utc_ms)),0),coalesce(sum(CASE WHEN j.state='SUCCEEDED' THEN max(0,coalesce(m.end_utc_ms,m.first_utc_ms)-m.first_utc_ms) ELSE 0 END),0),coalesce(sum(CASE WHEN (j.state IN ('FAILED_FINAL','EXPIRED') OR w.state='EXPIRED') THEN max(0,coalesce(m.end_utc_ms,m.first_utc_ms)-m.first_utc_ms) ELSE 0 END),0),coalesce(sum(CASE WHEN j.state='SUCCEEDED' AND w.detail='NO_SPEECH' THEN max(0,coalesce(m.end_utc_ms,m.first_utc_ms)-m.first_utc_ms) ELSE 0 END),0) FROM media_chunk m LEFT JOIN processing_job j ON j.id=(SELECT id FROM processing_job WHERE input_fingerprint=m.id AND kind='ASR' ORDER BY expected_epoch DESC LIMIT 1) LEFT JOIN recognition_work w ON w.id=m.id WHERE m.day_id=?",day).first()
        val total=r[0]!!.toLong();val complete=r[1]!!.toLong()
        val ocr=rows("SELECT count(*),coalesce(sum(CASE WHEN state='SUCCEEDED' THEN 1 ELSE 0 END),0),coalesce(sum(CASE WHEN state='FAILED' THEN 1 ELSE 0 END),0),coalesce(sum(has_text),0) FROM recognition_work WHERE day_id=? AND channel='SCREEN'",day).first()
        fun percent(n:Long,d:Long)=ProcessingMetrics.ratio(n,d)?.let {"${(it*100).toInt()}%"}?:"—"
        return listOf("声音处理完成 ${percent(complete,total)} · ${complete/1000.0}/${total/1000.0} 秒","声音无语音 ${r[3]!!.toLong()/1000.0} 秒 · 最终失败 ${r[2]!!.toLong()/1000.0} 秒","屏幕处理完成 ${percent(ocr[1]!!.toLong(),ocr[0]!!.toLong())} · ${ocr[1]}/${ocr[0]} 帧","屏幕文字产出 ${ocr[3]} 帧 · 处理失败 ${ocr[2]} 帧")
    }
    fun audioPending(day:String)=scalar("SELECT count(*) FROM media_chunk m WHERE m.day_id=? AND m.wrapped_dek IS NOT NULL AND m.state IN ('QUEUED','WRITING','PROCESSING','FAILED_RETRYABLE') AND NOT EXISTS(SELECT 1 FROM processing_job j WHERE j.input_fingerprint=m.id AND j.kind='ASR' AND j.state IN ('FAILED_FINAL','EXPIRED') AND j.expected_epoch=(SELECT content_epoch FROM app_meta))",day).toInt()
    fun audioFailed(day:String)=scalar("SELECT count(*) FROM processing_job j JOIN job_input i ON i.job_id=j.id JOIN media_chunk m ON m.id=i.chunk_id WHERE j.state IN ('FAILED_FINAL','EXPIRED') AND m.day_id=?",day).toInt()
    fun diary(day:String,state:String="PUBLISHED",revisionId:String?=null):Diary? {
        val r=if(revisionId==null)rows("SELECT r.artifact_id,r.title_text,r.revision_no,r.editor,r.state,r.used_fallback,d.has_pending_processing,d.has_capture_gap FROM diary_revision r JOIN artifact a ON a.id=r.artifact_id JOIN diary_day d ON d.id=r.day_id WHERE r.day_id=? AND r.state=? AND a.lifecycle='ACTIVE' ORDER BY revision_no DESC LIMIT 1",day,state).firstOrNull()
            else rows("SELECT r.artifact_id,r.title_text,r.revision_no,r.editor,r.state,r.used_fallback,d.has_pending_processing,d.has_capture_gap FROM diary_revision r JOIN artifact a ON a.id=r.artifact_id JOIN diary_day d ON d.id=r.day_id WHERE r.day_id=? AND r.artifact_id=? AND a.lifecycle='ACTIVE' AND r.state!='INVALIDATED'",day,revisionId).firstOrNull()
        if(r==null)return null
        val blocks=rows("SELECT b.artifact_id,b.body_text,b.origin,b.template_id,b.user_note_root_id FROM diary_block b JOIN artifact a ON a.id=b.artifact_id WHERE b.revision_id=? AND a.lifecycle='ACTIVE' ORDER BY b.ordinal",r[0]!!).map { b->DiaryBlock(b[0]!!,b[1]!!,b[2]!!,rows("SELECT parent_id FROM lineage_edge WHERE child_id=? AND relation='DERIVED_FROM'",b[0]!!).mapNotNull { it[0] },b[3],b[4],rows("SELECT event_id FROM inferred_block_source WHERE block_id=?",b[0]!!).firstOrNull()?.firstOrNull()) }
        return Diary(r[0]!!,day,r[1]!!,blocks,r[2]!!.toInt(),r[3]!!,r[4]!!,r[5]=="1",r[6]!!.toInt(),r[7]=="1")
    }
    fun publish(day:String,title:String,blocks:List<DiaryBlock>,editor:String,expectedRevision:Int?,expectedEpoch:Long,fallback:Boolean):Diary?=transaction {
        if(maintenance||epoch!=expectedEpoch)return@transaction null
        check(blocks.flatMap {it.eventIds}.distinct().all {scalar("SELECT count(*) FROM source_event s JOIN artifact a ON a.id=s.artifact_id WHERE a.id=? AND a.lifecycle='ACTIVE'",it)=="1"}) { "SOURCE_UNAVAILABLE" }
        ensureDay(day);val old=diary(day)
        val candidate=editor=="AI"&&(old?.editor=="USER"||old?.revision!=expectedRevision)
        if(editor=="USER"&&old?.revision!=expectedRevision)error("EDIT_CONFLICT")
        val rev=scalar("SELECT coalesce(max(revision_no),0)+1 FROM diary_revision WHERE day_id=?",day).toInt();val key=id()
        if(!candidate)exec("UPDATE diary_revision SET state='ARCHIVED' WHERE day_id=? AND state='PUBLISHED'",day)
        if(!candidate){
            exec("DELETE FROM memory_vector WHERE revision_id IN (SELECT artifact_id FROM diary_revision WHERE state!='PUBLISHED')")
            if(old!=null)rows("SELECT id,citations_json FROM assistant_turn WHERE instr(citations_json,?)>0",old.id).forEach {turn->
                val refs=runCatching {json.decodeFromString<List<MemoryHit>>(turn[1]!!)}.getOrNull()
                if(refs==null||refs.any {it.revisionId==old.id})exec("DELETE FROM assistant_turn WHERE id=?",turn[0])
            }
        }
        insertArtifact(key,"DIARY_REVISION",day,rev)
        exec("INSERT INTO diary_revision VALUES(?,?,?,?,?,?,?,?,?)",key,day,rev,old?.id,editor,TextRules.sanitize(title),if(candidate)"CANDIDATE" else "PUBLISHED",scalar("SELECT ingest_seq FROM app_meta").toLong(),if(fallback)1 else 0)
        blocks.forEachIndexed { ordinal,b->
            val bid=id();insertArtifact(bid,"DIARY_BLOCK",day)
            exec("INSERT INTO diary_block VALUES(?,?,?,?,?,?,?)",bid,key,ordinal,b.origin,b.userNoteRootId,b.templateId,TextRules.sanitize(b.text))
            b.primarySourceId?.let {source->check(source in b.eventIds);exec("INSERT INTO inferred_block_source VALUES(?,?)",bid,source)}
            b.eventIds.distinct().forEach { source-> link(source,bid);link(source,key) }
            index(bid,day,"DIARY",TextRules.sanitize(b.text))
        }
        if(!candidate)memoryChanges.value++
        diary(day,if(candidate)"CANDIDATE" else "PUBLISHED")
    }
    fun search(query:String,from:String?=null,to:String?=null,source:String?=null,favorites:Boolean=false,tag:String?=null,limit:Int=100,offset:Int=0):List<SearchHit> {
        require(limit in 1..1000&&offset>=0)
        if(query.isBlank())return emptyList();val fts=SearchTokens.ftsQuery(query);if(fts.isBlank())return emptyList()
        val conditions=mutableListOf("s.id IN (SELECT rowid FROM search_fts WHERE search_fts MATCH ?)","a.lifecycle='ACTIVE'","root.lifecycle='ACTIVE'","(s.source!='DIARY' OR EXISTS(SELECT 1 FROM diary_block b JOIN diary_revision r ON r.artifact_id=b.revision_id WHERE b.artifact_id=s.source_artifact_id AND r.state='PUBLISHED'))")
        val args=mutableListOf(fts)
        if(from!=null){ conditions+="s.day_id>=?";args+=from };if(to!=null){conditions+="s.day_id<=?";args+=to}
        if(source!=null){conditions+="s.source=?";args+=source};if(favorites)conditions+="l.favorite=1"
        if(!tag.isNullOrBlank()){conditions+="instr(','||l.tags||',',','||?||',')>0";args+=tag}
        SearchTokens.terms(query).forEach {term->conditions+="instr(s.folded_text,?)>0";args+=term}
        args+=limit.toString();args+=offset.toString()
        return rows("SELECT s.artifact_id,s.source_artifact_id,s.day_id,s.source,s.body_text,s.indexed_epoch FROM search_document s INDEXED BY search_order JOIN artifact a ON a.id=s.artifact_id JOIN artifact root ON root.id=s.source_artifact_id LEFT JOIN day_label l ON l.day=s.day_id WHERE ${conditions.joinToString(" AND ")} ORDER BY s.day_id DESC,s.id DESC LIMIT ? OFFSET ?",*args.toTypedArray()).filter { SearchTokens.literalMatch(it[4]!!,query) }.map { SearchHit(it[0]!!,it[1]!!,it[2]!!,it[3]!!,it[4]!!,it[5]!!.toLong()) }
    }
    fun favorite(day:String)=scalar("SELECT favorite FROM day_label WHERE day=?",day)=="1"
    fun setFavorite(day:String,favorite:Boolean,expectedEpoch:Long=epoch)=transaction {if(epoch!=expectedEpoch||maintenance)return@transaction;exec("INSERT INTO day_label(day,favorite) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET favorite=excluded.favorite",day,if(favorite)1 else 0);searchChanges.value++}
    fun restore(day:String,revisionId:String,expectedRevision:Int?,expectedEpoch:Long):Diary?=transaction {
        val version=diary(day,revisionId=revisionId)?:error("VERSION_UNAVAILABLE")
        publish(day,version.title,version.blocks,"USER",expectedRevision,expectedEpoch,version.fallback)
    }
    fun label(day:String,favorite:Boolean,tags:String,expectedEpoch:Long=epoch)=transaction {if(epoch!=expectedEpoch||maintenance)return@transaction;exec("INSERT OR REPLACE INTO day_label VALUES(?,?,?)",day,if(favorite)1 else 0,TextRules.sanitize(tags));searchChanges.value++ }
    fun days()=rows("SELECT local_date FROM diary_day ORDER BY local_date DESC").map { it[0]!! }
    fun bootId()=android.provider.Settings.Global.getInt(context.contentResolver,"boot_count",0).toString()

    fun recoverSwitch() {
        val pending=vault.read("retire.json")?:return
        val old=pending.getLong("old");val next=pending.getLong("next")
        if(descriptor.getLong("generation")==next) {
            vault.destroy("db",old);File(vault.root,"diary-$old.db").delete()
            com.blackbox.diary.security.FaultGate.hit(context,"FP11")
            com.blackbox.diary.security.FaultGate.hit(context,"FP12")
            exec("UPDATE app_meta SET maintenance_state='NORMAL'")
            exec("UPDATE deletion_operation SET state='COMPLETED' WHERE state IN ('SWITCHED','KEY_DESTROY_PENDING')")
            File(vault.root,"retire.json").delete()
        } else if(vault.exists("db",next)) { vault.destroy("db",next);File(vault.root,"diary-$next.db").delete() }
    }
    /** Called after deletion has hidden/tombstoned the complete dependency closure. */
    @Synchronized fun compactAndRetire(operation:String) {
        val old=descriptor.getLong("generation");val next=old+1
        // An interrupted copy has no committed manifest. Its random DB key was never
        // published, so retire that orphan generation and start a fresh clean copy.
        val orphan=File(vault.root,"diary-$next.db")
        check(orphan.canonicalFile.parentFile==vault.root.canonicalFile)
        if(orphan.exists()) {if(vault.exists("db",next))vault.destroy("db",next);check(orphan.delete());File(vault.root,"diary-$next.db-journal").delete()}
        val d=vault.newDescriptor(next);val clean=open(d)
        try {
            clean.execSQL("PRAGMA foreign_keys=OFF");clean.beginTransaction()
            val tables=rows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'search_fts%' AND name!='android_metadata'").map { it[0]!! }
            tables.forEachIndexed { tableIndex,table->
                require(table.matches(Regex("[a-z_]+")));clean.execSQL("DELETE FROM $table")
                db.rawQuery("SELECT * FROM $table",emptyArray<String>()).use { c->while(c.moveToNext()) {
                    val values=ContentValues();for(i in 0 until c.columnCount) when(c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL->values.putNull(c.getColumnName(i))
                        Cursor.FIELD_TYPE_INTEGER->values.put(c.getColumnName(i),c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT->values.put(c.getColumnName(i),c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB->values.put(c.getColumnName(i),c.getBlob(i))
                        else->values.put(c.getColumnName(i),c.getString(i))
                    };clean.insertOrThrow(table,null,values)
                } }
                if(tableIndex==tables.size/2)com.blackbox.diary.security.FaultGate.hit(context,"FP08")
            }
            clean.execSQL("UPDATE app_meta SET db_generation=?,maintenance_state='KEY_DESTROY_PENDING'",arrayOf(next))
            clean.execSQL("UPDATE search_document SET indexed_epoch=(SELECT content_epoch FROM app_meta)")
            clean.execSQL("UPDATE deletion_operation SET state='SWITCHED',new_generation=? WHERE id=?",arrayOf<Any>(next,operation))
            clean.rawQuery("PRAGMA foreign_key_check",emptyArray<String>()).use { check(!it.moveToFirst()) { "CLEAN_FOREIGN_KEY_CHECK" } }
            clean.execSQL("INSERT INTO search_fts(search_fts) VALUES('integrity-check')")
            clean.setTransactionSuccessful()
        } finally { clean.endTransaction();clean.close() }
        com.blackbox.diary.security.FaultGate.hit(context,"FP09")
        vault.atomic("retire.json",JSONObject().put("old",old).put("next",next))
        vault.atomic("active.json",d)
        com.blackbox.diary.security.FaultGate.hit(context,"FP10")
        db.close();descriptor=d;db=open(d)
        recoverSwitch();changes.value++;searchChanges.value++;memoryChanges.value++
    }
}
