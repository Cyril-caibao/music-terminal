package com.multitrack.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.multitrack.app.ui.EditorState
import com.multitrack.app.ui.EffectDialogWindow
import com.multitrack.app.ui.theme.CyrilMusicTheme
import org.junit.Rule
import org.junit.Test

/**
 * 图形 EQ 弹窗的截图回归：推子加粗（6px 轨 / 22px 手指）、读数 13sp、频点 12sp 之后，
 * 10 段是不是还排得下、有没有互相压字。
 */
class EqDialogShotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        theme = "android:Theme.Material.NoActionBar"
    )

    @Test
    fun graphicEqDialog() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                CompositionLocalProvider(LocalContext provides paparazzi.context) {
                    val state = remember {
                        EditorState(paparazzi.context).apply {
                            openFx("graphicEq")
                            fxParams["g0"] = 4f
                            fxParams["g1"] = 2.5f
                            fxParams["g2"] = -1f
                            fxParams["g3"] = -3.5f
                            fxParams["g4"] = -2f
                            fxParams["g5"] = 1f
                            fxParams["g6"] = 3f
                            fxParams["g7"] = 5f
                            fxParams["g8"] = 2f
                            fxParams["g9"] = -1.5f
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        EffectDialogWindow(state) {}
                    }
                }
            }
        }
    }
}
