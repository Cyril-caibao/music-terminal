package com.multitrack.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.multitrack.app.ui.ChannelReadout
import com.multitrack.app.ui.StereoLevelBar
import com.multitrack.app.ui.theme.CyrilMusicTheme
import com.multitrack.app.ui.theme.Palette
import org.junit.Rule
import org.junit.Test

/**
 * 电平表截图回归。
 *
 * 数字对不代表画得对：两条是不是真的各走各的、刻度和条子有没有错位、
 * 过载灯亮起来在深色底上够不够"亮"—— 这些只能画出来看。
 * 三种状态各截一张：正常、右声道偏低（声像偏了）、左声道过载。
 */
class LevelMeterShotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        theme = "android:Theme.Material.NoActionBar"
    )

    private val tickPaint by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            // 和真机一致：8.5sp 换算成像素，刻度数字才不会被截到或者糊掉
            textSize = 8.5f * paparazzi.context.resources.displayMetrics.scaledDensity
        }
    }

    @Composable
    private fun row(
        label: String,
        l: Float,
        r: Float,
        peakL: Float,
        peakR: Float,
        clipL: Boolean = false,
        clipR: Boolean = false
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(Palette.TimecodeBg)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label, color = Palette.TextWeak, fontSize = 10.5.sp,
                maxLines = 1, modifier = Modifier.width(42.dp)
            )
            StereoLevelBar(
                dbL = l,
                dbR = r,
                peakDbL = peakL,
                peakDbR = peakR,
                tickPaint = tickPaint,
                modifier = Modifier.weight(1f).height(32.dp)
            )
            Column(modifier = Modifier.width(64.dp)) {
                ChannelReadout("L", l, hot = clipL || l > 0.05f, clipped = clipL) {}
                ChannelReadout("R", r, hot = clipR || r > 0.05f, clipped = clipR) {}
            }
            Text("dB", color = Palette.TextDim, fontSize = 9.sp, modifier = Modifier.width(16.dp))
        }
    }

    @Test
    fun stereoLevelMeter() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                Column(modifier = Modifier.background(Palette.Bg)) {
                    row("正常", -12.4f, -13.1f, -11.0f, -12.0f)
                    row("偏声像", -6.2f, -28.7f, -5.5f, -27.0f)
                    // 只有左边爆：右声道那条灯必须是暗的，否则"哪边炸的"就看不出来了
                    row("左过载", 3.2f, -9.6f, 3.4f, -9.0f, clipL = true)
                }
            }
        }
    }
}
