package com.multitrack.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.multitrack.app.player.CyrilApp
import com.multitrack.app.player.audioPermission
import com.multitrack.app.player.ui.CyrilRoot
import kotlinx.coroutines.launch

/**
 * 单个 Activity。默认进播放器（CyrilMusic 的初始界面），多轨编辑器从播放器内切换进入。
 * 同时承接文件管理器「用 CyrilMusic 播放」的 VIEW intent。
 */
class MainActivity : ComponentActivity() {

    /** 等待用户授权后再打开的 uri（文件管理器直接点开时，可能还没给过读音频权限） */
    private var pendingView: Uri? = null

    private val audioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val uri = pendingView
        pendingView = null
        when {
            granted && uri != null -> play(uri)
            uri != null -> toast("需要读取音乐的权限，才能打开这个文件")
        }
    }

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
        handleView(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleView(intent)
    }

    /** 「用 CyrilMusic 播放」：解析文件 → 单独成队 → 立即开播 */
    private fun handleView(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val granted = ContextCompat.checkSelfPermission(this, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) play(uri) else {
            pendingView = uri
            audioPerm.launch(audioPermission())
        }
    }

    private fun play(uri: Uri) {
        val container = (application as CyrilApp).container
        lifecycleScope.launch {
            if (!container.playExternal(uri)) {
                toast("无法打开这个文件：它不在系统音乐库里，或格式不受支持")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onPause() {
        super.onPause()
        runCatching { (application as CyrilApp).container.playback.onAppBackground() }
    }
}
