package com.blackbox.diary.security

import android.util.Base64
import com.blackbox.diary.data.DiaryStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.security.SecureRandom

class AppLock(private val store:DiaryStore) {
    val unlocked=MutableStateFlow(false)
    val configured get()=store.read { setting("pin")!=null }
    private fun b64(a:ByteArray)=Base64.encodeToString(a,Base64.NO_WRAP)
    private fun decode(s:String)=Base64.decode(s,Base64.NO_WRAP)
    fun configure(pin:String) {
        require(pin.matches(Regex("[0-9]{6,12}"))) { "PIN_LENGTH" }
        val salt=ByteArray(24).also { SecureRandom().nextBytes(it) }
        val hash=CryptoVault.pinHash(pin.toCharArray(),salt)
        store.transaction { setting("pin",JSONObject().put("salt",b64(salt)).put("hash",b64(hash)).put("failures",0).put("until",0).toString()) }
        hash.fill(0);unlocked.value=true
    }
    fun verify(pin:String):Boolean {
        val saved=store.read { setting("pin") }?.let {JSONObject(it)}?:return false
        if(System.currentTimeMillis()<saved.optLong("until"))return false
        val hash=CryptoVault.pinHash(pin.toCharArray(),decode(saved.getString("salt")))
        val ok=CryptoVault.equal(hash,decode(saved.getString("hash")));hash.fill(0)
        val failures=if(ok)0 else saved.optInt("failures")+1
        val delay=if(failures<5)0L else (30_000L shl (failures-5).coerceAtMost(7)).coerceAtMost(3_600_000)
        saved.put("failures",failures).put("until",System.currentTimeMillis()+delay)
        store.transaction { setting("pin",saved.toString()) };unlocked.value=ok;return ok
    }
    fun remainingSeconds()=store.read { setting("pin")?.let { ((JSONObject(it).optLong("until")-System.currentTimeMillis()).coerceAtLeast(0)/1000) }?:0 }
    fun lock(){unlocked.value=false}
}
