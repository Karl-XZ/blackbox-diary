package com.blackbox.diary.inference

import android.Manifest
import android.media.*
import android.content.pm.PackageManager
import com.blackbox.diary.AppGraph
import com.blackbox.diary.core.*
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.Base64
import kotlin.math.sqrt

class Voiceprint(private val graph:AppGraph) {
    val progress=MutableStateFlow<String?>(null)
    private var recording:Job?=null
    fun enrolled()=graph.store.read {setting("voiceprint_profile")!=null}
    fun cancel(){recording?.cancel()}
    fun start(){if(recording?.isActive==true)return;recording=graph.scope.launch {try {enroll()}catch(t:Exception){if(t !is CancellationException)graph.message.value=t.message?.takeIf {it.startsWith("请")||it.startsWith("样本")}?:"声纹录制未完成，请在安静环境重试"}finally {progress.value=null;recording=null}}}
    fun vector(samples:FloatArray):FloatArray {
        val extractor=SpeakerEmbeddingExtractor(config=SpeakerEmbeddingExtractorConfig(model=graph.models.asset("speaker-campplus-zh-en.onnx").path,numThreads=2,debug=false,provider="cpu"))
        try {val stream=extractor.createStream();try {stream.acceptWaveform(samples,16000);stream.inputFinished();check(extractor.isReady(stream));return Vectors.normalized(extractor.compute(stream))}finally {stream.release()}}finally {extractor.release()}
    }
    fun match(samples:FloatArray):Pair<String,Float?> {
        val profile=graph.store.read {if(preferences().voiceprintEnabled)setting("voiceprint_profile") else null}?:return SpeakerPolicy.UNKNOWN to null
        if(samples.size<48000)return SpeakerPolicy.UNKNOWN to null
        return runCatching {
            val data=JSONObject(profile);require(data.getString("model")=="campplus-zh-en-aa3cfc16")
            val reference=Vectors.floats(Base64.getDecoder().decode(data.getString("embedding")))
            try {val windows=listOf(samples.copyOfRange(0,minOf(48000,samples.size)),samples.copyOfRange(maxOf(0,samples.size-48000),samples.size))
                val scores=windows.map {w->try {val v=vector(w);try {Vectors.cosine(v,reference)}finally {v.fill(0f)}}finally {w.fill(0f)}}
                SpeakerPolicy.classify(scores,samples.size/16000.0) to scores.minOrNull()
            }finally {reference.fill(0f)}
        }.getOrElse {SpeakerPolicy.UNKNOWN to null}
    }
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun enroll()=withContext(Dispatchers.IO){
        check(graph.visible.value&&graph.lock.unlocked.value);check(graph.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
        check(graph.capture.audio.value.state==CaptureState.OFF){"请先停止声音采集，再录制声纹"}
        val epoch=graph.store.epoch;val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        val record=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,32000))
        val samples=FloatArray(18*16000);val buffer=ShortArray(1600);val voiced=mutableListOf<FloatArray>()
        try {
            check(record.state==AudioRecord.STATE_INITIALIZED);record.startRecording();var at=0
            while(at<samples.size){currentCoroutineContext().ensureActive();check(graph.visible.value&&graph.lock.unlocked.value&&graph.store.epoch==epoch){"请保持应用在前台，再次录制"};progress.value="正在录制 · ${18-at/16000} 秒，持续自然朗读";val n=record.read(buffer,0,minOf(buffer.size,samples.size-at),AudioRecord.READ_BLOCKING);check(n>0);check(record.activeRecordingConfiguration?.isClientSilenced!=true){"请先结束占用麦克风的其他录音，再重试"};repeat(n){samples[at++]=buffer[it]/32768f}}
            record.stop();progress.value="正在检查语音样本…"
            val vad=Vad(config=VadModelConfig(sileroVadModelConfig=SileroVadModelConfig(model=graph.models.asset("silero_vad.onnx").path,minSpeechDuration=.3f,minSilenceDuration=.5f,maxSpeechDuration=20f),sampleRate=16000,numThreads=1))
            try {var i=0;while(i<samples.size){val w=FloatArray(512);samples.copyInto(w,0,i,minOf(i+512,samples.size));vad.acceptWaveform(w);i+=512};vad.flush();while(!vad.empty()){voiced+=vad.front().samples;vad.pop()}}finally {vad.release()}
            val speech=voiced.sumOf {it.size};check(speech>=9*16000){"样本中的有效语音不足 9 秒，请在安静环境持续朗读"}
            val all=FloatArray(speech);var offset=0;voiced.forEach {it.copyInto(all,offset);offset+=it.size}
            try {
                val rms=sqrt(all.sumOf {it.toDouble()*it}/all.size);check(rms>=.006&&all.count {kotlin.math.abs(it)>.98f}.toDouble()/all.size<.01){"样本音量过小或削波，请调整距离重试"}
                val vectors=(0..2).map {part->currentCoroutineContext().ensureActive();val piece=all.copyOfRange(part*all.size/3,(part+1)*all.size/3);try {vector(piece)}finally {piece.fill(0f)}}
                try {
                    check(vectors.indices.all {i->vectors.indices.all {j->i==j||Vectors.cosine(vectors[i],vectors[j])>=.6f}}){"样本声纹不一致，请确保只有本人朗读并重新录制"}
                    val mean=Vectors.normalized(FloatArray(vectors[0].size){d->vectors.sumOf {it[d].toDouble()}.toFloat()/3})
                    try {currentCoroutineContext().ensureActive();graph.store.transaction {check(this.epoch==epoch&&!maintenance&&graph.visible.value&&graph.lock.unlocked.value);setting("voiceprint_profile",JSONObject().put("model","campplus-zh-en-aa3cfc16").put("embedding",Base64.getEncoder().encodeToString(Vectors.bytes(mean))).put("createdMs",System.currentTimeMillis()).toString());savePreferences(preferences().copy(voiceprintEnabled=true))}}finally {mean.fill(0f)}
                }finally {vectors.forEach {it.fill(0f)}}
            }finally {all.fill(0f)}
            graph.message.value="声纹已保存在本机，仅用于辅助判断疑似本人"
        }finally {runCatching {record.stop()};record.release();samples.fill(0f);buffer.fill(0);voiced.forEach {it.fill(0f)}}
    }
}
