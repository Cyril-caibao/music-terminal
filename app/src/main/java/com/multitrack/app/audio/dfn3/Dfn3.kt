package com.multitrack.app.audio.dfn3

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DeepFilterNet3 的真实推理链路（3.49 新增）。
 *
 * ## 这里跑的是官方权重，不是"仿制品"
 *
 * `assets/dfn3/` 里的三个图来自官方 `DeepFilterNet3_onnx.tar.gz`（tag v0.5.6，
 * 7,983,136 字节，sha256 `c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616`）：
 *
 * | 文件 | 作用 | 输入 → 输出 |
 * |---|---|---|
 * | `enc.onnx` | 编码器 | `feat_erb[1,1,T,32]` + `feat_spec[1,2,T,96]` → `e0..e3`、`emb[1,T,512]`、`c0[1,64,T,96]`、`lsnr` |
 * | `erb_dec.onnx` | ERB 掩码解码 | `emb,e3,e2,e1,e0` → `m[1,1,T,32]`（频带增益） |
 * | `df_dec.onnx` | 深滤波系数 | `emb,c0` → `coefs[1,T,96,10]`（5 阶复数系数） |
 *
 * 深滤波那一步（`coefs` 与频谱的复数卷积）**不在图里**，官方是在 libDF 的 C/Rust 侧做的，
 * 这里按 `modules.py` 的 `spec_pad(...)+复数乘加` 原样实现 —— 见 [applyChunk]。
 *
 * ## 前端必须逐位对上，否则权重再真也没用
 *
 * 模型是对着 libDF 的**特征**训练的，喂别的东西进去只会得到一片噪声。所以这里照抄
 * `libDF/src/lib.rs`：
 *
 * - STFT：`fft_size=960`、`hop=480`、**Vorbis 窗** `w[i]=sin(π/2·sin²(π(i+0.5)/480))`，
 *   分析侧乘 `wnorm = 2·hop/fft²`（= 1/960），合成侧不再归一化（窗本身满足 50% 重叠 COLA）。
 * - 帧 f 覆盖采样点 `[(f-1)·hop, (f+1)·hop)`：libDF 的 analysis_mem 就是这么滑的。
 * - `feat_erb`：频带**平均**功率 → `10·log10(x+1e-10)` → 指数均值归一
 *   （`s=x(1-α)+sα`，`α=0.99`，初值在 32 条带上从 −60 线性走到 −90，再 `(x-s)/40`）。
 * - `feat_spec`：取前 96 个 bin 的**复数**谱，除以 `sqrt(s)`，`s=|x|(1-α)+sα`，
 *   初值 0.001→0.0001 线性分布。
 * - 掩码是 32 带增益，按带内所有 bin 同一个值铺开（`erb_fb(inverse=True)` 就是一堆 0/1）。
 * - `lsnr` 分级用 C API 的默认阈值：< −15dB 判为纯噪声（掩码置 0）、> 35dB 判为干净（原样放行）。
 *
 * ## 为什么是分块 + 重叠余量
 *
 * 一次跑完整首歌不现实：`e0` 是 `[1,64,T,32]`，4 分钟 48k 约 24000 帧 → 光这一个张量就 196MB。
 * 所以按 250 帧（约 2.5 秒）分块，块内前后各留余量：
 *
 * - **头部 26 帧**：给 GRU 暖机。只算不用，输出从暖机之后取，缝上不会有"每 2.5 秒抖一下"。
 * - **尾部 6 帧**：深滤波要往后看 2 帧、逆变换要下一帧才能补齐重叠相加。
 *
 * 分块之间在**采样点**上首尾相接、互不重叠，所以不需要交叉淡化。
 *
 * ## 对不上官方的地方（诚实清单）
 *
 * 1. 官方的流式实现有一个 `df_order-1 = 4` 帧的延迟（输出比输入晚 4 帧）。离线处理不需要这个延迟，
 *    这里直接把掩码作用在当帧频谱上；听感一致，差别只是"对齐"。
 * 2. 44.1kHz 素材会先线性重采样到 48kHz 再回来（模型只认 48kHz），高频会有轻微损失。
 * 3. 立体声是左右各跑一遍（图里 batch 维固定为 1），因此耗时约等于单声道的两倍。
 */
