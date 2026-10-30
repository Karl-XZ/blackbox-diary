package com.blackbox.diary.inference

import android.icu.text.Transliterator

/** Suggestions only: homophones found in nearby records never overwrite ASR or establish facts. */
object PhoneticHints {
    data class Hint(val raw:String,val candidate:String)
    @Synchronized fun suggest(raw:String,context:List<String>):List<Hint> {
        val latin=Transliterator.getInstance("Han-Latin; Latin-ASCII")
        val cache=mutableMapOf<String,String>()
        fun phonetic(s:String)=cache.getOrPut(s){latin.transliterate(s).lowercase(java.util.Locale.ROOT).filter {it in 'a'..'z'}}
        val phrases=Regex("[\u4e00-\u9fff]{2,}")
        val words=linkedMapOf<Pair<Int,String>,MutableSet<String>>()
        context.take(3).forEach {line->phrases.findAll(line.take(150)).forEach {match->val s=match.value
            for(n in 2..4)for(i in 0..s.length-n){val w=s.substring(i,i+n);words.getOrPut(n to phonetic(w)){linkedSetOf()}.add(w)}
        }}
        val found=mutableListOf<Hint>();val used=mutableListOf<IntRange>()
        phrases.findAll(raw.take(220)).forEach {match->val s=match.value
            for(n in 4 downTo 2)for(i in 0..s.length-n){
                val start=match.range.first+i;val range=start until start+n
                if(used.any {range.first<=it.last&&it.first<=range.last})continue
                val w=s.substring(i,i+n);val candidates=words[n to phonetic(w)]?:continue
                if(w in candidates||candidates.size!=1)continue
                found+=Hint(w,candidates.single());used+=range
                if(found.size>=6)return found
            }
        };return found
    }
}
