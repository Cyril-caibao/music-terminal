package com.multitrack.app.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 噪声样本：某段"只有噪声"的音频的**平均幅度谱**。
 *
 * AU 的「捕捉噪声样本」得到的是一张频率分布图（每个 bin 上噪声有多大），
 * 之后的降噪就是拿它逐帧做谱减。这里同样只存单声道幅度谱 ——
 * 左右声道共用一份，才不会把声像压歪。
 */
class NoiseProfile(
    val name: String,
    val sampleRate: Int,
    val fftSize: Int,
    val magnitude: FloatArray,
    /** 来源说明：选区 / 导入文件名 / 自动估计 */
    val source: String = ""
) {
    val bins: Int get() = magnitude.size
    val binHz: Float get() = sampleRate.toFloat() / fftSize
}

/**
 * 降噪参数。
 *
 * [strength] 是界面上那根「降噪强度」：0 不处理，100 最狠（34dB 衰减）。
 * 其余是 AU 对话框里的高级项，普通使用不用碰。
 */
data class DenoiseConfig(
    /** 强度 0..100（主滑块） */
    val strength: Float = 60f,
    /** 频带宽度 Hz：噪声谱与增益的平滑宽度，越大越"糊"但音乐噪声越少 */
    val bandWidthHz: Float = 120f,
    /** 残余底 dB：负得越多压得越干净，但会听到"水下声"；默认跟随强度 */
    val floorDb: Float? = null,
    /** 低频保护：低于此频率不做降噪，保住鼓和贝斯 */
    val protectLowHz: Float = 60f
) {
    /** 实际压制量（dB）：6..40 */
    val reductionDb: Float get() = 6f + (strength.coerceIn(0f, 100f) / 100f) * 34f
    val effectiveFloorDb: Float get() = floorDb ?: -reductionDb
}

object SpectralDenoise {

    const val FFT_SIZE = 2048
    const val HOP = 512

    /** 从一段选中音频提取噪声谱；太短（<0.15s）没有统计意义，返回 null */
    fun profileFrom(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        name: String,
        source: String = ""
    ): NoiseProfile? {
        val n = min(left.size, right.size)
        if (n < sampleRate / 7) return null
        val stft = Stft(FFT_SIZE, HOP)
        val total = stft.frameCount(n).coerceAtLeast(1)
        // 长样本按步进均匀取帧：只看前 4000 帧会把"后半段变吵"的样本算得太乐观
        val stride = max(1, total / 2000)
        val sum = DoubleArray(stft.bins)
        val mag = FloatArray(stft.bins)
        // 下混一次（噪声样本通常是稳态的，两声道平均避免单边静音把谱压偏）
        val mono = FloatArray(n) { (left[it] + right[it]) * 0.5f }
        var used = 0
        var f = 0
        while (f < total) {
            stft.analyse(mono, f, mag, null)
            for (b in 0 until stft.bins) sum[b] += mag[b].toDouble()
            used++
            f += stride
        }
        if (used == 0) return null
        val out = FloatArray(stft.bins) { (sum[it] / used).toFloat() }
        // 全零说明样本是数字静音，做噪声谱没有意义
        if (out.maxOrNull() ?: 0f < 1e-6f) return null
        return NoiseProfile(name, sampleRate, FFT_SIZE, out, source)
    }

