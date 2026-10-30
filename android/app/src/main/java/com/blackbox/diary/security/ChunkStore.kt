package com.blackbox.diary.security

import com.blackbox.diary.AppGraph
import com.blackbox.diary.core.*
import java.io.*
import java.time.ZoneId
import android.os.SystemClock

class ChunkStore(private val graph:AppGraph) {
    val directory=File(graph.app.noBackupFilesDir,"chunks").apply { mkdirs() }
    inner class Writer(val id:String,val day:String,val epoch:Long,val key:ByteArray,val file:File,val stream:FileOutputStream,val out:DataOutputStream,val header:ChunkCodec.Header) {
        var count=0;private set
        fun append(pcm:ByteArray,utc:Long,elapsed:Long) {
            ChunkCodec.write(out,key,header,count.toLong(),ChunkCodec.Record(utc,elapsed,pcm));out.flush();stream.fd.sync();count++
            graph.store.transaction { exec("UPDATE media_chunk SET sealed_record_count=?,byte_count=?,end_utc_ms=? WHERE id=?",count,file.length(),utc+pcm.size/32,id) }
            if(count==1)FaultGate.hit(graph.app,"FP01")
        }
        fun seal() {
            out.close();key.fill(0)
            graph.store.transaction {
                if(epoch!=this.epoch||maintenance) { exec("UPDATE media_chunk SET state='TOMBSTONED',wrapped_dek=NULL WHERE id=?",id);file.delete() }
                else exec("UPDATE media_chunk SET state='QUEUED',byte_count=? WHERE id=?",file.length(),id)
            }
            FaultGate.hit(graph.app,"FP02")
        }
    }
    fun create(session:String?,firstUtc:Long=System.currentTimeMillis(),firstElapsedNs:Long=SystemClock.elapsedRealtimeNanos()):Writer=graph.store.transaction {
        check(!maintenance);check(scalar("SELECT count(*) FROM media_chunk WHERE wrapped_dek IS NOT NULL").toInt()<256&&directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }<512L*1024*1024) { "CACHE_FULL" }
        check(directory.usableSpace>256L*1024*1024) { "LOW_SPACE" }
        val now=firstUtc;val zone=ZoneId.systemDefault().id;val day=DayRules.localDay(now,zone);ensureDay(day,zone)
        val id=id();val raw=ChunkCodec.randomKey();val generation=scalar("SELECT cache_generation FROM app_meta").toLong()
        val wrapped=graph.vault.wrap("cache",generation,raw);val file=File(directory,"$id.bbd")
        exec("INSERT INTO media_chunk(id,session_id,day_id,origin_key,path_token,first_utc_ms,expires_utc_ms,created_elapsed_ns,key_generation,wrapped_dek,state) VALUES(?,?,?,?,?,?,?,?,?,?,'WRITING')",id,session,day,id,id,now,now+MediaTtl.MAX_MS,firstElapsedNs,generation,wrapped)
        val stream=FileOutputStream(file);val out=DataOutputStream(stream);val header=ChunkCodec.writeHeader(out,id);out.flush();stream.fd.sync()
        Writer(id,day,epoch,raw,file,stream,out,header)
    }
    fun read(id:String,consumer:(ChunkCodec.Record)->Unit):Int {
        val material=graph.store.read {
            val info=rows("SELECT key_generation,path_token FROM media_chunk WHERE id=? AND wrapped_dek IS NOT NULL",id).firstOrNull()?:error("MEDIA_UNAVAILABLE")
            info[1]!! to graph.vault.unwrap("cache",info[0]!!.toLong(),blob("SELECT wrapped_dek FROM media_chunk WHERE id=?",id)!!)
        }
        val key=material.second
        return try { DataInputStream(FileInputStream(File(directory,"${material.first}.bbd"))).use { input->val header=ChunkCodec.readHeader(input);require(header.id==id);ChunkCodec.read(input,key,header,consumer) } } finally { key.fill(0) }
    }
    /** Rewrap all survivors before irrevocably retiring the previous KEK. */
    fun purge(ids:Set<String>) {
        if(ids.isEmpty())return
        graph.store.transaction {
        ids.forEach { id->exec("UPDATE media_chunk SET wrapped_dek=NULL,state='DELETED' WHERE id=?",id) }
        rotateSurvivors()
        }
        FaultGate.hit(graph.app,"FP05")
        ids.forEach { File(directory,"$it.bbd").delete() }
    }
    private fun rotateSurvivors() {
        val store=graph.store;val old=store.scalar("SELECT cache_generation FROM app_meta").toLong();val next=old+1
        // Commit retirement intent in the encrypted database. Recovery also retires earlier generations.
        store.rows("SELECT id,key_generation FROM media_chunk WHERE wrapped_dek IS NOT NULL").forEach { r->
            val id=r[0]!!;val gen=r[1]!!.toLong();val raw=graph.vault.unwrap("cache",gen,store.blob("SELECT wrapped_dek FROM media_chunk WHERE id=?",id)!!)
            try { store.exec("UPDATE media_chunk SET wrapped_dek=?,key_generation=? WHERE id=?",graph.vault.wrap("cache",next,raw),next,id) } finally { raw.fill(0) }
        }
        store.exec("UPDATE app_meta SET cache_generation=?",next)
        store.setting("cache_retire_before",next.toString())
        // Keys are retired only AFTER the caller's enclosing transaction commits.
    }
    fun retireOldKeys() {
        val before=graph.store.read { setting("cache_retire_before")?.toLong() }?:return
        val retired=graph.store.read {setting("cache_retired_through")?.toLong()?:0}
        for(g in retired+1 until before)if(graph.vault.exists("cache",g))graph.vault.destroy("cache",g)
        graph.store.transaction {setting("cache_retired_through",maxOf(setting("cache_retired_through")?.toLong()?:0,before-1).toString())}
        FaultGate.hit(graph.app,"FP06")
    }
    fun sweep() {
        if(graph.store.maintenance)return
        val now=System.currentTimeMillis()
        val elapsed=SystemClock.elapsedRealtime()
        val snapshot=graph.store.read {epoch to rows("SELECT m.id,m.first_utc_ms,m.expires_utc_ms,m.created_elapsed_ns,a.boot_id,m.state,m.day_id FROM media_chunk m LEFT JOIN capture_session s ON s.id=m.session_id LEFT JOIN clock_anchor a ON a.id=s.anchor_id WHERE m.wrapped_dek IS NOT NULL")}
        val expectedEpoch=snapshot.first;val candidates=snapshot.second
        val expired=candidates.filter {r->MediaTtl.expired(r[1]!!.toLong(),r[2]!!.toLong(),r[3]!!.toLong()/1_000_000,now,elapsed,r[4]==graph.store.bootId())}
        graph.store.transaction {
            if(epoch!=expectedEpoch||maintenance)return@transaction
            expired.filter {it[5] !in listOf("COMMITTED","TOMBSTONED")}.forEach {r->
                exec("UPDATE media_chunk SET state='DELETE_PENDING' WHERE id=?",r[0])
                exec("INSERT OR REPLACE INTO recognition_work VALUES(?,?,'MICROPHONE','EXPIRED',0,'EXPIRED')",r[0],r[6])
                exec("UPDATE processing_job SET state='EXPIRED',lease_until_utc_ms=NULL WHERE id IN (SELECT job_id FROM job_input WHERE chunk_id=?)",r[0])
                exec("UPDATE diary_day SET has_processing_failure=1 WHERE id=?",r[6])
            }
        }
        if(graph.store.epoch!=expectedEpoch||graph.store.maintenance)return
        val ids=(expired+candidates.filter {it[5] in listOf("COMMITTED","TOMBSTONED","DELETE_PENDING")}).map {it[0]!!}.toSet()
        purge(ids);retireOldKeys()
        graph.store.transaction {
            if(epoch!=expectedEpoch||maintenance)return@transaction
            rows("SELECT m.id,m.day_id,m.first_utc_ms FROM media_chunk m JOIN recognition_work w ON w.id=m.id WHERE m.wrapped_dek IS NULL AND m.state='DELETED' AND w.state='EXPIRED'").forEach {r->
                val text="有一段声音未在 24 小时内完成转换，已按隐私规则清除。"
                commitEvent(Event(id(),r[1]!!,Source.SYSTEM,text,r[2]!!.toLong(),r[2]!!.toLong(),ZoneId.systemDefault().id,Quality.HIGH,metadata=mapOf("kind" to "PROCESSING_STATUS")),expectedEpoch,"expiry:${r[0]}")
                exec("UPDATE media_chunk SET state='EXPIRED_UNPROCESSED' WHERE id=?",r[0])
            }
            val live=rows("SELECT path_token FROM media_chunk WHERE wrapped_dek IS NOT NULL").map {it[0]!!+".bbd"}.toSet()
            directory.listFiles()?.filter {it.name !in live}?.forEach {it.delete()}
        }
    }
}
