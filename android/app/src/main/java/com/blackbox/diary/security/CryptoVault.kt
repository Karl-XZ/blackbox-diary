package com.blackbox.diary.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

/** Only wrapped key material and generation numbers live outside SQLCipher. */
class CryptoVault(context: Context) {
    val root = File(context.noBackupFilesDir,"vault").apply { mkdirs() }
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(kind:String, generation:Long) = "blackbox.$kind.$generation"
    @Synchronized private fun key(kind:String, generation:Long):SecretKey {
        val a=alias(kind,generation)
        if(!ks.containsAlias(a)) KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(a,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());generateKey()
        }
        return ks.getKey(a,null) as SecretKey
    }
    fun wrap(kind:String,generation:Long,plain:ByteArray):ByteArray {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(kind,generation))
        c.updateAAD(alias(kind,generation).toByteArray());return c.iv+c.doFinal(plain)
    }
    fun unwrap(kind:String,generation:Long,sealed:ByteArray):ByteArray {
        // Missing keys are fatal; never create a replacement when decrypting.
        val k=ks.getKey(alias(kind,generation),null) as? SecretKey ?: error("KEY_UNAVAILABLE")
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,k,GCMParameterSpec(128,sealed.copyOfRange(0,12)))
        c.updateAAD(alias(kind,generation).toByteArray());return c.doFinal(sealed,12,sealed.size-12)
    }
    fun destroy(kind:String,generation:Long) { val a=alias(kind,generation);ks.deleteEntry(a);check(!ks.containsAlias(a)) { "KEY_DESTROY_PENDING" } }
    fun exists(kind:String,generation:Long)=ks.containsAlias(alias(kind,generation))
    fun atomic(name:String,json:JSONObject) {
        val f=AtomicFile(File(root,name));val out=f.startWrite()
        try { out.write(json.toString().toByteArray());f.finishWrite(out) } catch(t:Throwable){ f.failWrite(out);throw t }
    }
    fun read(name:String):JSONObject? = File(root,name).takeIf { it.exists() }?.let { AtomicFile(it).openRead().use { f->JSONObject(f.reader().readText()) } }
    fun newDescriptor(generation:Long):JSONObject {
        val raw=ByteArray(32).also { SecureRandom().nextBytes(it) }
        return try { JSONObject().put("generation",generation).put("key",Base64.encodeToString(wrap("db",generation,raw),Base64.NO_WRAP)) }
        finally { raw.fill(0) }
    }
    fun dbKey(descriptor:JSONObject)=unwrap("db",descriptor.getLong("generation"),Base64.decode(descriptor.getString("key"),Base64.NO_WRAP))
    companion object {
        fun pinHash(pin:CharArray,salt:ByteArray):ByteArray {
            val spec=PBEKeySpec(pin,salt,600_000,256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword();pin.fill('\u0000') }
        }
        fun equal(a:ByteArray,b:ByteArray)=MessageDigest.isEqual(a,b)
    }
}
