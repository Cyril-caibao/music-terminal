package com.multitrack.app.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 播放音效链（均衡器 + 超低重音 + 3D 宽度 + 输出增益）。
 *
 * 为什么不用系统 audiofx 的 Equalizer / BassBoost / Virtualizer：
 *
 * 1. 它们跑在 AudioFlinger 的效果链上，处理域是 **16bit 整数**。增益一加，超过 0dBFS 就被硬截断，
 *    为了听起来「不炸」，AOSP 的 BassBoost 内部还带一级自动增益补偿 —— 你越加低频，它把整体越往下压。
 *    这就是「加超低重音，声音整体反而变小」和「大幅调均衡器，整首歌变小声」的根因，在系统效果里改不掉。
 * 2. 设备段数固定（常见 5 段），10 根滑杆只能几根共用一段，大幅调整时相邻段互相拉扯。
 *
 * 这里改成 ExoPlayer 自己的 AudioProcessor：全程 float（32bit 浮点），
 * **不做限幅、不做自动增益补偿、不读回峰值做归一化**。想加多少就加多少，
 * 超过 0dBFS 就让它超 —— 最终 DAC 之前该破音就破音，这是用户明确要的。
 *
 * 混响仍然走系统的 EnvironmentalReverb（它只叠加湿声，不会压整体电平）。
 */
