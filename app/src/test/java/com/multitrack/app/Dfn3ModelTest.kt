package com.multitrack.app

import com.multitrack.app.audio.dfn3.BluesteinFft
import com.multitrack.app.audio.dfn3.Dfn3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * DeepFilterNet3 **真权重**的集成测试。
 *
 * 加载的是 `app/src/main/assets/dfn3/` 那三个官方 ONNX 图（7.6MB，v0.5.6，
 * sha256 见 [Dfn3] 顶部），走完整条链路：Bluestein 960 点 STFT → Vorbis 窗 → ERB/复数特征
 * → enc → erb_dec → df_dec → 掩码 + 5 阶深滤波 → 逆变换重叠相加。
 *
 * ## 怎么证明"接的是对的"
 *
 * 光"跑通不报错"没有意义 —— 特征接错了照样能跑出一个静音。所以这里做 A/B：
 * `src/test/resources/dfn3/` 下有两份 3 秒 48kHz 素材：
 *
 * - `noisy48k.wav`：合成素材（谐波嗡鸣 + 200Hz→8kHz 扫频 + 断续脉冲 + 白噪），
 * - `reference48k.wav`：**同一份输入喂给官方 `deep-filter` 0.5.6（Windows 版，`-D` 补偿延迟）**
 *   得到的输出。
 *
 * 然后拿本实现的输出和官方输出做逐样本相关。前端只要有一处没对上（窗、归一化、ERB 分带、
 * 复数特征布局），相关度立刻塌到 0.2 以下 —— 这是这条链路唯一靠得住的验收标准。
 *
 * 复现基线：
 * ```
 * deep-filter.exe -D -o out noisy48k.wav
 * ```
 */
class Dfn3ModelTest {

    private fun assets(): File {
        val dir = File("src/main/assets/dfn3")
        assertTrue("找不到模型目录：${dir.absolutePath}", dir.isDirectory)
        return dir
    }

    private fun load(name: String): ByteArray = File(assets(), name).readBytes()

    private class Wav(val sampleRate: Int, val mono: FloatArray)

