package com.multitrack.app.player.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.audioPermission

private val DarkScheme = darkColorScheme(
    primary = PColors.Accent,
    onPrimary = Color.Black,
    background = PColors.Bg,
    onBackground = PColors.Text,
    surface = PColors.Surface,
    onSurface = PColors.Text,
    surfaceVariant = PColors.SurfaceAlt,
    onSurfaceVariant = PColors.TextDim
)

fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

/** 通知权限：Android 13+ 才有。没有它，通知栏那个播放器根本不会出现 */
fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun PlayerApp(container: AppContainer) {
    MaterialTheme(colorScheme = DarkScheme) {
        val context = LocalContext.current
        val scan by container.scan.collectAsState()
        var tab by remember { mutableStateOf(0) }
        var granted by remember { mutableStateOf(hasAudioPermission(context)) }
        var tagSongs by remember { mutableStateOf<List<com.multitrack.app.player.data.SongEntity>>(emptyList()) }

        // 一次把「读音频 + 通知」都问完；用户只点一次授权
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            granted = hasAudioPermission(context)
            if (granted) container.ensureIndexed(force = true)
        }

        // 省电白名单：只问一次（用户拒绝就不再烦他，菜单里随时能再开）
        var battAsk by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (hasAudioPermission(context)) {
                container.ensureIndexed()
                // 音频权限上次已经给过了，却可能从来没问过通知权限（老版本就是这样装上去的）：
                // 而通知栏播放器全靠它，所以这里补问一次
                if (!hasNotificationPermission(context)) {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            } else {
                val perms = mutableListOf(audioPermission())
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                launcher.launch(perms.toTypedArray())
            }
        }

        LaunchedEffect(granted) {
            if (!granted) return@LaunchedEffect
            if (com.multitrack.app.player.BatteryOpt.isIgnoring(context)) return@LaunchedEffect
            if (container.prefs.getBoolean("batt_asked", false)) return@LaunchedEffect
            container.prefs.edit().putBoolean("batt_asked", true).apply()
            battAsk = true
        }

        // 一开播就再确认一次通知权限：启动时问过一次，用户当时点了「拒绝」的话
        // 之后就再没有机会了 —— 而通知栏那个播放器完全靠这条权限。
        val playbackUi by container.playback.state.collectAsState()
        var notifAsked by remember { mutableStateOf(false) }
        LaunchedEffect(playbackUi.isPlaying) {
            if (!playbackUi.isPlaying || notifAsked) return@LaunchedEffect
            notifAsked = true
            if (!hasNotificationPermission(context)) {
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        if (battAsk) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { battAsk = false },
                title = { Text("允许后台流畅播放？", color = PColors.Text) },
                text = {
                    Text(
                        "系统的省电策略会在锁屏后给后台应用降频，解码线程抢不到 CPU，放歌就会一顿一顿。\n" +
                            "把本应用加进「忽略电池优化」白名单后，后台播放就不会再被掐。\n" +
                            "（随时可以在播放页右上角菜单里改主意）",
                        color = PColors.TextDim, fontSize = 12.sp
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        battAsk = false
                        com.multitrack.app.player.BatteryOpt.request(context)
                    }) { Text("去设置", color = PColors.Accent) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { battAsk = false }) {
                        Text("暂不", color = PColors.TextDim)
                    }
                }
            )
        }

        // 内容铺满整屏、底部导航浮在上面：这样半透明底栏才会真的「透出」封面/列表，
        // 而不是盖在一层纯黑背景上（那看起来和实心黑一模一样）。
        Box(modifier = Modifier.fillMaxSize().background(PColors.Bg)) {
        // 注意：外层不加 statusBarsPadding —— 否则播放页的模糊封面就铺不到状态栏下面，
        // 顶部会露一条黑带。让每个页面自己处理顶部内边距，背景层保持满屏。
        Column(modifier = Modifier.fillMaxSize()) {
            if (!granted) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().background(PColors.SurfaceAlt).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("需要「读取音频」权限才能建立曲库", color = PColors.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val perms = mutableListOf(audioPermission())
                        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        launcher.launch(perms.toTypedArray())
                    }) { Text("授权", color = PColors.Accent) }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }) { Text("去设置", color = PColors.TextDim) }
                }
            } else if (scan.running) {
                Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = PColors.Accent,
                        trackColor = PColors.Border
                    )
                    Text(
                        scan.message,
                        color = PColors.TextWeak, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> NowPlayingScreen(container, onOpenQueue = { tab = 1 }, onEditTags = { tagSongs = it })
                    1 -> QueueScreen(container, onJumpToPlayer = { tab = 0 }, onEditTags = { tagSongs = it })
                    2 -> FoldersScreen(container, onPlayed = { tab = 0 }, onEditTags = { tagSongs = it })
                    3 -> CoverWallScreen(container, onPlayed = { tab = 0 })
                    else -> LibraryScreen(container, onPlayed = { tab = 0 }, onEditTags = { tagSongs = it })
                }
            }

            if (tagSongs.isNotEmpty()) {
                TagEditorDialog(container, tagSongs, onDismiss = { tagSongs = emptyList() })
            }

            }   // Column

            // 半透明毛玻璃风格底栏：浮在内容之上，透出下面的封面/列表，再压一层很淡的描边
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .drawBehind {
                        // 渐变玻璃：上缘只压 12% 黑，往下渐到 60%，内容透上来就有毛玻璃的层次
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.12f),
                                1f to Color.Black.copy(alpha = 0.62f)
                            )
                        )
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.10f))
            ) {
                val items = listOf(
                    Triple("正在播放", Icons.Rounded.PlayCircle, 0),
                    Triple("队列", Icons.Rounded.QueueMusic, 1),
                    Triple("文件夹", Icons.Rounded.Folder, 2),
                    // 原「歌单」位置：换成 Folia 的封面拼贴墙；歌单收进资料库
                    Triple("封面拼贴", Icons.Rounded.GridView, 3),
                    Triple("资料库", Icons.Rounded.LibraryMusic, 4)
                )
                items.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) },
                        label = { Text(label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PColors.Accent,
                            selectedTextColor = PColors.Accent,
                            unselectedIconColor = PColors.TextWeak,
                            unselectedTextColor = PColors.TextWeak,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    }
}
