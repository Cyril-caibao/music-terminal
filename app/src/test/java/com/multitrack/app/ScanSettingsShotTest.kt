package com.multitrack.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.multitrack.app.player.ui.PColors
import com.multitrack.app.player.ui.SettingsRow
import com.multitrack.app.player.ui.SettingsSectionLabel
import com.multitrack.app.player.ui.SettingsToggleRow
import com.multitrack.app.ui.theme.CyrilMusicTheme
import org.junit.Rule
import org.junit.Test

/**
 * 扫描设置页的截图回归：分组标签、行（标题+副标题+箭头）、开关行在窄屏上不重叠、不截断。
 */
class ScanSettingsShotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        theme = "android:Theme.Material.NoActionBar"
    )

    @Test
    fun scanSettingsRows() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PColors.Bg)
                        .verticalScroll(rememberScrollState())
                ) {
                    SettingsSectionLabel("歌曲库")
                    SettingsRow("需扫描的文件夹", "2 个 · 只扫这些目录（含子目录）") {}
                    SettingsRow("已排除的文件夹", "1 个 · 子文件夹一并排除") {}
                    SettingsRow("排除短时长的文件", "少于 0 分 30 秒") {}
                    SettingsRow("高级", "重置扫描设置") {}
                    SettingsSectionLabel("扫描")
                    SettingsRow("立即扫描", "按当前设置重建索引 · 当前 1234 首") {}
                    SettingsToggleRow(
                        "使用 folder.jpg、cover.jpg 等文件",
                        "若未找到歌曲内嵌的专辑封面，则使用同一文件夹的 folder.jpg、AlbumArt.jpg 等作为封面",
                        checked = true
                    ) {}
                    SettingsSectionLabel("信息")
                    SettingsRow("支持的文件类型", "mp3 / flac / wav / ogg / m4a …") {}
                    SettingsRow("存储设备", "") {}
                }
            }
        }
    }
}
