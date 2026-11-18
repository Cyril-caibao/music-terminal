package com.multitrack.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.runtime.mutableStateOf
import com.multitrack.app.player.data.ArtRepository
import com.multitrack.app.player.data.SongEntity
import com.multitrack.app.player.playback.PlaybackUi
import com.multitrack.app.player.ui.PColors
import com.multitrack.app.player.ui.wall.LatticeWall
import com.multitrack.app.player.ui.wall.WallInstance
import com.multitrack.app.player.ui.wall.WallMetrics
import com.multitrack.app.player.ui.wall.latticeGeometry
import com.multitrack.app.player.ui.wall.layoutLattice
import com.multitrack.app.player.ui.wall.worldBounds
import com.multitrack.app.ui.theme.CyrilMusicTheme
import org.junit.Rule
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * 拼贴墙截图回归：世界是 12×8 单元块的二维无限平铺，看数字看不出「有没有缝、卡是不是大小混排」，
 * 只能真的画一遍。展开态也要截 —— 让位表算错了，那一块的邻居就会歪。
 */
class CoverWallShotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        theme = "android:Theme.Material.NoActionBar"
    )

    private fun demoSongs(n: Int) = (1..n).map { i ->
        SongEntity(
            id = i.toLong(),
            uri = "content://media/external/audio/media/$i",
            path = "/storage/emulated/0/Music/demo$i.mp3",
            folderId = 1L,
            title = "示例曲目 $i",
            artist = "艺人 ${i % 7 + 1}",
            album = "专辑 ${i % 5 + 1}",
            durationMs = 180_000L
        )
    }

    private val art by lazy { ArtRepository(paparazzi.context) }
    private val idle = mutableStateOf(PlaybackUi())

    // 与 LatticeWall 内部同一套换算：Pixel 5 = 393 × 851 dp
    private val vw = 393f
    private val vh = 851f
    private val cell = min(vw / 7.6f, vh / 9.2f).coerceIn(34f, 68f)
    private val metrics = WallMetrics(cellSize = cell, gap = max(3f, cell * 0.09f))

    /** 选离屏幕中心最近的那张来展开 —— 真机上相机会把它送到正中，截图要截这个过程的结果 */
    private fun centerInstance(count: Int): WallInstance {
        val geometry = latticeGeometry(count, metrics)
        val list = layoutLattice(geometry, count, worldBounds(0f, 0f, vw, vh), 0f, metrics)
        return list.minByOrNull {
            val dx = it.centerX - vw / 2f
            val dy = it.centerY - vh / 2f
            dx * dx + dy * dy
        } ?: list.first()
    }

    private fun shot(count: Int, currentIndex: Int, activeForeign: Boolean = false) {
        val songs = demoSongs(count)
        val active = if (activeForeign) {
            centerInstance(count).let { it.copy(queueIndex = currentIndex) }
        } else null
        paparazzi.snapshot {
            CyrilMusicTheme {
                Box(Modifier.fillMaxSize().background(PColors.Bg)) {
                    LatticeWall(
                        songs = songs,
                        art = art,
                        lyricsOf = { emptyList() },
                        playback = idle,
                        currentSongId = (currentIndex + 1).toLong(),
                        active = active,
                        onTapCard = {},
                        onTapBlank = {},
                        onFollowActive = {},
                        onOpenPlayer = {},
                        onTogglePlay = {},
                        onNext = {},
                        onPrev = {},
                        reduceMotion = true
                    )
                }
            }
        }
    }

    @Test
    fun wall_36() = shot(36, 4)

    @Test
    fun wall_12() = shot(12, 0)

    @Test
    fun wall_120() = shot(120, 70)

    /** 展开：一块里 12 张重新咬合，6×6 那张顶到中间 */
    @Test
    fun wall_expanded() = shot(36, 4, activeForeign = true)

    /**
     * 展开着切歌：卡还是那张卡（位置不动），但标题/序号要换成「正在播放」的那首。
     * 卡本身是第 5 首，currentSongId 指第 13 首 —— 截图里应当出现「示例曲目 13」。
     */
    @Test
    fun wall_expanded_follows_current_song() {
        val songs = demoSongs(36)
        val active = centerInstance(36).copy(queueIndex = 4)
        paparazzi.snapshot {
            CyrilMusicTheme {
                Box(Modifier.fillMaxSize().background(PColors.Bg)) {
                    LatticeWall(
                        songs = songs,
                        art = art,
                        lyricsOf = { emptyList() },
                        playback = idle,
                        currentSongId = 13L,
                        active = active,
                        onTapCard = {},
                        onTapBlank = {},
                        onFollowActive = {},
                        onOpenPlayer = {},
                        onTogglePlay = {},
                        onNext = {},
                        onPrev = {},
                        reduceMotion = true
                    )
                }
            }
        }
    }

    /**
     * 歌词面板单独截一张：大字排版、当前行放大 + 逐字点亮、上下行渐隐、译文小一号。
     * 走的是 WallLyricsBody（纯渲染），绕开「异步取歌词」——截图里才看得到真内容。
     */
    @Test
    fun wall_lyrics_body() {
        val lines = listOf(
            "夜色沉进窗台", "风把路灯吹得摇晃",
            "这一段正在唱，后半句还没点亮", "唱到这里就该换行了",
            "远处的鼓点慢慢靠近", "收尾"
        )
        val stanzas = lines.mapIndexed { i, text ->
            com.multitrack.app.player.data.LyricsRepository.Stanza(
                atMs = i * 6_000L,
                lines = listOf(text) + if (i == 2) listOf("and the line under it") else emptyList()
            )
        }
        paparazzi.snapshot {
            CyrilMusicTheme {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF2A1030))) {
                    Box(Modifier.padding(18.dp).size(357.dp, 520.dp)) {
                        com.multitrack.app.player.ui.wall.WallLyricsBody(
                            stanzas = stanzas,
                            positionMs = 15_500L,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
