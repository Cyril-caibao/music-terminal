package com.multitrack.app.audio

import kotlin.math.*

/**
 * 数字信号处理（DSP）工具集：混音所需基础算子 + 对标「音编美声」的
 * 降噪、升降调、EQ、混响、淡入淡出、增益、声像、归一化、反转等。
 *
 * 所有函数直接操作 Float32Array（平面立体声），便于离线对 Clip 的 PCM 施加效果。
 * 【从鸿蒙版 Dsp.ets 1:1 平移，纯 Kotlin，不依赖 Android/Compose】
 */

/**
 * 声像（pan ∈ [-1,1]）→ 左右增益。**平衡法则：正中就是 1.0 / 1.0。**
 *
 * 以前这里是等功率的 cos/sin，而音轨和片段上各有一个 pan，两处都在正中时
 * 0.7071 × 0.7071 = 0.5 —— 一首 0dBFS 的歌进了 App 只剩一半幅度，
 * 电平表比 AU 低 6dB，还听不出"哪儿错了"，只觉得声音小、表不准。
 * 立体声素材的 pan 本来就该是平衡：正中不动，往一边推只是把另一边压下去。
 */
fun panBalance(pan: Float): PanGain {
    val p = max(-1f, min(1f, pan))
    return if (p > 0f) PanGain(1f - p, 1f) else PanGain(1f, 1f + p)
}

/**
 * float → 16bit PCM。**必须钳位**：直接 `(x*32767).toInt().toShort()` 在 |x|>1 时
 * 会整数溢出翻转符号（1.2 → −26216），听感是一声爆响，在 AU 里看是满幅方波。
 */
fun pcm16(x: Float): Short {
    val v = if (x > 1f) 1f else if (x < -1f) -1f else x
    return Math.round(v * 32767f).toShort()
}

/** 线性重采样到目标采样率（任意比例，双声道） */
fun resampleToRate(
    left: FloatArray,
    right: FloatArray,
    fromSr: Int,
    toSr: Int
): StereoBuffer {
    if (fromSr == toSr) return StereoBuffer(left, right)
    val ratio = toSr.toDouble() / fromSr.toDouble()
    val newLen = max(1, round(left.size * ratio).toInt())
    val nl = FloatArray(newLen)
    val nr = FloatArray(newLen)
    val threads = minOf(4, maxOf(1, newLen / (1 shl 18)))
    val work = { a: Int, b: Int ->
        for (i in a until b) {
            val src = i / ratio
            val i0 = floor(src).toInt()
            val i1 = min(i0 + 1, left.size - 1)
            val frac = (src - i0).toFloat()
            nl[i] = left[i0] * (1 - frac) + left[i1] * frac
            nr[i] = right[i0] * (1 - frac) + right[i1] * frac
        }
    }
    if (threads <= 1) {
        work(0, newLen)
    } else {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val latch = java.util.concurrent.CountDownLatch(threads)
        val chunk = (newLen + threads - 1) / threads
        var from = 0
        while (from < newLen) {
            val a = from
            val b = minOf(newLen, from + chunk)
            pool.execute { work(a, b); latch.countDown() }
            from = b
        }
        latch.await()
        pool.shutdown()
    }
    return StereoBuffer(nl, nr)
}

/** 整体增益（线性），原地修改 */
fun applyGain(left: FloatArray, right: FloatArray, gain: Float) {
    for (i in left.indices) {
        left[i] *= gain
        right[i] *= gain
    }
}

/** 归一化到指定峰值（默认 0.99），原地修改 */
fun normalize(left: FloatArray, right: FloatArray, peak: Float = 0.99f) {
    var max = 1e-6f
    for (i in left.indices) {
        val a = abs(left[i]); val b = abs(right[i])
        if (a > max) max = a
        if (b > max) max = b
    }
    applyGain(left, right, peak / max)
}

/** 反转（倒放 / 特殊音效），原地修改 */
fun reverse(left: FloatArray, right: FloatArray) {
    left.reverse()
    right.reverse()
}

/** 淡入淡出包络：首尾 fadeIn/fadeOut 帧内做线性斜坡，原地修改 */
fun applyFade(left: FloatArray, right: FloatArray, fadeIn: Int, fadeOut: Int) {
    val n = left.size
    for (i in 0 until n) {
        var env = 1f
        if (fadeIn > 0 && i < fadeIn) env *= i.toFloat() / fadeIn
        if (fadeOut > 0 && i > n - fadeOut) env *= (n - 1 - i).toFloat() / fadeOut
        left[i] *= env
        right[i] *= env
    }
}

