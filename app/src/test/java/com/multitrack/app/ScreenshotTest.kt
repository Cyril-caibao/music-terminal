package com.multitrack.app

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

import app.cash.paparazzi.DeviceConfig
import com.android.resources.ScreenOrientation
import com.android.ide.common.rendering.api.SessionParams
import app.cash.paparazzi.Paparazzi
import androidx.compose.ui.graphics.Color
import com.multitrack.app.audio.ProQ
import com.multitrack.app.ui.EqEditorScreen
import com.multitrack.app.ui.theme.CyrilMusicTheme
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        theme = "android:Theme.Material.NoActionBar"
    )

    private class FakeRegistry : androidx.activity.result.ActivityResultRegistry() {
        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
            input: I,
            options: androidx.core.app.ActivityOptionsCompat?
        ) = Unit

    }

    private val fakeRegistryOwner = object : androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry = FakeRegistry()
    }

    @Test
    fun editorScreen() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    private fun demoSpec(): FloatArray = FloatArray(120) { i ->
        val f = 20.0 * Math.pow(10.0, i / 119.0 * 3.0)
        var v = 0.0
        v += 0.55 * Math.exp(-Math.pow(Math.log10(f / 60.0) / 0.5, 2.0))
        v += 0.42 * Math.exp(-Math.pow(Math.log10(f / 900.0) / 0.35, 2.0))
        v += 0.30 * Math.exp(-Math.pow(Math.log10(f / 6000.0) / 0.25, 2.0))
        v += 0.08 * Math.random()
        v.toFloat()
    }

    private val landscapeConfig = DeviceConfig.PIXEL_5.copy(
        screenWidth = 2340, screenHeight = 1080,
        orientation = ScreenOrientation.LANDSCAPE,
        softButtons = false
    )

    @Test
    fun editorLandscape() {
        paparazzi.unsafeUpdateConfig(
            landscapeConfig, "android:Theme.Material.NoActionBar", SessionParams.RenderingMode.NORMAL
        )
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    private fun seedDemoClip(state: com.multitrack.app.ui.EditorState) {
        val sr = 44100
        val frames = sr * 18
        val left = FloatArray(frames) { i ->
            val t = i.toFloat() / sr
            (0.45 * Math.sin(2.0 * Math.PI * 220.0 * t) * (0.7 + 0.3 * Math.sin(2.0 * Math.PI * 0.4 * t))).toFloat()
        }
        val right = FloatArray(frames) { i ->
            val t = i.toFloat() / sr
            (0.32 * Math.sin(2.0 * Math.PI * 330.0 * t) * (0.6 + 0.4 * Math.sin(2.0 * Math.PI * 0.7 * t))).toFloat()
        }
        val buf = com.multitrack.app.audio.PcmBuffer(sr, 2, left, right, frames)
        com.multitrack.app.audio.AudioBufferStore.put("demo-buf", buf)
        val track = com.multitrack.app.model.Project.tracks.firstOrNull()
            ?: com.multitrack.app.model.Project.addTrack()
        val clip = com.multitrack.app.model.Project.addClip(
            track!!, "demo-buf", "Demo_220Hz_330Hz.wav", frames, 0, track.color
        )
        com.multitrack.app.model.Project.recomputePeaks(clip)
        com.multitrack.app.model.Project.notifyChanged()
    }

    @Test
    fun editorWithClip() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    androidx.compose.runtime.LaunchedEffect(Unit) { seedDemoClip(state) }
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun menuPanelOnly() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context
                ) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.size(300.dp, 800.dp)
                    ) {
                        val state = androidx.compose.runtime.remember {
                            com.multitrack.app.ui.EditorState(paparazzi.context)
                        }
                        com.multitrack.app.ui.EditorMenuPanel("效果", state, {}, {}, {})
                    }
                }
            }
        }
    }

    @Test
    fun editorMenuOpen() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.selectClip(com.multitrack.app.model.Project.tracks[0].clips[0])
                    state.toggleMenu("效果")
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun graphicEqDialog() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.openFx("graphicEq")
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun delayDialog() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.openFx("delay")
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun lyricsStrip() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.lyrics = com.multitrack.app.lyrics.parseLrc(
                        "[00:10.00]那天下雨的时候\n[00:26.00]我还在等你回头\n[00:31.00]雨停了 人也没来"
                    )
                    state.seekTo(26f)
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun mixSelection() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.focusMixBus()
                    state.playheadTouched = true
                    state.beginSelection(44100)
                    state.updateSelection(44100 * 3)
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun emptyFarScroll() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    state.requestScrollTo(500f)
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun dragGhost() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    val clip = com.multitrack.app.model.Project.tracks.first().clips.first()
                    state.draggingClipId = clip.id
                    state.dragDx = 80f
                    state.dragDy = 0f
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun clipActions() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.playheadTouched = true
                    state.beginSelection(44100)
                    state.updateSelection(44100 * 3)
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun limiterDialog() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.openFx("limiter")
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun eqWindowMin() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.eqWinW = 300f
                    state.eqWinH = 380f
                    state.openEq()
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun eqWindow() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.openEq()
                    com.multitrack.app.ui.EqWindow(state) { state.closeEq() }
                }
            }
        }
    }

    @Test
    fun settingsWindow() {
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    state.toggleSettings(true)
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
    }

    @Test
    fun lightTheme() {
        com.multitrack.app.ui.theme.Palette.lightMode = true
        paparazzi.snapshot {
            CyrilMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides paparazzi.context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeRegistryOwner
                ) {
                    val state = androidx.compose.runtime.remember {
                        com.multitrack.app.ui.EditorState(paparazzi.context)
                    }
                    seedDemoClip(state)
                    com.multitrack.app.ui.EditorScreen(state)
                }
            }
        }
        com.multitrack.app.ui.theme.Palette.lightMode = false
    }

    @Test
    fun eqLandscape() {
        paparazzi.unsafeUpdateConfig(
            landscapeConfig, "android:Theme.Material.NoActionBar", SessionParams.RenderingMode.NORMAL
        )
        ProQ.update(2) { it.copy(gainDb = -6f, q = 1.2f) }
        ProQ.update(1) { it.copy(gainDb = 4f) }
        ProQ.update(3) { it.copy(gainDb = -3f) }
        val eqState = com.multitrack.app.ui.EditorState(paparazzi.context)
        val demo = demoSpec()
        paparazzi.snapshot {
            CyrilMusicTheme {
                EqEditorScreen(state = eqState, onClose = {}, demoSpectrum = demo, demoLevelDb = -9f)
            }
        }
    }

    @Test
    fun eqScreen() {
        ProQ.update(2) { it.copy(gainDb = -6f, q = 1.2f) }
        ProQ.update(1) { it.copy(gainDb = 4f) }
        ProQ.update(3) { it.copy(gainDb = -3f) }
        val eqState = com.multitrack.app.ui.EditorState(paparazzi.context)
        val demo = demoSpec()
        paparazzi.snapshot {
            CyrilMusicTheme {
                EqEditorScreen(state = eqState, onClose = {}, demoSpectrum = demo, demoLevelDb = -9f)
            }
        }
    }
}
