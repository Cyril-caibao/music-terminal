package com.multitrack.app.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.multitrack.app.player.AppContainer
import com.multitrack.app.ui.EditorScreen
import com.multitrack.app.ui.EditorState

/**
 * 应用根：默认进播放器（CyrilMusic 的初始界面），
 * 多轨编辑器是二级页面 —— 只有真正切过去时才构造 EditorState，冷启动不背它的开销。
 */
@Composable
fun CyrilRoot(container: AppContainer) {
    var editing by remember { mutableStateOf(false) }

    if (editing) BackHandler { editing = false }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!editing) {
            PlayerApp(container)
        } else {
            EditorHost()
        }

        // 播放器 ⇄ 多轨编辑器：右下角悬浮按钮。
        // 播放页底部有半透明导航栏（约 80dp + 系统手势条），按钮要贴在它上方；
        // 编辑器页没有导航栏，就贴近右下角即可。
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.62f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = if (editing) 18.dp else 96.dp, end = 12.dp)
                .size(48.dp)
        ) {
            IconButton(onClick = { editing = !editing }, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (editing) Icons.Rounded.MusicNote else Icons.Rounded.GraphicEq,
                    contentDescription = if (editing) "回到播放器" else "进入多轨编辑器",
                    tint = Color(0xFF00C853)
                )
            }
        }
    }
}

@Composable
private fun EditorHost() {
    val context = LocalContext.current
    // 进入编辑器才创建：工程状态、音频引擎都不参与播放器冷启动
    val state = remember { EditorState(context.applicationContext) }
    EditorScreen(state)
}
