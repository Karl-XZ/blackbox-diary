package com.blackbox.diary.core

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** XLM-R SentencePiece unigram + its bundled precompiled Unicode normalization map. */
class UnigramTokenizer(json:String) {
    private data class Piece(val id:Int,val score:Double)
    private val vocab=HashMap<String,Piece>(300000)
    private val trie:IntArray
    private val replacements:ByteArray
    private var maxLength=1
    private var minScore=0.0
    init {
        val root=Json.parseToJsonElement(json).jsonObject
        val model=root.getValue("model").jsonObject
        require(model.getValue("type").jsonPrimitive.content=="Unigram")
        model.getValue("vocab").jsonArray.forEachIndexed {id,p->val a=p.jsonArray;val text=a[0].jsonPrimitive.content;val score=a[1].jsonPrimitive.double;if(id>=4){vocab[text]=Piece(id,score);maxLength=maxOf(maxLength,text.length);minScore=minOf(minScore,score)}}
        val normalizer=root.getValue("normalizer").jsonObject.getValue("normalizers").jsonArray.first().jsonObject
        val map=Base64.getDecoder().decode(normalizer.getValue("precompiled_charsmap").jsonPrimitive.content)
        val b=ByteBuffer.wrap(map).order(ByteOrder.LITTLE_ENDIAN);val size=b.int;require(size%4==0&&size+4<=map.size)
        trie=IntArray(size/4){b.int};replacements=map.copyOfRange(size+4,map.size)
    }
    private fun offset(unit:Int)=(unit ushr 10) shl ((unit and 512) ushr 6)
    fun normalize(text:String):String {
        val input=text.toByteArray(Charsets.UTF_8);val output=java.io.ByteArrayOutputStream();var i=0
        while(i<input.size){
            var node=offset(trie[0]);var last=-1;var length=0;var j=i
            while(j<input.size){node=node xor (input[j].toInt() and 255);if(node !in trie.indices)break;val unit=trie[node];if((unit and 0x800000ff.toInt())!=(input[j].toInt() and 255))break;node=node xor offset(unit);j++
                if(unit and 256!=0&&node in trie.indices){last=trie[node] and 0x7fffffff;length=j-i}}
            if(last>=0){var k=last;while(k<replacements.size&&replacements[k].toInt()!=0)output.write(replacements[k++].toInt());i+=length}
            else {val lead=input[i].toInt() and 255;val n=when {lead<128->1;lead<224->2;lead<240->3;else->4}.coerceAtMost(input.size-i);output.write(input,i,n);i+=n}
        }
        return output.toString("UTF-8").replace(Regex(" {2,}")," ")
    }
    fun encode(text:String,maxTokens:Int=256):LongArray {
        require(maxTokens>=2)
        val normalized=normalize(text)
        val meta=(if(normalized.startsWith(" "))normalized else " $normalized").replace(' ','▁')
        val ids=mutableListOf(0L)
        // Metaspace splits at word boundaries before unigram segmentation.
        Regex("▁[^▁]*|[^▁]+").findAll(meta).forEach {m->
            val s=m.value;val scores=DoubleArray(s.length+1){Double.NEGATIVE_INFINITY};val from=IntArray(s.length+1);val token=IntArray(s.length+1){3};scores[0]=0.0
            for(i in s.indices){if(!scores[i].isFinite())continue;var single=false
                for(end in i+1..minOf(s.length,i+maxLength)){val p=vocab[s.substring(i,end)]?:continue;if(end==i+Character.charCount(s.codePointAt(i)))single=true;val score=scores[i]+p.score;if(score>scores[end]){scores[end]=score;from[end]=i;token[end]=p.id}}
                if(!single){val end=i+Character.charCount(s.codePointAt(i));val score=scores[i]+minScore-10;if(score>scores[end]){scores[end]=score;from[end]=i;token[end]=3}}
            }
            val backwards=mutableListOf<Int>();var at=s.length;while(at>0){backwards+=token[at];check(from[at]<at);at=from[at]}
            backwards.asReversed().forEach {if(it!=3||ids.last()!=3L)ids+=it.toLong()}
        }
        return (ids.take(maxTokens-1)+2L).toLongArray()
    }
}
