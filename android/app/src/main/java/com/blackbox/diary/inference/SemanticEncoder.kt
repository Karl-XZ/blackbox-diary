package com.blackbox.diary.inference

import ai.onnxruntime.*
import com.blackbox.diary.core.*
import java.nio.LongBuffer

class SemanticEncoder(models:LocalModels):AutoCloseable {
    private val env=OrtEnvironment.getEnvironment()
    private val tokenizer=UnigramTokenizer(models.asset("e5-tokenizer.json").readText())
    private val options=OrtSession.SessionOptions().apply {setIntraOpNumThreads(2);setInterOpNumThreads(1)}
    private val session=env.createSession(models.asset("e5-small-int8.onnx").path,options)
    fun inputLength(text:String,query:Boolean=false)=tokenizer.encode((if(query)"query: " else "passage: ")+text,8192).size
    @Synchronized fun encode(text:String,query:Boolean):FloatArray {
        val ids=tokenizer.encode((if(query)"query: " else "passage: ")+TextRules.takeCp(text,1200),256)
        val shape=longArrayOf(1,ids.size.toLong())
        val inputs=session.inputNames.associateWith {name->OnnxTensor.createTensor(env,LongBuffer.wrap(when(name){"input_ids"->ids;"attention_mask"->LongArray(ids.size){1};"token_type_ids"->LongArray(ids.size);else->error("EMBEDDING_INPUT")}),shape)}
        try {session.run(inputs).use {result->
            val tensor=result[0] as OnnxTensor;val dims=(tensor.info as TensorInfo).shape;require(dims.contentEquals(longArrayOf(1,ids.size.toLong(),384)))
            val values=tensor.floatBuffer;val pooled=FloatArray(384);repeat(ids.size){repeat(384){d->pooled[d]+=values.get()/ids.size}}
            return Vectors.normalized(pooled)
        }} finally {inputs.values.forEach {it.close()}}
    }
    override fun close(){session.close();options.close()}
}
