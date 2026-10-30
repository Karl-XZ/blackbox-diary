package com.blackbox.diary.inference

import android.graphics.Bitmap
import android.graphics.Rect
import ai.onnxruntime.*
import com.blackbox.diary.core.Recognition
import java.nio.FloatBuffer
import kotlin.math.*

/** PP-OCRv4, BGR/CHW normalization, memory-only image input and greedy CTC decoding. */
class OfflineOcr(models:LocalModels):AutoCloseable {
    private val environment=OrtEnvironment.getEnvironment()
    private val options=OrtSession.SessionOptions().apply { setIntraOpNumThreads(2);setInterOpNumThreads(1) }
    private val detector=environment.createSession(models.asset("ch_PP-OCRv4_det_infer.onnx").path,options)
    private val recognizer=environment.createSession(models.asset("ch_PP-OCRv4_rec_infer.onnx").path,options)
    private val chars=listOf("")+recognizer.metadata.customMetadata.getValue("character").lines()+listOf(" ")
    private fun tensor(bitmap:Bitmap,w:Int,h:Int,padWidth:Int=w):OnnxTensor {
        val scaled=Bitmap.createScaledBitmap(bitmap,w,h,true);val pixels=IntArray(w*h);scaled.getPixels(pixels,0,w,0,0,w,h)
        val data=FloatArray(3*h*padWidth)
        for(y in 0 until h)for(x in 0 until w) { val rgb=pixels[y*w+x];for(c in 0..2)data[c*h*padWidth+y*padWidth+x]=((rgb shr (c*8)) and 255)/127.5f-1f }
        if(scaled!==bitmap)scaled.recycle();pixels.fill(0)
        return OnnxTensor.createTensor(environment,FloatBuffer.wrap(data),longArrayOf(1,3,h.toLong(),padWidth.toLong()))
    }
    @Synchronized fun recognize(bitmap:Bitmap):Recognition {
        val ratio=minOf(1f,960f/max(bitmap.width,bitmap.height))
        val w=(bitmap.width*ratio/32).roundToInt().coerceAtLeast(1)*32;val h=(bitmap.height*ratio/32).roundToInt().coerceAtLeast(1)*32
        val boxes=mutableListOf<Rect>()
        tensor(bitmap,w,h).use { input->detector.run(mapOf(detector.inputNames.first() to input)).use { result->
            val out=result[0] as OnnxTensor;val shape=(out.info as TensorInfo).shape;val oh=shape[2].toInt();val ow=shape[3].toInt();val p=out.floatBuffer;val values=FloatArray(p.remaining());p.get(values)
            val visited=BooleanArray(values.size);val queue=IntArray(values.size)
            for(seed in values.indices) {
                if(visited[seed]||values[seed]<.3f)continue
                var head=0;var tail=0;queue[tail++]=seed;visited[seed]=true
                var minX=ow;var minY=oh;var maxX=0;var maxY=0;var score=0f
                while(head<tail) {
                    val index=queue[head++];val x=index%ow;val y=index/ow;minX=min(minX,x);maxX=max(maxX,x);minY=min(minY,y);maxY=max(maxY,y);score+=values[index]
                    for(n in intArrayOf(index-1,index+1,index-ow,index+ow))if(n in values.indices&&!visited[n]&&abs(n%ow-x)<=1&&values[n]>=.3f) { visited[n]=true;queue[tail++]=n }
                }
                if(tail<6||score/tail<.5f||maxY-minY<2)continue
                val margin=((maxY-minY)*.65f).roundToInt().coerceAtLeast(2)
                boxes+=Rect(((minX-margin).coerceAtLeast(0)*bitmap.width/ow),((minY-margin).coerceAtLeast(0)*bitmap.height/oh),((maxX+margin+1).coerceAtMost(ow)*bitmap.width/ow),((maxY+margin+1).coerceAtMost(oh)*bitmap.height/oh))
                if(boxes.size>=160)break
            };values.fill(0f)
        } }
        val lines=mutableListOf<String>();val scores=mutableListOf<Float>()
        boxes.sortedWith(compareBy<Rect> { it.top/12 }.thenBy { it.left }).forEach { b->
            if(b.width()<3||b.height()<3)return@forEach
            val crop=Bitmap.createBitmap(bitmap,b.left,b.top,b.width(),b.height())
            try {
                val width=ceil(48.0*b.width()/b.height()).toInt().coerceIn(8,2048)
                tensor(crop,width,48,maxOf(320,width)).use { input->recognizer.run(mapOf(recognizer.inputNames.first() to input)).use { result->
                    val t=result[0] as OnnxTensor;val shape=(t.info as TensorInfo).shape;val steps=shape[1].toInt();val count=shape[2].toInt();val buffer=t.floatBuffer
                    var previous=0;var sum=0f;var accepted=0;val text=StringBuilder()
                    repeat(steps) { var best=0;var score=-Float.MAX_VALUE;repeat(count) { k->val v=buffer.get();if(v>score){score=v;best=k} }
                        if(best!=0&&best!=previous&&best<chars.size){text.append(chars[best]);sum+=score;accepted++};previous=best
                    }
                    if(accepted>0){lines+=text.toString();scores+=sum/accepted}
                } }
            } finally { crop.recycle() }
        }
        return Recognition(lines.joinToString("\n"),scores.takeIf { it.isNotEmpty() }?.average()?.toFloat(),setOf("UNCALIBRATED"),"ppocrv4-rapidocr1.4.4")
    }
    override fun close(){detector.close();recognizer.close();options.close()}
}
