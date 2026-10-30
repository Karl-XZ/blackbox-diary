package com.blackbox.diary

import android.media.AudioManager
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** Vivo ignores programmatic volume changes here; use the real media-volume keys. */
object LiveTestAudio {
    fun volume(audio:AudioManager,target:Int) {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(20) {
            val current=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if(current==target||(target>0&&kotlin.math.abs(current-target)<10))return
            device.pressKeyCode(if(current<target)KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN)
            Thread.sleep(120)
        }
        check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==target){"Media-volume keys did not reach requested level"}
    }
}
