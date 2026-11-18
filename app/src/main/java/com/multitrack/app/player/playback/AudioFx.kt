package com.multitrack.app.player.playback

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.EnvironmentalReverb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 极限外放的推子（dB）：比原来 +12 低 3dB，剩下的响度由限幅器榨出来 */
private const val EXTREME_MAKEUP_DB = 9f

/** 限幅天花板：留 1dB 余量，避免 16bit 出口最后那一下硬削 */
private const val EXTREME_CEILING_DB = -1f

/**
 * 播放音效参数与混响。
 *
 * 均衡器 / 超低重音 / 3D 宽度 / 输出增益 全部由 [FxAudioProcessor] 在 float 域实时计算，
 * 这里只负责保存参数、把参数打成一份不可变快照交给音频线程。
 *
 * 为什么不再用系统 Equalizer / BassBoost / Virtualizer：见 FxAudioProcessor 顶部注释 —
 * 那套东西跑在 16bit 域、带自动增益补偿，加得越多整体越小声，是「音效一开声音就变小」的根因。
 *
 * 混响仍用系统 EnvironmentalReverb：它只叠加湿声、不动干声电平，不存在压整体的问题。
 */
object AudioFx {

    /** 10 段固定中心频率（Hz），不再受设备均衡器段数影响 */
    val BAND_HZ = floatArrayOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    private var rev: EnvironmentalReverb? = null

    var sessionId by mutableStateOf(0)
        private set
    var ready by mutableStateOf(false)
        private set

    /** 10 根滑杆的增益，单位 dB（-12…+12） */
    val bands = mutableStateListOf<Int>().apply { repeat(10) { add(0) } }

    var bassStrength by mutableStateOf(0)               // 0…1000 → 0…+18dB 低架
    var spatialWidth by mutableStateOf(0)               // 0…1000
    var reverbWetLevel by mutableStateOf(0)             // 0…1000
    var reverbRoomSize by mutableStateOf(500)           // 0…1000
    var reverbDiffusionValue by mutableStateOf(500)     // 0…1000
    /** 输出增益（dB）：-12…+18。用户自己的推子，不参与任何自动归一化 */
    var outputGainDb by mutableStateOf(0f)

    /** 自研 DSP 永远可用；混响看设备 */
    val hasEq: Boolean get() = true
    val hasBass: Boolean get() = true
    val hasVirt: Boolean get() = true
    var hasRev by mutableStateOf(false)
        private set

    var extremeMode by mutableStateOf(false)
        private set
    /** 极限外放的限幅器是否挂在处理链出口 */
    private var limiterOn = false
    private var appContext: Context? = null
    private var savedVolume = -1
    private var savedGain = 0f
    private var savedBass = 0
    private var savedWidth = 0

    /**
     * 给音频线程的快照：不可变，读的时候不用抢锁。
     * version 变化 = 需要重算滤波器系数。
     *
     * [limiter] 是「极限外放」的限幅器开关：开了之后出口有 5ms 前视 + 软削波，
     * 不会再出现「推到头全是破音」那种炸法。
     */
    class Snapshot(
        val version: Int,
        val eq: FloatArray,
        val bassDb: Float,
        val width: Float,
        val gainDb: Float,
        val limiter: Boolean = false,
        val ceilingDb: Float = -1f
    )

    @Volatile
    var snapshot = Snapshot(0, FloatArray(10), 0f, 0f, 0f)
        private set

    private var version = 0

    private fun bump() {
        version++
        val eq = FloatArray(10) { bands[it] / 100f }
        snapshot = Snapshot(
            version = version,
            eq = eq,
            bassDb = bassStrength / 1000f * 18f,
            width = spatialWidth / 1000f,
            gainDb = outputGainDb,
            limiter = limiterOn,
            ceilingDb = if (limiterOn) EXTREME_CEILING_DB else -1f
        )
    }

    fun bindContext(c: Context) { appContext = c.applicationContext }

    /** 只有混响需要挂在播放会话上；DSP 在播放器自己的处理链里，与 session 无关 */
    fun attach(id: Int) {
        if (id == sessionId && ready) return
        runCatching { rev?.release() }
        rev = null
        hasRev = false
        sessionId = id
        ready = false
        if (id == 0) return
        runCatching {
            rev = EnvironmentalReverb(0, id).also { r ->
                r.enabled = reverbWetLevel > 0
                applyReverbLocked()
            }
            hasRev = true
        }
        ready = true
        // 混响重挂即可。极限外放调的只是 DSP 参数（增益/低频/宽度），
        // 那套参数跟着 processor 走、与 session 无关，不需要在这里重放一遍 ——
        // 重放会把 +12dB 再叠一次，越切输出越响。
    }

