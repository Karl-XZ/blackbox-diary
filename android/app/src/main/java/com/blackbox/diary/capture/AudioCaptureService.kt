package com.blackbox.diary.capture

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import com.blackbox.diary.DiaryApplication
import com.blackbox.diary.core.*
import com.blackbox.diary.security.ChunkStore
import kotlinx.coroutines.*
import java.time.ZoneId

class AudioCaptureService:Service() {
    private val graph get()=(application as DiaryApplication).graph
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var task:Job?=null;private var recorder:AudioRecord?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(task!=null)return START_NOT_STICKY
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { graph.capture.interrupt(Channel.MICROPHONE,"麦克风权限不可用");stopSelf();return START_NOT_STICKY }
        startForeground(101,captureNotification(this,Channel.MICROPHONE),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        task=scope.launch { record() };return START_NOT_STICKY
    }
    private suspend fun record() {
        graph.capture.taskStarted()
        var writer:ChunkStore.Writer?=null
        try {
            val size=maxOf(AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT),64000)
            val r=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).build()
            recorder=r;check(r.state==AudioRecord.STATE_INITIALIZED);r.startRecording();graph.capture.begin(Channel.MICROPHONE)
            val chunks=ChunkStore(graph);val bytes=ByteArray(32000);var pauseStart:Long?=null
            while(currentCoroutineContext().isActive) {
                val now=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtimeNanos()
                if(graph.capture.checkClock(Channel.MICROPHONE,now,elapsed)){writer?.seal();writer=null}
                val exclusion=DayRules.inSleep(now,ZoneId.systemDefault().id,graph.store.read { preferences() })
                if(graph.capture.audio.value.state==CaptureState.PAUSED_USER||exclusion) {
                    if(r.recordingState==AudioRecord.RECORDSTATE_RECORDING)r.stop()
                    writer?.seal();writer=null
                    if(exclusion&&graph.capture.audio.value.state!=CaptureState.PAUSED_USER) {
                        if(pauseStart==null){pauseStart=now;graph.capture.rulePause(Channel.MICROPHONE,now)}
                        graph.capture.audio.value=CaptureStatus(Channel.MICROPHONE,CaptureState.PAUSED_RULE,"睡眠时段")
                    }
                    delay(250);continue
                }
                pauseStart?.let {graph.capture.endRulePause(Channel.MICROPHONE,now);pauseStart=null}
                if(r.recordingState!=AudioRecord.RECORDSTATE_RECORDING)r.startRecording()
                val readSession=graph.capture.session(Channel.MICROPHONE)
                var read=0
                while(read<bytes.size&&currentCoroutineContext().isActive) { val n=r.read(bytes,read,bytes.size-read,AudioRecord.READ_BLOCKING);check(n>0) { "AUDIO_READ" };read+=n }
                if(graph.capture.audio.value.state !in listOf(CaptureState.RECORDING,CaptureState.PAUSED_RULE)||readSession!=graph.capture.session(Channel.MICROPHONE)||!currentCoroutineContext().isActive) {bytes.fill(0);continue}
                val end=System.currentTimeMillis();val elapsedEnd=SystemClock.elapsedRealtimeNanos()
                val sampleMs=read/32L;val sampleStart=end-sampleMs;val sampleElapsed=elapsedEnd-sampleMs*1_000_000
                val silenced=r.activeRecordingConfiguration?.isClientSilenced==true
                when {
                    exclusion->{ if(pauseStart==null)pauseStart=now;writer?.seal();writer=null;graph.capture.audio.value=CaptureStatus(Channel.MICROPHONE,CaptureState.PAUSED_RULE,"睡眠时段") }
                    else->{
                        pauseStart?.let { graph.capture.exclusion(Channel.MICROPHONE,it,end,"SCHEDULED_EXCLUSION");pauseStart=null }
                        graph.capture.audio.value=CaptureStatus(Channel.MICROPHONE,CaptureState.RECORDING)
                        if(!silenced) {
                            var offset=0
                            while(offset<read) {
                                val at=sampleStart+offset/32;val zone=ZoneId.systemDefault().id;val day=DayRules.localDay(at,zone)
                                if(writer!=null&&writer!!.day!=day){writer!!.seal();writer=null}
                                if(writer==null)writer=chunks.create(readSession,at,sampleElapsed+offset/32*1_000_000L)
                                val available=((DayRules.range(day,zone).end-at)*32).coerceAtMost((read-offset).toLong()).toInt().coerceAtLeast(2)
                                val part=bytes.copyOfRange(offset,minOf(read,offset+available))
                                try {writer!!.append(part,at,sampleElapsed+offset/32*1_000_000L)}finally {part.fill(0)}
                                offset+=available
                                if(writer!!.count>=60||offset<read){writer!!.seal();writer=null;ScheduleBridge.enqueue(graph.app)}
                            }
                        } else {writer?.seal();writer=null}
                        graph.capture.tick(Channel.MICROPHONE,sampleStart,end,sampleElapsed,elapsedEnd,if(silenced)"SYSTEM_SILENCED" else "VALID_PCM",if(silenced)0 else sampleMs,if(silenced)"系统将麦克风静音" else null)
                    }
                };bytes.fill(0)
            }
        } catch(t:Throwable) {
            if(t !is CancellationException)graph.capture.interrupt(Channel.MICROPHONE,when(t.message){"CACHE_FULL"->"缓存已满，等待处理";"LOW_SPACE"->"可用空间不足";else->"录音中断，请重新开启"})
        } finally {
            graph.capture.endRulePause(Channel.MICROPHONE,System.currentTimeMillis())
            runCatching { writer?.seal() };runCatching { recorder?.stop() };recorder?.release();recorder=null
            ScheduleBridge.enqueue(graph.app)
            graph.capture.taskFinished()
        }
    }
    override fun onDestroy(){task?.cancel();runCatching { recorder?.stop() };scope.cancel();super.onDestroy()}
}
private object ScheduleBridge { fun enqueue(context:android.content.Context)=com.blackbox.diary.processing.Schedule.process(context) }