    /**
     * 没有样本时的自动估计：整段里能量最低的 12% 帧，视为背景噪声。
     *
     * 这是 AU「自适应降噪」的思路 —— 任何一段真实录音，总有一些帧只有底噪。
     * 全曲都是连续乐音时估计会偏高，所以再按分位数收紧一次。
     */
    fun adaptiveProfile(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        name: String = "自动估计"
    ): NoiseProfile? {
        val n = min(left.size, right.size)
        if (n < sampleRate / 4) return null
        val stft = Stft(FFT_SIZE, HOP)
        val total = stft.frameCount(n)
        if (total < 4) return null
        val mono = FloatArray(n) { (left[it] + right[it]) * 0.5f }
        val mag = FloatArray(stft.bins)
        // 第一遍只记能量：把每帧幅度谱都存下来，一首 4 分钟的歌要吃掉上百 MB
        val energy = FloatArray(total)
        for (f in 0 until total) {
            stft.analyse(mono, f, mag, null)
            var e = 0f
            for (b in 1 until stft.bins) e += mag[b] * mag[b]
            energy[f] = e
        }
        val sorted = energy.sortedArray()
        val idx = ((total - 1) * 0.12f).toInt().coerceIn(0, total - 1)
        val threshold = sorted[idx]
        // 第二遍只累加"足够安静"的帧
        val out = DoubleArray(stft.bins)
        var take = 0
        for (f in 0 until total) {
            if (energy[f] > threshold) continue
            stft.analyse(mono, f, mag, null)
            for (b in 0 until stft.bins) out[b] += mag[b].toDouble()
            take++
        }
        if (take == 0) return null
        val mag2 = FloatArray(stft.bins) { (out[it] / take).toFloat() }
        if (mag2.maxOrNull() ?: 0f < 1e-6f) return null
        return NoiseProfile(name, sampleRate, FFT_SIZE, mag2, "自动估计")
    }

    /**
     * 谱减降噪（就地）。
     *
     * 逐帧：`g = (|X|² - k·|N|²) / |X|²`，再夹到 [floor, 1]。
     * - 噪声占主导的 bin，分子为负 → 取 floor（压到 -reduction dB）；
     * - 音乐远大于噪声的 bin，g→1，原样通过；
     * - 过渡带由过减系数 k 控制，k 稍大于 1 能压掉残余的"沙沙"。
     *
     * 增益先按频带宽度做频率平滑（去掉孤立尖刺 → 少"音乐噪声"），
     * 再做时间平滑（下降快、回升慢），所以不会出现一格一格的水声。
     *
     * 实现上直接乘复数频谱、不重建相位：少掉每帧几千次 atan2/sin/cos，
     * 一首 4 分钟的歌从"要等半分钟"降到几秒。
     */
    fun apply(left: FloatArray, right: FloatArray, sampleRate: Int, profile: NoiseProfile, cfg: DenoiseConfig) {
        val n = min(left.size, right.size)
        if (n <= 0) return
        val stft = Stft(FFT_SIZE, HOP)
        val bins = stft.bins
        if (profile.bins != bins) return

        val noise = smoothNoise(profile.magnitude, cfg.bandWidthHz, sampleRate)
        val floorGain = 10.0.pow(cfg.effectiveFloorDb / 20.0).toFloat()
        val protectBin = ((cfg.protectLowHz / sampleRate) * FFT_SIZE).toInt().coerceIn(0, bins)
        val smoothBins = ((cfg.bandWidthHz / (sampleRate.toFloat() / FFT_SIZE)) / 2f)
            .toInt().coerceIn(0, 6)

        val frames = stft.frameCount(n)
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        val magL = FloatArray(bins)
        val magR = FloatArray(bins)
        val reL = FloatArray(bins)
        val imL = FloatArray(bins)
        val reR = FloatArray(bins)
        val imR = FloatArray(bins)
        val gain = FloatArray(bins)
        val prev = FloatArray(bins) { 1f }
        val tmp = FloatArray(bins)

        for (f in 0 until frames) {
            stft.analyseSpectrum(left, f, reL, imL, magL)
            stft.analyseSpectrum(right, f, reR, imR, magR)
            for (b in 0 until bins) {
                val pl = magL[b] * magL[b]
                val pr = magR[b] * magR[b]
                val px = max(pl, pr)
                if (b < protectBin || px <= 1e-12f) {
                    gain[b] = 1f
                    continue
                }
                val n2 = noise[b] * noise[b] * 2.56f // 1.6² 过减
                var g = (px - n2) / px
                if (g < floorGain) g = floorGain
                if (g > 1f) g = 1f
                gain[b] = g
            }
            // 频率平滑：3 点 / 5 点加权，宽度跟着「频带宽度」走
            if (smoothBins >= 1) {
                System.arraycopy(gain, 0, tmp, 0, bins)
                for (b in 0 until bins) {
                    var acc = tmp[b] * 2f
                    var wsum = 2f
                    for (d in 1..smoothBins) {
                        val w = 1f / d
                        if (b - d >= 0) { acc += tmp[b - d] * w; wsum += w }
                        if (b + d < bins) { acc += tmp[b + d] * w; wsum += w }
                    }
                    gain[b] = acc / wsum
                }
            }
            // 时间平滑：下降快（马上压住）、回升慢（不"抽气"）
            for (b in 0 until bins) {
                val p = prev[b]
                val g = gain[b]
                val next = if (g < p) p + (g - p) * 0.55f else p + (g - p) * 0.08f
                prev[b] = next
                reL[b] *= next
                imL[b] *= next
                reR[b] *= next
                imR[b] *= next
            }
            stft.synthesiseSpectrum(f, reL, imL, outL)
            stft.synthesiseSpectrum(f, reR, imR, outR)
        }

        // 3.49 修：这里以前在稳态（Σw²=1.5）直接用了未归一的重叠相加结果，
        // 整段被抬了 1.5 倍 —— 用户听到的是"降噪一开音量变大"，噪声反而更明显。
        stft.normaliseOverlapAdd(outL, left, n)
        stft.normaliseOverlapAdd(outR, right, n)
    }

