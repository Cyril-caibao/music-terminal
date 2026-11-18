package com.multitrack.app.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pro-Q 风格参数均衡器内核。
 *
 * - 频段模型：[EqBand]，支持 Bell / Low Shelf / High Shelf / High Pass / Low Pass / Notch
 * - 滤波器：RBJ Audio EQ Cookbook 双二阶（biquad），多段串联
 * - 实时处理：主总线在 [AudioEngine] 每块混音后过一遍 EQ
 * - 曲线绘制：[magnitudeDb] 逐像素求各段叠加后的幅频响应
 * - 频谱分析：[SpectrumAnalyzer] 对混音做 1024 点 FFT，做 UI 背景分析曲线
 *
 * 所有 DSP 都是纯 Kotlin，不依赖 Android，方便单元测试与离线渲染复用。
 */

enum class EqType(val label: String, val short: String) {
    BELL("Bell 峰值", "BELL"),
    LOW_SHELF("Low Shelf 低架", "LS"),
    HIGH_SHELF("High Shelf 高架", "HS"),
    HIGH_PASS("High Pass 高通", "HP"),
    LOW_PASS("Low Pass 低通", "LP"),
    NOTCH("Notch 陷波", "NT");

    /** 高/低通与搁架类频段不显示 Q 以外的增益效果 */
    val usesGain: Boolean get() = this == BELL || this == LOW_SHELF || this == HIGH_SHELF
}

/** Pro-Q 3 的频段配色（红/橙/黄/绿/青/蓝…） */
val EQ_COLORS = listOf(
    0xFFE8D24B, 0xFF6ECD7B, 0xFF5AA9F0, 0xFFD964D9,
    0xFFA46BE0, 0xFFE8894A, 0xFFE0574F, 0xFF4FD1C5
)

data class EqBand(
    val id: Int,
    val type: EqType = EqType.BELL,
    val freq: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 0.7f,
    val enabled: Boolean = true
) {
    val color: Long get() = EQ_COLORS[(id - 1).coerceAtLeast(0) % EQ_COLORS.size]
}

/** 双二阶系数（已归一化到 a0=1） */
data class Coefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

