package com.multitrack.app.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 效果库 DSP（对标 AU「效果」菜单里各类别挑出的常用项）。
 *
 * 全部是就地（in-place）处理立体声 FloatArray，采样率统一 44.1k。
 * 只做离线渲染：作用在选中片段的 PCM 上，随后重算波形峰值。
 */
object Fx {

    // ---------------- 动态：强制限幅 / 单频段压缩器 ----------------

    /**
     * 强制限幅（对标 AU Hard Limiter）：
     * - Maximum Amplitude：输出上限 dB
     * - Input Boost：限幅前先增益
     * - Lookahead：提前 look 帧拿到峰值，在峰值到来前把增益降下来
     * - Release：峰值过后增益恢复时间
     * 立体声联动，输出严格被 ceiling 截断。
     */
    fun limiter(
        left: FloatArray,
        right: FloatArray,
        sr: Int,
        ceilingDb: Float = -3f,
        inputBoostDb: Float = 0f,
        lookaheadMs: Float = 5f,
        releaseMs: Float = 80f
    ) {
        val ceiling = 10f.pow(ceilingDb / 20f)
        val boost = 10f.pow(inputBoostDb / 20f)
        val look = (sr * lookaheadMs / 1000f).toInt().coerceIn(1, 8192)
        val rel = exp(-1.0 / (sr * (releaseMs.coerceAtLeast(1f)) / 1000.0)).toFloat()
        val n = min(left.size, right.size)
        if (n <= 0) return
        val dL = FloatArray(n)
        val dR = FloatArray(n)
        for (i in 0 until n) {
            dL[i] = left[i] * boost
            dR[i] = right[i] * boost
        }
        // 单调队列维护最近 look 帧的最大峰值
        val qIdx = IntArray(n + 1)
        val qVal = FloatArray(n + 1)
        var head = 0
        var tail = 0
        var gain = 1f
        for (i in 0 until n + look) {
            if (i < n) {
                val peak = max(abs(dL[i]), abs(dR[i]))
                while (tail > head && qVal[tail - 1] <= peak) tail--
                qIdx[tail] = i
                qVal[tail] = peak
                tail++
                while (qIdx[head] < i - look + 1) head++
                val maxPeak = qVal[head]
                val target = if (maxPeak > ceiling) ceiling / maxPeak else 1f
                gain = if (target < gain) target else rel * gain + (1 - rel) * target
            }
            val src = i - look
            if (src in 0 until n) {
                left[src] = (dL[src] * gain).coerceIn(-ceiling, ceiling)
                right[src] = (dR[src] * gain).coerceIn(-ceiling, ceiling)
            }
        }
    }

    /** 单频段压缩器：阈值 -18dB、4:1、attack 5ms、release 120ms */
    fun compressor(
        left: FloatArray,
        right: FloatArray,
        sr: Int,
        thresholdDb: Float = -18f,
        ratio: Float = 4f,
        attackMs: Float = 5f,
        releaseMs: Float = 120f,
        outputGainDb: Float = 0f
    ) {
        val thr = 10f.pow(thresholdDb / 20f)
        val atk = exp(-1.0 / (sr * attackMs / 1000.0)).toFloat()
        val rel = exp(-1.0 / (sr * releaseMs / 1000.0)).toFloat()
        val outGain = 10f.pow(outputGainDb / 20f)
        var env = 0f
        for (i in left.indices) {
            val a = max(abs(left[i]), abs(right[i]))
            env = if (a > env) atk * env + (1 - atk) * a else rel * env + (1 - rel) * a
            val gain = if (env > thr) {
                val over = env / thr
                (thr * over.pow(1f / ratio)) / env
            } else 1f
            left[i] *= gain * outGain
            right[i] *= gain * outGain
        }
    }

    // ---------------- 延迟与回声 ----------------

    /** 单抽头延迟（带反馈），默认 140ms / 反馈 0.3 / 混合 35% */
    fun delay(
        left: FloatArray,
        right: FloatArray,
        sr: Int,
        delayMs: Float = 140f,
        feedback: Float = 0.3f,
        mix: Float = 0.35f
    ) {
        val d = (sr * delayMs / 1000f).toInt().coerceAtLeast(1)
        if (d >= left.size) return
        for (i in d until left.size) {
            val l = left[i] + left[i - d] * feedback
            val r = right[i] + right[i - d] * feedback
            left[i] = left[i] * (1 - mix) + l * mix
            right[i] = right[i] * (1 - mix) + r * mix
        }
    }

    // ---------------- 滤波与均衡：10 段图形均衡器 ----------------

