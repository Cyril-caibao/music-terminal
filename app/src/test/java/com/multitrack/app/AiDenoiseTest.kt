package com.multitrack.app

import com.multitrack.app.audio.AiDenoise
import com.multitrack.app.audio.SpectralDenoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * AI 降噪（人声 / 伴奏）的硬指标。
 *
 * 这一版没有预训练权重（见 [AiDenoise] 顶部说明），所以这里钉的不是"像不像 DFN3"，
 * 而是三条不能破的底线：
 *
 * 1. **噪声必须真的下去**，不管是宽带白噪还是高频嘶声；
 * 2. **有用的东西必须留住** —— 人声主区的音、伴奏的贝斯基频；
 * 3. **不能把人声/乐音当成噪声吃掉**（持续长音被自己噪声谱吃掉是这类算法的经典事故）。
 *
 * 素材刻意做成「断续的音 + 稳态噪声」：真实人声/乐器本来就有气口，
 * 全段连续的长音在统计上本来就和噪声不可分（DFN3 靠权重先验，我们靠音高保护）。
 */
class AiDenoiseTest {

    private val sr = 44100

    private fun noise(seconds: Float, amp: Float, seed: Int = 5): FloatArray {
        val n = (sr * seconds).toInt()
        val rnd = Random(seed)
        return FloatArray(n) { (rnd.nextFloat() * 2f - 1f) * amp }
    }

    /** 断续的音（模拟说话/拉弦）：on 一段时间、停一段时间 */
    private fun bursts(freq: Float, seconds: Float, amp: Float, onMs: Int = 240, offMs: Int = 160): FloatArray {
        val n = (sr * seconds).toInt()
        val on = sr * onMs / 1000
        val period = on + sr * offMs / 1000
        return FloatArray(n) { i ->
            if (i % period < on) amp * sin(2.0 * PI * freq * i / sr).toFloat() else 0f
        }
    }

