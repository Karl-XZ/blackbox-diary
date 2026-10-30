package com.blackbox.diary.core

/** Bound model context while preserving distribution across the recorded day. */
object DiarySelection {
    fun blocks(input:List<Pair<Long,DiaryBlock>>,limit:Int):List<DiaryBlock> {
        require(limit>0)
        val ordered=input.distinctBy {it.second.id}.sortedBy {it.first}
        if(ordered.size<=limit)return ordered.map {it.second}
        fun sample(items:List<Pair<Long,DiaryBlock>>,count:Int):List<Pair<Long,DiaryBlock>> {
            val n=minOf(count,items.size)
            if(n<=0)return emptyList()
            if(n==1)return listOf(items.first())
            return List(n){i->items[(i.toLong()*(items.size-1)/(n-1)).toInt()]}
        }
        val notes=sample(ordered.filter {it.second.templateId=="note.quote.v1"||it.second.origin=="USER_AUTHORED"},(limit/2).coerceAtLeast(1))
        val selected=notes.map {it.second.id}.toSet()
        return (notes+sample(ordered.filter {it.second.id !in selected},limit-notes.size)).sortedBy {it.first}.map {it.second}
    }
    fun distributed(facts:List<Fact>,limit:Int):List<Fact> {
        require(limit>0)
        val ordered=facts.distinctBy {it.id}.sortedBy {it.startMs}
        if(ordered.size<=limit)return ordered
        val notes=ordered.filter {it.type==ClaimType.USER_NOTE_REPORTED}
        val chosen=linkedMapOf<String,Fact>()
        fun sample(input:List<Fact>,count:Int) {
            if(count<=0||input.isEmpty())return
            if(count==1){chosen[input.first().id]=input.first();return}
            repeat(minOf(count,input.size)) {i->val index=(i.toLong()*(input.size-1)/(minOf(count,input.size)-1)).toInt();val f=input[index];chosen[f.id]=f}
        }
        // Explicit hand-written material has priority; the remaining slots span all periods.
        sample(notes,minOf(notes.size,(limit/2).coerceAtLeast(1)))
        sample(ordered.filter {it.id !in chosen},limit-chosen.size)
        return chosen.values.sortedBy {it.startMs}.take(limit)
    }
}
