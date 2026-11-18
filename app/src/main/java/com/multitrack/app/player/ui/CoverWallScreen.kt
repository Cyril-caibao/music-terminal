package com.multitrack.app.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.data.LyricsRepository
import com.multitrack.app.player.data.QueueRepository
import com.multitrack.app.player.data.SongEntity
import com.multitrack.app.player.ui.wall.LatticeWall
import com.multitrack.app.player.ui.wall.WallInstance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 点封面的去向：原版 Folia 在墙内展开显示歌词 / 直接进播放页 */
private enum class WallTapMode(val pref: String) {
    Lyrics("lyrics"),
    Player("player");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.pref == value } ?: Lyrics
    }
}

private const val PREF_TAP_MODE = "wall_tap_mode"

/**
 * 封面拼贴 —— 复刻 oirge/Mineradio 二创里 Folia 的无限拼贴墙。
 *
 * 上一版把「一块 12×8 = 屏宽」竖着排，只能上下滚；这一版照搬上游的二维世界：
 * 相机在无边界的世界里平移，上下左右都能一直翻，视野也收窄了（一屏约 7.6 格，卡片明显变大）。
 * 点一张封面：它吃掉块内 6×6 的齿位，邻居按让位表重新咬合、相机把它送到正中，
 * 卡内换成歌词 —— 想要「点一下直接进播放页」的话，右上角可以切换。
 */
@Composable
fun CoverWallScreen(container: AppContainer, onPlayed: () -> Unit) {
    val scope = rememberCoroutineScope()

    var queueId by remember { mutableStateOf<Long?>(null) }
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<WallInstance?>(null) }
    var tapMode by remember {
        mutableStateOf(WallTapMode.of(container.prefs.getString(PREF_TAP_MODE, null)))
    }

    // 只跟「换歌」这一个离散事件，不跟 50ms 的进度：否则整面墙每 50ms 重组一次
    val currentSongId by produceState(0L) {
        container.playback.state.map { it.songId }.distinctUntilChanged().collect { value = it }
    }

    // 墙的数据源：优先当前队列（Folia 也是拿队列铺墙），没有队列就退回全部歌曲
    LaunchedEffect(Unit) {
        val activeQueue = container.queueRepo.activeQueueIdOnce()
        val fromQueue = activeQueue?.let { container.queueRepo.songs(it) }.orEmpty()
        if (fromQueue.isNotEmpty()) {
            queueId = activeQueue
            songs = fromQueue
        } else {
            queueId = null
            songs = container.songDao().allOnce()
        }
        loaded = true
    }

    /**
     * 点哪首播哪首。
     *
     * 不能把墙上的下标直接交给播放器：墙打开之后队列可能在别处被改过（加歌、删歌、重排），
     * 墙上这份 index 和队列里的位置就对不上了，`activate(startIndex=...)` 会落到另一首上 ——
     * 这正是「点的和实际播放的不是一个歌曲」。所以每次点击都按 songId 现查队列里的真实位置，
     * 队列里确实没有这首歌了，才用墙上这份列表重建队列。
     */
    fun playSong(song: SongEntity) {
        scope.launch {
            val qid = queueId
            if (qid != null) {
                val list = container.queueRepo.songs(qid)
                val at = list.indexOfFirst { it.id == song.id }
                if (at >= 0) {
                    container.playback.activate(
                        qid, autoPlay = true, startIndex = at, startSongId = song.id
                    )
                    return@launch
                }
            }
            val fresh = container.queueRepo.createFromSongs(
                "全部歌曲", QueueRepository.T_MANUAL, 0L, songs.map { it.id }
            ) ?: return@launch
            queueId = fresh
            val at = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            container.playback.activate(
                fresh, autoPlay = true, startIndex = at, startSongId = song.id
            )
        }
    }

    fun togglePlay() {
        // 展开卡的内容是跟着「当前播放」走的，所以按钮就是纯粹的播放/暂停；
        // 只有在什么都没播的时候，才用展开卡那首起播。
        if (currentSongId == 0L) {
            active?.let { songs.getOrNull(it.queueIndex) }?.let { playSong(it) }
        } else {
            container.playback.toggle()
        }
    }

    // 展开着的时候，返回键先收起卡片，而不是退出应用
    BackHandler(enabled = active != null) { active = null }

    Box(modifier = Modifier.fillMaxSize().background(PColors.Bg)) {
        if (loaded && songs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有歌曲，先在资料库里扫描本机音乐", color = PColors.TextWeak, fontSize = 13.sp)
            }
        } else {
            LatticeWall(
                songs = songs,
                art = container.art,
                lyricsOf = remember(container) {
                    val load: suspend (SongEntity) -> List<LyricsRepository.Stanza> = { song ->
                        val lines = container.lyrics.lines(song)
                        if (lines.isEmpty()) emptyList() else LyricsRepository.stanzas(lines)
                    }
                    load
                },
                playback = container.playback.state.collectAsState(),
                currentSongId = currentSongId,
                active = active,
                onTapCard = { instance ->
                    // 墙上这份列表可能比实例池旧（池子只增不删）：越界就当没点中
                    val song = songs.getOrNull(instance.queueIndex)
                    if (song == null) {
                        active = null
                    } else if (tapMode == WallTapMode.Player) {
                        playSong(song)
                        onPlayed()
                    } else if (active?.instanceId == instance.instanceId) {
                        active = null
                    } else {
                        active = instance
                        if (song.id != currentSongId) playSong(song)
                    }
                },
                onTapBlank = { active = null },
                onFollowActive = { active = it },
                onOpenPlayer = onPlayed,
                onTogglePlay = { togglePlay() },
                onNext = { container.playback.next() },
                onPrev = { container.playback.prev() }
            )
        }

        WallHeader(
            queueMode = queueId != null,
            count = songs.size,
            mode = tapMode,
            onToggleMode = {
                tapMode = if (tapMode == WallTapMode.Lyrics) WallTapMode.Player else WallTapMode.Lyrics
                container.prefs.edit().putString(PREF_TAP_MODE, tapMode.pref).apply()
            },
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
private fun WallHeader(
    queueMode: Boolean,
    count: Int,
    mode: WallTapMode,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.72f),
                    0.72f to Color.Black.copy(alpha = 0.30f),
                    1f to Color.Transparent
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.GridView, contentDescription = null,
                tint = PColors.Accent, modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text("封面拼贴", color = PColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (queueMode) "当前队列 " else "全部歌曲 ") + "$count 首 · 上下左右都能翻，点封面展开",
                    color = PColors.TextWeak, fontSize = 10.sp
                )
            }
            Row(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
                    .clickable(onClick = onToggleMode)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "点封面",
                    color = PColors.TextWeak, fontSize = 10.sp
                )
                Text(
                    if (mode == WallTapMode.Lyrics) "看歌词" else "开播放页",
                    color = PColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
