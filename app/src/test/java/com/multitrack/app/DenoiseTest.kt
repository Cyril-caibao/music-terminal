package com.multitrack.app

import com.multitrack.app.audio.DenoiseConfig
import com.multitrack.app.audio.Fft
import com.multitrack.app.audio.NoiseProfile
import com.multitrack.app.audio.Restore
import com.multitrack.app.audio.SpectralDenoise
import com.multitrack.app.audio.Stft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 降噪链路的硬指标。
 *
 * 谱减最容易出两种事故：一是"降了个寂寞"（噪声没下去），二是"降过头"（人声/乐器一起被吃掉）。
 * 所以这里的断言成对出现：噪声必须掉够多，有用信号必须留住。
 */
class DenoiseTest {

    private val sr = 44100

    private fun sine(freq: Float, seconds: Float, amp: Float, phase: Float = 0f): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n) { i -> amp * sin(2.0 * PI * freq * i / sr + phase).toFloat() }
    }

    private fun noise(seconds: Float, amp: Float, seed: Int = 7): FloatArray {
        val n = (sr * seconds).toInt()
        val rnd = Random(seed)
        return FloatArray(n) { (rnd.nextFloat() * 2f - 1f) * amp }
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val n = parts.minOf { it.size }
        return FloatArray(n) { i -> parts.sumOf { it[i].toDouble() }.toFloat() }
    }

    /**
     * 宽带底噪的度量：取几个远离主音的频点，算它们的合成幅度。
     * 用一阶差分不行 —— 440Hz 正弦的差分比噪声还大，会把结论带偏。
     */
    private fun highBandNoise(buf: FloatArray): Double {
        var acc = 0.0
        for (f in listOf(6000f, 8000f, 10000f, 12000f, 14000f)) {
            val a = Restore.toneAmplitude(buf, sr, f)
            acc += a * a
        }
        return kotlin.math.sqrt(acc / 5)
    }

    @Test
    fun fftRoundTripRestoresTheSignal() {
        val n = 1024
        val re = FloatArray(n) { Random(3).nextFloat() - 0.5f }
        val im = FloatArray(n)
        val src = re.copyOf()
        val fft = Fft(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until n) {
            assertEquals("第 $i 点没还原", src[i], re[i], 1e-4f)
            assertEquals(0f, im[i], 1e-4f)
        }
    }

    @Test
    fun stftKeepsLengthAndDoesNotBlowUp() {
        val stft = Stft(2048, 512)
        val n = sr
        val buf = sine(1000f, 1f, 0.5f)
        val mag = FloatArray(stft.bins)
        val phase = FloatArray(stft.bins)
        val out = FloatArray(n)
        val frames = stft.frameCount(n)
        for (f in 0 until frames) {
            stft.analyse(buf, f, mag, phase)
            stft.synthesise(f, mag, phase, out)
        }
        val wsum = stft.windowSum(n)
        var err = 0.0
        for (i in n / 4 until n * 3 / 4) {
            val v = out[i] / (if (wsum[i] > 1e-6f) wsum[i] else 1f)
            err += abs(v - buf[i]).toDouble()
        }
        val avg = err / (n / 2)
        assertTrue("重构误差太大：$avg", avg < 0.02)
    }

    @Test
    fun profilePutsThePeakOnTheToneFrequency() {
        val tone = sine(1000f, 0.6f, 0.4f)
        val p = SpectralDenoise.profileFrom(tone, tone, sr, "测试")
        assertNotNull(p)
        val peakBin = p!!.magnitude.indices.maxByOrNull { p.magnitude[it] }!!
        val freq = peakBin * sr.toFloat() / p.fftSize
        assertTrue("峰值落在 $freq Hz，应该在 1kHz 附近", abs(freq - 1000f) < 60f)
    }

    @Test
    fun tooShortSampleIsRejected() {
        val short = FloatArray(sr / 20)
        assertEquals(null, SpectralDenoise.profileFrom(short, short, sr, "太短"))
    }

    @Test
    fun denoiseLowersNoiseAndKeepsTheTone() {
        val cleanL = sine(440f, 3f, 0.45f)
        val n = cleanL.size
        val noiseL = noise(3f, 0.05f)
        val noiseR = noise(3f, 0.05f, seed = 11)
        val dryL = FloatArray(n) { cleanL[it] + noiseL[it] }
        val dryR = FloatArray(n) { cleanL[it] + noiseR[it] }

        // 前 0.8 秒当作"纯噪声"去捕捉样本
        val cut = (sr * 0.8f).toInt()
        val p = SpectralDenoise.profileFrom(
            noiseL.copyOfRange(0, cut), noiseR.copyOfRange(0, cut), sr, "样本"
        )!!
        val before = highBandNoise(dryL)
        val toneBefore = Restore.toneAmplitude(dryL, sr, 440f)

        val outL = dryL.copyOf()
        val outR = dryR.copyOf()
        SpectralDenoise.apply(outL, outR, sr, p, DenoiseConfig(strength = 70f))

        val after = highBandNoise(outL)
        val toneAfter = Restore.toneAmplitude(outL, sr, 440f)
        val dropDb = (20 * kotlin.math.log10(before / after)).toFloat()
        assertTrue("噪声只降了 ${"%.1f".format(dropDb)}dB", dropDb > 8f)
        assertTrue(
            "主音被削掉了：${"%.2f".format(toneAfter / toneBefore)}",
            toneAfter / toneBefore > 0.75f
        )
    }

    @Test
    fun strongerSettingLeavesLessNoise() {
        val clean = sine(700f, 2f, 0.4f)
        val n = clean.size
        val nz = noise(2f, 0.06f, seed = 21)
        val dry = FloatArray(n) { clean[it] + nz[it] }
        val p = SpectralDenoise.profileFrom(nz, nz, sr, "样本")!!

        fun residual(strength: Float): Double {
            val l = dry.copyOf()
            val r = dry.copyOf()
            SpectralDenoise.apply(l, r, sr, p, DenoiseConfig(strength = strength))
            return highBandNoise(l)
        }
        val soft = residual(20f)
        val hard = residual(95f)
        assertTrue("强度 95 应该比 20 更干净：$soft → $hard", hard < soft)
    }

    @Test
    fun zeroStrengthChangesLessThanFullStrength() {
        val clean = sine(500f, 1.2f, 0.4f)
        val nz = noise(1.2f, 0.03f, seed = 5)
        val n = clean.size
        val dry = FloatArray(n) { clean[it] + nz[it] }
        val p = SpectralDenoise.profileFrom(nz, nz, sr, "样本")!!

        fun delta(strength: Float): Double {
            val l = dry.copyOf()
            val r = dry.copyOf()
            SpectralDenoise.apply(l, r, sr, p, DenoiseConfig(strength = strength))
            var acc = 0.0
            for (i in n / 4 until n * 3 / 4) acc += abs(l[i] - dry[i]).toDouble()
            return acc / (n / 2)
        }
        // strength=0 仍然会削掉一点底噪（-6dB），但改动必须明显小于拉满
        assertTrue("强度 0 的改动应小于强度 90", delta(0f) < delta(90f))
    }

    @Test
    fun dehumKillsMainsHumButLeavesTheVoiceBand() {
        val hum = sine(50f, 2f, 0.3f)
        val voice = sine(1000f, 2f, 0.3f)
        val buf = mix(hum, voice)
        val right = buf.copyOf()
        val humBefore = Restore.toneAmplitude(buf, sr, 50f)
        val voiceBefore = Restore.toneAmplitude(buf, sr, 1000f)

        Restore.dehum(buf, right, sr, 50f, 8)

        val humAfter = Restore.toneAmplitude(buf, sr, 50f)
        val voiceAfter = Restore.toneAmplitude(buf, sr, 1000f)
        assertTrue("50Hz 只降了 ${humAfter / humBefore}", humAfter / humBefore < 0.2f)
        assertTrue("1kHz 被误伤：${voiceAfter / voiceBefore}", voiceAfter / voiceBefore > 0.9f)
    }

    @Test
    fun humBaseDetectsSixty() {
        val hum = sine(60f, 1f, 0.3f)
        val other = sine(1000f, 1f, 0.05f)
        val buf = mix(hum, other)
        assertEquals(60f, Restore.detectHumBase(buf, buf, sr), 0.01f)
    }

    @Test
    fun declickRepairsImpulses() {
        val base = sine(600f, 1.5f, 0.3f)
        val left = base.copyOf()
        val right = base.copyOf()
        val spots = intArrayOf(sr / 4, sr / 3, sr / 2, sr * 2 / 3, sr * 4 / 5)
        for (s in spots) {
            left[s] += 0.9f
            right[s] -= 0.9f
        }
        val before = left[spots[1]]
        val n = Restore.declick(left, right, sr, 0.6f)
        assertTrue("一处都没检测到", n > 0)
        assertTrue("脉冲没被削平", abs(left[spots[1]]) < abs(before))
    }

    @Test
    fun deEsserTamesHighBandWithoutTouchingLow() {
        val low = sine(200f, 1.5f, 0.3f)
        val hiss = noise(1.5f, 0.05f, seed = 31) // 宽带噪声，高频部分就是"嘶声"
        val n = low.size
        val buf = FloatArray(n) { low[it] + hiss[it] }
        val right = buf.copyOf()
        val lowBefore = Restore.toneAmplitude(buf, sr, 200f)
        Restore.deSibilance(buf, right, sr, 18f)
        val lowAfter = Restore.toneAmplitude(buf, sr, 200f)
        assertTrue("低频被削：${lowAfter / lowBefore}", lowAfter / lowBefore > 0.85f)
    }

    /**
     * 性能闸门：UI 上写着「正在处理」，但用户不会等半分钟。
     * 10 秒素材（约 862 帧、四次 2048 点 FFT/帧）在 JVM 上应当在 1 秒量级；
     * 真机大约 2~3 倍，所以阈值放到 6 秒 —— 一旦有人把"频谱直乘"改回相位重建，这里会立刻炸。
     */
    @Test
    fun tenSecondsIsProcessedInAReasonableTime() {
        val clean = sine(330f, 10f, 0.4f)
        val nz = noise(10f, 0.05f, seed = 77)
        val n = clean.size
        val l = FloatArray(n) { clean[it] + nz[it] }
        val r = l.copyOf()
        val p = SpectralDenoise.profileFrom(nz, nz, sr, "样本")!!
        val t0 = System.currentTimeMillis()
        SpectralDenoise.apply(l, r, sr, p, DenoiseConfig(strength = 70f))
        val ms = System.currentTimeMillis() - t0
        println("10 秒降噪耗时：${ms}ms")
        assertTrue("降噪太慢：${ms}ms", ms < 6000)
    }
}