/* ------------------------------------------------------------------ */
/* 升降调：保持速度不变（变调不变速）                                  */
/* 做法：先按 2^(semitones/12) 上/下采样改变音高，再用 OLA 时域拉伸   */
/* 把时长拉回原长。                                                    */
/* ------------------------------------------------------------------ */

/** 线性重采样（单声道，ratio>1 表示加快/升调） */
private fun resampleMono(input: FloatArray, ratio: Double): FloatArray {
    val newLen = max(1, round(input.size * ratio).toInt())
    val out = FloatArray(newLen)
    for (i in 0 until newLen) {
        val src = i / ratio
        val i0 = floor(src).toInt()
        val i1 = min(i0 + 1, input.size - 1)
        val frac = (src - i0).toFloat()
        out[i] = input[i0] * (1 - frac) + input[i1] * frac
    }
    return out
}

/** OLA 时域拉伸：ratio>1 输出更长（减速），ratio<1 更短（加速） */
private fun olaTimeStretch(input: FloatArray, ratio: Double): FloatArray {
    val frameLen = 1024
    val synHop = 256
    val anaHop = max(1, round(synHop * ratio).toInt())
    val outLen = round(input.size * ratio).toInt()
    val out = FloatArray(outLen)
    val winSum = FloatArray(outLen)
    val hann = FloatArray(frameLen)
    for (i in 0 until frameLen) {
        hann[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (frameLen - 1))).toFloat()
    }
    var outPos = 0
    var inPos = 0
    while (inPos + frameLen <= input.size) {
        for (j in 0 until frameLen) {
            val idx = outPos + j
            if (idx < outLen) {
                val w = hann[j]
                out[idx] += input[inPos + j] * w
                winSum[idx] += w * w
            }
        }
        outPos += synHop
        inPos += anaHop
    }
    for (i in 0 until outLen) {
        if (winSum[i] > 1e-6f) out[i] /= winSum[i]
    }
    return out
}

/** 升降调（semitones 可正可负），保持时长不变，返回新缓冲 */
fun pitchShift(left: FloatArray, right: FloatArray, semitones: Float): StereoBuffer {
    val factor = 2.0.pow(semitones / 12.0)
    val lUp = resampleMono(left, factor)
    val rUp = resampleMono(right, factor)
    val l = olaTimeStretch(lUp, 1.0 / factor)
    val r = olaTimeStretch(rUp, 1.0 / factor)
    return StereoBuffer(l, r)
}

/* ------------------------------------------------------------------ */
/* 3 段 EQ：低架 / 中频峰值 / 高架（Audio EQ Cookbook 双二阶）          */
/* ------------------------------------------------------------------ */

private class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    companion object {
        fun lowShelf(fs: Int, f0: Double, q: Double, gainDb: Double) = make("lowshelf", fs, f0, q, gainDb)
        fun highShelf(fs: Int, f0: Double, q: Double, gainDb: Double) = make("highshelf", fs, f0, q, gainDb)
        fun peaking(fs: Int, f0: Double, q: Double, gainDb: Double) = make("peaking", fs, f0, q, gainDb)

        private fun make(type: String, fs: Int, f0: Double, q: Double, gainDb: Double): Biquad {
            val b = Biquad()
            val A = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * f0 / fs
            val cw = cos(w0); val sw = sin(w0)
            val alpha = sw / (2 * q)
            var b0: Double; var b1: Double; var b2: Double
            var a0: Double; var a1: Double; var a2: Double
            when (type) {
                "lowshelf" -> {
                    val t = 2 * sqrt(A) * alpha
                    b0 = A * ((A + 1) - (A - 1) * cw + t)
                    b1 = 2 * A * ((A - 1) - (A + 1) * cw)
                    b2 = A * ((A + 1) - (A - 1) * cw - t)
                    a0 = (A + 1) + (A - 1) * cw + t
                    a1 = -2 * ((A - 1) + (A + 1) * cw)
                    a2 = (A + 1) + (A - 1) * cw - t
                }
                "highshelf" -> {
                    val t = 2 * sqrt(A) * alpha
                    b0 = A * ((A + 1) + (A - 1) * cw + t)
                    b1 = -2 * A * ((A - 1) + (A + 1) * cw)
                    b2 = A * ((A + 1) + (A - 1) * cw - t)
                    a0 = (A + 1) - (A - 1) * cw + t
                    a1 = 2 * ((A - 1) - (A + 1) * cw)
                    a2 = (A + 1) - (A - 1) * cw - t
                }
                else -> { // peaking
                    b0 = 1 + alpha * A
                    b1 = -2 * cw
                    b2 = 1 - alpha * A
                    a0 = 1 + alpha / A
                    a1 = -2 * cw
                    a2 = 1 - alpha / A
                }
            }
            b.b0 = b0 / a0; b.b1 = b1 / a0; b.b2 = b2 / a0
            b.a1 = a1 / a0; b.a2 = a2 / a0
            return b
        }
    }

    fun process(x: FloatArray): FloatArray {
        val y = FloatArray(x.size)
        for (i in x.indices) {
            val v = x[i].toDouble()
            val out = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v
            y2 = y1; y1 = out
            y[i] = out.toFloat()
        }
        return y
    }
}