object Dfn3 {

    /** 模型只接受 48kHz */
    const val SAMPLE_RATE = 48000

    private const val FFT_SIZE = 960
    private const val HOP = 480
    private const val BINS = FFT_SIZE / 2 + 1          // 481
    private const val NB_ERB = 32
    private const val NB_DF = 96
    private const val DF_ORDER = 5
    private const val DF_LOOK = 2
    private const val NORM_ALPHA = 0.99f
    private const val MIN_NB_ERB_FREQS = 2
    private const val CHUNK = 250

    /** 分段长度（帧）：20 秒。内存只跟它有关，跟整首歌多长无关 */
    private const val SEG_FRAMES = 2000
    private const val PAD_HEAD = 26
    private const val PAD_TAIL = 6

    /** C API 的默认分级阈值，见 `libDF/src/capi.rs` */
    private const val LSNR_SILENCE = -15f
    private const val LSNR_CLEAN = 35f

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var enc: OrtSession? = null
    private var erbDec: OrtSession? = null
    private var dfDec: OrtSession? = null

    private lateinit var fft: BluesteinFft
    private lateinit var window: FloatArray

    /** 每条 ERB 带占多少个 FFT bin，总和 = 481 */
    private var erbWidths: IntArray = IntArray(0)

    /** bin → 频带号（把 32 个增益铺到 481 个 bin 上） */
    private var binBand: IntArray = IntArray(0)

    var lastError: String? = null
        private set

    /** 诊断用：最近一块的 lsnr 分布、掩码均值与掩码宽度（单测里会打印出来） */
    var debugLsnr: FloatArray = FloatArray(0)
        private set
    var debugMaskMean: Float = 0f
        private set
    var debugMaskWidth: Int = 0
        private set
    var debugCleanFrames: Int = 0
        private set
    var debugSilentFrames: Int = 0
        private set

    val ready: Boolean get() = enc != null

    private class ChannelState {
        val meanNorm = FloatArray(NB_ERB) { -60f + it * (-30f / (NB_ERB - 1)) }
        val unitNorm = FloatArray(NB_DF) { 0.001f + it * (0.0009f / (NB_DF - 1)) }

        /**
         * 归一化状态已经推进到第几帧（绝对帧号）。
         *
         * 分块/分段都要往前多取几十帧做上下文预热，这些帧会被**重复**扫到；如果每次都推一遍
         * 状态，等于把时间轴按 1.1 倍速跑（每 250 帧多吃 26 帧），越到后面归一化越偏。
         * 这里记一下水位线，只有真正的新帧才推进。
         */
        var nextFrame = 0
    }

    data class Stats(val frames: Int, val averageDropDb: Float)

    /**
     * 加载三个图。[load] 按文件名（`enc.onnx` 等）返回字节，Android 侧从 assets 读，
     * 单测里直接从磁盘读 —— 这样同一套代码在 JVM 上也能被真模型验证。
     */
    @Synchronized
    fun prepare(load: (String) -> ByteArray): Boolean {
        if (ready) return true
        lastError = null
        return try {
            fft = BluesteinFft(FFT_SIZE)
            window = FloatArray(FFT_SIZE) { i ->
                val s = sin(0.5 * PI * (i + 0.5) / (FFT_SIZE / 2.0))
                sin(0.5 * PI * s * s).toFloat()
            }
            erbWidths = erbFilterBank(SAMPLE_RATE, FFT_SIZE, NB_ERB, MIN_NB_ERB_FREQS)
            binBand = IntArray(BINS).also { map ->
                var b = 0
                for (band in erbWidths.indices) {
                    repeat(erbWidths[band]) { if (b < BINS) map[b++] = band }
                }
                while (b < BINS) map[b++] = NB_ERB - 1
            }

            val threads = min(4, Runtime.getRuntime().availableProcessors())
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            enc = env.createSession(load("enc.onnx"), opts)
            erbDec = env.createSession(load("erb_dec.onnx"), opts)
            dfDec = env.createSession(load("df_dec.onnx"), opts)
            true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            enc = null
            erbDec = null
            dfDec = null
            false
        }
    }

