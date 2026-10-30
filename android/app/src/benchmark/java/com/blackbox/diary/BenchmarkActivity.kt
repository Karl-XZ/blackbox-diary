package com.blackbox.diary

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.blackbox.diary.core.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File

/** Dedicated optimized test package; never compiled into debug or release. */
class BenchmarkActivity:Activity() {
    private val words=listOf("公园","读书","咖啡","工作","散步","朋友","地铁","晚饭","日记","电影")
    private val g get()=(application as DiaryApplication).graph
    private fun state(stage:String,rows:Int=0,error:String?=null,queries:Int=0) {
        File(getExternalFilesDir(null),"benchmark-run.json").writeText(JSONObject().put("run",intent.getStringExtra("run")).put("stage",stage).put("rows",rows).put("queries",queries).put("error",error).toString())
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {text="黑匣子日记\n搜索性能验证中";textSize=22f;setPadding(48,96,48,48)})
        val operation=intent.getStringExtra("operation")
        g.scope.launch {
            try {when(operation){"prepare"->prepare();"measure"->measure();else->error("UNKNOWN_BENCHMARK_OPERATION")};state("SUCCEEDED",100000)}
            catch(t:Throwable){state("FAILED",error=t.javaClass.simpleName+":"+t.message?.take(160))}
            finally {withContext(Dispatchers.Main){finish()}}
        }
    }
    private fun prepare() {
        val existing=g.store.read {scalar("SELECT count(*) FROM source_event").toInt()}
        check(existing in 0..100000&&existing%500==0)
        val random=kotlin.random.Random(20261027);state("PREPARING",existing)
        repeat(200) {batch->g.store.transaction {
            repeat(500) {index->val n=batch*500+index;val day="2023-06-${(n%28+1).toString().padStart(2,'0')}"
                val text=buildString {append("${words[n%words.size]} 片段$n ");if(n%5==0)append("BlackBox notebook token${n%100} ");val length=if(n%10==0)1000 else 200;while(this.length<length)append(words[random.nextInt(words.size)]+"，今天留下一个普通的生活片段。")}.take(if(n%10==0)1000 else 200)
                if(n>=existing){val at=DayRules.range(day,"Asia/Shanghai").start+(n%3600)*1000;check(commitEvent(Event("benchmark-$n",day,Source.USER_NOTE,text,at,at,"Asia/Shanghai",Quality.HIGH),epoch))}
            }
        };if(batch%5==0)state("PREPARING",(batch+1)*500)}
        check(g.store.read {scalar("SELECT count(*) FROM source_event")}=="100000")
    }
    private fun measure() {
        check(g.store.read {scalar("SELECT count(*) FROM source_event")}=="100000");state("MEASURING",100000)
        fun query(i:Int):Double {
            val base=words[i%words.size];val term=when(i%3){0->base.take(1);1->base;else->"$base BlackBox"}
            val start=SystemClock.elapsedRealtimeNanos()
            val hits=g.store.read {search(term,from=if(i%4==0)"2023-06-05" else null,to=if(i%4==0)"2023-06-26" else null,source=if(i%5==0)"USER_NOTE" else null)}
            check(hits.isNotEmpty()) {"KNOWN_QUERY_MISSED_$i"}
            hits.forEach {check(SearchTokens.literalMatch(it.text,term));check(SearchTokens.highlightRanges(it.text,term).isNotEmpty())}
            return (SystemClock.elapsedRealtimeNanos()-start)/1e6
        }
        val cold=query(0);val times=(0 until 300).map {i->query(i).also {if(i%10==9)state("MEASURING",100000,queries=i+1)}};val sorted=times.sorted()
        val known=(0 until 300).map {i->
            val n=if(i%3==2)(i*997%20000)*5 else i*313%100000;val base=words[n%words.size]
            val term=when(i%3){0->"${base.take(1)} 片段$n";1->"$base 片段$n";else->"$base BlackBox 片段$n"}
            val day="2023-06-${(n%28+1).toString().padStart(2,'0')}";val start=SystemClock.elapsedRealtimeNanos()
            val result=g.store.read {search(term,from=day,to=day,source="USER_NOTE")}
            check(result.any {it.sourceId=="benchmark-$n"}) {"UNIQUE_KNOWN_QUERY_MISSED_$i"}
            check(result.all {SearchTokens.highlightRanges(it.text,term).isNotEmpty()})
            if(i%10==9)state("VALIDATING",100000,queries=i+1)
            (SystemClock.elapsedRealtimeNanos()-start)/1e6
        }.sorted()
        val report=JSONObject().put("count",100000).put("seed",20261027).put("queries",300).put("knownUniqueQueriesPassed",300).put("knownQueryP95Ms",known[284]).put("build","R8 optimized benchmark package, debug test signing; in-app harness").put("device",android.os.Build.MODEL).put("coldFirstQueryMs",cold).put("hotP50Ms",sorted[149]).put("hotP95Ms",sorted[284]).put("hotMaxMs",sorted.last()).put("allHotQueryMs",JSONArray(times)).put("targetDeviceGate","Pending physical-device execution; emulator timing is not a vivo result")
        File(getExternalFilesDir(null),"search-benchmark.json").writeText(report.toString(2))
    }
}