    /** 噪声谱按频带宽度做移动平均 */
    private fun smoothNoise(src: FloatArray, widthHz: Float, sr: Int): FloatArray {
        if (widthHz <= 0f) return src
        val binsPerHz = FFT_SIZE.toFloat() / sr
        val radius = ((widthHz * binsPerHz) / 2f).toInt().coerceIn(0, 12)
        if (radius <= 0) return src
        val out = FloatArray(src.size)
        for (i in src.indices) {
            var acc = 0.0
            var cnt = 0
            for (d in -radius..radius) {
                val j = i + d
                if (j in src.indices) { acc += src[j]; cnt++ }
            }
            out[i] = (acc / cnt).toFloat()
        }
        return out
    }
}

/* ------------------------------------------------------------------ */
/* 降噪/恢复 里的其余几件：去嗡声、去咔嗒、降嘶声、减混响             */
/* ------------------------------------------------------------------ */

object Restore {

    /**
     * 消除嗡嗡声（AU「消除嗡嗡声」）：50/60Hz 基频 + 各次谐波窄带陷波。
     *
     * 先看哪个基频的能量大 —— 中国/欧洲 50Hz、美洲 60Hz，用户不需要自己知道。
     * Q 取 30（-3dB 带宽约 1.7Hz），级联两级，抑制约 24dB，但只挖极窄的一条，
     * 不会像低切那样把贝斯一起砍掉。
     */
    fun dehum(left: FloatArray, right: FloatArray, sr: Int, baseHz: Float = 50f, harmonics: Int = 8) {
        val f0 = detectHumBase(left, right, sr, baseHz)
        var h = 1
        while (h <= harmonics) {
            val f = f0 * h
            if (f > sr / 2f - 100f) break
            notch(left, sr, f)
            notch(right, sr, f)
            notch(left, sr, f)
            notch(right, sr, f)
            h++
        }
    }

