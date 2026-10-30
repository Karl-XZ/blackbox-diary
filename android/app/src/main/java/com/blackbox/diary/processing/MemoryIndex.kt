package com.blackbox.diary.processing

import com.blackbox.diary.AppGraph
import com.blackbox.diary.core.*
import com.blackbox.diary.inference.SemanticEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.PriorityQueue

class MemoryIndex(private val graph:AppGraph) {
    companion object {const val MODEL="multilingual-e5-small-int8-dd476dd0-v2"}
    val status=MutableStateFlow("等待日记建立记忆")
    private val mutex=Mutex()
    suspend fun sync()=mutex.withLock {withContext(Dispatchers.IO){
        val store=graph.store;if(store.maintenance)return@withContext
        val epoch=store.epoch
        if(store.read {scalar("SELECT count(*) FROM memory_vector WHERE model!=?",MODEL)}!="0")store.transaction {if(!maintenance&&this.epoch==epoch)exec("DELETE FROM memory_vector WHERE model!=?",MODEL)}
        val blocks=store.read {rows("SELECT b.artifact_id,b.revision_id,b.body_text FROM diary_block b JOIN diary_revision r ON r.artifact_id=b.revision_id JOIN artifact a ON a.id=b.artifact_id WHERE r.state='PUBLISHED' AND a.lifecycle='ACTIVE' ORDER BY r.day_id,b.ordinal")}
        if(blocks.isEmpty()){status.value="还没有可检索的日记";return@withContext}
        var encoder:SemanticEncoder?=null;var done=0
        try {for(b in blocks){
            val text=b[2]!!;val length=TextRules.codePointLength(text);val hash=TextRules.sha256(text);var start=0
            while(start<length){currentCoroutineContext().ensureActive();if(store.maintenance||store.epoch!=epoch)return@withContext
                var end=minOf(start+360,length);val key="${b[0]}:$start";val existing=store.read {rows("SELECT end_cp FROM memory_vector WHERE chunk_key=? AND model=? AND text_sha256=?",key,MODEL,hash).firstOrNull()?.firstOrNull()?.toInt()}
                if(existing==null){
                    status.value="正在建立日记记忆 · ${done+1} 段"
                    val model=encoder?:SemanticEncoder(graph.models).also {encoder=it}
                    while(model.inputLength(TextRules.slice(text,start,end))>256&&end>start+1)end-=maxOf(1,(end-start)/4)
                    check(model.inputLength(TextRules.slice(text,start,end))<=256)
                    val vector=model.encode(TextRules.slice(text,start,end),false)
                    try {store.transaction {
                        if(maintenance||this.epoch!=epoch)return@transaction
                        if(scalar("SELECT count(*) FROM diary_block b JOIN diary_revision r ON r.artifact_id=b.revision_id JOIN artifact a ON a.id=b.artifact_id WHERE b.artifact_id=? AND r.state='PUBLISHED' AND a.lifecycle='ACTIVE'",b[0]!!)!="1")return@transaction
                        exec("INSERT OR REPLACE INTO memory_vector VALUES(?,?,?,?,?,?,?,?)",key,b[0],b[1],hash,start,end,MODEL,Vectors.bytes(vector))
                    }}finally {vector.fill(0f)}
                }else end=existing
                done++;if(end==length)break;start=maxOf(start+1,end-48)
            }
        };status.value="日记记忆已就绪 · $done 段"}
        catch(t:Exception){if(t is CancellationException)throw t;status.value="记忆索引尚未完成，可稍后重试";throw t}
        finally {encoder?.close()}
    }}
    suspend fun retrieve(query:String,limit:Int=6):List<MemoryHit> {sync();return mutex.withLock {withContext(Dispatchers.IO){
        if(graph.store.maintenance)return@withContext emptyList()
        SemanticEncoder(graph.models).use {encoder->val q=encoder.encode(query,true)
            try {graph.store.read {
                if(maintenance)return@read emptyList()
                val current=epoch;val best=PriorityQueue<MemoryHit>(compareBy {it.score});val terms=SearchTokens.tokens(query)
                db.rawQuery("SELECT v.chunk_key,v.block_id,v.revision_id,r.day_id,b.body_text,CASE WHEN b.template_id='audio.inferred.v1' OR b.template_id LIKE 'diary.summary.%' THEN 'AI_INFERRED' ELSE b.origin END,v.start_cp,v.end_cp,v.vector,v.text_sha256 FROM memory_vector v JOIN diary_block b ON b.artifact_id=v.block_id JOIN diary_revision r ON r.artifact_id=v.revision_id JOIN artifact a ON a.id=b.artifact_id JOIN artifact rev ON rev.id=r.artifact_id WHERE v.model=? AND r.state='PUBLISHED' AND a.lifecycle='ACTIVE' AND rev.lifecycle='ACTIVE'",arrayOf(MODEL)).use {c->while(c.moveToNext()){
                    val body=c.getString(4);if(TextRules.sha256(body)!=c.getString(9))continue
                    val text=TextRules.slice(body,c.getInt(6),c.getInt(7));val v=Vectors.floats(c.getBlob(8));val cosine=try {Vectors.cosine(q,v)}finally {v.fill(0f)}
                    val lexical=if(terms.isEmpty())0f else terms.count {text.contains(it,ignoreCase=true)}.toFloat()/terms.size
                    val hit=MemoryHit(c.getString(1),c.getString(2),c.getString(3),text,c.getString(5),cosine*.9f+lexical*.1f,current)
                    best.add(hit);if(best.size>limit)best.poll()
                }};best.toList().sortedByDescending {it.score}
            }}finally {q.fill(0f)}
        }
    }}}
    fun live(hit:MemoryHit)=graph.store.read {!maintenance&&epoch==hit.epoch&&scalar("SELECT count(*) FROM diary_block b JOIN diary_revision r ON r.artifact_id=b.revision_id JOIN artifact a ON a.id=b.artifact_id WHERE b.artifact_id=? AND r.artifact_id=? AND r.state='PUBLISHED' AND a.lifecycle='ACTIVE'",hit.blockId,hit.revisionId)=="1"}
}
