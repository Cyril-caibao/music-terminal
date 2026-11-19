package com.multitrack.app.player.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.playback.AudioFx
import com.multitrack.app.player.playback.PlaybackUi
import com.multitrack.app.player.data.LyricsRepository
import com.multitrack.app.player.data.SongEntity
import java.io.File

/**
 * 正在播放页（对标截图 2）：大封面 + 虚化背景 + 三行曲目信息 + 进度条 + 传输行。
 *
 * **横屏专属布局**：横屏时屏幕高度只有 200~300dp，竖排（封面 300dp + 信息 + 控件）
 * 根本放不下，底部传输键会被浮在内容之上的底部导航栏整个盖住 ——
 * 所以横屏切成左右双栏：左栏封面 + 曲目信息（封面尺寸按剩余高度自适应），
 * 右栏工具行 + 进度条 + 传输键；整块内容垫高 88dp 避开导航栏浮层。
 */
@Composable
fun NowPlayingScreen(container: AppContainer, onOpenQueue: () -> Unit, onEditTags: (List<SongEntity>) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playback by container.playback.state.collectAsState()
    val song by produceState<SongEntity?>(initialValue = null, playback.songId) {
        value = if (playback.songId == 0L) null else runCatching {
            container.db.songDao().byId(playback.songId)
        }.getOrNull()
    }
    val cover: File? = rememberArt(container.art, song)

    var lyricsMode by remember { mutableStateOf(false) }
    // 「加入歌单」浮层：左下角原来的收藏/点赞（点了没有任何作用）换成了这个
    var addToOpen by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(false) }
    var extremeAsk by remember { mutableStateOf(false) }
    // 倍速 / 定时关闭：都是「滑块 + 按钮」，不再给预设档位
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    val lyrics by produceState<List<LyricsRepository.Line>>(emptyList(), song?.id) {
        value = song?.let { container.lyrics.lines(it) } ?: emptyList()
    }

    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize().background(PColors.Bg)) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.72f,
                modifier = Modifier.fillMaxSize().blur(60.dp)
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)))
        }

        if (landscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    // 底部导航是浮在内容上的（默认高 80dp + 手势条）：垫高避开它
                    .padding(horizontal = 24.dp).padding(bottom = 88.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左栏：封面（高度不够时自动缩小）+ 曲目信息；双击封面看歌词
                BoxWithConstraints(Modifier.weight(1.1f)) {
                    val infoBlock = 110.dp   // 标题/艺人/专辑/错误提示的预留高度
                    val coverSize = (maxHeight - infoBlock).coerceIn(110.dp, 220.dp)
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (lyricsMode) {
                            LyricsPanel(
                                lines = lyrics,
                                positionMs = playback.positionMs,
                                hint = song?.let { s ->
                                    val f = java.io.File(s.path)
                                    "暂无歌词\n把 ${f.nameWithoutExtension}.lrc 放进\n${f.parent ?: "歌曲所在目录"}"
                                } ?: "暂无歌词",
                                modifier = Modifier.fillMaxWidth().height((coverSize + 20.dp).coerceAtMost(280.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier.pointerInput(song?.id) {
                                    detectTapGestures(onDoubleTap = { lyricsMode = true })
                                }
                            ) {
                                CoverArt(file = cover, size = coverSize, corner = 6.dp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        SongInfoBlock(song = song, error = playback.error)
                    }
                }

                // 右栏：工具行 + 进度条 + 传输键，垂直居中
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    UtilityRow(
                        container = container,
                        playback = playback,
                        song = song,
                        lyricsMode = lyricsMode,
                        onToggleLyrics = { lyricsMode = !lyricsMode },
                        onAddToPlaylist = { if (song != null) addToOpen = true },
                        onEditSong = { song?.let { onEditTags(listOf(it)) } },
                        onOpenQueue = onOpenQueue,
                        onOpenFx = { fxOpen = true },
                        onAskExtreme = { extremeAsk = true },
                        onOpenSpeed = { speedOpen = true },
                        onOpenSleep = { sleepOpen = true }
                    )
                    Spacer(Modifier.height(8.dp))
                    ProgressRow(container = container, playback = playback)
                    Spacer(Modifier.height(4.dp))
                    TransportRow(container = container, playback = playback)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp),
                // 整块内容在竖直方向居中偏下：上面空出大片，下面贴着导航栏
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                if (lyricsMode) {
                    LyricsPanel(
                        lines = lyrics,
                        positionMs = playback.positionMs,
                        hint = song?.let { s ->
                            val f = java.io.File(s.path)
                            "暂无歌词\n把 ${f.nameWithoutExtension}.lrc 放进\n${f.parent ?: "歌曲所在目录"}"
                        } ?: "暂无歌词",
                        modifier = Modifier.fillMaxWidth().height(300.dp)
                    )
                } else {
                    // 双击封面直接看歌词（原版 Musicolet 也是这个操作）
                    Box(
                        modifier = Modifier.pointerInput(song?.id) {
                            detectTapGestures(onDoubleTap = { lyricsMode = true })
                        }
                    ) {
                        CoverArt(file = cover, size = 300.dp, corner = 6.dp)
                    }
                }
                Spacer(Modifier.height(26.dp))

                SongInfoBlock(song = song, error = playback.error)

                Spacer(Modifier.height(14.dp))
                UtilityRow(
                    container = container,
                    playback = playback,
                    song = song,
                    lyricsMode = lyricsMode,
                    onToggleLyrics = { lyricsMode = !lyricsMode },
                    onAddToPlaylist = { if (song != null) addToOpen = true },
                    onEditSong = { song?.let { onEditTags(listOf(it)) } },
                    onOpenQueue = onOpenQueue,
                    onOpenFx = { fxOpen = true },
                    onAskExtreme = { extremeAsk = true },
                    onOpenSpeed = { speedOpen = true },
                    onOpenSleep = { sleepOpen = true }
                )

                Spacer(Modifier.height(6.dp))
                ProgressRow(container = container, playback = playback)

                Spacer(Modifier.height(6.dp))
                TransportRow(container = container, playback = playback)
            }
        }

        if (fxOpen) EffectsDialog(onDismiss = { fxOpen = false })

        if (speedOpen) {
            SpeedDialog(
                speed = playback.speed,
                onSet = { container.playback.setSpeed(it) },
                onDismiss = { speedOpen = false }
            )
        }

        if (sleepOpen) {
            SleepTimerDialog(
                remainingMinutes = container.playback.remainingSleepMinutes(),
                onStart = { container.playback.setSleepMinutes(it) },
                onCancel = { container.playback.setSleepMinutes(0) },
                onDismiss = { sleepOpen = false }
            )
        }

        if (addToOpen) {
            AddToPlaylistDialog(
                container = container,
                songs = listOfNotNull(song),
                onDismiss = { addToOpen = false }
            )
        }

        // 极限外放：必须先确认（音量会拉到最大、声压明显变大）
        if (extremeAsk) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { extremeAsk = false },
                title = { Text("开启极限外放？", color = PColors.Text) },
                text = {
                    Text(
                        "把系统媒体音量拉到最大，再叠加 +9dB 数字增益、补一点低频、拉开 3D 宽度。\n" +
                            "出口有 5ms 前视限幅（天花板 −1dBFS）+ 软削波：会明显更响，但不会炸成破音。\n" +
                            "戴耳机时请务必先调小音量。",
                        color = PColors.TextDim, fontSize = 12.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { extremeAsk = false; AudioFx.setExtreme(true) }) {
                        Text("开启", color = PColors.Accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { extremeAsk = false }) { Text("取消", color = PColors.TextDim) }
                }
            )
        }
    }
}

