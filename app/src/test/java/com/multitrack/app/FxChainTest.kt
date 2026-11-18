package com.multitrack.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.multitrack.app.player.playback.AudioFx
import com.multitrack.app.player.playback.FxAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 音效链的行为验收。
 *
 * 这几条正是用户反馈的那几个毛病：
 * - 加超低重音 / 大幅拉均衡器，整首歌会变小声（系统 BassBoost 的自动增益补偿 + 16bit 截断）；
 * - 想要「推爆就破音」，而不是被悄悄压回去。
 *
 * 注意输出是 16bit（AudioTrack 的 float 通道在部分机型上会让播放服务起不来），
 * 所以真正的过载表现为**硬削波到 ±1**，而不是数字跑到 2.0 —— 听感上就是破音，
 * 但关键是「别的频段不会被一起压小」，这才是用户抱怨的点。
 */
class FxChainTest {

    private val sr = 44_100

    /** 把输入喂给处理器，返回解交织后的 float 输出 */
    private fun process(
        samples: FloatArray,
        blockFrames: Int = 0,
        prepare: () -> Unit = {}
    ): FloatArray {
        AudioFx.resetAll()
        prepare()
        val p = FxAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        p.flush()

        val totalFrames = samples.size / 2
        val chunk = if (blockFrames > 0) blockFrames else totalFrames
        val result = ArrayList<Float>(samples.size)
        var pos = 0
        while (pos < totalFrames) {
            val n = minOf(chunk, totalFrames - pos)
            val bytes = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until n * 2) {
                bytes.putShort((samples[pos * 2 + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            bytes.flip()
            p.queueInput(bytes)
            val out = p.output
            out.order(ByteOrder.nativeOrder())
            while (out.remaining() >= 2) result.add(out.short / 32768f)
            pos += n
        }
        p.queueEndOfStream()
        return result.toFloatArray()
    }

    private fun tone(freq: Double, amp: Float, frames: Int): FloatArray {
        val a = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (amp * sin(2.0 * PI * freq * i / sr)).toFloat()
            a[i * 2] = v
            a[i * 2 + 1] = v
        }
        return a
    }

    private fun peak(a: FloatArray, skipFrames: Int = 0): Float {
        var m = 0f
        for (i in skipFrames * 2 until a.size) if (abs(a[i]) > m) m = abs(a[i])
        return m
    }

    @Test
    fun flatChainIsTransparent() {
        val input = tone(1000.0, 0.4f, 8192)
        val out = process(input)
        val pIn = peak(input, 512)
        val pOut = peak(out, 512)
        assertTrue("默认参数应逐样本透明：in=$pIn out=$pOut", abs(pOut - pIn) < 0.005f)
    }

    @Test
    fun bassBoostNeverPullsTheWholeMixDown() {
        // 80Hz 落在低架通带里：60% 超低重音（约 +10.8dB）之后从 0.25 推到 0.87。
        // 旧实现（系统 BassBoost）在这里反而会整体变小。
        val input = tone(80.0, 0.25f, 16_384)
        val base = peak(process(input), 2048)
        val boosted = peak(process(input) { AudioFx.setBass(600) }, 2048)
        assertTrue(
            "超低重音应该把低频推上去：base=$base boosted=$boosted",
            boosted > base * 1.9f
        )
    }

    @Test
    fun heavyEqClipsInsteadOfDuckingEverything() {
        // 1kHz 推 +12dB：0.5 → 2.0，落地到 16bit 就是削到 1.0（破音）。
        val hot = process(tone(1000.0, 0.5f, 16_384)) { AudioFx.setBand(5, 1200) }
        assertTrue("大幅拉均衡器应当削波（破音），实际峰值 ${peak(hot, 2048)}", peak(hot, 2048) > 0.97f)

        // 关键：同一次设置下，别的频段不能被一起压小。
        // 200Hz 不在 1kHz 那一段的峰里，应该原样保留。
        val other = process(tone(200.0, 0.4f, 16_384)) { AudioFx.setBand(5, 1200) }
        val p = peak(other, 2048)
        assertTrue("拉 1kHz 不该把 200Hz 也压小，实际 $p", p > 0.38f)
    }

    @Test
    fun outputGainIsPlainMultiplication() {
        val input = tone(1000.0, 0.3f, 8192)
        val out = process(input) { AudioFx.setOutputGain(12f) }
        val p = peak(out, 1024)
        // 0.3 × 3.98 ≈ 1.19 → 16bit 削到 1.0
        assertTrue("+12dB 应把 0.3 推到顶（削波），实际 $p", p > 0.97f)
    }

    @Test
    fun negativeGainStillWorks() {
        val input = tone(1000.0, 0.5f, 8192)
        val out = process(input) { AudioFx.setOutputGain(-6f) }
        val p = peak(out, 1024)
        assertTrue("-6dB 应减半，实际 $p", p > 0.22f && p < 0.28f)
    }

    /**
     * 分块喂入（模拟 AudioSink 每 1024 帧来一块）：输出的样本数必须和输入严格相等，
     * 否则播放器会认为处理器吃掉了数据 / 无限缓冲，最后就是「放不了」。
     */
    @Test
    fun blockProcessingKeepsSampleCountExact() {
        val frames = 10_000
        val input = tone(440.0, 0.5f, frames)
        val out = process(input, blockFrames = 1024)
        assertEquals("分块处理必须一进一出", frames * 2, out.size)
    }

    /** 空块不该产生输出，也不该抛 */
    @Test
    fun emptyInputIsHarmless() {
        val p = FxAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.queueInput(ByteBuffer.allocateDirect(0))
        assertEquals(0, p.output.remaining())
    }
}
