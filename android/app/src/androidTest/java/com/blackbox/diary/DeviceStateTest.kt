package com.blackbox.diary

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.security.keystore.KeyInfo
import com.blackbox.diary.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

class DeviceStateTest {
    private val g get()=ApplicationProvider.getApplicationContext<DiaryApplication>().graph
    @Test fun inspectHardwareKeyAndInstalledPackage():Unit=runBlocking {
        DeviceActivity.launch().use {
            val ks=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            val key=ks.getKey("blackbox.db.${g.store.descriptor.getLong("generation")}",null) as SecretKey
            val info=SecretKeyFactory.getInstance(key.algorithm,"AndroidKeyStore").getKeySpec(key,KeyInfo::class.java) as KeyInfo
            val hash=MessageDigest.getInstance("SHA-256")
            File(g.app.applicationInfo.sourceDir).inputStream().use {stream->val bytes=ByteArray(1024*1024);while(true){val n=stream.read(bytes);if(n<0)break;hash.update(bytes,0,n)}}
            val report=JSONObject().put("device",android.os.Build.MODEL).put("keySize",info.keySize).put("securityLevel",info.securityLevel).put("hardwareBacked",info.isInsideSecureHardware).put("installedApkSha256",hash.digest().joinToString(""){"%02x".format(it)}).put("biometricStrongAvailability",androidx.biometric.BiometricManager.from(g.app).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG))
            File(g.app.getExternalFilesDir(null),"device-security.json").writeText(report.toString(2))
            assertTrue("This physical device did not provide a hardware-backed key",info.isInsideSecureHardware)
            assertEquals(256,info.keySize)
        }
    }
    @Test fun finishDedicatedTestInstall():Unit=runBlocking {
        check(InstrumentationRegistry.getArguments().getString("confirmTestInstall")=="empty-before-session")
        val foreground=if(InstrumentationRegistry.getArguments().getString("background","false")=="true")null else DeviceActivity.launch()
        try {
            Channel.entries.forEach {g.capture.stop(it)}
            val known=setOf("午后在窗边读书，喝了一杯茶。","上午在公园散步，傍晚阅读一本书。")
            val unexpectedDays=g.store.read {days().filter {day->events(day).any {it.source==Source.USER_NOTE&&it.text !in known}}}.toSet()
            val owned=g.store.read {days().toSet()}-unexpectedDays
            g.deletion.deleteDays(owned)
            g.store.transaction {exec("DELETE FROM private_setting WHERE name LIKE 'lifecycle_test_%'");if(unexpectedDays.isEmpty())exec("DELETE FROM inference_budget")}
            var reset=false
            if(unexpectedDays.isEmpty()&&g.lock.verify("592618")) {
                g.store.transaction {exec("DELETE FROM private_setting WHERE name='pin'")}
                g.store.savePreferences(Preference());reset=true
            }
            g.deletion.progress.value=null;g.lock.lock()
            val duplicate=File(g.app.getExternalFilesDir(null),"spark-device-source.litertlm")
            val removed=if(g.models.sparkFile.exists())!duplicate.exists()||duplicate.delete() else false
            File(g.models.root,"Spark-X2.5-4B_int4.litertlm").delete()
            File(g.app.getExternalFilesDir(null),"cleanup-probe.json").writeText(JSONObject().put("deletedSyntheticDays",owned.size).put("retainedUnexpectedDays",unexpectedDays.size).put("testPinRemoved",reset).put("sourceModelDuplicateRemoved",removed).put("fixedModelRetained",g.models.sparkFile.exists()).put("audioOff",g.capture.audio.value.state==CaptureState.OFF).put("screenOff",g.capture.screen.value.state==CaptureState.OFF).toString(2))
        } finally {foreground?.close()}
    }
}