    /** 连续的音（持续低音 / 长音） */
    private fun steady(freq: Float, seconds: Float, amp: Float): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n) { i -> amp * sin(2.0 * PI * freq * i / sr).toFloat() }
    }

    private fun mix(a: FloatArray, b: FloatArray): FloatArray {
        val n = minOf(a.size, b.size)
        return FloatArray(n) { a[it] + b[it] }
    }

    private fun copy(l: FloatArray) = l.copyOf()

    private fun hasNaN(l: FloatArray) = l.any { it.isNaN() || it.isInfinite() }

    @Test
    fun vocalDropsBroadbandNoiseAndKeepsTheVoiceBand() {
        val voice = bursts(320f, 3f, 0.35f)
        val dry = mix(voice, noise(3f, 0.05f))
        val left = copy(dry)
        val right = copy(dry)
        val before = AiDenoise.highBandNoise(dry, sr)

        val r = AiDenoise.process(left, right, sr, AiDenoise.Model.VOCAL, AiDenoise.Config(strength = 70f))

        val after = AiDenoise.highBandNoise(left, sr)
        val dropDb = 20.0 * kotlin.math.log10(before / after.coerceAtLeast(1e-9))
        assertTrue("高频噪声只降了 %.1f dB".format(dropDb), dropDb > 5.0)

        val keep = AiDenoise.toneAmplitude(left, sr, 320f) / AiDenoise.toneAmplitude(dry, sr, 320f)
        println("人声档：高频噪声 %.1f dB ↓，主音保留 %.0f%%，%d 帧".format(dropDb, keep * 100, r.frames))
        assertTrue("主音只剩 %.0f%%".format(keep * 100), keep > 0.55)
        assertTrue("输出有 NaN", !hasNaN(left))
        assertTrue("没跑起来", r.frames > 100)
    }

    @Test
    fun musicKeepsTheBassVocalDoesNot() {
        val bass = steady(55f, 2.5f, 0.32f)
        val dry = mix(bass, noise(2.5f, 0.05f, seed = 9))

        val musicL = copy(dry)
        AiDenoise.process(musicL, copy(dry), sr, AiDenoise.Model.MUSIC, AiDenoise.Config(strength = 70f))
        val musicKeep = AiDenoise.toneAmplitude(musicL, sr, 55f) / AiDenoise.toneAmplitude(dry, sr, 55f)

        val vocalL = copy(dry)
        AiDenoise.process(vocalL, copy(dry), sr, AiDenoise.Model.VOCAL, AiDenoise.Config(strength = 70f))
        val vocalKeep = AiDenoise.toneAmplitude(vocalL, sr, 55f) / AiDenoise.toneAmplitude(dry, sr, 55f)

        println("55Hz 基频：伴奏档留 %.0f%%，人声档留 %.0f%%".format(musicKeep * 100, vocalKeep * 100))
        assertTrue("伴奏档把贝斯基频也削了：只剩 %.0f%%".format(musicKeep * 100), musicKeep > 0.55)
        assertTrue("人声档本该压掉 55Hz，却留下 %.0f%%".format(vocalKeep * 100), vocalKeep < musicKeep)
    }

    @Test
    fun sustainedToneIsNotEatenByItsOwnNoiseFloor() {
        // 整段只有一个持续长音 + 一点底噪：噪声谱不能把长音算成噪声
        val tone = steady(440f, 2f, 0.3f)
        val dry = mix(tone, noise(2f, 0.03f, seed = 21))
        val left = copy(dry)
        AiDenoise.process(left, copy(dry), sr, AiDenoise.Model.MUSIC, AiDenoise.Config(strength = 80f))
        val keep = AiDenoise.toneAmplitude(left, sr, 440f) / AiDenoise.toneAmplitude(dry, sr, 440f)
        println("持续 440Hz 长音（强度 80）：保留 %.0f%%".format(keep * 100))
        assertTrue("持续长音被吃掉了：只剩 %.0f%%".format(keep * 100), keep > 0.5)
    }

    @Test
    fun strengthZeroChangesNothing() {
        val dry = mix(bursts(300f, 1f, 0.3f), noise(1f, 0.05f))
        val left = copy(dry)
        val right = copy(dry)
        val r = AiDenoise.process(left, right, sr, AiDenoise.Model.VOCAL, AiDenoise.Config(strength = 0f))
        for (i in dry.indices) {
            assertEquals("强度 0 却改了第 $i 个采样", dry[i], left[i], 1e-6f)
        }
        assertEquals(0, r.frames)
    }

    @Test
    fun strongerStrengthIsCleaner() {
        val dry = mix(bursts(300f, 2f, 0.3f), noise(2f, 0.06f, seed = 33))
        val soft = copy(dry)
        AiDenoise.process(soft, copy(dry), sr, AiDenoise.Model.VOCAL, AiDenoise.Config(strength = 25f))
        val hard = copy(dry)
        AiDenoise.process(hard, copy(dry), sr, AiDenoise.Model.VOCAL, AiDenoise.Config(strength = 90f))

        val softNoise = AiDenoise.highBandNoise(soft, sr)
        val hardNoise = AiDenoise.highBandNoise(hard, sr)
        println("强度 25 高频残留 %.5f，强度 90 高频残留 %.5f".format(softNoise, hardNoise))
        assertTrue(
            "强度 25 (%.4f) 和 90 (%.4f) 差别不对".format(softNoise, hardNoise),
            hardNoise < softNoise * 0.9
        )
    }

    @Test
    fun digitalSilenceStaysSilent() {
        val left = FloatArray(sr) { 0f }
        val right = FloatArray(sr) { 0f }
        val r = AiDenoise.process(left, right, sr, AiDenoise.Model.VOCAL)
        assertTrue("纯静音不该处理", r.frames == 0)
        assertTrue(left.all { it == 0f })
    }

    @Test
    fun capturedNoiseProfileIsReused() {
        // 前 0.8 秒只有噪声：捕捉成样本，再用它去处理后面「人声 + 噪声」
        val pureNoise = noise(0.8f, 0.05f, seed = 41)
        val profile = SpectralDenoise.profileFrom(pureNoise, pureNoise, sr, "测试噪声")
        assertTrue("样本没捕捉成功", profile != null)

        val dry = mix(bursts(320f, 2f, 0.3f), noise(2f, 0.05f, seed = 42))
        val left = copy(dry)
        val r = AiDenoise.process(
            left, copy(dry), sr, AiDenoise.Model.VOCAL,
            AiDenoise.Config(strength = 70f), profile
        )
        assertTrue(r.frames > 100)
        val keep = AiDenoise.toneAmplitude(left, sr, 320f) / AiDenoise.toneAmplitude(dry, sr, 320f)
        assertTrue("用了样本反而把人声吃掉：%.0f%%".format(keep * 100), keep > 0.45)
    }

    @Test
    fun bandMapIsMonotonicAndInRange() {
        val bands = 32
        val map = AiDenoise.bandMap(sr, 1025, bands)
        assertEquals(1025, map.size)
        assertTrue(map.all { it in 0 until bands })
        for (i in 1 until map.size) {
            assertTrue("bin $i 的频带比前一个还小", map[i] >= map[i - 1])
        }
        assertEquals("直流/超低频应该落在第 0 带", 0, map[0])
        assertEquals("奈奎斯特应该落在最后一带", bands - 1, map[1024])
    }

    @Test
    fun tenSecondsIsFastEnough() {
        val dry = mix(bursts(300f, 10f, 0.3f), noise(10f, 0.05f, seed = 51))
        val t0 = System.currentTimeMillis()
        AiDenoise.process(copy(dry), copy(dry), sr, AiDenoise.Model.MUSIC)
        val ms = System.currentTimeMillis() - t0
        println("AI 降噪 10 秒立体声用时 ${ms}ms")
        // 4 分钟整曲大约是这个数的 24 倍；这里卡住"别慢到要用分钟算"
        assertTrue("10 秒素材跑了 ${ms}ms，太慢", ms < 12_000)
    }

    @Test
    fun bothModelsStayFiniteOnSilenceAndNoise() {
        val ramp = FloatArray(sr) { i -> (i.toFloat() / sr) * 0.5f }
        for (m in AiDenoise.Model.entries) {
            val l = ramp.copyOf()
            val r = ramp.copyOf()
            AiDenoise.process(l, r, sr, m, AiDenoise.Config(strength = 100f))
            assertTrue("$m 输出 NaN", !hasNaN(l) && !hasNaN(r))
            for (v in l) assertTrue("幅度越界：$v", abs(v) < 4f)
        }
    }
}