    private val GRAPHIC_FREQS = floatArrayOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    /** 10 段图形均衡器，gains 长度 10，单位 dB */
    fun graphicEq(left: FloatArray, right: FloatArray, sr: Int, gains: FloatArray) {
        for (b in GRAPHIC_FREQS.indices) {
            val g = if (b < gains.size) gains[b] else 0f
            if (abs(g) < 0.05f) continue
            applyPeaking(left, right, sr, GRAPHIC_FREQS[b].toDouble(), 1.4, g.toDouble())
        }
    }

    /** 单段峰值滤波（RBJ），就地处理 */
    fun applyPeaking(left: FloatArray, right: FloatArray, sr: Int, f0: Double, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * f0 / sr
        val alpha = sin(w0) / (2.0 * q)
        val cosw = Math.cos(w0)
        val b0 = 1 + alpha * a
        val b1 = -2 * cosw
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cosw
        val a2 = 1 - alpha / a
        val nb0 = (b0 / a0).toFloat(); val nb1 = (b1 / a0).toFloat(); val nb2 = (b2 / a0).toFloat()
        val na1 = (a1 / a0).toFloat(); val na2 = (a2 / a0).toFloat()
        filterChannel(left, nb0, nb1, nb2, na1, na2)
        filterChannel(right, nb0, nb1, nb2, na1, na2)
    }

