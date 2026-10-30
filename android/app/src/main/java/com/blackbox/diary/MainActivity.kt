package com.blackbox.diary

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blackbox.diary.core.Channel
import com.blackbox.diary.export.DiaryExporter
import com.blackbox.diary.ui.DiaryApp
import kotlinx.coroutines.*

class MainActivity:FragmentActivity() {
    val graph get()=(application as DiaryApplication).graph
    private var lockJob:Job?=null
    private var exportDay:String?=null;private var markdown=true
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result->
        if(result[Manifest.permission.RECORD_AUDIO]==true)graph.capture.startAudio()
        else graph.message.value="需要麦克风权限才能记录声音"
    }
    private val projection=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result->
        if(result.resultCode==Activity.RESULT_OK&&result.data!=null)graph.capture.startScreen(result.resultCode,result.data!!)
        else graph.message.value="屏幕采集尚未授权"
    }
    private val voicePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)graph.voice.start() else graph.message.value="声纹录制需要你允许麦克风权限"}
    private val import=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null)graph.work { graph.models.importSpark(uri) }
    }
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri->
        val day=exportDay
        if(uri!=null&&day!=null)graph.work { DiaryExporter.write(graph,day,uri,markdown);graph.message.value="已导出到你选择的位置" }
        exportDay=null
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { DiaryApp(graph,onAudio={permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS))},onScreen={projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())},onImport={import.launch(arrayOf("*/*"))},onExport={day,md->exportDay=day;markdown=md;export.launch("黑匣子日记-$day.${if(md)"md" else "txt"}")},onBiometric={biometric()},onVoiceRecord={if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)graph.voice.start() else voicePermission.launch(Manifest.permission.RECORD_AUDIO)}) }
    }
    override fun onStart(){super.onStart();lockJob?.cancel();val timeout=graph.store.read {preferences().lockTimeoutSeconds}.toLong()*1000;if(graph.backgroundAt>0&&SystemClock.elapsedRealtime()-graph.backgroundAt>=timeout)graph.lock.lock();graph.backgroundAt=0;graph.visible.value=true}
    override fun onStop(){super.onStop();graph.backgroundAt=SystemClock.elapsedRealtime();graph.visible.value=false;graph.voice.cancel();lockJob=lifecycleScope.launch { delay(graph.store.read { preferences().lockTimeoutSeconds }.toLong()*1000);graph.lock.lock() }}
    private fun biometric() {
        if(!graph.lock.configured||!graph.store.read { preferences().biometric })return
        BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){graph.lock.unlocked.value=true}
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("解锁黑匣子日记").setSubtitle("内容保存在这台设备上").setNegativeButtonText("使用 PIN").build())
    }
}
