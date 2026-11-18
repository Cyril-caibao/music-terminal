package com.multitrack.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.multitrack.app.player.ui.LibraryEntryRow
import com.multitrack.app.player.ui.PColors
import com.multitrack.app.ui.theme.CyrilMusicTheme
import org.junit.Rule
import org.junit.Test

/**
 * 资料库顶部三行入口的截图回归。
 *
 * 关心的是窄屏上的排版：三行叠起来别把列表挤没了、副标题别被截成 "12 位 · 按音乐标签…"、
 * 箭头要贴右边对齐。像素比代码更诚实。
 */
class LibraryEntryShotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        theme = "android:Theme.Material.NoActionBar"
    )

    @Test
    fun libraryTopEntries() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                Column(modifier = Modifier.fillMaxWidth().background(PColors.Bg)) {
                    LibraryEntryRow(
                        icon = Icons.Rounded.Person,
                        title = "歌手",
                        subtitle = "128 位 · 按音乐标签自动归并",
                        onClick = {}
                    )
                    LibraryEntryRow(
                        icon = Icons.Rounded.Album,
                        title = "专辑",
                        subtitle = "264 张 · 按音乐标签自动归并",
                        onClick = {}
                    )
                    LibraryEntryRow(
                        icon = Icons.Rounded.PlaylistPlay,
                        title = "歌单",
                        subtitle = "3 个 · 新建 / 导入 M3U / 导出",
                        onClick = {}
                    )
                }
            }
        }
    }
}
