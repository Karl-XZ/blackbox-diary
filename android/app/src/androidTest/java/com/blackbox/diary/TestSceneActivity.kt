package com.blackbox.diary

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color

/** Public synthetic screen, in the test APK's separate package; never a user's launcher. */
class TestSceneActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            setBackgroundColor(Color.WHITE);setTextColor(Color.BLACK);textSize=26f
            setPadding(40,100,40,40)
            text="黑匣子日记 真机测试\n\n今天去公园散步。\n晚上阅读一本书。\n\nBlackBox diary 2026\n\n以下是合成隐私测试号码：\n13812345678\n\n这是专用测试画面。"
        })
    }
}