object BiquadCalc {
    fun compute(band: EqBand, sr: Int): Coefficients {
        val w0 = 2.0 * Math.PI * band.freq.coerceIn(10f, sr / 2f - 100f) / sr
        val cosw = cos(w0)
        val sinw = sin(w0)
        val q = band.q.coerceIn(0.05f, 20f).toDouble()
        val alpha = sinw / (2.0 * q)
        val gain = band.gainDb.coerceIn(-30f, 30f).toDouble()
        val a = 10.0.pow(gain / 40.0)
        val sqrtA = sqrt(a)

        return when (band.type) {
            EqType.BELL -> {
                val b0 = 1.0 + alpha * a
                val b1 = -2.0 * cosw
                val b2 = 1.0 - alpha * a
                val a0 = 1.0 + alpha / a
                val a1 = -2.0 * cosw
                val a2 = 1.0 - alpha / a
                Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            EqType.LOW_SHELF -> {
                val b0 = a * ((a + 1) - (a - 1) * cosw + 2 * sqrtA * alpha)
                val b1 = 2 * a * ((a - 1) - (a + 1) * cosw)
                val b2 = a * ((a + 1) - (a - 1) * cosw - 2 * sqrtA * alpha)
                val a0 = (a + 1) + (a - 1) * cosw + 2 * sqrtA * alpha
                val a1 = -2 * ((a - 1) + (a + 1) * cosw)
                val a2 = (a + 1) + (a - 1) * cosw - 2 * sqrtA * alpha
                Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            EqType.HIGH_SHELF -> {
                val b0 = a * ((a + 1) + (a - 1) * cosw + 2 * sqrtA * alpha)
                val b1 = -2 * a * ((a - 1) + (a + 1) * cosw)
                val b2 = a * ((a + 1) + (a - 1) * cosw - 2 * sqrtA * alpha)
                val a0 = (a + 1) - (a - 1) * cosw + 2 * sqrtA * alpha
                val a1 = 2 * ((a - 1) - (a + 1) * cosw)
                val a2 = (a + 1) - (a - 1) * cosw - 2 * sqrtA * alpha
                Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
            EqType.HIGH_PASS -> {
                val b0 = (1 + cosw) / 2
                val b1 = -(1 + cosw)
                val b2 = (1 + cosw) / 2
                val a0 = 1 + alpha
                Coefficients(b0 / a0, b1 / a0, b2 / a0, -2 * cosw / a0, (1 - alpha) / a0)
            }
            EqType.LOW_PASS -> {
                val b0 = (1 - cosw) / 2
                val b1 = 1 - cosw
                val b2 = (1 - cosw) / 2
                val a0 = 1 + alpha
                Coefficients(b0 / a0, b1 / a0, b2 / a0, -2 * cosw / a0, (1 - alpha) / a0)
            }
            EqType.NOTCH -> {
                val a0 = 1 + alpha
                Coefficients(1.0 / a0, -2 * cosw / a0, 1.0 / a0, -2 * cosw / a0, (1 - alpha) / a0)
            }
        }
    }

    /** 单个双二阶在指定频率处的增益（dB） */
    fun magnitudeDb(c: Coefficients, freq: Double, sr: Int): Double {
        val w = 2.0 * Math.PI * freq / sr
        val zr = cos(-w); val zi = sin(-w)
        val z2r = cos(-2 * w); val z2i = sin(-2 * w)
        val nr = c.b0 + c.b1 * zr + c.b2 * z2r
        val ni = c.b1 * zi + c.b2 * z2i
        val dr = 1.0 + c.a1 * zr + c.a2 * z2r
        val di = c.a1 * zi + c.a2 * z2i
        val num = sqrt(nr * nr + ni * ni)
        val den = sqrt(dr * dr + di * di)
        return 20.0 * log10((num / den).coerceAtLeast(1e-9))
    }
}

/** 单个频段的直通/串联状态（每声道一份） */
private class BiquadState {
    var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

    fun process(c: Coefficients, input: Double): Double {
        val out = c.b0 * input + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1; x1 = input
        y2 = y1; y1 = out
        return out
    }
}

/**
 * 全局 EQ 状态（UI 与音频引擎共享）。
 * bands 用 Compose 的 mutableStateOf 保存，UI 改动后自动重组并实时生效。
 */
object ProQ {
    var bands by mutableStateOf(
        listOf(
            EqBand(1, EqType.LOW_SHELF, 100f, 0f, 0.7f),
            EqBand(2, EqType.BELL, 1000f, 0f, 0.7f),
            EqBand(3, EqType.HIGH_SHELF, 8000f, 0f, 0.7f)
        )
    )
    var bypass by mutableStateOf(false)
    var outputGainDb by mutableStateOf(0f)
    var analyzerOn by mutableStateOf(true)
    var selectedId by mutableStateOf(2)
    var open by mutableStateOf(false)

    private var nextId = 4

    fun newBand(freq: Float, gain: Float = 3f): EqBand {
        val b = EqBand(nextId++, EqType.BELL, freq, gain, 0.7f)
        bands = bands + b
        selectedId = b.id
        return b
    }

    fun removeBand(id: Int) {
        if (bands.size <= 1) return
        bands = bands.filterNot { it.id == id }
        if (selectedId == id) selectedId = bands.first().id
    }

    fun update(id: Int, block: (EqBand) -> EqBand) {
        bands = bands.map { if (it.id == id) block(it) else it }
    }

    fun resetAll() {
        bands = bands.map { it.copy(gainDb = 0f, q = 0.7f) }
    }

    fun reset() {
        bands = listOf(
            EqBand(1, EqType.LOW_SHELF, 100f, 0f, 0.7f),
            EqBand(2, EqType.BELL, 1000f, 0f, 0.7f),
            EqBand(3, EqType.HIGH_SHELF, 8000f, 0f, 0.7f)
        )
        bypass = false
        outputGainDb = 0f
        selectedId = 2
        nextId = 4
    }

    /** 单段自身的响应（画曲线用，不含其他频段） */
    fun bandMagnitudeDb(band: EqBand, freq: Double, sr: Int = C.SAMPLE_RATE): Double {
        if (!band.enabled) return 0.0
        return BiquadCalc.magnitudeDb(BiquadCalc.compute(band, sr), freq, sr)
    }

    fun magnitudeDb(freq: Double, sr: Int = C.SAMPLE_RATE): Double {
        if (bypass) return 0.0
        var sum = 0.0
        for (b in bands) {
            if (!b.enabled) continue
            val c = BiquadCalc.compute(b, sr)
            sum += BiquadCalc.magnitudeDb(c, freq, sr)
        }
        return sum + outputGainDb
    }
}

/** 立体声处理实例（实时总线用，保留滤波器状态，保证块与块之间连续） */
class ProQProcessor {
    private val lStates = Array(8) { BiquadState() }
    private val rStates = Array(8) { BiquadState() }

    fun reset() {
        lStates.forEach { it.reset() }
        rStates.forEach { it.reset() }
    }

    /** 处理一块立体声样本，原地修改 */
    fun process(left: FloatArray, right: FloatArray, offset: Int, count: Int, sr: Int) {
        if (ProQ.bypass) return
        val bands = ProQ.bands
        val coeffs = Array(min(bands.size, 8)) { BiquadCalc.compute(bands[it], sr) }
        val active = Array(min(bands.size, 8)) { bands[it].enabled }
        val outGain = 10.0.pow(ProQ.outputGainDb / 20.0).toFloat()
        for (i in offset until offset + count) {
            var l = left[i].toDouble()
            var r = right[i].toDouble()
            for (k in coeffs.indices) {
                if (!active[k]) continue
                l = lStates[k].process(coeffs[k], l)
                r = rStates[k].process(coeffs[k], r)
            }
            left[i] = (l * outGain).toFloat()
            right[i] = (r * outGain).toFloat()
        }
    }

    /** 离线整段处理（导出/烘焙），每次从零状态开始 */
    fun processBuffer(left: FloatArray, right: FloatArray, sr: Int) {
        reset()
        process(left, right, 0, left.size, sr)
    }
}

/** 频谱分析仪：环形缓冲 + Radix-2 FFT，供 EQ 界面画灰色分析曲线 */
object SpectrumAnalyzer {
    private const val FFT_N = 1024
    private val ring = FloatArray(FFT_N)
    private var pos = 0
    private var window = FloatArray(FFT_N)
    private var filled = 0

    init {
        for (i in 0 until FFT_N) {
            window[i] = 0.5f - 0.5f * cos(2.0 * Math.PI * i / (FFT_N - 1)).toFloat()
        }
    }

    fun push(left: FloatArray, right: FloatArray, count: Int) {
        for (i in 0 until count) {
            ring[pos] = (left[i] + right[i]) * 0.5f
            pos = (pos + 1) % FFT_N
            if (filled < FFT_N) filled++
        }
    }

    fun clear() {
        ring.fill(0f); pos = 0; filled = 0
    }

    /** 返回 [minHz,maxHz] 内按对数均分的 bins 个幅值（0..1，已平滑） */
    fun spectrum(bins: Int, minHz: Float = 20f, maxHz: Float = 20000f, sr: Int = C.SAMPLE_RATE): FloatArray {
        val out = FloatArray(bins)
        if (filled < FFT_N / 4) return out
        val re = FloatArray(FFT_N)
        val im = FloatArray(FFT_N)
        for (i in 0 until FFT_N) {
            val idx = (pos + i) % FFT_N
            re[i] = ring[idx] * window[i]
        }
        fft(re, im)
        val logMin = log10(minHz.toDouble())
        val logMax = log10(maxHz.toDouble())
        val binHz = sr.toDouble() / FFT_N
        for (b in 0 until bins) {
            val f0 = 10.0.pow(logMin + (logMax - logMin) * (b.toDouble() / bins))
            val f1 = 10.0.pow(logMin + (logMax - logMin) * ((b + 1).toDouble() / bins))
            val lo = (f0 / binHz).toInt().coerceIn(1, FFT_N / 2 - 1)
            val hi = (f1 / binHz).toInt().coerceIn(lo + 1, FFT_N / 2)
            var peak = 1e-9f
            for (k in lo until hi) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / FFT_N
                if (mag > peak) peak = mag
            }
            val db = 20.0 * log10(peak.toDouble())
            out[b] = ((db + 70.0) / 70.0).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cwR = 1.0; var cwI = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwR - im[i + k + len / 2] * cwI
                    val vi = re[i + k + len / 2] * cwI + im[i + k + len / 2] * cwR
                    re[i + k] = (ur + vr).toFloat(); im[i + k] = (ui + vi).toFloat()
                    re[i + k + len / 2] = (ur - vr).toFloat(); im[i + k + len / 2] = (ui - vi).toFloat()
                    val nwr = cwR * wr - cwI * wi
                    cwI = cwR * wi + cwI * wr
                    cwR = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
