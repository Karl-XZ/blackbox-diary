package com.blackbox.diary

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.Closeable

/** OEM-safe test launch through the authorized shell, without changing background permissions. */
class DeviceActivity private constructor(val activity:MainActivity):Closeable {
    fun onActivity(block:(MainActivity)->Unit) {InstrumentationRegistry.getInstrumentation().runOnMainSync {block(activity)}}
    override fun close(){onActivity {it.finish()}}
    companion object {
        fun launch():DeviceActivity {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val pkg=instrumentation.targetContext.packageName
            instrumentation.uiAutomation.executeShellCommand("am start -W -n $pkg/com.blackbox.diary.MainActivity").use {descriptor->android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {it.readBytes()}}
            var activity:MainActivity?=null
            val end=android.os.SystemClock.elapsedRealtime()+10000
            while(activity==null&&android.os.SystemClock.elapsedRealtime()<end){
                instrumentation.runOnMainSync {activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()}
                if(activity==null)Thread.sleep(100)
            }
            return DeviceActivity(checkNotNull(activity){"Target activity was not resumed"})
        }
    }
}
