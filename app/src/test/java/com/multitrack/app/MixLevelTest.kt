package com.multitrack.app

import com.multitrack.app.audio.AudioBufferStore
import com.multitrack.app.audio.AudioEngine
import com.multitrack.app.audio.PcmBuffer
import com.multitrack.app.audio.panBalance
import com.multitrack.app.audio.pcm16
import com.multitrack.app.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * 总线增益校准（3.51）。
 *
 * 用户的原话是「电平条不准，同一首歌在 AU 中会超载」。查下来不是表画错了，
 * 是**进表的信号本身被白扣了 6dB**：音轨 pan 和片段 pan 各乘一次等功率系数，
 * 而两处默认都在正中 —— 0.7071 × 0.7071 = 0.5。
 * 于是 0dBFS 的素材在 App 里只到 -6.02dB，AU 一看是满幅，表看是 -6，怎么都对不上。
 *
 * 这组断言把「0dBFS 进 = 0dBFS 出」钉住：以后谁再把 pan 法则改回去，这里立刻红。
 */
class MixLevelTest {

    private fun reset() {
        Project.clearAll()
        AudioBufferStore.clear()
    }

    private fun tone(n: Int, amp: Float, sr: Int = 44100): FloatArray =
        FloatArray(n) { i -> (amp * sin(2.0 * PI * 997.0 * i / sr)).toFloat() }

    private fun peak(a: FloatArray): Float = a.maxOf { abs(it) }

    private fun db(v: Float): Float = if (v <= 1e-6f) -120f else (20.0 * kotlin.math.log10(v.toDouble())).toFloat()

    /** 建一条轨、放一个满幅立体声片段，返回离线混音结果 */
    private fun mixOneClip(amp: Float, n: Int = 44100, trackPan: Float = 0f, clipPan: Float = 0f): AudioEngine.MixResult {
        reset()
        val l = tone(n, amp)
        val r = tone(n, amp)
        AudioBufferStore.put("tone", PcmBuffer(44100, 2, l, r, n))
        val track = Project.addTrack()
        assertNotNull("建轨失败", track)
        val clip = Project.addClip(track!!, "tone", "tone", n, 0, "#fff")
        track.pan = trackPan
        clip.pan = clipPan
        val mix = AudioEngine().renderMix()
        assertNotNull("renderMix 返回 null", mix)
        return mix!!
    }

    @Test
    fun fullScaleStereoClipKeepsItsLevelThroughTheBus() {
        // 0.99 ≈ -0.09dBFS，也就是一张正常母带的峰值
        val mix = mixOneClip(0.99f)
        val pk = max(peak(mix.left), peak(mix.right))
        println("总线峰值 ${"%.4f".format(pk)} = ${"%.2f".format(db(pk))} dBFS（源 0.9900）")
        assertEquals(
            "0dBFS 的素材进了总线变成 ${"%.2f".format(db(pk))} dBFS —— 又是 pan 叠加扣了增益",
            0.99, pk.toDouble(), 0.01
        )
    }

    @Test
    fun centerPanIsUnityOnBothChannels() {
        val g = panBalance(0f)
        assertEquals("正中时左声道不是 1.0", 1f, g.l, 1e-6f)
        assertEquals("正中时右声道不是 1.0", 1f, g.r, 1e-6f)
        println("pan=0 → L ${g.l} / R ${g.r}（以前是 0.7071 / 0.7071，两层相乘 = 0.5）")
    }

    @Test
    fun panOnlyTakesAwayFromTheOppositeSide() {
        val left = panBalance(-1f)
        assertEquals(1f, left.l, 1e-6f)
        assertEquals("硬左时右声道没关掉", 0f, left.r, 1e-6f)
        val half = panBalance(0.5f)
        assertEquals(0.5f, half.l, 1e-6f)
        assertEquals("往右推时右声道应该保持 1.0，而不是被抬到 1.414", 1f, half.r, 1e-6f)
    }

    @Test
    fun panningHardLeftKeepsTheSourcePeakOnThatSide() {
        val mix = mixOneClip(0.99f, trackPan = -1f)
        val l = peak(mix.left)
        val r = peak(mix.right)
        println("硬左：L ${"%.4f".format(l)} / R ${"%.4f".format(r)}")
        assertEquals("硬左时左声道不该掉电平", 0.99, l.toDouble(), 0.01)
        assertEquals("硬左时右声道应该安静", 0.0, r.toDouble(), 1e-3)
    }

    @Test
    fun clipPanAndTrackPanAreCombinedNotMultipliedTwice() {
        // 音轨 +0.5、片段 0：合成后等效 +0.5，左 0.5 / 右 1.0 —— 不是 0.5×1 再乘一层
        val mix = mixOneClip(0.99f, trackPan = 0.5f)
        val l = peak(mix.left)
        val r = peak(mix.right)
        println("轨 +0.5：L ${"%.4f".format(l)} / R ${"%.4f".format(r)}")
        assertEquals("右声道应保持 1.0 倍", 0.99, r.toDouble(), 0.01)
        assertEquals("左声道应为 0.5 倍", 0.495, l.toDouble(), 0.01)
    }

    @Test
    fun pcm16ClampsInsteadOfWrappingAround() {
        // 溢出翻转：1.2×32767 = 39320 → toShort() 变 -26216，听感是一声爆响，
        // 在 AU 里看是满幅方波 —— 导出文件"超载"的另一条来路。
        assertEquals(32767, pcm16(1.2f).toInt())
        assertEquals(-32767, pcm16(-1.2f).toInt())
        assertEquals(0, pcm16(0f).toInt())
        assertEquals(16384, pcm16(0.5f).toInt())
    }

    @Test
    fun meterAttacksInstantlyAndFallsAtAFixedRate() {
        // 起振：一个采样也不许漏（以前界面自己再平滑一次，就是"慢半拍"）
        assertEquals("起振没跟上", 0f, AudioEngine.meterStep(-40f, 0f, 0.023f), 1e-4f)
        // 回落：22dB/秒 × 23ms ≈ 0.51dB
        val fall = AudioEngine.meterStep(0f, -60f, 0.023f)
        println("一块（23ms）回落 ${"%.3f".format(fall)} dB")
        assertEquals(-0.506f, fall, 0.02f)
        // 已经低于 -60 就停在表底，不会无限往下掉
        assertEquals(-60f, AudioEngine.meterStep(-59.9f, -80f, 0.023f), 1e-3f)
    }
}