data class EqSettings(val lowGainDb: Double = 0.0, val midGainDb: Double = 0.0, val highGainDb: Double = 0.0)

/** 3 段 EQ，直接修改传入的缓冲 */
fun applyEq(left: FloatArray, right: FloatArray, sr: Int, eq: EqSettings) {
    val lb = Biquad.lowShelf(sr, 100.0, 0.707, eq.lowGainDb)
    val mb = Biquad.peaking(sr, 1000.0, 0.9, eq.midGainDb)
    val hb = Biquad.highShelf(sr, 8000.0, 0.707, eq.highGainDb)
    for (c in 0 until 2) {
        val src = if (c == 0) left else right
        var t = lb.process(src)
        t = mb.process(t)
        t = hb.process(t)
        if (c == 0) t.copyInto(left) else t.copyInto(right)
    }
}

/* ------------------------------------------------------------------ */
/* 混响：经典 Schroeder 算法（4 个并联梳状滤波 + 2 个全通）            */
/* ------------------------------------------------------------------ */

data class ReverbSettings(val roomSize: Float = 0.5f, val wet: Float = 0.25f)

fun applyReverb(left: FloatArray, right: FloatArray, sr: Int, cfg: ReverbSettings) {
    if (cfg.wet <= 0) return
    val base = floor(sr * 0.03).toInt() // 30ms 基准延迟
    val delays = intArrayOf(base, (base * 1.4).toInt(), (base * 1.7).toInt(), (base * 2.3).toInt())
    val decay = 0.7f + cfg.roomSize * 0.25f
    val l = reverbChannel(left, delays, decay)
    val r = reverbChannel(right, delays, decay)
    for (i in left.indices) {
        left[i] = left[i] * (1 - cfg.wet) + l[i] * cfg.wet
        right[i] = right[i] * (1 - cfg.wet) + r[i] * cfg.wet
    }
}

private fun reverbChannel(input: FloatArray, delays: IntArray, decay: Float): FloatArray {
    val out = FloatArray(input.size)
    val comb = Array(delays.size) { FloatArray(delays[it]) }
    val cpos = IntArray(delays.size)
    for (i in input.indices) {
        var sum = 0f
        for (k in delays.indices) {
            val d = delays[k]
            val pos = cpos[k]
            val delayed = comb[k][pos]
            val v = input[i] + delayed * decay
            comb[k][pos] = v
            sum += v
            cpos[k] = (pos + 1) % d
        }
        out[i] = sum / delays.size
    }
    allpass(out, (delays[0] * 0.5).toInt(), 0.6f)
    allpass(out, (delays[1] * 0.5).toInt(), 0.6f)
    return out
}

private fun allpass(buf: FloatArray, delay: Int, g: Float) {
    if (delay <= 0) return
    val line = FloatArray(delay)
    var pos = 0
    for (i in buf.indices) {
        val x = buf[i]
        val delayed = line[pos]
        val y = delayed - g * x
        line[pos] = x + g * y
        buf[i] = y
        pos = (pos + 1) % delay
    }
}

/* ------------------------------------------------------------------ */
/* 降噪：高通去低频隆隆声 + 噪声门（低于门限电平归零）                 */
/* ------------------------------------------------------------------ */

data class DenoiseSettings(val highpassHz: Float = 80f, val gateDb: Float = -50f)

fun applyDenoise(left: FloatArray, right: FloatArray, sr: Int, cfg: DenoiseSettings) {
    val rc = 1.0 / (2 * PI * max(20f, cfg.highpassHz))
    val dt = 1.0 / sr
    val alpha = rc / (rc + dt)
    val gate = 10.0.pow(cfg.gateDb / 20.0)
    for (c in 0 until 2) {
        val ch = if (c == 0) left else right
        var prevIn = 0.0; var prevOut = 0.0
        for (i in ch.indices) {
            val x = ch[i].toDouble()
            val y = alpha * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            ch[i] = if (abs(y) < gate) 0f else y.toFloat()
        }
    }
}
