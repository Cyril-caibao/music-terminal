package com.multitrack.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.CyrilApp
import com.multitrack.app.player.audioPermission
import com.multitrack.app.player.data.SongEntity
import com.multitrack.app.player.ui.PColors
import com.multitrack.app.player.ui.TagEditorDialog

/**
 * 文件管理器「用 CyrilMusic 编辑标签」的入口。
 * 和其它播放器一样，这里只做一件事：拿到外部传入的音乐文件 → 弹出标签编辑器 → 关掉对话框就退出。
 */
class TagEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val container = (application as CyrilApp).container
        val incoming = intent
            ?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data

        setContent {
            TagEditHost(
                container = container,
                incoming = incoming,
                onDone = { finish() }
            )
        }
    }
}

@Composable
private fun TagEditHost(
    container: AppContainer,
    incoming: android.net.Uri?,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var song by remember { mutableStateOf<SongEntity?>(null) }
    var error by remember {
        mutableStateOf(if (incoming == null) "没有收到要编辑的文件" else null)
    }
    // 授权通过后 +1 触发重新解析
    var attempt by remember { mutableIntStateOf(0) }

    val perm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) attempt++ else error = "需要读取音乐的权限，才能编辑这个文件的标签"
    }

    LaunchedEffect(incoming, attempt) {
        if (incoming == null || song != null) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            perm.launch(audioPermission())
            return@LaunchedEffect
        }
        error = null
        val s = runCatching { container.resolveExternal(incoming) }.getOrNull()
        if (s == null) error = "无法识别这个文件：它不在系统音乐库里，或格式不受支持"
        else song = s
    }

    Box(Modifier.fillMaxSize().background(PColors.Bg), contentAlignment = Alignment.Center) {
        val s = song
        when {
            s != null -> TagEditorDialog(container, listOf(s), onDismiss = onDone)
            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    error!!,
                    color = PColors.TextDim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onDone) { Text("关闭", color = PColors.Accent) }
            }
            else -> Text("正在打开…", color = PColors.TextWeak, fontSize = 13.sp)
        }
    }
}
