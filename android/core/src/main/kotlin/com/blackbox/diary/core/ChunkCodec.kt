package com.blackbox.diary.core

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** BBD1 records: random UUID header, sequence + length + ciphertext/tag; timing is encrypted. */
object ChunkCodec {
    private val MAGIC=byteArrayOf(66,66,68,49)
    const val MAX_RECORD_BYTES=128*1024
    fun randomKey()=ByteArray(32).also(SecureRandom()::nextBytes)
    data class Header(val id:String,val prefix:ByteArray)
    data class Record(val utcMs:Long,val elapsedNs:Long,val pcm:ByteArray)
    fun writeHeader(output: DataOutputStream,id:String):Header {
        require(id.length<=96);val prefix=ByteArray(4).also(SecureRandom()::nextBytes)
        output.write(MAGIC);output.writeUTF(id);output.write(prefix);return Header(id,prefix)
    }
    fun readHeader(input:DataInputStream):Header {
        val magic=ByteArray(4);input.readFully(magic);require(magic.contentEquals(MAGIC)){"BAD_MAGIC"};val id=input.readUTF();require(id.length<=96)
        val prefix=ByteArray(4);input.readFully(prefix);return Header(id,prefix)
    }
    private fun crypt(mode:Int,key:ByteArray,h:Header,seq:Long,bytes:ByteArray):ByteArray {
        val nonce=ByteBuffer.allocate(12).put(h.prefix).putLong(seq).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run {init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));updateAAD("BBD1|${h.id}|$seq".toByteArray());doFinal(bytes)}
    }
    fun write(output:DataOutputStream,key:ByteArray,h:Header,seq:Long,record:Record){
        require(seq>=0&&record.pcm.size<=MAX_RECORD_BYTES-32)
        val clear=ByteBuffer.allocate(16+record.pcm.size).putLong(record.utcMs).putLong(record.elapsedNs).put(record.pcm).array()
        val cipher=try{crypt(Cipher.ENCRYPT_MODE,key,h,seq,clear)}finally{clear.fill(0)}
        output.writeLong(seq);output.writeInt(cipher.size);output.write(cipher)
    }
    /** A corrupt/incomplete tail is never interpreted or counted. No caller appends after recovery. */
    fun read(input:DataInputStream,key:ByteArray,h:Header,consumer:(Record)->Unit):Int {
        var expected=0L
        while(true){
            val seq=try{input.readLong()}catch(_:EOFException){break};val size=try{input.readInt()}catch(_:EOFException){break}
            if(seq!=expected||size !in 32..MAX_RECORD_BYTES)break
            val bytes=ByteArray(size);try{input.readFully(bytes)}catch(_:EOFException){break}
            val clear=try{crypt(Cipher.DECRYPT_MODE,key,h,seq,bytes)}catch(_:Exception){break}
            try{val b=ByteBuffer.wrap(clear);val utc=b.long;val elapsed=b.long;val pcm=ByteArray(b.remaining());b.get(pcm);try{consumer(Record(utc,elapsed,pcm))}finally{pcm.fill(0)}}finally{clear.fill(0)}
            expected++
        };return expected.toInt()
    }
}
