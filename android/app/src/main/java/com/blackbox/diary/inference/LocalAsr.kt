package com.blackbox.diary.inference

import com.k2fsa.sherpa.onnx.*
import com.blackbox.diary.core.Recognition
import com.blackbox.diary.core.RecognitionLanguages
import com.blackbox.diary.core.SpeechSegment

class LocalAsr(private val models:LocalModels,private val languages:Set<String> = setOf("zh"),private val speaker:((FloatArray)->Pair<String,Float?>)?=null):AutoCloseable {
    private val recognizerDelegate=lazy { OfflineRecognizer(config=OfflineRecognizerConfig(
        featConfig=FeatureConfig(sampleRate=16000,featureDim=80),
        modelConfig=OfflineModelConfig(senseVoice=OfflineSenseVoiceModelConfig(model=models.asset("sensevoice.int8.onnx").path,language="auto",useInverseTextNormalization=true),tokens=models.asset("sensevoice.tokens.txt").path,numThreads=2,debug=false,provider="cpu"))) }
    private val recognizer by recognizerDelegate
    @Synchronized fun transcribe(samples:FloatArray):Recognition {
        val vad=Vad(config=VadModelConfig(sileroVadModelConfig=SileroVadModelConfig(model=models.asset("silero_vad.onnx").path,minSilenceDuration=.5f,minSpeechDuration=.25f,maxSpeechDuration=25f),sampleRate=16000,numThreads=1))
        val texts=mutableListOf<String>()
        val segments=mutableListOf<SpeechSegment>();var excluded=0
        try {
            var i=0
            while(i<samples.size) { val window=FloatArray(512);samples.copyInto(window,0,i,minOf(i+512,samples.size));vad.acceptWaveform(window);i+=512 }
            vad.flush()
            while(!vad.empty()) {
                val segment=vad.front();val stream=recognizer.createStream()
                try { stream.acceptWaveform(segment.samples,16000);recognizer.decode(stream);val result=recognizer.getResult(stream)
                    if(RecognitionLanguages.allows(languages,result.lang)){
                        texts+=result.text
                        if(result.text.isNotBlank()){val who=speaker?.invoke(segment.samples)?:Pair("UNKNOWN",null);segments+=SpeechSegment(result.text,segment.start,segment.start+segment.samples.size,result.lang.removePrefix("<|").removeSuffix("|>"),who.first,who.second)}
                    }else excluded++
                } finally { stream.release();segment.samples.fill(0f) }
                vad.pop()
            }
        } finally { vad.release() }
        val nonEmpty=texts.filter {it.isNotBlank()}
        return Recognition(nonEmpty.joinToString("\n"),flags=when {excluded>0&&texts.isEmpty()->setOf("LANGUAGE_EXCLUDED");texts.isEmpty()->setOf("NO_SPEECH");nonEmpty.isEmpty()->setOf("ASR_DONE_NO_TEXT");else->setOf("UNCALIBRATED")},modelFingerprint="sensevoice-int8-20240717",segments=segments)
    }
    override fun close(){if(recognizerDelegate.isInitialized())recognizer.release()}
}