    private fun filterChannel(x: FloatArray, b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val xi = x[i]
            val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            x[i] = y
        }
    }

    // ---------------- 调制：和声 / 颤音 ----------------

    /** 和声（chorus）：0.8Hz、3ms 深度、35% 混合 */
    fun chorus(
        left: FloatArray,
        right: FloatArray,
        sr: Int,
        rateHz: Float = 0.8f,
        depthMs: Float = 3f,
        mix: Float = 0.35f
    ) {
        val base = (sr * 0.012f).toInt()               // 12ms 基础延时
        val depth = (sr * depthMs / 1000f).toInt()
        val bufSize = base + depth + 2
        if (bufSize >= left.size) return
        val ringL = FloatArray(bufSize); val ringR = FloatArray(bufSize)
        var w = 0
        for (i in left.indices) {
            ringL[w] = left[i]; ringR[w] = right[i]
            val phase = i.toFloat() / sr * rateHz * 2f * Math.PI.toFloat()
            val off = base + ((sin(phase.toDouble()) * 0.5 + 0.5) * depth).toInt()
            var ri = w - off
            if (ri < 0) ri += bufSize
            left[i] = left[i] * (1 - mix) + ringL[ri] * mix
            right[i] = right[i] * (1 - mix) + ringR[ri] * mix
            w = (w + 1) % bufSize
        }
    }

    /** 颤音：4Hz、深度 40% */
    fun tremolo(left: FloatArray, right: FloatArray, sr: Int, rateHz: Float = 4f, depth: Float = 0.4f) {
        for (i in left.indices) {
            val lfo = sin(i.toDouble() / sr * rateHz * 2.0 * Math.PI)
            val g = 1f - depth * (0.5f - 0.5f * lfo.toFloat())
            left[i] *= g
            right[i] *= g
        }
    }

    // ---------------- 降噪/恢复：消除齿音 ----------------

    /** 消除齿音：把 6kHz 以上单独压掉（简化版宽带 de-esser） */
    fun deEsser(left: FloatArray, right: FloatArray, sr: Int, thresholdDb: Float = -26f) {
        val hpL = FloatArray(left.size); val hpR = FloatArray(right.size)
        val rc = 1f - exp(-2f * Math.PI.toFloat() * 6000f / sr)
        var lpL = 0f; var lpR = 0f
        for (i in left.indices) {
            lpL += rc * (left[i] - lpL); lpR += rc * (right[i] - lpR)
            hpL[i] = left[i] - lpL
            hpR[i] = right[i] - lpR
        }
        val thr = 10f.pow(thresholdDb / 20f)
        var env = 0f
        val rel = exp(-1.0 / (sr * 0.03)).toFloat()
        for (i in left.indices) {
            val a = max(abs(hpL[i]), abs(hpR[i]))
            env = rel * env + (1 - rel) * a
            val g = if (env > thr) (thr / env).coerceAtLeast(0.25f) else 1f
            left[i] = left[i] - hpL[i] * (1 - g)
            right[i] = right[i] - hpR[i] * (1 - g)
        }
    }

    // ---------------- 立体声：立体声扩展器 ----------------

    /**
     * 立体声扩展：把 M/S 的 S 分量放大 amount 倍。
     * 不再 clamp 到 ±1：扩展本身就会推高峰值，截断等于悄悄加了一层限幅。
     */
    fun stereoWiden(left: FloatArray, right: FloatArray, amount: Float = 1.4f) {
        for (i in left.indices) {
            val m = (left[i] + right[i]) * 0.5f
            val s = (left[i] - right[i]) * 0.5f * amount
            left[i] = m + s
            right[i] = m - s
        }
    }

    // ---------------- 特殊：扭曲 / 人声增强 ----------------

    /** 扭曲：tanh 软饱和，drive 越大越硬 */
    fun distort(left: FloatArray, right: FloatArray, drive: Float = 2.2f, mix: Float = 0.8f) {
        val norm = tanh(drive.toDouble()).toFloat()
        for (i in left.indices) {
            val l = tanh((left[i] * drive).toDouble()).toFloat() / norm
            val r = tanh((right[i] * drive).toDouble()).toFloat() / norm
            left[i] = left[i] * (1 - mix) + l * mix
            right[i] = right[i] * (1 - mix) + r * mix
        }
    }

    /** 人声增强：2.5kHz 临场感 + 8kHz 空气感 + 轻压缩 */
    fun vocalEnhance(left: FloatArray, right: FloatArray, sr: Int) {
        applyPeaking(left, right, sr, 2500.0, 1.1, 3.0)
        applyPeaking(left, right, sr, 8000.0, 1.0, 2.0)
        compressor(left, right, sr, -20f, 2.5f, 8f, 150f)
    }

    // ---------------- 诊断：静音检测 / 删除静音 ----------------

    data class SilenceSpan(val start: Int, val end: Int)

    /** 检测低于阈值（默认 -45dB）且长于 minMs 的静音段 */
    fun detectSilence(
        left: FloatArray,
        right: FloatArray,
        sr: Int,
        thresholdDb: Float = -45f,
        minMs: Int = 250
    ): List<SilenceSpan> {
        val thr = 10f.pow(thresholdDb / 20f)
        val minLen = sr * minMs / 1000
        val out = ArrayList<SilenceSpan>()
        var start = -1
        for (i in left.indices) {
            val a = max(abs(left[i]), abs(right[i]))
            if (a < thr) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= minLen) out.add(SilenceSpan(start, i))
                start = -1
            }
        }
        if (start >= 0 && left.size - start >= minLen) out.add(SilenceSpan(start, left.size))
        return out
    }

    /** 删除静音：保留有声区段（前后各留 20ms），返回拼接后的新缓冲与长度 */
    fun removeSilence(
        left: FloatArray,
        right: FloatArray,
        sr: Int,
        spans: List<SilenceSpan>
    ): Pair<Pair<FloatArray, FloatArray>, Int> {
        if (spans.isEmpty()) return (left to right) to left.size
        val pad = sr / 50                     // 20ms
        val keep = ArrayList<IntRange>()
        var cursor = 0
        for (s in spans) {
            val a = (s.start + pad).coerceAtMost(left.size)
            val b = (s.end - pad).coerceAtLeast(a)
            if (a > cursor) keep.add(cursor until a)
            cursor = max(cursor, b)
        }
        if (cursor < left.size) keep.add(cursor until left.size)

        var total = 0
        for (r in keep) total += r.last - r.first + 1
        val nl = FloatArray(total); val nr = FloatArray(total)
        var w = 0
        for (r in keep) {
            for (i in r) {
                nl[w] = left[i]; nr[w] = right[i]; w++
            }
        }
        // 接缝淡变
        val fade = (sr / 100).coerceAtLeast(1)   // 10ms
        var pos = 0
        for (r in keep) {
            val len = r.last - r.first + 1
            for (i in 0 until min(fade, len)) {
                val g = i.toFloat() / fade
                nl[pos + i] *= g
                nr[pos + i] *= g
            }
            pos += len
        }
        return (nl to nr) to total
    }

    // ---------------- 变速（伸缩） ----------------

    /** 伸缩：线性重采样，改变时长（音调会跟着变，简版） */
    fun stretch(left: FloatArray, right: FloatArray, ratio: Float): Pair<FloatArray, FloatArray> {
        val n = (left.size / ratio).toInt().coerceAtLeast(1)
        val nl = FloatArray(n); val nr = FloatArray(n)
        for (i in 0 until n) {
            val src = (i.toFloat() * ratio).coerceIn(0f, (left.size - 1).toFloat())
            val i0 = src.toInt(); val i1 = min(i0 + 1, left.size - 1)
            val t = src - i0
            nl[i] = left[i0] * (1 - t) + left[i1] * t
            nr[i] = right[i0] * (1 - t) + right[i1] * t
        }
        return nl to nr
    }

    /** dBFS 显示辅助 */
    fun db(x: Float): Float = if (x <= 0.00001f) -100f else 20f * log10(x).toFloat()
}
