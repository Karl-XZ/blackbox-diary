package com.blackbox.diary.capture

import android.content.Intent
import android.os.SystemClock
import com.blackbox.diary.AppGraph
import com.blackbox.diary.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.ZoneId

class CaptureController(val graph:AppGraph) {
    val activeTasks=MutableStateFlow(0)
    @Synchronized fun taskStarted(){activeTasks.value++}
    @Synchronized fun taskFinished(){activeTasks.value=(activeTasks.value-1).coerceAtLeast(0)}
    val audio=MutableStateFlow(CaptureStatus(Channel.MICROPHONE))
    val screen=MutableStateFlow(CaptureStatus(Channel.SCREEN))
    private val intents=mutableMapOf<Channel,String>()
    private val sessions=mutableMapOf<Channel,String>()
    private val clocks=mutableMapOf<Channel,Pair<Long,Long>>()
    private val zones=mutableMapOf<Channel,String>()
    init {
        Channel.entries.forEach {c->
            val last=graph.store.read {rows("SELECT s.state,s.stop_reason,s.end_utc_ms FROM capture_session s JOIN capture_intent i ON i.id=s.intent_id WHERE i.channel=? ORDER BY s.start_utc_ms DESC,s.rowid DESC LIMIT 1",c.name).firstOrNull()}
            if(last!=null) {
                val interrupted=last[2]==null||last[0]=="INTERRUPTED"
                val reason=when {
                    last[2]==null||last[1]=="PROCESS_RESTART"->"上次因应用或系统重启中断，请重新开启"
                    last[1]=="USER_STOP"->"上次已停止记录，点击开启后继续"
                    else->last[1]?:"上次记录已结束，请重新开启"
                }
                flow(c).value=CaptureStatus(c,if(interrupted)CaptureState.INTERRUPTED else CaptureState.OFF,reason)
            }
        }
    }
    @Synchronized fun checkClock(c:Channel,utc:Long,elapsed:Long):Boolean {
        val status=flow(c).value
        if(status.state !in listOf(CaptureState.RECORDING,CaptureState.PAUSED_USER,CaptureState.PAUSED_RULE))return false
        val previous=clocks.put(c,utc to elapsed)?:return false
        val zone=ZoneId.systemDefault().id
        val previousZone=zones.put(c,zone)
        if(previousZone!=null&&previousZone!=zone) {
            graph.store.transaction {sessions.remove(c)?.let {exec("UPDATE capture_session SET end_utc_ms=max(start_utc_ms,?),state='INTERRUPTED',stop_reason='ZONE_CHANGED' WHERE id=?",utc,it)}}
            begin(c,status);return true
        }
        if(!ClockRules.discontinuity(previous.first,previous.second,utc,elapsed))return false
        graph.store.transaction {
            intents[c]?.let {key->exec("UPDATE capture_zone SET end_ms=max(start_ms,?) WHERE intent_id=? AND end_ms IS NULL",previous.first,key)}
            sessions.remove(c)?.let {key->exec("UPDATE capture_session SET end_utc_ms=max(start_utc_ms,?),state='INTERRUPTED',stop_reason='CLOCK_CHANGED' WHERE id=?",previous.first,key)}
            intents.remove(c)?.let {key->exec("UPDATE capture_intent SET end_utc_ms=max(start_utc_ms,?) WHERE id=?",previous.first,key)}
        }
        begin(c,status)
        val text="系统时钟发生变化，这段时间的记录时长无法确认。"
        graph.store.transaction {sessions[c]?.let {exec("UPDATE clock_anchor SET quality='CLOCK_CHANGED' WHERE id=(SELECT anchor_id FROM capture_session WHERE id=?)",it)}}
        listOf(previous.first,utc).distinctBy {DayRules.localDay(it,zone)}.forEach {at->graph.store.commitEvent(Event(graph.store.id(),DayRules.localDay(at,zone),Source.SYSTEM,text,at,at,zone,Quality.HIGH,metadata=mapOf("kind" to "CAPTURE_GAP","clock_changed" to "true")),graph.store.epoch)}
        return true
    }
    fun flow(c:Channel)=if(c==Channel.MICROPHONE)audio else screen
    @Synchronized fun begin(c:Channel,status:CaptureStatus=CaptureStatus(c,CaptureState.RECORDING)) {
        check(!graph.store.maintenance)
        graph.store.transaction {
            sessions.remove(c)?.let {key->exec("UPDATE capture_session SET end_utc_ms=max(start_utc_ms,?),state='INTERRUPTED',stop_reason='NEW_ANCHOR' WHERE id=?",System.currentTimeMillis(),key)}
            val existing=intents[c]?.takeIf {scalar("SELECT count(*) FROM capture_intent WHERE id=? AND end_utc_ms IS NULL",it)=="1"}
                ?:rows("SELECT id FROM capture_intent WHERE channel=? AND end_utc_ms IS NULL ORDER BY start_utc_ms DESC LIMIT 1",c.name).firstOrNull()?.firstOrNull()
            val intent=existing?:id().also {exec("INSERT INTO capture_intent VALUES(?,?,?,NULL,'USER')",it,c.name,System.currentTimeMillis())}
            intents[c]=intent
            val now=System.currentTimeMillis();val zone=ZoneId.systemDefault().id
            val existingZone=rows("SELECT zone_id FROM capture_zone WHERE intent_id=? AND end_ms IS NULL",intent).firstOrNull()?.firstOrNull()
            if(existingZone!=zone){exec("UPDATE capture_zone SET end_ms=max(start_ms,?) WHERE intent_id=? AND end_ms IS NULL",now,intent);exec("INSERT INTO capture_zone VALUES(?,?,?,NULL,?)",id(),intent,now,zone)}
            zones[c]=zone
            val anchor=id();exec("INSERT INTO clock_anchor VALUES(?,?,?,?,?,'VALID')",anchor,bootId(),SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis(),ZoneId.systemDefault().id)
            val session=id();sessions[c]=session
            exec("INSERT INTO capture_session VALUES(?,?,?,?,NULL,'RECORDING',NULL,?)",session,intent,anchor,System.currentTimeMillis(),SystemClock.elapsedRealtimeNanos())
        };flow(c).value=status
    }
    fun startAudio() {
        if(graph.store.maintenance)return
        graph.app.startForegroundService(Intent(graph.app,AudioCaptureService::class.java))
    }
    fun startScreen(result:Int,data:Intent) {
        if(graph.store.maintenance)return
        graph.app.startForegroundService(Intent(graph.app,ScreenCaptureService::class.java).putExtra("result",result).putExtra("projection",data))
    }
    @Synchronized fun stop(c:Channel,reason:String="USER_STOP",state:CaptureState=CaptureState.OFF) {
        val now=System.currentTimeMillis()
        graph.store.transaction {
            if(state!=CaptureState.PAUSED_USER) {
                val rule=setting("rule_pause_${c.name}")?.toLongOrNull();val ruleIntent=intents[c]
                if(rule!=null&&ruleIntent!=null&&now>rule)exec("INSERT INTO capture_exclusion VALUES(?,?,?,?,?)",id(),ruleIntent,rule,now,"SCHEDULED_EXCLUSION")
                exec("DELETE FROM private_setting WHERE name=?","rule_pause_${c.name}")
                val pause=setting("pause_${c.name}")?.split('|');val intent=intents[c]
                if(pause!=null&&intent!=null&&now>pause[0].toLong())exec("INSERT INTO capture_exclusion VALUES(?,?,?,?,?)",id(),intent,pause[0].toLong(),now,if(pause[1]=="0")"USER_PAUSE" else "TIMED_PAUSE")
                exec("DELETE FROM private_setting WHERE name=?","pause_${c.name}")
            }
            sessions.remove(c)?.let { exec("UPDATE capture_session SET end_utc_ms=?,state=?,stop_reason=? WHERE id=?",now,state.name,reason,it) }
            if(state==CaptureState.OFF||state==CaptureState.PAUSED_MAINTENANCE)intents.remove(c)?.let { exec("UPDATE capture_intent SET end_utc_ms=max(start_utc_ms,?) WHERE id=?",now,it);exec("UPDATE capture_zone SET end_ms=max(start_ms,?) WHERE intent_id=? AND end_ms IS NULL",now,it) }
        }
        flow(c).value=CaptureStatus(c,state,reason)
        if(state!=CaptureState.PAUSED_USER)graph.app.stopService(Intent(graph.app,if(c==Channel.MICROPHONE)AudioCaptureService::class.java else ScreenCaptureService::class.java))
    }
    fun interrupt(c:Channel,reason:String) { if(flow(c).value.state in listOf(CaptureState.RECORDING,CaptureState.PAUSED_RULE,CaptureState.PAUSED_USER))stop(c,reason,CaptureState.INTERRUPTED) }
    fun rulePause(c:Channel,start:Long) {graph.store.transaction {if(setting("rule_pause_${c.name}")==null)setting("rule_pause_${c.name}",start.toString())}}
    fun endRulePause(c:Channel,end:Long) {graph.store.transaction {
        val start=setting("rule_pause_${c.name}")?.toLongOrNull();val intent=intents[c]
        if(start!=null&&intent!=null&&end>start)exec("INSERT INTO capture_exclusion VALUES(?,?,?,?,?)",id(),intent,start,end,"SCHEDULED_EXCLUSION")
        exec("DELETE FROM private_setting WHERE name=?","rule_pause_${c.name}")
    }}
    fun maintenance() { Channel.entries.forEach { stop(it,"删除维护",CaptureState.PAUSED_MAINTENANCE) } }
    @Synchronized fun finishMaintenance() {
        if(graph.store.maintenance)return
        Channel.entries.forEach {c->if(flow(c).value.state==CaptureState.PAUSED_MAINTENANCE)flow(c).value=CaptureStatus(c,CaptureState.OFF)}
    }
    fun pause(c:Channel,minutes:Int?) {
        stop(c,"用户暂停",CaptureState.PAUSED_USER)
        val until=minutes?.let { System.currentTimeMillis()+it*60_000L }
        flow(c).value=CaptureStatus(c,CaptureState.PAUSED_USER,"用户暂停",until)
        graph.store.transaction { setting("pause_${c.name}","${System.currentTimeMillis()}|${until?:0}") }
        if(until!=null)graph.scope.launch {delay((until-System.currentTimeMillis()).coerceAtLeast(0));if(flow(c).value.state==CaptureState.PAUSED_USER&&flow(c).value.pauseUntilMs==until)resume(c)}
    }
    fun resume(c:Channel) {
        graph.store.transaction {
            val p=setting("pause_${c.name}")?.split('|')
            val intent=intents[c]
            if(p!=null&&intent!=null)exec("INSERT INTO capture_exclusion VALUES(?,?,?,?,?)",id(),intent,p[0].toLong(),System.currentTimeMillis(),if(p[1]=="0")"USER_PAUSE" else "TIMED_PAUSE")
            exec("DELETE FROM private_setting WHERE name=?","pause_${c.name}")
        }
        if(intents.containsKey(c))begin(c) else flow(c).value=CaptureStatus(c,CaptureState.NEEDS_PERMISSION,"请重新授权记录")
    }
    @Synchronized fun tick(c:Channel,start:Long,end:Long,elapsedStart:Long,elapsedEnd:Long,outcome:String,valid:Long,reason:String?=null) {
        val session=sessions[c]?:return;val zone=ZoneId.systemDefault().id
        graph.store.transaction {
            // Split civil-day boundaries; elapsed durations remain the coverage authority.
            var a=start
            while(a<end) {
                val day=DayRules.localDay(a,zone);val b=minOf(end,DayRules.range(day,zone).end)
                val slice=ensureDay(day,zone);val total=(end-start).coerceAtLeast(1)
                val es=elapsedStart+(elapsedEnd-elapsedStart)*(a-start)/total
                val ee=elapsedStart+(elapsedEnd-elapsedStart)*(b-start)/total
                if(ee>es)exec("INSERT OR IGNORE INTO capture_tick VALUES(?,?,?,?,?,?,?,?,?,?)",id(),session,slice,a,b,es,ee,minOf(valid*(b-a)/total,(ee-es)/1_000_000),outcome,reason)
                a=b
            }
            exec("UPDATE capture_session SET last_confirmed_elapsed_ns=? WHERE id=?",elapsedEnd,session)
        }
    }
    fun exclusion(c:Channel,start:Long,end:Long,reason:String) {
        val intent=intents[c]?:return;if(end<=start)return
        graph.store.transaction { exec("INSERT INTO capture_exclusion VALUES(?,?,?,?,?)",id(),intent,start,end,reason) }
    }
    fun coverage(c:Channel,day:String):CoverageReport=graph.store.read {
        val range=DayRules.range(day,ZoneId.systemDefault().id);val now=System.currentTimeMillis()
        val zoneRows=rows("SELECT z.start_ms,coalesce(z.end_ms,?),z.zone_id FROM capture_zone z JOIN capture_intent i ON i.id=z.intent_id WHERE i.channel=?",now.toString(),c.name)
        val dateSlices=zoneRows.flatMap {r->val a=r[0]!!.toLong();val b=r[1]!!.toLong();if(b<=a)emptyList() else Intervals.intersect(listOf(Interval(a,b)),listOf(DayRules.range(day,r[2]!!)))}.ifEmpty {if(zoneRows.isEmpty())listOf(range) else emptyList()}
        val expected=rows("SELECT start_utc_ms,coalesce(end_utc_ms,?) FROM capture_intent WHERE channel=?",now.toString(),c.name).map { Interval(it[0]!!.toLong(),it[1]!!.toLong()) }
        val excluded=rows("SELECT e.start_utc_ms,e.end_utc_ms FROM capture_exclusion e JOIN capture_intent i ON i.id=e.intent_id WHERE i.channel=?",c.name).map { Interval(it[0]!!.toLong(),it[1]!!.toLong()) }
        val openPause=setting("pause_${c.name}")?.split('|')?.firstOrNull()?.toLongOrNull()?.let {if(now>=it)listOf(Interval(it,now)) else emptyList()}?:emptyList()
        val openRule=setting("rule_pause_${c.name}")?.toLongOrNull()?.let {if(now>=it)listOf(Interval(it,now)) else emptyList()}?:emptyList()
        val actual=rows("SELECT t.start_utc_ms,t.end_utc_ms,t.valid_ms FROM capture_tick t JOIN capture_session s ON s.id=t.session_id JOIN capture_intent i ON i.id=s.intent_id JOIN day_slice d ON d.id=t.slice_id WHERE i.channel=? AND d.day_id=? AND t.valid_ms>0",c.name,day).map { Interval(it[0]!!.toLong(),minOf(it[1]!!.toLong(),it[0]!!.toLong()+it[2]!!.toLong())) }
        val report=Intervals.report(expected,excluded+openPause+openRule,actual,dateSlices)
        val uncertain=scalar("SELECT count(*) FROM source_event s JOIN artifact a ON a.id=s.artifact_id WHERE a.owner_day_id=? AND s.source='SYSTEM' AND s.metadata_json LIKE ?",day,"%\"clock_changed\":\"true\"%")!="0"
        if(uncertain)report.copy(unknownMs=report.expectedMs.coerceAtLeast(1)) else report
    }
    @Synchronized fun session(c:Channel)=sessions[c]
}