    fun bandCenterLabel(i: Int): String {
        val c = BAND_HZ[i.coerceIn(0, 9)]
        return if (c >= 1000f) "${(c / 1000f)}k" else "${c.toInt()}"
    }

    fun setBand(i: Int, level: Int) {
        bands[i] = level.coerceIn(-1200, 1200)
        bump()
    }

    fun setBass(v: Int) {
        bassStrength = v.coerceIn(0, 1000)
        bump()
    }

    fun setSpatial(v: Int) {
        spatialWidth = v.coerceIn(0, 1000)
        bump()
    }

    fun setOutputGain(db: Float) {
        outputGainDb = db.coerceIn(-12f, 18f)
        bump()
    }

    fun setReverbWet(v: Int) { reverbWetLevel = v.coerceIn(0, 1000); applyReverbLocked() }
    fun setReverbRoom(v: Int) { reverbRoomSize = v.coerceIn(0, 1000); applyReverbLocked() }
    fun setReverbDiffusion(v: Int) { reverbDiffusionValue = v.coerceIn(0, 1000); applyReverbLocked() }

    /** 湿声 = reverbLevel；房间大小 = decayTime；宽度 ≈ diffusion + roomLevel */
    private fun applyReverbLocked() {
        val r = rev ?: return
        runCatching {
            r.reverbLevel = ((reverbWetLevel - 500) * 40).toShort()      // -2000…2000
            r.decayTime = (300 + reverbRoomSize * 5).toInt()             // 300…5300 ms
            r.diffusion = (reverbDiffusionValue * 10).toShort()           // 0…1000（千分比）
            r.roomLevel = ((reverbRoomSize - 500) * 20).toShort()
        }
        runCatching { r.enabled = reverbWetLevel > 0 }
    }

    /** 全部复位（输出增益也回 0：它同样是音效的一部分） */
    fun resetAll() {
        for (i in 0 until 10) bands[i] = 0
        bassStrength = 0
        spatialWidth = 0
        outputGainDb = 0f
        reverbWetLevel = 0
        reverbRoomSize = 500
        reverbDiffusionValue = 500
        if (extremeMode) {
            // 复位不该偷偷把「极限外放」的限幅器关掉，但增益必须回到用户的 0dB
            limiterOn = false
            extremeMode = false
        }
        bump()
        applyReverbLocked()
    }

    /**
     * 极限外放：系统媒体音量拉满 + 数字增益，**出口过一道前视限幅器**。
     *
     * 3.44 那版是「纯 +12dB 不做任何限制」，在手机上就是全频段方波 —— 用户听到的是
     * 「炸」，不是「响」。现在改成：+9dB 推子 + 5ms 前视限幅（天花板 -1dBFS）+ 软削波，
     * 低频提升也从 +8.3dB 收到 +6.5dB、混响湿声减半 —— 响度接近原来，
     * 但瞬态不再糊成一片、人声也不会被低频盖住。
     */
    fun setExtreme(on: Boolean) {
        val was = extremeMode
        extremeMode = on
        val ctx = appContext
        if (on) {
            ctx?.let { c ->
                runCatching {
                    val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        0
                    )
                }
            }
            // 只在「从关到开」这一次记录原始值，重复调用不会把增益越叠越高
            if (!was) {
                savedGain = outputGainDb
                savedBass = bassStrength
                savedWidth = spatialWidth
            }
            limiterOn = true
            setBass(360)
            setSpatial(520)
            setReverbWet(180)
            setReverbRoom(600)
            setReverbDiffusion(650)
            setOutputGain((savedGain + EXTREME_MAKEUP_DB).coerceAtMost(18f))
        } else {
            limiterOn = false
            setOutputGain(savedGain)
            setBass(savedBass)
            setSpatial(savedWidth)
            setReverbWet(0)
            ctx?.let { c ->
                runCatching {
                    if (savedVolume >= 0) {
                        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                    }
                }
            }
            savedVolume = -1
        }
    }

    fun release() {
        runCatching { rev?.release() }
        rev = null
        ready = false
        hasRev = false
    }
}
