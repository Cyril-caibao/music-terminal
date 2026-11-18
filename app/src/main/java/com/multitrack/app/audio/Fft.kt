package com.multitrack.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 定长基数 2 的 FFT（复数，原地）。
 *
 * 降噪要走 STFT：2048 点、512 跳步，一首 4 分钟的歌要算两万帧、每帧一次正变换 + 一次逆变换。
 * 所以这里不追求通用性 —— 旋转因子与位反转表在构造时算好，之后只剩乘加；
 * 也不做实数变换的优化，先保证相位能原样还原（谱减只改幅度、不动相位）。
 */
class Fft(val size: Int) {

    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT 长度必须是 2 的幂：$size" }
    }

    private val levels = Integer.numberOfTrailingZeros(size)
    private val rev = IntArray(size).also { table ->
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - levels)
    }
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, inverse = true)
        val scale = 1f / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        // 位反转重排
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            for (i in 0 until size step len) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosTable[k]
                    val s = if (inverse) sinTable[k] else -sinTable[k]
                    val a = j + half
                    val tr = re[a] * c - im[a] * s
                    val ti = re[a] * s + im[a] * c
                    re[a] = re[j] - tr
                    im[a] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
            }
            len = len shl 1
        }
    }
}

/**
 * STFT 帧分析工具：Hann 窗 + 75% 重叠 + 权重累加归一化。
 *
 * 为什么要自己算 [windowSum] 而不是除一个常数：首尾补零的帧权重不满，
 * 用固定增益会在音频开头/结尾留下一个台阶（听起来是"咔"的一声）。
 */
class Stft(val size: Int = 2048, val hop: Int = 512) {

    val fft = Fft(size)
    val window = FloatArray(size) { i ->
        // 周期 Hann：w[0] = 0，端点不为 1，加窗后首尾相接更平滑
        (0.5 - 0.5 * cos(2.0 * PI * i / size)).toFloat()
    }
    val bins = size / 2 + 1

    /** 每帧的复数缓冲，避免逐帧分配 */
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    fun frameCount(frames: Int): Int = if (frames <= 0) 0 else (frames + hop - 1) / hop + 2

    /**
     * 取第 [index] 帧（以 hop 为步长，前面补 [size]/2 个零），返回幅度谱与相位。
     * 调用方自己决定要不要保留相位（降噪保留、噪声谱统计不需要）。
     */
    fun analyse(samples: FloatArray, index: Int, mag: FloatArray, phase: FloatArray?) {
        java.util.Arrays.fill(re, 0f)
        java.util.Arrays.fill(im, 0f)
        val start = index * hop - size / 2
        for (i in 0 until size) {
            val p = start + i
            if (p in samples.indices) {
                re[i] = samples[p] * window[i]
            }
        }
        fft.forward(re, im)
        for (b in 0 until bins) {
            val r = re[b]
            val q = im[b]
            mag[b] = kotlin.math.sqrt(r * r + q * q)
            if (phase != null) phase[b] = kotlin.math.atan2(q, r)
        }
    }

    /**
     * 分析一帧，把**原始复数频谱**复制出去，同时给幅度谱。
     *
     * 降噪只改幅度、不动相位，所以没必要先 atan2 再 sin/cos 转回来 ——
     * 直接把频谱乘上增益再逆变换，既精确又快（省掉每帧几千次三角函数）。
     */
    fun analyseSpectrum(samples: FloatArray, index: Int, reOut: FloatArray, imOut: FloatArray, mag: FloatArray) {
        java.util.Arrays.fill(re, 0f)
        java.util.Arrays.fill(im, 0f)
        val start = index * hop - size / 2
        for (i in 0 until size) {
            val p = start + i
            if (p in samples.indices) re[i] = samples[p] * window[i]
        }
        fft.forward(re, im)
        for (b in 0 until bins) {
            reOut[b] = re[b]
            imOut[b] = im[b]
            mag[b] = kotlin.math.sqrt(re[b] * re[b] + im[b] * im[b])
        }
    }

    /** 把已经乘过增益的频谱逆变换并重叠相加到 [out] */
    fun synthesiseSpectrum(index: Int, reIn: FloatArray, imIn: FloatArray, out: FloatArray) {
        for (b in 0 until bins) {
            re[b] = reIn[b]
            im[b] = imIn[b]
        }
        for (b in 1 until size / 2) {
            re[size - b] = reIn[b]
            im[size - b] = -imIn[b]
        }
        im[0] = 0f
        im[size / 2] = 0f
        fft.inverse(re, im)
        val start = index * hop - size / 2
        for (i in 0 until size) {
            val p = start + i
            if (p in out.indices) out[p] += re[i] * window[i]
        }
    }

    /** 把一帧写回 [out] 的对应位置（调用方负责重叠相加与除以权重和） */
    fun synthesise(index: Int, mag: FloatArray, phase: FloatArray, out: FloatArray) {
        for (b in 0 until bins) {
            val p = phase[b]
            val m = mag[b]
            re[b] = m * cos(p)
            im[b] = m * sin(p)
        }
        // 负频率对称，保证逆变换出来是实数
        for (b in 1 until size / 2) {
            re[size - b] = re[b]
            im[size - b] = -im[b]
        }
        im[0] = 0f
        im[size / 2] = 0f
        fft.inverse(re, im)
        val start = index * hop - size / 2
        for (i in 0 until size) {
            val p = start + i
            if (p in out.indices) out[p] += re[i] * window[i]
        }
    }

    /** 每帧每个采样点的窗权重累加和（Hann² 在 75% 重叠下理论值 1.5） */
    fun windowSum(frames: Int): FloatArray {
        val sum = FloatArray(frames)
        val n = frameCount(frames)
        for (f in 0 until n) {
            val start = f * hop - size / 2
            for (i in 0 until size) {
                val p = start + i
                if (p in 0 until frames) sum[p] += window[i] * window[i]
            }
        }
        return sum
    }

    /**
     * 把 [synthesiseSpectrum] 叠加出来的结果按窗权重归一回 [dry]，就地写回。
     *
     * **必须每一步都除以权重和**：稳态下 Σw² = 1.5（Hann + 75% 重叠），
     * 只把 `w >= 0.999` 的那段直接当结果用，整段会凭空响 1.5 倍（+3.5dB）——
     * 听感上是"降噪一开声音变大"，而不是"噪声变小"。
     *
     * 首尾各半个窗的覆盖不满：按覆盖度混回干声，既不出台阶也不响半拍。
     *
     * @return 稳态权重和，调用方要用它算别的量时可以直接拿
     */
    fun normaliseOverlapAdd(wet: FloatArray, dry: FloatArray, frames: Int): Float {
        val wsum = windowSum(frames)
        val steady = (wsum.maxOrNull() ?: 0f).coerceAtLeast(1e-6f)
        for (i in 0 until frames) {
            val w = wsum[i]
            if (w <= 1e-6f) continue                       // 没有被任何窗覆盖：保持干声
            val cover = (w / steady).coerceIn(0f, 1f)
            val v = wet[i] / w
            dry[i] = if (cover >= 1f) v else dry[i] * (1f - cover) + v * cover
        }
        return steady
    }
}
