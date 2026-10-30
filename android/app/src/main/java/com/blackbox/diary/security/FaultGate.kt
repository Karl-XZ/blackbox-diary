package com.blackbox.diary.security

import android.content.Context
import com.blackbox.diary.BuildConfig
import java.io.File
import org.json.JSONObject

/** Only the dedicated .fault package can terminate itself at a contract checkpoint. */
object FaultGate {
    @Synchronized fun hit(context:Context,point:String) {
        if(!BuildConfig.FAULT_TESTING)return
        val file=File(context.noBackupFilesDir,"fault-arm.json")
        if(!file.exists())return
        val data=JSONObject(file.readText())
        if(data.optString("point")!=point)return
        file.delete()
        val receipt=File(context.noBackupFilesDir,"fault-hit.txt")
        receipt.outputStream().use {it.write(point.toByteArray());it.flush();(it as java.io.FileOutputStream).fd.sync()}
        android.os.Process.killProcess(android.os.Process.myPid())
        error("FAULT_PROCESS_KILL")
    }
}
