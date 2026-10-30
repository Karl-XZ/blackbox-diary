package com.blackbox.diary.capture

import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.graphics.PixelFormat
import com.blackbox.diary.DiaryApplication
import com.blackbox.diary.core.*
import com.blackbox.diary.inference.OfflineOcr
import kotlinx.coroutines.*
import java.time.ZoneId

class ScreenCaptureService:Service() {
    private val graph get()=(application as DiaryApplication).graph
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val handler=Handler(Looper.getMainLooper())
    private var projection:MediaProjection?=null;private var display:VirtualDisplay?=null;private var reader:ImageReader?=null
    private var width=0;private var height=0;private var last=0L;private var busy=false;private var lastDigest:String?=null
    private val screenOff=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){graph.capture.interrupt(Channel.SCREEN,"屏幕已关闭，请重新授权");projection?.stop();stopSelf()}}
    override fun onCreate(){super.onCreate();registerReceiver(screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(projection!=null)return START_NOT_STICKY
        val data=intent?.getParcelableExtra("projection",Intent::class.java)?:run { stopSelf();return START_NOT_STICKY }
        startForeground(102,captureNotification(this,Channel.SCREEN),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("result",0),data)
            projection!!.registerCallback(object:MediaProjection.Callback() {
                override fun onStop(){graph.capture.interrupt(Channel.SCREEN,"屏幕授权已结束");stopSelf()}
                override fun onCapturedContentResize(w:Int,h:Int) { if(w>0&&h>0&&(w!=width||h!=height))resize(w,h) }
            },handler)
            val m=resources.displayMetrics;resize(m.widthPixels,m.heightPixels)
            graph.capture.begin(Channel.SCREEN)
        } catch(_:Throwable) { graph.capture.interrupt(Channel.SCREEN,"请重新授权屏幕采集");stopSelf() }
        return START_NOT_STICKY
    }
    private fun resize(w:Int,h:Int) {
        width=w;height=h;display?.surface=null;reader?.close()
        val r=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);reader=r
        r.setOnImageAvailableListener({ source->
            val image=runCatching { source.acquireLatestImage() }.getOrNull()?:return@setOnImageAvailableListener
            val elapsed=SystemClock.elapsedRealtimeNanos();val now=System.currentTimeMillis()
            if(busy||elapsed-last<5_000_000_000){image.close();return@setOnImageAvailableListener};last=elapsed
            graph.capture.checkClock(Channel.SCREEN,now,elapsed)
            if(graph.capture.screen.value.state==CaptureState.PAUSED_USER){image.close();return@setOnImageAvailableListener}
            val own=graph.visible.value;val sleeping=DayRules.inSleep(now,ZoneId.systemDefault().id,graph.store.read { preferences() })
            if(own||sleeping) {
                graph.capture.exclusion(Channel.SCREEN,now-5000,now,if(own)"OWN_APP_PRIVACY" else "SCHEDULED_EXCLUSION")
                image.close();return@setOnImageAvailableListener
            }
            busy=true
            val bitmap=try {
                val p=image.planes[0];val padded=Bitmap.createBitmap(p.rowStride/p.pixelStride,image.height,Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(p.buffer);val crop=Bitmap.createBitmap(padded,0,0,image.width,image.height);if(crop!==padded)padded.recycle();crop
            } catch(_:Throwable){image.close();busy=false;return@setOnImageAvailableListener}
            image.close();val expected=graph.store.epoch
            val blank=isBlank(bitmap)
            graph.capture.tick(Channel.SCREEN,now-5000,now,elapsed-5_000_000_000,elapsed,if(blank)"BLOCKED_OR_BLANK" else "VALID_FRAME",if(blank)0 else 5000)
            if(blank){bitmap.recycle();busy=false;return@setOnImageAvailableListener}
            val workId=graph.store.id();val capturedZone=ZoneId.systemDefault().id;val day=DayRules.localDay(now,capturedZone)
            graph.store.transaction {ensureDay(day);exec("INSERT INTO recognition_work VALUES(?,?,'SCREEN','RUNNING',0,'')",workId,day)}
            graph.capture.taskStarted()
            scope.launch {
                try {
                    val text=OfflineOcr(graph.models).use { it.recognize(bitmap) }
                    val sanitized=TextRules.sanitize(text.text);val digest=TextRules.sha256(sanitized)
                    val duplicate=digest==lastDigest;lastDigest=digest
                    graph.store.transaction {if(epoch==expected&&!maintenance)exec("UPDATE recognition_work SET state='SUCCEEDED',has_text=?,detail=? WHERE id=?",if(sanitized.isNotBlank())1 else 0,if(duplicate)"DUPLICATE_TEXT" else if(sanitized.isBlank())"NO_TEXT" else "TEXT",workId)}
                    if(!duplicate&&sanitized.isNotBlank()&&!blank)graph.store.commitEvent(Event(graph.store.id(),day,Source.SCREEN,sanitized,now,now,capturedZone,metadata=mapOf("model" to text.modelFingerprint)),expected)
                } catch(_:Throwable) {graph.store.transaction {if(epoch==expected&&!maintenance)exec("UPDATE recognition_work SET state='FAILED',detail='OCR_FAILURE' WHERE id=?",workId)}}
                finally { bitmap.recycle();busy=false;graph.capture.taskFinished() }
            }
        },handler)
        if(display==null)display=projection!!.createVirtualDisplay("BlackBox private OCR",w,h,resources.displayMetrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,r.surface,null,handler)
        else { display!!.resize(w,h,resources.displayMetrics.densityDpi);display!!.surface=r.surface }
    }
    private fun isBlank(b:Bitmap):Boolean { val pixels=IntArray(64);for(i in pixels.indices)pixels[i]=b.getPixel(i%8*(b.width-1)/7,i/8*(b.height-1)/7) and 0xffffff;return pixels.distinct().size<=2 }
    override fun onDestroy(){runCatching {unregisterReceiver(screenOff)};scope.cancel();reader?.close();display?.release();projection?.stop();projection=null;super.onDestroy()}
}
