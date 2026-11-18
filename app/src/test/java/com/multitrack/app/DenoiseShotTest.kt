package com.multitrack.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.multitrack.app.audio.NoiseProfile
import com.multitrack.app.ui.EditorState
import com.multitrack.app.ui.EffectDialogWindow
import com.multitrack.app.ui.EffectMenuWindow
import com.multitrack.app.ui.LevelMeterBar
import com.multitrack.app.ui.theme.CyrilMusicTheme
import org.junit.Rule
import org.junit.Test

/**
 * AU 化之后的三块界面：两栏效果菜单、降噪窗口（含噪声样本区）、底部电平条。
 * 截图回归盯的是排版：字有没有被截、两栏在 393dp 宽上是否还放得下。
 */
class DenoiseShotTest {

    private fun emptyRegistry(): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) = Unit
        }
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        theme = "android:Theme.Material.NoActionBar"
    )

    @Test
    fun auEffectMenu() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                CompositionLocalProvider(LocalContext provides paparazzi.context) {
                    val state = remember { EditorState(paparazzi.context) }
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(Modifier.padding(top = 20.dp)) { EffectMenuWindow(state) {} }
                    }
                }
            }
        }
    }

    @Test
    fun denoiseDialogWithSample() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                CompositionLocalProvider(LocalContext provides paparazzi.context) {
                    // Paparazzi 里没有 Activity，降噪窗口里的「导入噪声样本」需要一个空的注册表
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides emptyRegistry()) {
                    val state = remember {
                        EditorState(paparazzi.context).apply {
                            openFx("denoise")
                            fxParams["dnStrength"] = 72f
                            fxParams["dnBand"] = 90f
                            fxParams["dnFloor"] = -28f
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

    @Test
    fun levelMeterHotMix() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                CompositionLocalProvider(LocalContext provides paparazzi.context) {
                    val state = remember { EditorState(paparazzi.context) }
                    Column(Modifier.fillMaxWidth().background(Color(0xFF1B1B1B))) {
                        LevelMeterBar(state, overrideDb = -3.2f)
                        LevelMeterBar(state, overrideDb = 0.6f)
                        LevelMeterBar(state, overrideDb = -60f)
                    }
                }
            }
        }
    }
}
