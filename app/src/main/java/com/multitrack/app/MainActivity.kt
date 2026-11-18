package com.multitrack.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.multitrack.app.player.CyrilApp
import com.multitrack.app.player.ui.CyrilRoot

/**
 * 单个 Activity。默认进播放器（CyrilMusic 的初始界面），多轨编辑器从播放器内切换进入。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容铺到状态栏 / 手势条下面：原来系统栏各占一条不透明色块，
        // 音乐页的模糊封面铺不上去，上下就留下两条灰边。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = java.io.File(filesDir, "crash_last.txt")
                f.writeText(
                    "time=${System.currentTimeMillis()}\nthread=${t.name}\n" +
                        e.stackTraceToString()
                )
            }
            prev?.uncaughtException(t, e)
        }
        setContent {
            CyrilRoot((application as CyrilApp).container)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { (application as CyrilApp).container.playback.onAppBackground() }
    }
}
