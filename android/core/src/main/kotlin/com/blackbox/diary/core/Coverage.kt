package com.blackbox.diary.core

data class Interval(val start: Long, val end: Long) { init { require(end>=start) }; val duration get()=end-start }
data class CoverageReport(val expectedMs: Long,val capturedMs: Long,val excludedMs: Long,val gapMs: Long,val unknownMs: Long=0) {
    val ratio: Double? get()=if(expectedMs==0L||unknownMs>0)null else capturedMs.toDouble()/expectedMs
}
object Intervals {
    fun union(input: List<Interval>): List<Interval> {
        val sorted=input.filter { it.duration>0 }.sortedWith(compareBy({it.start},{it.end}));if(sorted.isEmpty())return emptyList()
        val out=mutableListOf<Interval>();var current=sorted.first()
        for(n in sorted.drop(1))if(n.start<=current.end)current=Interval(current.start,maxOf(current.end,n.end)) else {out+=current;current=n}
        out+=current;return out
    }
    fun intersect(a: List<Interval>,b: List<Interval>): List<Interval> {
        val x=union(a);val y=union(b);val result=mutableListOf<Interval>();var i=0;var j=0
        while(i<x.size&&j<y.size){val start=maxOf(x[i].start,y[j].start);val end=minOf(x[i].end,y[j].end);if(end>start)result+=Interval(start,end);if(x[i].end<y[j].end)i++ else j++};return result
    }
    fun subtract(a: List<Interval>,b: List<Interval>): List<Interval> {
        val cuts=union(b);val result=mutableListOf<Interval>()
        for(s in union(a)){var cursor=s.start;for(c in cuts){if(c.end<=cursor)continue;if(c.start>=s.end)break;if(c.start>cursor)result+=Interval(cursor,minOf(c.start,s.end));cursor=maxOf(cursor,c.end);if(cursor>=s.end)break};if(cursor<s.end)result+=Interval(cursor,s.end)}
        return result
    }
    fun duration(a: List<Interval>)=union(a).sumOf {it.duration}
    fun report(intents: List<Interval>, exclusions: List<Interval>, actual: List<Interval>, daySlices: List<Interval>, unknown: List<Interval> = emptyList()): CoverageReport {
        val i=intersect(intents,daySlices);val x=intersect(i,exclusions);val w=subtract(i,x);val a=intersect(w,actual)
        return CoverageReport(duration(w),duration(a),duration(x),duration(subtract(w,a)),duration(intersect(subtract(w,a),unknown)))
    }
}
object ProcessingMetrics {
    fun ratio(success: Long,total: Long): Double? {require(success>=0&&total>=success);return if(total==0L)null else success.toDouble()/total}
}
object MediaTtl {
    const val MAX_MS=86_400_000L
    fun expired(firstUtc: Long,expiresUtc: Long,createdElapsed: Long,nowUtc: Long,nowElapsed: Long,sameBoot: Boolean): Boolean {
        val ttl=(expiresUtc-firstUtc).coerceIn(0,MAX_MS)
        return nowUtc>=expiresUtc || (sameBoot&&nowElapsed>=createdElapsed&&nowElapsed-createdElapsed>=ttl)
    }
}
