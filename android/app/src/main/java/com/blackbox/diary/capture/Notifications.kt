package com.blackbox.diary.capture

import android.app.*
import android.content.*
import com.blackbox.diary.DiaryApplication
import com.blackbox.diary.MainActivity
import com.blackbox.diary.core.Channel

fun captureNotification(context:Context,channel:Channel):Notification {
    fun action(name:String)=PendingIntent.getBroadcast(context,channel.ordinal*10+name.hashCode(),Intent(context,NotificationReceiver::class.java).setAction(name).putExtra("channel",channel.name),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Builder(context,"capture").setSmallIcon(com.blackbox.diary.R.drawable.ic_launcher).setContentTitle("黑匣子日记 · ${if(channel==Channel.MICROPHONE)"声音记录" else "屏幕记录"}")
        .setContentText("仅在本机处理 · 点击管理记录").setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE))
        .addAction(Notification.Action.Builder(null,"暂停",action("pause")).build()).addAction(Notification.Action.Builder(null,"停止",action("stop")).build()).build()
}
class NotificationReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val graph=(context.applicationContext as DiaryApplication).graph
        val channel=runCatching { Channel.valueOf(intent.getStringExtra("channel")?:"") }.getOrNull()?:return
        if(intent.action=="pause")graph.capture.pause(channel,null) else graph.capture.stop(channel)
    }
}