/** 标题 / 艺人 / 专辑 三行 + 播放错误提示。 */
@Composable
private fun SongInfoBlock(song: SongEntity?, error: String?) {
    Text(
        text = song?.title ?: "未在播放",
        color = PColors.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 2
    )
    Spacer(Modifier.height(6.dp))
    Text(song?.artist ?: "", color = PColors.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center)
    Text(song?.album ?: "", color = PColors.TextWeak, fontSize = 12.sp, textAlign = TextAlign.Center)
    // 播放器报错时把错误码摆出来：不然「放不了」在界面上完全不可见
    error?.let { msg ->
        Text(
            msg,
            color = Color(0xFFFF6B6B),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp)
        )
    }
}

/**
 * 工具行：左边 歌词 / 加入歌单 / 标签信息 / 队列，右边 循环 / 随机 / 更多菜单。
 * 竖屏横屏共用同一套；菜单锚在本行右侧的 ⋮ 上。
 */
@Composable
private fun UtilityRow(
    container: AppContainer,
    playback: PlaybackUi,
    song: SongEntity?,
    lyricsMode: Boolean,
    onToggleLyrics: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEditSong: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenFx: () -> Unit,
    onAskExtreme: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleep: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row {
            IconButton(onClick = onToggleLyrics) {
                Icon(
                    Icons.Rounded.TextSnippet, null,
                    tint = if (lyricsMode) PColors.Accent else PColors.Text
                )
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = "加入歌单", tint = PColors.Text)
            }
            IconButton(onClick = onEditSong) { Icon(Icons.Rounded.Info, null, tint = PColors.Text) }
            IconButton(onClick = onOpenQueue) { Icon(Icons.Rounded.QueueMusic, contentDescription = "播放队列", tint = PColors.Text) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { container.playback.cycleRepeat() }) {
                Icon(
                    if (playback.repeatMode == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = null,
                    tint = if (playback.repeatMode != 0) PColors.Accent else PColors.TextDim
                )
            }
            IconButton(onClick = { container.playback.toggleShuffle() }) {
                Icon(
                    Icons.Rounded.Shuffle, null,
                    tint = if (playback.shuffle) PColors.Accent else PColors.TextDim
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, null, tint = PColors.TextDim)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("均衡器 / 音效", fontSize = 13.sp, color = PColors.Text) },
                        onClick = { menuOpen = false; onOpenFx() }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (AudioFx.extremeMode) "关闭极限外放" else "极限外放（音量拉满 / +9dB / 限幅）",
                                fontSize = 13.sp,
                                color = if (AudioFx.extremeMode) PColors.Accent else PColors.Text
                            )
                        },
                        onClick = {
                            menuOpen = false
                            if (AudioFx.extremeMode) AudioFx.setExtreme(false) else onAskExtreme()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            val on = container.playback.isAutoGainOn()
                            Text(
                                if (on) "音量自动补偿：已开启" else "音量自动补偿：已关闭",
                                fontSize = 13.sp,
                                color = if (on) PColors.TextDim else PColors.Accent
                            )
                        },
                        onClick = {
                            menuOpen = false
                            container.playback.setAutoGain(!container.playback.isAutoGainOn())
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (com.multitrack.app.player.BatteryOpt.isIgnoring(context))
                                    "后台播放：已忽略省电限制"
                                else "后台播放：允许忽略省电限制",
                                fontSize = 13.sp,
                                color = if (com.multitrack.app.player.BatteryOpt.isIgnoring(context))
                                    PColors.TextDim else PColors.Accent
                            )
                        },
                        onClick = {
                            menuOpen = false
                            com.multitrack.app.player.BatteryOpt.request(context)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            // 三种状态分开说：没授权 / 被系统关掉 / 权限都有但通知没挂上
                            val granted = com.multitrack.app.player.NotificationCheck.runtimeGranted(context)
                            val on = com.multitrack.app.player.NotificationCheck.enabled(context)
                            val shown = on && com.multitrack.app.player.NotificationCheck.mediaShown(context)
                            Text(
                                when {
                                    !granted -> "通知栏播放器：未授权通知（点此开启）"
                                    !on -> "通知栏播放器：被系统关闭（点此开启）"
                                    shown -> "通知栏播放器：已显示 · 点此重发"
                                    else -> "通知栏播放器：未显示 · 点此重发"
                                },
                                fontSize = 13.sp,
                                color = if (shown) PColors.TextDim else PColors.Accent
                            )
                        },
                        onClick = {
                            menuOpen = false
                            if (com.multitrack.app.player.NotificationCheck.enabled(context)) {
                                container.playback.repostNotification()
                            } else {
                                com.multitrack.app.player.NotificationCheck.openSettings(context)
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑标签 / 封面", fontSize = 13.sp, color = PColors.Text) },
                        onClick = {
                            menuOpen = false
                            onEditSong()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "播放速度  " + String.format(java.util.Locale.US, "%.2f×", playback.speed),
                                fontSize = 13.sp,
                                color = if (kotlin.math.abs(playback.speed - 1f) > 0.01f) PColors.Accent else PColors.Text
                            )
                        },
                        onClick = { menuOpen = false; onOpenSpeed() }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                when {
                                    playback.abStartMs < 0 -> "A-B：在此设 A 点"
                                    playback.abEndMs < 0 -> "A-B：在此设 B 点（A=${formatTime(playback.abStartMs)}）"
                                    else -> "A-B：清除（${formatTime(playback.abStartMs)}–${formatTime(playback.abEndMs)}）"
                                }
                            , fontSize = 13.sp, color = PColors.Text
                            )
                        },
                        onClick = {
                            if (playback.abEndMs < 0) container.playback.markB()
                            else if (playback.abStartMs < 0) container.playback.markA()
                            else container.playback.clearAb()
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text("A-B：重设 A 点（当前位置）", fontSize = 13.sp, color = PColors.TextDim)
                        },
                        onClick = { container.playback.markA(); menuOpen = false }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (playback.sleepAtMs > 0L)
                                    "定时关闭  剩 ${container.playback.remainingSleepMinutes()} 分"
                                else "定时关闭",
                                fontSize = 13.sp,
                                color = if (playback.sleepAtMs > 0L) PColors.Accent else PColors.Text
                            )
                        },
                        onClick = { menuOpen = false; onOpenSleep() }
                    )
                }
            }
        }
    }
}