    @Synchronized
    fun close() {
        enc?.close(); erbDec?.close(); dfDec?.close()
        enc = null; erbDec = null; dfDec = null
    }

    /**
     * 就地降噪。[left]/[right] 可以是任意采样率，进来先重采样到 48k，出去再换回去。
     *
     * **按 20 秒分段处理**：一首 4 分钟的立体声素材在 48k 下是 1150 万个采样点，
     * 左右各一条 float 数组就是 92MB；若再整条复制进模型、整条写出，峰值会到 200MB 以上，
     * 手机上必然 OOM。分段之后内存只跟段长有关（每段约 8MB），和整首歌多长无关。
     *
     * @return 处理了多少帧、掩码平均压了多少 dB（给 UI 显示"它到底干了多少活"）
     */
    fun processInPlace(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Stats {
        if (!ready) return Stats(0, 0f)
        val n48 = if (sampleRate == SAMPLE_RATE) left.size
        else (left.size.toLong() * SAMPLE_RATE / sampleRate).toInt().coerceAtLeast(1)
        val totalFrames = frameCount(n48)

        // 归一化状态必须跨段保留，否则每 20 秒重新"认一遍"环境，段缝会抖
        val stL = ChannelState()
        val stR = ChannelState()
        // 段 k 的上下文窗口会往前多取 27 个 hop，而那段区间已经被段 k-1 的输出覆盖过了。
        // 拿降噪后的信号当"原始输入"再分析一遍，共享帧的谱就废了（实测段缝冒出一段
        // 近奈奎斯特的爆发，跳变 0.049，是正常值的 30 倍）。所以在覆盖之前先留一份原样。
        var headL = FloatArray(0)
        var headR = FloatArray(0)
        var headFrom = 0
        var frames = 0
        var gainSum = 0.0
        var gainCnt = 0L
        var segFrom = 0
        while (segFrom < totalFrames) {
            val segTo = min(segFrom + SEG_FRAMES, totalFrames)
            // 源窗口：头部多留 PAD_HEAD 帧给 GRU 暖机，尾部多留 PAD_TAIL 帧给深滤波
            val sFrom = ((segFrom - PAD_HEAD - 1) * HOP).coerceAtLeast(0)
            val sTo = ((segTo + PAD_TAIL + 2) * HOP).coerceAtMost(n48)
            if (sTo - sFrom >= FFT_SIZE) {
                val srcL = loadRange(left, sampleRate, sFrom, sTo)
                val srcR = loadRange(right, sampleRate, sFrom, sTo)
                // 把上一段覆盖掉的原始头部还回去
                if (headL.isNotEmpty()) {
                    val to = min(segFrom * HOP, sTo)
                    for (p in maxOf(sFrom, headFrom) until to) {
                        val si = p - headFrom
                        val di = p - sFrom
                        if (si in headL.indices && di in srcL.indices) {
                            srcL[di] = headL[si]
                            srcR[di] = headR[si]
                        }
                    }
                }
                // 输出窗口从 (segFrom-1) 个 hop 开始：段首那个采样点要靠上一帧一起重叠相加
                val oFrom = ((segFrom - 1) * HOP).coerceAtLeast(0)
                val oTo = (segTo * HOP).coerceAtMost(n48)
                if (oTo > oFrom) {
                    // 下一段的头部现在还都是原样，趁没写入先存下来
                    val nextFrom = ((segTo - PAD_HEAD - 1) * HOP).coerceAtLeast(0)
                    val capTo = min(segTo * HOP, n48)
                    if (capTo > nextFrom) {
                        headFrom = nextFrom
                        headL = loadRange(left, sampleRate, nextFrom, capTo)
                        headR = loadRange(right, sampleRate, nextFrom, capTo)
                    }
                    val dstL = FloatArray(oTo - oFrom)
                    val dstR = FloatArray(oTo - oFrom)
                    val g = runSegment(
                        srcL, srcR, sFrom / HOP, (sTo + HOP - 1) / HOP,
                        segFrom, segTo, segFrom > 0, stL, stR, dstL, dstR, oFrom, oTo
                    )
                    gainSum += g.first
                    gainCnt += g.second
                    // 段首那一个 hop 上一段已经写过，这里是第二份结果：交叉淡化，不要直接覆盖。
                    // 段缝正好落在共享那一帧窗函数的**峰值**上（两侧分别是它的后半窗和前半窗），
                    // 两个段给这一帧算出的掩码不可能完全相同（双向 GRU 看到的上下文不一样），
                    // 硬切就是一个 0.03 量级的"咔"。淡化 10ms 后残差被摊平到听不出来。
                    storeRange(left, sampleRate, dstL, oFrom, oTo, segFrom * HOP)
                    storeRange(right, sampleRate, dstR, oFrom, oTo, segFrom * HOP)
                }
            }
            frames += segTo - segFrom
            onProgress?.invoke((segTo.toFloat() / totalFrames).coerceIn(0f, 1f))
            segFrom = segTo
        }

        val avgGain = if (gainCnt > 0) (gainSum / gainCnt).toFloat() else 1f
        val dropDb = if (avgGain <= 1e-6f) -60f else 20f * log10(avgGain)
        return Stats(frames, dropDb)
    }

    /** 一段内按 [CHUNK] 分块跑模型；[cs]/[ce] 是源窗口覆盖的帧范围，[es]/[ee) 是要产出的帧 */
    private fun runSegment(
        srcL: FloatArray,
        srcR: FloatArray,
        cs0: Int,
        ce0: Int,
        es0: Int,
        ee0: Int,
        overlapHead: Boolean,
        stL: ChannelState,
        stR: ChannelState,
        dstL: FloatArray,
        dstR: FloatArray,
        dstOffset: Int,
        dstEnd: Int
    ): Pair<Double, Long> {
        var sum = 0.0
        var cnt = 0L
        var es = es0
        while (es < ee0) {
            val ee = min(es + CHUNK, ee0)
            val cs = (es - PAD_HEAD).coerceAtLeast(cs0)
            val ce = (ee + PAD_TAIL).coerceAtMost(ce0)
            val t = ce - cs
            if (t > 0) {
                // 段首多产出一帧，供交叉淡化用（那一帧上一段也算过，两边都有值）
                val emitStart = if (overlapHead && es == es0) es - 1 else es
                val g = applyChunk(
                    srcL, srcR, cs, t, stL, stR, dstL, dstR,
                    srcOffset = cs0 * HOP,
                    emitStart = emitStart,
                    emitEnd = ee,
                    dstOffset = dstOffset,
                    dstEnd = dstEnd
                )
                sum += g.first
                cnt += g.second
            }
            es = ee
        }
        return sum to cnt
    }

    // ------------------------------------------------------------------ 分块

    /** 一段 [cs, cs+t) 帧：STFT → 特征 → 三个图 → 掩码/深滤波 → 逆变换拼接 */
    private fun applyChunk(
        l: FloatArray,
        r: FloatArray,
        cs: Int,
        t: Int,
        stL: ChannelState,
        stR: ChannelState,
        outL: FloatArray,
        outR: FloatArray,
        srcOffset: Int,
        emitStart: Int,
        emitEnd: Int,
        dstOffset: Int,
        dstEnd: Int
    ): Pair<Double, Long> {
        val specL = transformChannel(l, srcOffset, cs, t, stL)
        val specR = transformChannel(r, srcOffset, cs, t, stR)
        var sum = 0.0
        var cnt = 0L
        for (ch in 0 until 2) {
            val spec = if (ch == 0) specL else specR
            val (g, c) = enhance(t, spec)
            sum += g
            cnt += c
            // 逆变换写回：只有 [emitStart, emitEnd) 之间、且两个覆盖帧都算过的采样点是完整的
            val out = if (ch == 0) outL else outR
            synthesize(spec.re, spec.im, cs, t, out, emitStart, emitEnd, dstOffset, dstEnd)
        }
        return sum to cnt
    }

    /** 一帧块的分析结果：频谱 + 两组特征（特征要原样喂给 enc） */
    private class Spectrum(
        val re: FloatArray,
        val im: FloatArray,
        val erb: FloatArray,
        val cplx: FloatArray
    )

    /** STFT + 特征（特征顺便推进 [st] 的归一化状态） */
    private fun transformChannel(
        x: FloatArray,
        srcOffset: Int,
        cs: Int,
        t: Int,
        st: ChannelState
    ): Spectrum {
        val re = FloatArray(t * BINS)
        val im = FloatArray(t * BINS)
        val erb = FloatArray(t * NB_ERB)
        val cplx = FloatArray(2 * t * NB_DF)
        val fr = FloatArray(FFT_SIZE)
        val fi = FloatArray(FFT_SIZE)
        val wnorm = 1f / (FFT_SIZE.toFloat() * FFT_SIZE / (2f * HOP))   // = 1/960

        for (f in 0 until t) {
            val start = (cs + f - 1) * HOP - srcOffset
            for (i in 0 until FFT_SIZE) {
                val p = start + i
                fr[i] = if (p in x.indices) x[p] * window[i] else 0f
                fi[i] = 0f
            }
            fft.forward(fr, fi)
            val base = f * BINS
            for (b in 0 until BINS) {
                re[base + b] = fr[b] * wnorm
                im[base + b] = fi[b] * wnorm
            }

            // 预热帧（分块/分段的上下文）不推进归一化状态，否则时间轴被按 1.1 倍速跑
            val advance = cs + f >= st.nextFrame

            // ---- feat_erb：频带平均功率 → dB → 指数均值归一 ----
            var offset = 0
            for (band in 0 until NB_ERB) {
                val w = erbWidths[band]
                var s = 0f
                for (j in 0 until w) {
                    val b = offset + j
                    val rr = re[base + b]
                    val ii = im[base + b]
                    s += rr * rr + ii * ii
                }
                var v = 10f * log10(s / w + 1e-10f)
                if (advance) {
                    st.meanNorm[band] = v * (1f - NORM_ALPHA) + st.meanNorm[band] * NORM_ALPHA
                }
                v = (v - st.meanNorm[band]) / 40f
                erb[f * NB_ERB + band] = v
                offset += w
            }

            // ---- feat_spec：前 96 bin 的复数谱做单位归一 ----
            for (b in 0 until NB_DF) {
                val rr = re[base + b]
                val ii = im[base + b]
                val mag = hypot(rr, ii)
                if (advance) {
                    st.unitNorm[b] = mag * (1f - NORM_ALPHA) + st.unitNorm[b] * NORM_ALPHA
                }
                val inv = 1f / sqrt(st.unitNorm[b].coerceAtLeast(1e-12f))
                cplx[f * NB_DF + b] = rr * inv
                cplx[t * NB_DF + f * NB_DF + b] = ii * inv
            }

            if (advance) st.nextFrame = cs + f + 1
        }
        return Spectrum(re, im, erb, cplx)
    }

    /**
     * 跑三个图，把掩码与深滤波作用到频谱上（就地）。
     *
     * @return (掩码增益之和, 参与统计的 bin 数)
     */
    private fun enhance(t: Int, spec: Spectrum): Pair<Double, Long> {
        val e = enc ?: return 0.0 to 0L
        val ed = erbDec ?: return 0.0 to 0L
        val dd = dfDec ?: return 0.0 to 0L

        val re = spec.re
        val im = spec.im
        val erb = spec.erb
        val cplx = spec.cplx

        val erbTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(erb), longArrayOf(1, 1, t.toLong(), NB_ERB.toLong())
        )
        val specTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(cplx), longArrayOf(1, 2, t.toLong(), NB_DF.toLong())
        )
        var encRes: OrtSession.Result? = null
        var maskRes: OrtSession.Result? = null
        var coefsRes: OrtSession.Result? = null
        try {
            encRes = e.run(mapOf("feat_erb" to erbTensor, "feat_spec" to specTensor))
            val e0 = tensor(encRes, "e0")
            val e1 = tensor(encRes, "e1")
            val e2 = tensor(encRes, "e2")
            val e3 = tensor(encRes, "e3")
            val emb = tensor(encRes, "emb")
            val c0 = tensor(encRes, "c0")
            val lsnr = floats(encRes, "lsnr", t)

            maskRes = ed.run(mapOf("emb" to emb, "e3" to e3, "e2" to e2, "e1" to e1, "e0" to e0))
            val maskTensor = tensor(maskRes, "m")
            val maskBuf = maskTensor.floatBuffer
            val maskCount = maskBuf.remaining() / t.coerceAtLeast(1)
            val mask = FloatArray(maskBuf.remaining())
            maskBuf.get(mask)

            coefsRes = dd.run(mapOf("emb" to emb, "c0" to c0))
            val coefsTensor = tensor(coefsRes, "coefs")
            val coefsBuf = coefsTensor.floatBuffer
            val coefs = FloatArray(coefsBuf.remaining())
            coefsBuf.get(coefs)

            // coefs 形状 [1,T,96,10]；万一被压平了，用长度反推
            val coefStride = if (coefs.size >= t * NB_DF * 2 * DF_ORDER) NB_DF * 2 * DF_ORDER else coefs.size / t
            val applyMask = maskCount >= BINS

            var gainSum = 0.0
            var gainCnt = 0L
            val yRe = FloatArray(BINS)
            val yIm = FloatArray(BINS)
            var cleanFrames = 0
            var silentFrames = 0
            var maskSum = 0.0
            var maskN = 0L

            // 深滤波吃的是**未加掩码**的原始谱：libDF 里 X 缓冲（原始）和 Y 缓冲（加掩码）
            // 是两份，df() 读的是 X。用加过掩码的谱等于把两级串成"先压再滤"，结果会明显偏干、
            // 和官方对不上（实测相关度从 0.9+ 掉到 0.26）。
            val rawRe = FloatArray(t * NB_DF) { re[it / NB_DF * BINS + it % NB_DF] }
            val rawIm = FloatArray(t * NB_DF) { im[it / NB_DF * BINS + it % NB_DF] }

            for (f in 0 until t) {
                val ln = lsnr[f]
                val base = f * BINS
                val silent = ln < LSNR_SILENCE
                val clean = ln > LSNR_CLEAN
                if (clean) { cleanFrames++; continue }       // 判为干净：原样放行
                if (silent) {                             // 判为纯噪声：整帧压掉
                    silentFrames++
                    java.util.Arrays.fill(re, base, base + BINS, 0f)
                    java.util.Arrays.fill(im, base, base + BINS, 0f)
                    gainCnt += BINS
                    continue
                }

                // ---- 第一级：ERB 带掩码 ----
                for (b in 0 until BINS) {
                    val g = if (applyMask) {
                        mask[f * maskCount + b]
                    } else {
                        mask[f * maskCount + binBand[b].coerceAtMost(maskCount - 1)]
                    }
                    val gg = g.coerceIn(0f, 1f)
                    gainSum += gg
                    gainCnt++
                    maskSum += gg
                    maskN++
                    re[base + b] *= gg
                    im[base + b] *= gg
                }

                // ---- 第二级：前 96 bin 用深滤波（5 阶复数 FIR，往后看 2 帧）----
                if (coefStride >= NB_DF * 2 * DF_ORDER) {
                    val cb = f * coefStride
                    for (b in 0 until NB_DF) {
                        var sr = 0f
                        var si = 0f
                        for (i in 0 until DF_ORDER) {
                            val src = f + i - DF_LOOK
                            if (src < 0 || src >= t) continue
                            val sBase = src * NB_DF + b
                            val xr = rawRe[sBase]
                            val xi = rawIm[sBase]
                            val cr = coefs[cb + b * (2 * DF_ORDER) + 2 * i]
                            val ci = coefs[cb + b * (2 * DF_ORDER) + 2 * i + 1]
                            sr += xr * cr - xi * ci
                            si += xr * ci + xi * cr
                        }
                        yRe[b] = sr
                        yIm[b] = si
                    }
                    for (b in 0 until NB_DF) {
                        re[base + b] = yRe[b]
                        im[base + b] = yIm[b]
                    }
                }
            }
            debugLsnr = lsnr
            debugMaskMean = if (maskN > 0) (maskSum / maskN).toFloat() else 1f
            debugMaskWidth = maskCount
            debugCleanFrames = cleanFrames
            debugSilentFrames = silentFrames
            return gainSum to gainCnt
        } finally {
            coefsRes?.close()
            maskRes?.close()
            encRes?.close()
            erbTensor.close()
            specTensor.close()
        }
    }

    private fun tensor(res: OrtSession.Result, name: String): OnnxTensor {
        // 用迭代而不是 get(name)：Result.get 在不同版本里签名不一样（有的返回 Optional）
        for (entry in res) {
            if (entry.key == name) {
                return entry.value as? OnnxTensor
                    ?: throw IllegalStateException("模型输出 $name 类型不对")
            }
        }
        throw IllegalStateException("模型输出缺少 $name，实际有 ${res.map { it.key }}")
    }

    private fun floats(res: OrtSession.Result, name: String, expect: Int): FloatArray {
        val t = tensor(res, name)
        val buf = t.floatBuffer
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return if (out.size >= expect) out else out + FloatArray(expect - out.size)
    }

    /** 逆变换 + 重叠相加，只写 [emitStart, emitEnd) 帧对应的采样点 */
    private fun synthesize(
        re: FloatArray,
        im: FloatArray,
        cs: Int,
        t: Int,
        out: FloatArray,
        emitStart: Int,
        emitEnd: Int,
        dstOffset: Int,
        dstEnd: Int
    ) {
        val firstSample = (emitStart - 1).coerceAtLeast(cs - 1) * HOP
        val lastSample = ((emitEnd + 1) * HOP).coerceAtMost(dstEnd)
        if (lastSample <= firstSample) return
        val acc = FloatArray(lastSample - firstSample)
        val fr = FloatArray(FFT_SIZE)
        val fi = FloatArray(FFT_SIZE)
        for (f in 0 until t) {
            java.util.Arrays.fill(fr, 0f)
            java.util.Arrays.fill(fi, 0f)
            val base = f * BINS
            for (b in 0 until BINS) {
                fr[b] = re[base + b]
                fi[b] = im[base + b]
            }
            // 共轭对称补全，逆变换才是实信号
            for (b in 1 until FFT_SIZE / 2) {
                fr[FFT_SIZE - b] = fr[b]
                fi[FFT_SIZE - b] = -fi[b]
            }
            fi[0] = 0f
            fi[FFT_SIZE / 2] = 0f
            // 用不除 N 的逆变换：分析侧的 wnorm=1/960 正好抵掉这里的 ×960（见 BluesteinFft）
            fft.inverseUnnormalized(fr, fi)
            val start = (cs + f - 1) * HOP
            for (i in 0 until FFT_SIZE) {
                val p = start + i
                if (p < firstSample || p >= lastSample) continue
                acc[p - firstSample] += fr[i] * window[i]
            }
        }
        val from = maxOf(firstSample, emitStart * HOP)
        val to = minOf(lastSample, emitEnd * HOP)
        for (p in from until to) {
            // 只有被至少两个窗覆盖的采样点才是完整的；首帧的"上一半"靠上面 clamp 的余量补齐
            val idx = p - dstOffset
            if (idx in out.indices) out[idx] = acc[p - firstSample]
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun frameCount(samples: Int): Int = samples / HOP + 2

    /**
     * 取 48kHz 域里的 [from, to) 这段采样。
     *
     * 源是 48k 就直接切；不是就按比例线性插值（44.1k → 48k 是上采样，线性足够；
     * 高频那点损失在降噪场景里听不出来，但省掉了整条数组常驻内存）。
     */
    private fun loadRange(src: FloatArray, sampleRate: Int, from: Int, to: Int): FloatArray {
        val n = (to - from).coerceAtLeast(1)
        val out = FloatArray(n)
        if (src.isEmpty()) return out
        if (sampleRate == SAMPLE_RATE) {
            for (i in 0 until n) {
                val p = from + i
                out[i] = if (p in src.indices) src[p] else 0f
            }
            return out
        }
        val step = sampleRate.toDouble() / SAMPLE_RATE
        for (i in 0 until n) {
            val pos = (from + i) * step
            val i0 = pos.toInt()
            if (i0 >= src.size) break
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = src[i0] * (1f - frac) + src[i1] * frac
        }
        return out
    }

    /**
     * 把 48kHz 域的结果写回原数组。
     *
     * `[bufFrom, writeFrom)` 这一段是上一段已经写过的重叠区（一个 hop）：新结果按线性权重淡入、
     * 旧结果淡出，避免段缝处硬切出"咔"。`writeFrom` 之后直接覆盖。
     */
    private fun storeRange(
        dst: FloatArray,
        sampleRate: Int,
        buf: FloatArray,
        bufFrom: Int,
        bufTo: Int,
        writeFrom: Int
    ) {
        val blendSpan = (writeFrom - bufFrom).toFloat()
        if (sampleRate == SAMPLE_RATE) {
            for (p in bufFrom until bufTo) {
                val idx = p - bufFrom
                if (idx !in buf.indices || p !in dst.indices) continue
                val v = buf[idx]
                dst[p] = if (p < writeFrom && blendSpan > 0f) {
                    val w = (p - bufFrom + 0.5f) / blendSpan
                    dst[p] * (1f - w) + v * w
                } else {
                    v
                }
            }
            return
        }
        // 写回时的目标采样率更低：按比例取样（线性）
        val step = SAMPLE_RATE.toDouble() / sampleRate
        var j = kotlin.math.ceil(bufFrom / step).toInt()
        while (true) {
            val p = (j * step)
            if (p >= bufTo) break
            val idx = p.toInt() - bufFrom
            if (idx in buf.indices && j in dst.indices) {
                val v = buf[idx]
                dst[j] = if (p < writeFrom && blendSpan > 0f) {
                    val w = ((p - bufFrom) / (writeFrom - bufFrom)).toFloat().coerceIn(0f, 1f)
                    dst[j] * (1f - w) + v * w
                } else {
                    v
                }
            }
            j++
        }
    }

    /**
     * ERB 滤波器组的带宽划分，逐行照抄 `libDF` 的 `erb_fb`。
     *
     * 之所以不自己设计一套"更合理"的分带：模型是在这一套分带上训练的，
     * 差一条边界的 bin 都会让特征分布偏掉。
     */
    private fun erbFilterBank(sr: Int, fftSize: Int, nbBands: Int, minNbFreqs: Int): IntArray {
        val nyq = sr / 2
        val freqWidth = sr.toFloat() / fftSize
        val erbLow = freq2erb(0f)
        val erbHigh = freq2erb(nyq.toFloat())
        val step = (erbHigh - erbLow) / nbBands
        val erb = IntArray(nbBands)
        var prevFreq = 0
        var freqOver = 0
        for (i in 1..nbBands) {
            val f = erb2freq(erbLow + i * step)
            val fb = (f / freqWidth).roundToInt()
            var n = fb - prevFreq - freqOver
            if (n < minNbFreqs) {
                freqOver = minNbFreqs - n
                n = minNbFreqs
            } else {
                freqOver = 0
            }
            erb[i - 1] = n
            prevFreq = fb
        }
        erb[nbBands - 1] += 1
        val tooLarge = erb.sum() - (fftSize / 2 + 1)
        if (tooLarge > 0) erb[nbBands - 1] -= tooLarge
        return erb
    }

    private fun freq2erb(f: Float): Float = 9.265f * kotlin.math.ln(1f + f / (24.7f * 9.265f))

    private fun erb2freq(e: Float): Float = 24.7f * 9.265f * (kotlin.math.exp(e / 9.265f) - 1f)
}