class FxAudioProcessor(
    /**
     * 输出编码。默认 16bit：AudioTrack 的 float 输出在部分设备上会直接初始化失败，
     * 一旦失败整条播放链就废了（表现就是「歌都放不了」），代价太大。
     * 内部依然全程 float 计算，只在最后一步落地到 16bit —— 推过头就是硬削波，
     * 也就是用户要的「破音」，而不是把整体增益稀释掉。
     */
    private val floatOut: Boolean = false
) : BaseAudioProcessor() {

    /** RBJ 双二阶滤波器（Direct Form I），L/R 各一套状态 */
    private class Biquad {
        var b0 = 1f
        var b1 = 0f
        var b2 = 0f
        var a1 = 0f
        var a2 = 0f
        var bypass = true
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }

        fun process(x: Float): Float {
            if (bypass) return x
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun setPeaking(sr: Int, f0: Double, q: Double, gainDb: Double) {
            if (kotlin.math.abs(gainDb) < 0.01 || f0 >= sr * 0.49) {
                bypass = true; b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / sr
            val alpha = sin(w0) / (2.0 * q)
            val cw = cos(w0)
            val nb0 = 1 + alpha * a
            val nb1 = -2 * cw
            val nb2 = 1 - alpha * a
            val na0 = 1 + alpha / a
            val na1 = -2 * cw
            val na2 = 1 - alpha / a
            b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
            a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
            bypass = false
        }

        /** 低架滤波：整段低频一起抬，用来做「超低重音」（线性，不衰减别处） */
        fun setLowShelf(sr: Int, f0: Double, gainDb: Double, s: Double = 1.0) {
            if (kotlin.math.abs(gainDb) < 0.01 || f0 >= sr * 0.49) {
                bypass = true; b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / sr
            val cw = cos(w0)
            val sw = sin(w0)
            val alpha = sw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0)
            val beta = 2.0 * sqrt(a) * alpha
            val nb0 = a * ((a + 1) - (a - 1) * cw + beta)
            val nb1 = 2 * a * ((a - 1) - (a + 1) * cw)
            val nb2 = a * ((a + 1) - (a - 1) * cw - beta)
            val na0 = (a + 1) + (a - 1) * cw + beta
            val na1 = -2 * ((a - 1) + (a + 1) * cw)
            val na2 = (a + 1) + (a - 1) * cw - beta
            b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
            a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
            bypass = false
        }
    }

    private val eqL = Array(10) { Biquad() }
    private val eqR = Array(10) { Biquad() }
    private val shelfL = Biquad()
    private val shelfR = Biquad()
    private var lastVersion = -1
    private var outGain = 1f
    private var sideGain = 1f

    // ---------------- 极限外放的前视限幅 ----------------
    /**
     * 只在「极限外放」打开时工作，关掉时**完全旁路**（不引入延迟、不碰采样）。
     *
     * 做法和 AU 的 Hard Limiter 一样：把信号延迟 [LIM_LOOK] 个采样，同时用单调队列
     * 维护这段窗口里的最大峰值 —— 于是增益可以在峰值到达之前就降下去，
     * 不需要「先削了再补」，听感上是变响而不是变糊。出口再串一级 tanh 软削波，
     * 保证任何情况下都不会超过 ceiling（16bit 出口不会出现方波）。
     */
    private var limiterOn = false
    private var ceiling = 0.89f
    private var releaseCoef = 0.9997f
    private val delayL = FloatArray(LIM_LOOK)
    private val delayR = FloatArray(LIM_LOOK)
    private var delayPos = 0
    /** 单调队列：存的是「候选峰值的全局采样号 + 幅值」 */
    private val qIdx = LongArray(LIM_LOOK + 2)
    private val qVal = FloatArray(LIM_LOOK + 2)
    private var qHead = 0
    private var qTail = 0
    private var sampleIndex = 0L
    private var limGain = 1f
    /** 避免每次分配：限幅后的左右声道暂时放这里 */
    private var limL = 0f
    private var limR = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 输出一律立体声 float。声道数在这里定死 2，处理时把多余声道丢掉。
        val outEncoding = if (floatOut) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT ->
                AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, outEncoding)
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() = resetFilters()

    override fun onReset() = resetFilters()

    private fun resetFilters() {
        for (i in 0 until 10) { eqL[i].reset(); eqR[i].reset() }
        shelfL.reset(); shelfR.reset()
        lastVersion = -1
        resetLimiter()
    }

    private fun resetLimiter() {
        java.util.Arrays.fill(delayL, 0f)
        java.util.Arrays.fill(delayR, 0f)
        delayPos = 0
        qHead = 0
        qTail = 0
        sampleIndex = 0L
        limGain = 1f
    }

    private fun rebuild(snap: AudioFx.Snapshot, sr: Int) {
        for (i in 0 until 10) {
            val f = AudioFx.BAND_HZ[i].toDouble()
            val g = snap.eq[i].toDouble()
            eqL[i].setPeaking(sr, f, Q, g)
            eqR[i].setPeaking(sr, f, Q, g)
        }
        shelfL.setLowShelf(sr, 88.0, snap.bassDb.toDouble())
        shelfR.setLowShelf(sr, 88.0, snap.bassDb.toDouble())
        outGain = 10.0.pow(snap.gainDb / 20.0).toFloat()
        // 0 → 1.0 倍（原样），1000 → 2.4 倍侧信号（很宽，会明显超出 ±1，不拦）
        sideGain = 1f + snap.width * 1.4f

        val wantLimit = snap.limiter
        if (wantLimit != limiterOn) {
            // 打开/关闭的瞬间清一次状态：否则延迟线里的旧采样会「漏」出来
            resetLimiter()
            limiterOn = wantLimit
        }
        if (wantLimit) {
            ceiling = 10.0.pow(snap.ceilingDb / 20.0).toFloat()
            // 释放 150ms：够快跟上下一句，又不会把低频抽气抽成「噗噗」
            releaseCoef = kotlin.math.exp(-1.0 / (sr * 0.15)).toFloat()
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        try {
            processInput(inputBuffer)
        } catch (t: Throwable) {
            // DSP 出任何问题都不该连累播放：原样搬运，最多是「没有音效」
            passthrough(inputBuffer)
        }
    }

    /** 出错时的兜底：输入直接搬到输出，格式照旧 */
    private fun passthrough(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val from = inputBuffer.position()
        val out = replaceOutputBuffer(remaining)
        for (i in 0 until remaining) out.put(inputBuffer.get(from + i))
        out.flip()
        inputBuffer.position(from + remaining)
    }

    private fun processInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val inFmt = inputAudioFormat
        val inCh = inFmt.channelCount.coerceAtLeast(1)
        val bytesPer = if (inFmt.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = remaining / (bytesPer * inCh)
        if (frames <= 0) return

        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(frames * 2 * if (floatOut) 4 else 2)
        out.order(ByteOrder.nativeOrder())

        val snap = AudioFx.snapshot
        if (snap.version != lastVersion) {
            rebuild(snap, inFmt.sampleRate)
            lastVersion = snap.version
        }

        val isFloat = inFmt.encoding == C.ENCODING_PCM_FLOAT
        for (f in 0 until frames) {
            var l: Float
            var r: Float
            if (isFloat) {
                l = inputBuffer.float
                r = if (inCh >= 2) inputBuffer.float else l
            } else {
                l = inputBuffer.short / 32768f
                r = if (inCh >= 2) inputBuffer.short / 32768f else l
            }
            // 多声道输入：多出来的声道读掉丢掉，只保留前两路
            if (inCh > 2) {
                for (c in 2 until inCh) {
                    if (isFloat) inputBuffer.float else inputBuffer.short
                }
            }

            // 均衡 10 段
            for (i in 0 until 10) {
                l = eqL[i].process(l)
                r = eqR[i].process(r)
            }
            // 超低重音（低架，纯线性提升，绝不回落）
            l = shelfL.process(l)
            r = shelfR.process(r)

            // 3D 宽度：M/S 的 S 分量放大
            if (sideGain > 1.0001f) {
                val m = (l + r) * 0.5f
                val s = (l - r) * 0.5f * sideGain
                l = m + s
                r = m - s
            }

            // 输出增益。正常模式下刻意不做 clamp / limiter —— 过载就让它过载。
            // 极限外放时改走「前视限幅 + 软削波」：同样响，但不是把波形削成方波。
            var ol = l * outGain
            var or = r * outGain
            if (limiterOn) {
                limitSample(ol, or)
                ol = limL
                or = limR
            }
            if (floatOut) {
                out.putFloat(ol)
                out.putFloat(or)
            } else {
                // 只有这种没有 float 输出的老设备才不得不截断
                out.putShort((ol.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                out.putShort((or.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }

    /**
     * 一个采样的前视限幅（立体声联动：两声道共用一个增益，否则声像会被拉偏）。
     *
     * 结果写进 [limL] / [limR]，不分配对象（这是音频线程，一秒要跑 4.4 万次）。
     */
    private fun limitSample(l: Float, r: Float) {
        sampleIndex++
        val al = if (l < 0f) -l else l
        val ar = if (r < 0f) -r else r
        val peak = if (al > ar) al else ar

        // 单调队列维护最近 LIM_LOOK 个采样里的最大峰值
        while (qTail > qHead && qVal[qTail - 1] <= peak) qTail--
        if (qTail >= qIdx.size - 1) {
            val len = qTail - qHead
            System.arraycopy(qIdx, qHead, qIdx, 0, len)
            System.arraycopy(qVal, qHead, qVal, 0, len)
            qTail = len
            qHead = 0
        }
        qIdx[qTail] = sampleIndex
        qVal[qTail] = peak
        qTail++
        val oldest = sampleIndex - LIM_LOOK
        while (qHead < qTail && qIdx[qHead] <= oldest) qHead++
        val maxPeak = if (qHead < qTail) qVal[qHead] else peak

        val target = if (maxPeak > ceiling) ceiling / maxPeak else 1f
        // 压下去要立刻（有前视，来得及），抬起来要慢（150ms 释放，听不出抽气）
        limGain = if (target < limGain) target else limGain + (target - limGain) * (1f - releaseCoef)

        // 输出的是 LIM_LOOK 个采样之前的样本：增益在峰值到达前就已经降好
        limL = softClip(delayL[delayPos] * limGain, ceiling)
        limR = softClip(delayR[delayPos] * limGain, ceiling)
        delayL[delayPos] = l
        delayR[delayPos] = r
        delayPos++
        if (delayPos >= LIM_LOOK) delayPos = 0
    }

    /** 软削波：ceiling 的 90% 以下完全线性，之上用 tanh 圆过去，永远不超过 ceiling */
    private fun softClip(x: Float, ceil: Float): Float {
        val a = if (x < 0f) -x else x
        val knee = ceil * 0.9f
        if (a <= knee) return x
        val room = ceil - knee
        if (room <= 1e-6f) return if (x < 0f) -ceil else ceil
        val y = knee + room * tanh((a - knee) / room)
        return if (x < 0f) -y else y
    }

    private companion object {
        /** 10 段都用 1.1 的 Q：相邻段略有交叠，拉一根不会在频谱上留个洞 */
        const val Q = 1.1

        /** 前视 256 采样（44.1k 约 5.8ms）：比这更短就压不住低频鼓点 */
        const val LIM_LOOK = 256
    }
}