/** 进度条行：当前时间 + 滑块 + 总时长。拖动中按本地值显示，松手才真正 seek。 */
@Composable
private fun ProgressRow(container: AppContainer, playback: PlaybackUi) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }
    val duration = playback.durationMs.coerceAtLeast(1)
    val progress = if (scrubbing) scrubValue else playback.positionMs.toFloat() / duration
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(formatTime(if (scrubbing) (scrubValue * duration).toLong() else playback.positionMs),
            color = PColors.TextDim, fontSize = 12.sp, modifier = Modifier.width(52.dp))
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { scrubbing = true; scrubValue = it },
            onValueChangeFinished = {
                container.playback.seekTo((scrubValue * duration).toLong())
                scrubbing = false
            },
            modifier = Modifier.weight(1f)
        )
        Text(formatTime(playback.durationMs), color = PColors.TextDim, fontSize = 12.sp,
            textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
    }
}

/** 传输行：音量 / 上一首 / 快退 / 播放暂停 / 快进 / 下一首。 */
@Composable
private fun TransportRow(container: AppContainer, playback: PlaybackUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = {}) { Icon(Icons.Rounded.VolumeUp, null, tint = PColors.TextWeak) }
        IconButton(onClick = { container.playback.prev() }) {
            Icon(Icons.Rounded.SkipPrevious, null, tint = PColors.Text, modifier = Modifier.size(30.dp))
        }
        IconButton(onClick = { container.playback.seekRelative(-10_000) }) {
            Icon(Icons.Rounded.FastRewind, null, tint = PColors.TextWeak)
        }
        IconButton(onClick = {
            if (!playback.ready) container.playback.connect() else container.playback.toggle()
        }) {
            Icon(
                if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null, tint = PColors.Text, modifier = Modifier.size(44.dp)
            )
        }
        IconButton(onClick = { container.playback.seekRelative(10_000) }) {
            Icon(Icons.Rounded.FastForward, null, tint = PColors.TextWeak)
        }
        IconButton(onClick = { container.playback.next() }) {
            Icon(Icons.Rounded.SkipNext, null, tint = PColors.Text, modifier = Modifier.size(30.dp))
        }
        // 多轨编辑器入口统一放在屏幕右上角的同一个按钮上（CyrilRoot 内）
        Spacer(Modifier.size(44.dp))
    }
}

