package com.blackbox.diary.core

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

object TextRules {
    private val credential=Regex("(?i)((?:验证码|动态码|密码|口令|password|secret|api[_-]?key|access[_-]?token|authorization)\\s*[:：=]?\\s*)[^\\s，。；;\"'<>]{4,}")
    private val phones=Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9](?:[ -]?[0-9]){9}(?![0-9])")
    private val identities=Regex("(?<![0-9])[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])")
    private val card=Regex("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])")
    fun nfc(s: String)=Normalizer.normalize(s,Normalizer.Form.NFC)
    fun sha256(s: String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    fun sanitize(input: String): String {
        var s=nfc(input).replace(Regex("[\\p{Cc}&&[^\\n\\t]]"),"")
        s=s.map {ch->if(ch.isDigit())ch.digitToIntOrNull()?.let {('0'.code+it).toChar()}?:ch else ch}.joinToString("")
        s=credential.replace(s){it.groupValues[1]+"[已遮蔽]"};s=phones.replace(s,"[号码已遮蔽]");s=identities.replace(s,"[身份凭证已遮蔽]")
        s=card.replace(s){if(luhn(it.value.filter(Char::isDigit)))"[卡号已遮蔽]" else it.value};return s
    }
    fun containsSensitive(s: String)=sanitize(s)!=nfc(s)
    private fun luhn(s: String): Boolean {if(s.length !in 13..19)return false;var sum=0;for((i,c)in s.reversed().withIndex()){var n=c-'0';if(i%2==1){n*=2;if(n>9)n-=9};sum+=n};return sum%10==0}
    fun codePointLength(s: String)=s.codePointCount(0,s.length)
    fun slice(s: String,start: Int,end: Int): String {require(start>=0&&end>start&&end<=codePointLength(s));return s.substring(s.offsetByCodePoints(0,start),s.offsetByCodePoints(0,end))}
    fun takeCp(s: String,n: Int): String = if(codePointLength(s)<=n)s else s.substring(0,s.offsetByCodePoints(0,n))
}

@Serializable data class OffsetSegment(val sourceStartUtf16:Int,val sourceEndUtf16:Int,val foldedStartUtf16:Int,val foldedEndUtf16:Int)
object SearchTokens {
    fun fold(s: String)=Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun cjk(cp: Int)=Character.UnicodeScript.of(cp) in setOf(Character.UnicodeScript.HAN,Character.UnicodeScript.HIRAGANA,Character.UnicodeScript.KATAKANA)
    fun tokens(s: String): Set<String> {
        val cps=fold(s).codePoints().toArray();val out=linkedSetOf<String>();var word=StringBuilder()
        fun flush(){if(word.isNotEmpty()){out+=word.toString();word=StringBuilder()}}
        for(i in cps.indices){val cp=cps[i];if(cjk(cp)){flush();out+=String(Character.toChars(cp));if(i+1<cps.size&&cjk(cps[i+1]))out+=String(Character.toChars(cp))+String(Character.toChars(cps[i+1]))}else if(Character.isLetterOrDigit(cp))word.appendCodePoint(cp) else flush()};flush();return out
    }
    fun terms(query: String)=fold(query).trim().split(Regex("\\s+")).filter {it.isNotBlank()}.take(12)
    fun ftsQuery(query: String): String {
        return terms(query).flatMap { term ->
            val cp=term.codePoints().toArray()
            if(cp.size>1&&cp.all(::cjk))cp.toList().zipWithNext {a,b->String(Character.toChars(a))+String(Character.toChars(b))} else tokens(term).toList()
        }.distinct().joinToString(" AND "){"\"${it.replace("\"","\"\"")}\""}
    }
    fun literalMatch(text: String,query: String)=terms(query).all {fold(text).contains(it)}
    fun mapping(text:String):List<OffsetSegment> {
        val folded=fold(text)
        if(Normalizer.isNormalized(text,Normalizer.Form.NFKC)&&folded.length==text.length)return listOf(OffsetSegment(0,text.length,0,folded.length))
        val iterator=java.text.BreakIterator.getCharacterInstance(Locale.ROOT);iterator.setText(text)
        val result=mutableListOf<OffsetSegment>();var start=iterator.first();var end=iterator.next();var offset=0
        while(end!=java.text.BreakIterator.DONE) {
            val length=fold(text.substring(start,end)).length
            val segment=OffsetSegment(start,end,offset,offset+length);val previous=result.lastOrNull()
            if(previous!=null&&previous.sourceEndUtf16-previous.sourceStartUtf16==previous.foldedEndUtf16-previous.foldedStartUtf16&&end-start==length)result[result.lastIndex]=previous.copy(sourceEndUtf16=end,foldedEndUtf16=offset+length) else result+=segment
            offset+=length;start=end;end=iterator.next()
        };return result
    }
    fun highlightRanges(text: String,query: String): List<IntRange> {
        val normalized=fold(text)
        if(Normalizer.isNormalized(text,Normalizer.Form.NFKC)&&normalized.length==text.length) {
            val ranges=mutableListOf<IntRange>();for(term in terms(query)){var at=normalized.indexOf(term);while(at>=0){ranges+=at until at+term.length;at=normalized.indexOf(term,at+1)}};return ranges.distinct()
        }
        val folded=StringBuilder();val starts=mutableListOf<Int>();val ends=mutableListOf<Int>()
        val iterator=java.text.BreakIterator.getCharacterInstance(Locale.ROOT);iterator.setText(text)
        var start=iterator.first();var end=iterator.next()
        while(end!=java.text.BreakIterator.DONE) {val transformed=fold(text.substring(start,end));folded.append(transformed);repeat(transformed.length){starts+=start;ends+=end};start=end;end=iterator.next()}
        val ranges=mutableListOf<IntRange>();for(term in terms(query)){var at=folded.indexOf(term);while(at>=0){val last=at+term.length-1;if(last<starts.size)ranges+=starts[at] until ends[last];at=folded.indexOf(term,at+1)}};return ranges.distinct()
    }
}