    private fun readWav(path: String): Wav {
        val bytes = File(path).readBytes()
        var i = 12
        var sr = 48000
        var ch = 1
        var bits = 16
        var data: ByteArray? = null
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "fmt ") {
                val bb = ByteBuffer.wrap(bytes, i + 8, 16).order(ByteOrder.LITTLE_ENDIAN)
                bb.short // format
                ch = bb.short.toInt()
                sr = bb.int
                bb.int
                bb.short
                bits = bb.short.toInt()
            } else if (id == "data") {
                data = bytes.copyOfRange(i + 8, minOf(i + 8 + size, bytes.size))
            }
            i += 8 + size + (size and 1)
        }
        val d = data ?: error("WAV 没有 data 块：$path")
        assertEquals("只支持 16bit", 16, bits)
        val frames = d.size / (2 * ch)
        val out = FloatArray(frames)
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until frames) {
            var acc = 0f
            for (c in 0 until ch) acc += bb.short.toFloat() / 32768f
            out[k] = acc / ch
        }
        return Wav(sr, out)
    }

    private fun tone(buf: FloatArray, sr: Int, f: Float): Double {
        val n = minOf(buf.size, sr)
        val w = 2.0 * PI * f / sr
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            re += buf[i] * cos(w * i)
            im += buf[i] * sin(w * i)
        }
        return 2.0 * sqrt(re * re + im * im) / n
    }

    private fun rms(buf: FloatArray): Double {
        var s = 0.0
        for (v in buf) s += v.toDouble() * v
        return sqrt(s / buf.size)
    }

    /** 归一化互相关（去掉均值），[shift] 表示 b 相对 a 的偏移 */
    private fun correlation(a: FloatArray, b: FloatArray, shift: Int, from: Int, count: Int): Double {
        var num = 0.0
        var da = 0.0
        var db = 0.0
        var n = 0
        for (k in from until from + count) {
            val j = k + shift
            if (k !in a.indices || j !in b.indices) continue
            num += a[k].toDouble() * b[j]
            da += a[k].toDouble() * a[k]
            db += b[j].toDouble() * b[j]
            n++
        }
        if (n < 100) return 0.0
        val den = sqrt(da * db)
        return if (den < 1e-12) 0.0 else num / den
    }

    @Test
    fun bluesteinRoundTripsAt960() {
        val n = 960
        val fft = BluesteinFft(n)
        val rnd = Random(3)
        val re = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val im = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val origRe = re.copyOf()
        val origIm = im.copyOf()
        fft.forward(re, im)
        fft.inverse(re, im)
        var maxErr = 0f
        for (i in 0 until n) maxErr = maxOf(maxErr, abs(re[i] - origRe[i]), abs(im[i] - origIm[i]))
        assertTrue("960 点正逆变换没还原，最大误差 $maxErr", maxErr < 1e-4f)
    }

    @Test
    fun modelLoads() {
        val ok = Dfn3.prepare { load(it) }
        assertTrue("模型没加载起来：${Dfn3.lastError}", ok)
        assertTrue(Dfn3.ready)
        println("DFN3 就绪：enc/erb_dec/df_dec 三个图全部加载")
    }

    /**
     * 与官方 `deep-filter` 逐样本对比 —— 这是整个前端（窗函数、归一化、ERB 分带、
     * 复数特征布局、深滤波算子、重叠相加）是否与原版一致的唯一硬证据。
     */
    @Test
    fun matchesTheOfficialImplementation() {
        assertTrue("模型没加载起来：${Dfn3.lastError}", Dfn3.prepare { load(it) })
        val dry = readWav("src/test/resources/dfn3/speech48k.wav")
        val ref = readWav("src/test/resources/dfn3/speech_ref48k.wav")
        assertEquals("素材采样率应为 48k", 48000, dry.sampleRate)

        val left = dry.mono.copyOf()
        val right = dry.mono.copyOf()
        val t0 = System.currentTimeMillis()
        val stats = Dfn3.processInPlace(left, right, dry.sampleRate)
        val ms = System.currentTimeMillis() - t0

        // 官方 -D 会补偿延迟，两边长度差 1440 样本（30ms）；先在小窗口里粗搜偏移再细化
        val probe = 8192
        val mid = minOf(60000, dry.mono.size - probe - 1)
        var bestShift = 0
        var bestCorr = -1.0
        var sh = -2400
        while (sh <= 2400) {
            val c = correlation(left, ref.mono, sh, mid, probe)
            if (c > bestCorr) { bestCorr = c; bestShift = sh }
            sh += 4
        }
        var fine = bestShift - 4
        while (fine <= bestShift + 4) {
            val c = correlation(left, ref.mono, fine, mid, probe)
            if (c > bestCorr) { bestCorr = c; bestShift = fine }
            fine++
        }

        val whole = minOf(left.size, ref.mono.size) - bestShift
        val corr = correlation(left, ref.mono, bestShift, 2400, whole - 2400)
        val levelRatio = rms(left) / rms(ref.mono).coerceAtLeast(1e-9)
        val highDry = rms(dry.mono)
        println(
            "DFN3 3s 立体声 ${ms}ms · ${stats.frames} 帧 · 掩码平均 ${"%.1f".format(stats.averageDropDb)}dB · " +
                "与官方相关 ${"%.3f".format(corr)}（偏移 $bestShift 样本）· 电平比 ${"%.2f".format(levelRatio)} · " +
                "整体 rms ${"%.3f".format(highDry)} → ${"%.3f".format(rms(left))}"
        )

        assertTrue("和官方实现差太远：相关只有 ${"%.3f".format(corr)}（前端某处没对上）", corr > 0.85)
        assertTrue("电平偏离官方太多：×${"%.2f".format(levelRatio)}", levelRatio > 0.6 && levelRatio < 1.6)
        assertTrue("输出有 NaN / Inf", left.all { it.isFinite() })
        assertTrue("一帧都没跑", stats.frames > 250)
    }

    @Test
    fun resamplesAndKeepsLength() {
        assertTrue("模型没加载起来：${Dfn3.lastError}", Dfn3.prepare { load(it) })
        val n = 44100
        val dry = FloatArray(n) { i -> 0.3f * sin(2.0 * PI * 440.0 * i / 44100).toFloat() }
        val left = dry.copyOf()
        val right = dry.copyOf()
        Dfn3.processInPlace(left, right, 44100)
        assertEquals("长度变了", n, left.size)
        assertTrue("输出有 NaN", left.all { it.isFinite() })
        println("44.1kHz 素材进出重采样正常，rms ${"%.4f".format(rms(left))}")
    }

    @Test
    fun silenceInSilenceOut() {
        assertTrue("模型没加载起来：${Dfn3.lastError}", Dfn3.prepare { load(it) })
        val left = FloatArray(48000) { 0f }
        val right = FloatArray(48000) { 0f }
        Dfn3.processInPlace(left, right, 48000)
        assertTrue("静音输出了东西", left.all { abs(it) < 1e-6f })
    }

    @Test
    fun longInputHasNoSeamDropout() {
        assertTrue("模型没加载起来：${Dfn3.lastError}", Dfn3.prepare { load(it) })
        // 25 秒 → 会切成两段（分段长度 20 秒）。段缝不能塌电平、不能有咔的一声。
        val n = 48000 * 25
        val rnd = Random(17)
        val dry = FloatArray(n) { i ->
            val t = i / 48000.0
            val tone = 0.25 * sin(2.0 * PI * 220.0 * i / 48000)
            val gate = if ((t * 4).toInt() % 2 == 0) 1.0 else 0.15
            (tone * gate + (rnd.nextFloat() * 2f - 1f) * 0.04).toFloat()
        }
        val left = dry.copyOf()
        val right = dry.copyOf()
        val t0 = System.currentTimeMillis()
        val stats = Dfn3.processInPlace(left, right, 48000)
        val ms = System.currentTimeMillis() - t0

        fun rmsAt(fromSec: Int, lenSec: Int): Double {
            var s = 0.0
            val a = fromSec * 48000
            for (i in a until minOf(a + lenSec * 48000, left.size)) s += left[i].toDouble() * left[i]
            return sqrt(s / (lenSec * 48000))
        }

        // 段缝落在 2000 帧 × 480 跳步 = 960000 采样 = 正好 20 秒。
        // 对照点取 10 秒处：同一段内部、门控相位与段缝完全一致，所以两边的"正常电平"
        // 本来就该一样——只要段缝处塌下去或者冒出咔哒，比值立刻偏离 1。
        val seamIdx = 20 * 48000
        val ctrlIdx = 10 * 48000

        fun meanAbsAt(center: Int, half: Int): Double {
            var s = 0.0
            var c = 0
            for (i in (center - half).coerceAtLeast(0) until (center + half).coerceAtMost(left.size)) {
                s += abs(left[i].toDouble()); c++
            }
            return if (c == 0) 0.0 else s / c
        }

        /** 该点 ±248 采样内最大的逐采样跳变，用来抓"咔"的一声 */
        fun maxStepAt(center: Int): Float {
            var m = 0f
            for (i in (center - 248).coerceAtLeast(1) until (center + 248).coerceAtMost(left.size)) {
                m = maxOf(m, abs(left[i] - left[i - 1]))
            }
            return m
        }

        val seamMean = meanAbsAt(seamIdx, 24000)
        val ctrlMean = meanAbsAt(ctrlIdx, 24000)
        val seamStep = maxStepAt(seamIdx)
        val ctrlStep = maxStepAt(ctrlIdx)
        val seam = rmsAt(19, 2)
        val peak = left.maxOf { abs(it) }
        // 全文件扫一遍：分段/分块如果有硬切，一定留下一个远超正常值的逐采样跳变
        var globalStep = 0f
        for (i in 1 until left.size) {
            val d = abs(left[i] - left[i - 1])
            if (d > globalStep) globalStep = d
        }
        println(
            "DFN3 25s 立体声 ${ms}ms · ${stats.frames} 帧 · 段缝/段内 电平比 " +
                "${"%.2f".format(seamMean / ctrlMean)}（${"%.5f".format(seamMean)} vs ${"%.5f".format(ctrlMean)}）· " +
                "跳变比 ${"%.2f".format(seamStep / ctrlStep)}（${"%.5f".format(seamStep)} vs " +
                "${"%.5f".format(ctrlStep)}）· 全文件最大跳变 ${"%.5f".format(globalStep)} · " +
                "19-21s rms ${"%.4f".format(seam)} · 峰值 ${"%.2f".format(peak)}"
        )
        assertTrue(
            "段缝处电平塌了：${"%.5f".format(seamMean)} vs 段内 ${"%.5f".format(ctrlMean)}",
            seamMean > ctrlMean * 0.45
        )
        assertTrue(
            "段缝处电平爆了：${"%.5f".format(seamMean)} vs 段内 ${"%.5f".format(ctrlMean)}",
            seamMean < ctrlMean * 2.5 + 0.01
        )
        assertTrue(
            "段缝处有咔哒声：跳变 ${"%.5f".format(seamStep)} vs 段内 ${"%.5f".format(ctrlStep)}",
            seamStep <= ctrlStep * 1.5f + 1e-4f
        )
        // 这条兜的是"没在段缝、但在别处硬切"的情况：整条波形不许有单采样突跳
        assertTrue("波形里有点击声：最大跳变 ${"%.5f".format(globalStep)}", globalStep < 0.02f)
        assertTrue("输出爆音：峰值 $peak", peak < 2.0f)
        assertTrue("输出有 NaN", left.all { it.isFinite() })
        assertTrue("一帧都没跑", stats.frames > 2000)
    }

    @Test
    fun denoiseIsSlowButBearable() {
        assertTrue("模型没加载起来：${Dfn3.lastError}", Dfn3.prepare { load(it) })
        // 1 秒立体声耗时（手机上大概是这里的 2~4 倍，用来提醒用户这是个"跑一会儿"的效果）
        val dry = FloatArray(48000) { i -> 0.2f * sin(2.0 * PI * 300.0 * i / 48000).toFloat() }
        val left = dry.copyOf()
        val right = dry.copyOf()
        val t0 = System.currentTimeMillis()
        Dfn3.processInPlace(left, right, 48000)
        val ms = System.currentTimeMillis() - t0
        println("DFN3 1s 立体声 ${ms}ms（桌面 CPU）")
        assertTrue("慢得离谱：${ms}ms/秒", ms < 30_000)
        assertTrue("电平异常：${"%.3f".format(rms(left))}", rms(left) < 1.0)
    }

    @Test
    fun levelDoesNotExplodeOnLoudInput() {
        assertTrue("模型没加载起来：${Dfn3.lastError}", Dfn3.prepare { load(it) })
        val rnd = Random(9)
        val dry = FloatArray(48000) { (rnd.nextFloat() * 2f - 1f) * 0.95f }
        val left = dry.copyOf()
        val right = dry.copyOf()
        Dfn3.processInPlace(left, right, 48000)
        val peak = left.maxOf { abs(it) }
        println("满幅白噪输入：峰值 ${"%.2f".format(peak)}，rms ${"%.4f".format(rms(left))}")
        assertTrue("削顶了：峰值 $peak", peak <= 1.2f)
        assertTrue("输出几乎无声", rms(left) > 1e-4)
        assertTrue("衰减异常：${20 * log10(rms(left) / rms(dry))}dB", rms(left) / rms(dry) > 1e-3)
    }
}
