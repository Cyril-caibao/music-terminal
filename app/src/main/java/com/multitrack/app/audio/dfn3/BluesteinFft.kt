package com.multitrack.app.audio.dfn3

import com.multitrack.app.audio.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 960 点复数 FFT（DFN3 的 fft_size）。
 *
 * 为什么不复用 [Fft]：960 = 2^6 × 3 × 5，不是 2 的幂，基 2 蝶形吃不下。
 * 两条路：写混合基（radix-3/5）蝶形，或者 Bluestein —— 后者把任意长度 N 的变换
 * 化成一个 2 的幂长度（≥ 2N-1）的循环卷积，只用到已有的 [Fft]。
 *
 * 复杂度：每次变换 3 趟 2048 点 FFT（正、逆、核乘积），约 3 × 2048 × 11 次复数乘加，
 * 比混合基慢一倍多，但 960 点对手机来说仍是每帧几十微秒的量级，换来的是"不容易写错"。
 */
class BluesteinFft(val n: Int) {

    private val m: Int = run {
        var v = 1
        while (v < 2 * n - 1) v = v shl 1
        v
    }
    private val inner = Fft(m)

    /** chirp：exp(-i·π·k²/n)，k² 先对 2n 取模，避免大 k 时的浮点精度塌陷 */
    private val chirpRe = FloatArray(n)
    private val chirpIm = FloatArray(n)

    /** 预先把核的频谱算好：b = conj(chirp) 按循环方式摆放 */
    private val kernelRe = FloatArray(m)
    private val kernelIm = FloatArray(m)

    private val aRe = FloatArray(m)
    private val aIm = FloatArray(m)

    init {
        val twoN = 2L * n
        for (i in 0 until n) {
            val k = (i.toLong() * i) % twoN
            val ang = -PI * k / n
            chirpRe[i] = cos(ang).toFloat()
            chirpIm[i] = sin(ang).toFloat()
        }
        for (i in 0 until n) {
            kernelRe[i] = chirpRe[i]
            kernelIm[i] = -chirpIm[i]
        }
        for (i in 1 until n) {
            kernelRe[m - i] = chirpRe[i]
            kernelIm[m - i] = -chirpIm[i]
        }
        inner.forward(kernelRe, kernelIm)
    }

    /** 正变换，就地写回 [re]/[im]（长度必须是 [n]） */
    fun forward(re: FloatArray, im: FloatArray) {
        java.util.Arrays.fill(aRe, 0f)
        java.util.Arrays.fill(aIm, 0f)
        for (i in 0 until n) {
            val cr = chirpRe[i]
            val ci = chirpIm[i]
            val xr = re[i]
            val xi = im[i]
            aRe[i] = xr * cr - xi * ci
            aIm[i] = xr * ci + xi * cr
        }
        inner.forward(aRe, aIm)
        for (k in 0 until m) {
            val br = kernelRe[k]
            val bi = kernelIm[k]
            val ar = aRe[k]
            val ai = aIm[k]
            aRe[k] = ar * br - ai * bi
            aIm[k] = ar * bi + ai * br
        }
        inner.inverse(aRe, aIm)
        for (i in 0 until n) {
            val cr = chirpRe[i]
            val ci = chirpIm[i]
            val ar = aRe[i]
            val ai = aIm[i]
            re[i] = ar * cr - ai * ci
            im[i] = ar * ci + ai * cr
        }
    }

    /** 逆变换（含 1/n 归一化）：conj(FFT(conj(x))) / n */
    fun inverse(re: FloatArray, im: FloatArray) {
        inverseUnnormalized(re, im)
        val s = 1f / n
        for (i in 0 until n) {
            re[i] *= s
            im[i] *= s
        }
    }

    /**
     * 逆变换（**不**除 n）。
     *
     * STFT 合成侧必须用这个：libDF 用的是 realfft 的原始逆变换（结果放大 n 倍），
     * 而分析侧乘了 `wnorm = 2·hop/N²`，两边正好抵消，加上 Vorbis 窗在 50% 重叠下
     * `Σw² = 1`，全程增益才是 1。要是这里也除一个 n，整段会被压掉 1/N²（约 −60dB）——
     * 听感就是"降噪一开，声音没了"。
     */
    fun inverseUnnormalized(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) im[i] = -im[i]
        forward(re, im)
        for (i in 0 until n) im[i] = -im[i]
    }
}