/** 歌词面板：有时间轴就高亮当前行并自动滚动；没有时间轴就按纯文本展示。 */
@Composable
private fun LyricsPanel(
    lines: List<LyricsRepository.Line>,
    positionMs: Long,
    hint: String = "暂无歌词",
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                hint,
                color = PColors.TextWeak, fontSize = 12.sp, textAlign = TextAlign.Center
            )
        }
        return
    }
    val listState = rememberLazyListState()
    // 双语歌词：同一时间戳的原文与译文合成一段，整段一起高亮，原文是主行。
    // 之前按「行」二分，同一时间戳下返回的是最后一行 —— 也就是译文，高亮就落在译文上了。
    val stanzas = remember(lines) { LyricsRepository.stanzas(lines) }
    val timed = (stanzas.firstOrNull()?.atMs ?: -1L) >= 0L
    val current = remember(stanzas, positionMs) {
        if (timed) indexOfStanza(stanzas, positionMs) else -1
    }
    LaunchedEffect(current) {
        if (current >= 0) {
            val target = (current - 2).coerceAtLeast(0)
            val first = listState.firstVisibleItemIndex
            runCatching {
                // 远距离直接跳，近距离才做动画。原来一律 animateScrollToItem：
                // 一句还没滚到位下一句就来了，动画被反复打断，看着就是「歌词追不上」。
                if (kotlin.math.abs(target - first) > 2) listState.scrollToItem(target)
                else listState.animateScrollToItem(target)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(stanzas) { idx, stanza ->
            val active = idx == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 8.dp)
            ) {
                Text(
                    stanza.lines.first(),
                    color = when {
                        !timed -> PColors.TextDim
                        active -> PColors.Accent
                        else -> PColors.TextWeak
                    },
                    fontSize = if (active) 16.sp else 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                // 译文/音译：跟着原文一起亮，但字号更小、力度更轻，不抢主行
                for (sub in stanza.lines.drop(1)) {
                    Text(
                        sub,
                        color = when {
                            !timed -> PColors.TextWeak
                            active -> PColors.Accent.copy(alpha = 0.86f)
                            else -> PColors.TextWeak.copy(alpha = 0.72f)
                        },
                        fontSize = if (active) 13.5.sp else 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private fun indexOfStanza(stanzas: List<LyricsRepository.Stanza>, positionMs: Long): Int {
    var lo = 0
    var hi = stanzas.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (stanzas[mid].atMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}