    /** 用 50/60Hz 附近能量投票，返回更像工频的那个 */
    fun detectHumBase(left: FloatArray, right: FloatArray, sr: Int, fallback: Float = 50f): Float {
        val n = min(left.size, 65536)
        if (n < sr / 4) return fallback
        fun band(f: Float): Double {
            // 单频 DFT（Goertzel 形式），省一次 FFT
            val w = 2.0 * Math.PI * f / sr
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val x = (left[i] + right[i]) * 0.5
                re += x * cos(w * i)
                im += x * sin(w * i)
            }
            return re * re + im * im
        }
        val e50 = band(50f)
        val e60 = band(60f)
        return if (e60 > e50 * 1.15) 60f else 50f
    }

    private fun notch(buf: FloatArray, sr: Int, f: Float) {
        if (f <= 0f || f >= sr / 2f) return
        val w0 = 2.0 * Math.PI * f / sr
        val alpha = sin(w0) / (2.0 * 30.0)
        val a0 = 1.0 + alpha
        val b0 = 1.0 / a0
        val b1 = -2.0 * cos(w0) / a0
        val b2 = 1.0 / a0
        val a1 = -2.0 * cos(w0) / a0
        val a2 = (1.0 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in buf.indices) {
            val x = buf[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            buf[i] = y.toFloat()
        }
    }

    /**
     * 自动咔嗒声 / 爆音消除。
     *
     * 判据是「二阶差分突变」：正常音频相邻样本是连续的，爆音是一个孤立的尖刺，
     * 它和前后线性预测值的误差会远大于局部 RMS。命中后用两端线性插值补上。
     * [sensitivity] 0.1..1，越大越敏感（阈值越低）。
     */
    fun declick(left: FloatArray, right: FloatArray, sr: Int, sensitivity: Float = 0.5f): Int {
        var total = 0
        for (pass in 0 until 2) {
            total += declickChannel(left, sr, sensitivity)
            total += declickChannel(right, sr, sensitivity)
        }
        return total
    }

    private fun declickChannel(buf: FloatArray, sr: Int, sensitivity: Float): Int {
        val n = buf.size
        if (n < 8) return 0
        val win = max(64, sr / 40)
        // 局部 RMS（滑动，窗口 win）—— 用块级近似，够用且 O(n)
        var count = 0
        var i = 2
        while (i < n - 2) {
            var acc = 0.0
            var cnt = 0
            val a = max(0, i - win)
            val b = min(n - 1, i + win)
            var j = a
            while (j <= b) { acc += buf[j] * buf[j].toDouble(); cnt++; j += 8 }
            val rms = sqrt(acc / max(1, cnt))
            val sens = sensitivity.coerceIn(0f, 1f).toDouble()
            val thresh = max(0.012f, (rms * (3.2 - 2.4 * sens)).toFloat())
            val pred = (buf[i - 1] + buf[i + 1]) * 0.5f
            val err = abs(buf[i] - pred)
            if (err > thresh) {
                // 找这段异常的长度（连续异常）
                var e = i
                while (e < n - 1 && abs(buf[e] - (buf[e - 1] + buf[e + 1]) * 0.5f) > thresh * 0.8f) e++
                val endLen = min(e, n - 1)
                val x0 = buf[i - 1]
                val x1 = buf[endLen]
                val span = endLen - (i - 1)
                if (span > 0) {
                    for (k in i until endLen) {
                        val t = (k - (i - 1)).toFloat() / span
                        buf[k] = x0 + (x1 - x0) * t
                    }
                    count++
                }
                i = endLen + 1
            } else {
                i++
            }
        }
        return count
    }

    /**
     * 降低嘶声（AU「降低嘶声」）：只对 3kHz 以上做谱减，用自适应噪声谱。
     * 和全带降噪分开，是因为人声齿音/镲片也在这段，压全带会把它们一起吃掉。
     */
    fun deSibilance(left: FloatArray, right: FloatArray, sr: Int, amountDb: Float = 12f) {
        val profile = SpectralDenoise.adaptiveProfile(left, right, sr, "嘶声估计") ?: return
        val stft = Stft(SpectralDenoise.FFT_SIZE, SpectralDenoise.HOP)
        val bins = stft.bins
        val fromBin = ((3200f / sr) * SpectralDenoise.FFT_SIZE).toInt().coerceIn(0, bins)
        val floorGain = 10.0.pow(-amountDb / 20.0).toFloat()
        val mag = FloatArray(bins)
        val re = FloatArray(bins)
        val im = FloatArray(bins)

        for (ch in 0 until 2) {
            val src = if (ch == 0) left else right
            val n = src.size
            val out = FloatArray(n)
            val frames = stft.frameCount(n)
            for (f in 0 until frames) {
                stft.analyseSpectrum(src, f, re, im, mag)
                for (b in fromBin until bins) {
                    val px = mag[b] * mag[b]
                    val n2 = profile.magnitude[b] * profile.magnitude[b] * 2.25f
                    var g = (px - n2) / px
                    if (g < floorGain) g = floorGain
                    if (g > 1f) g = 1f
                    re[b] *= g
                    im[b] *= g
                }
                stft.synthesiseSpectrum(f, re, im, out)
            }
            val wsum = stft.windowSum(n)
            for (i in 0 until n) {
                val w = if (wsum[i] > 1e-6f) wsum[i] else 1f
                src[i] = out[i] / w
            }
        }
    }

    /**
     * 减少混响（AU「减少混响」的简化版）。
     *
     * 混响的尾巴有个特征：能量在衰减。所以给每个 bin 记一个「历史峰值包络」，
     * 当前幅度低于包络时按比例往下压，包络本身缓慢下滑。稳态的直达声几乎不受影响。
     */
    fun deReverb(left: FloatArray, right: FloatArray, sr: Int, amount: Float = 0.5f) {
        val stft = Stft(SpectralDenoise.FFT_SIZE, SpectralDenoise.HOP)
        val bins = stft.bins
        val magL = FloatArray(bins)
        val magR = FloatArray(bins)
        val reL = FloatArray(bins)
        val imL = FloatArray(bins)
        val reR = FloatArray(bins)
        val imR = FloatArray(bins)
        val envL = FloatArray(bins)
        val envR = FloatArray(bins)
        val floorGain = 10.0.pow((-18f * amount.coerceIn(0f, 1f) - 4f) / 20.0).toFloat()
        val n = min(left.size, right.size)
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        val frames = stft.frameCount(n)
        for (f in 0 until frames) {
            stft.analyseSpectrum(left, f, reL, imL, magL)
            stft.analyseSpectrum(right, f, reR, imR, magR)
            for (b in 0 until bins) {
                envL[b] = max(magL[b], envL[b] * 0.86f)
                envR[b] = max(magR[b], envR[b] * 0.86f)
                if (envL[b] > 1e-6f) {
                    val r = (magL[b] / envL[b]).coerceIn(0f, 1f)
                    val g = (r.pow(1f + amount * 2.5f)).coerceIn(floorGain, 1f)
                    reL[b] *= g
                    imL[b] *= g
                }
                if (envR[b] > 1e-6f) {
                    val r = (magR[b] / envR[b]).coerceIn(0f, 1f)
                    val g = (r.pow(1f + amount * 2.5f)).coerceIn(floorGain, 1f)
                    reR[b] *= g
                    imR[b] *= g
                }
            }
            stft.synthesiseSpectrum(f, reL, imL, outL)
            stft.synthesiseSpectrum(f, reR, imR, outR)
        }
        val wsum = stft.windowSum(n)
        for (i in 0 until n) {
            val w = if (wsum[i] > 1e-6f) wsum[i] else 1f
            left[i] = outL[i] / w
            right[i] = outR[i] / w
        }
    }

    /** 测试用：单频 DFT 幅度（避免测试里再写一遍） */
    fun toneAmplitude(buf: FloatArray, sr: Int, f: Float): Double {
        val n = min(buf.size, sr)
        val w = 2.0 * Math.PI * f / sr
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            re += buf[i] * cos(w * i)
            im += buf[i] * sin(w * i)
        }
        return 2.0 * sqrt(re * re + im * im) / n
    }
}
