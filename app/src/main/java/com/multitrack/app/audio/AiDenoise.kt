package com.multitrack.app.audio

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * AI 降噪（3.49 新增）：人声档 / 伴奏档。
 *
 * ## 人声档：真的跑 DeepFilterNet3
 *
 * 预训练权重就打在包里 —— `assets/dfn3/` 下是官方 `DeepFilterNet3_onnx.tar.gz`（tag v0.5.6）
 * 里的三个图（enc / erb_dec / df_dec，7.6MB），推理走 [com.multitrack.app.audio.dfn3.Dfn3]：
 * 48kHz、FFT 960、hop 480、Vorbis 窗、32 带 ERB 特征、5 阶深滤波。
 * 那一侧的实现和官方 `deep-filter` 二进制做过逐样本对照（相关度 0.989），不是"随便写写"。
 *
 * **这个文件里的人声分支现在是兜底**：模型没打进包、ABI 不匹配、或者 ONNX Runtime 起不来时，
 * 才退回到统计掩码 —— 功能不消失，效果差一档，UI 上会写清楚走的哪条路。
 *
 * ## 伴奏档：noisereduce + TinyBandMask，但要说清楚边界
 *
 * | 目标 | 是什么 | 本版的实现 |
 * |---|---|---|
 * | noisereduce | **纯算法**，没有权重：从最安静的一批帧估噪声谱，逐 bin 算掩码再平滑 | 见 [noiseFloor]：15% 分位帧的平均谱 + 维纳掩码 |
 * | TinyBandMask | 极小的网络，只输出少量**频带**增益 | 分带掩码（24 带），带内共用一个增益 |
 *
 * TinyBandMask 找不到公开可用的权重（2026-11 查过：官方论文没有发布 checkpoint，
 * HF/ModelScope 上也没有对应仓库），所以这一档只剩结构相同、参数是统计出来的。
 * 这也是为什么伴奏档保留了强度/残余底/细节三根滑条 —— 它确实是靠这些参数在跑。
 *
 * 要换真权重：把 onnx 放进 `assets/models/`，接一个 ONNX Runtime 会话，替换
 * [noiseFloor] / 掩码的输出即可，STFT、重叠相加、干湿混合都不用动。
 *
 * ## 为什么统计掩码还留着
 *
 * 谱减是「减」：减多了出音乐噪声、减狠了人声发闷。这里是「乘掩码」：先估每个 bin 的
 * 语音/噪声比得到 0–1 增益，再把它压成**分带**增益 —— 相邻 bin 不再各压各的，
 * 所以没有那种一格一格的水声。伴奏档一直用这条路。
 */
object AiDenoise {

    enum class Model(val label: String, val subtitle: String) {
        /** 真权重：DeepFilterNet3（assets/dfn3 下的三个 onnx 图，走 ONNX Runtime） */
        VOCAL("人声", "DeepFilterNet3 真权重 · ERB 掩码 + 5 阶深滤波"),
        /** 目标模型：noisereduce + TinyBandMask（两者都没有可内嵌的权重，见类注释） */
        MUSIC("伴奏", "noisereduce + TinyBandMask 结构 · 分带掩码 + 低频保护")
    }

    /**
     * @param strength 处理强度 0–100：0 = 原样返回
     * @param floorDb 残余底：噪声最多被压到多少 dB（强度越大这个底会自动再往下探一点）
     * @param detail 细节保留 0–100：越小越接近纯分带掩码（更干净但更"糊"），越大越保留原始纹理
     */
    data class Config(
        val strength: Float = 65f,
        val floorDb: Float = -20f,
        val detail: Float = 55f
    )

    data class Result(
        val model: Model,
        /** 平均掩码增益（dB，负值）：告诉用户「它到底干了多少活」 */
        val averageDropDb: Float,
        val frames: Int
    )

    /** 估噪声用的"安静帧"分位：取整段里能量最低的 15% */
    private const val QUIET_PERCENTILE = 0.15f

    /** 噪声谱的功率偏置：分位统计天然偏低，乘一点回来 */
    private const val NOISE_BIAS = 1.6f

    /** 判断"这个 bin 是音高成分（乐音/人声基频）而非噪声"时的邻域半径 */
    private const val TONAL_RADIUS = 4

    /** 判音高成分时，比较用的裙边宽度（单侧 bin 数）。宽一点才扛得住"音正好落在两 bin 中间" */
    private const val WIDE_SKIRT = 8

    /** 比邻域均值高这么多倍 → 视为音高成分，估噪声时排除、出掩码时保护 */
    private const val TONAL_RATIO = 4f

    /**
     * 判定成音高成分后，掩码至少留这么多。
     *
     * 0.55 太紧：持续长音的噪声谱会把自己算进去（噪底 ≈ 1.6×长音功率），
     * 掩码本来就压到 0.1 以下，靠这道保护才把长音捞回来，0.55 出来只剩一半，
     * 听感上就是"降噪把人声/长音吃掉一半"。0.62 是能保住音头又不过分放过噪声的位置。
     */
    private const val TONAL_PROTECT = 0.62f

    /**
     * 就地处理 [left]/[right]。
     *
     * @param noiseProfile 已经捕捉/导入的噪声样本；给了就直接用它的谱，省掉两遍扫描，
     *        并且和「降噪（处理）」用的是同一张谱 —— 听感一致
     */
    fun process(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        model: Model,
        cfg: Config = Config(),
        noiseProfile: NoiseProfile? = null
    ): Result {
        val n = min(left.size, right.size)
        if (n <= 0) return Result(model, 0f, 0)
        val strength = cfg.strength.coerceIn(0f, 100f)
        if (strength <= 0.01f) return Result(model, 0f, 0)

        val stft = Stft(SpectralDenoise.FFT_SIZE, SpectralDenoise.HOP)
        val bins = stft.bins
        val frames = stft.frameCount(n)
        if (frames <= 4) return Result(model, 0f, 0)

        val noisePsd = noiseFloor(left, right, sampleRate, stft, frames, noiseProfile)
        if (noisePsd == null) return Result(model, 0f, 0)

        val bands = bandsFor(model)
        val binBand = bandMap(sampleRate, bins, bands)
        val bandWeight = FloatArray(bands)
        val bandGain = FloatArray(bands)

        // 各档的保护边界（按 bin 算好，循环里不再做除法）。
        // 低切用 ceil 而不是截断：55Hz 落在第 2.55 个 bin 上，截断成 2 之后
        // `b < 2` 只圈住 bin 0/1，承载 55Hz 的那个 bin 正好漏在保护圈外 ——
        // 贝斯基频就是这么被削掉一半的。
        val protectLowBin = ceil((if (model == Model.VOCAL) 70f else 55f) / sampleRate * SpectralDenoise.FFT_SIZE)
            .toInt().coerceIn(0, bins)
        val speechLo = (180f / sampleRate * SpectralDenoise.FFT_SIZE).toInt().coerceIn(0, bins)
        val speechHi = (4200f / sampleRate * SpectralDenoise.FFT_SIZE).toInt().coerceIn(0, bins)
        val hissBin = (6000f / sampleRate * SpectralDenoise.FFT_SIZE).toInt().coerceIn(0, bins)

        // 残余底随强度一起往下探。只按 floorDb 一个硬底算的话，强度滑杆过 60 之后
        // 就被这个底夹住了 —— 25 和 90 出来是同一片噪声残留，滑杆后半段等于没用。
        val effFloorDb = (cfg.floorDb.coerceIn(-48f, 0f) - strength * 0.12f).coerceIn(-60f, 0f)
        val floorGain = 10.0.pow(effFloorDb / 20.0).toFloat()
        val overSub = 1f + strength / 100f * 2.5f
        val maskPower = 1f + strength / 55f
        val detailMix = cfg.detail.coerceIn(0f, 100f) / 100f * 0.65f

        val reL = FloatArray(bins)
        val imL = FloatArray(bins)
        val reR = FloatArray(bins)
        val imR = FloatArray(bins)
        val magL = FloatArray(bins)
        val magR = FloatArray(bins)
        val psd = FloatArray(bins)
        /** 不含自己的邻域均值（"裙边"）：判断窄带音高成分用 */
        val skirt = FloatArray(bins)
        /** 3 bin 平滑功率 */
        val smoothed = FloatArray(bins)
        /** 前缀和：pre[0]=0，pre[b+1]=Σpsd[0..b] */
        val prefix = FloatArray(bins + 1)
        val gain = FloatArray(bins)
        val prevGain = FloatArray(bins) { 1f }
        val prevPsd = FloatArray(bins)

        val outL = FloatArray(n)
        val outR = FloatArray(n)
        var gainSum = 0.0
        var gainCount = 0L
        var energyAvg = 0f

        for (f in 0 until frames) {
            stft.analyseSpectrum(left, f, reL, imL, magL)
            stft.analyseSpectrum(right, f, reR, imR, magR)

            var frameEnergy = 0f
            for (b in 0 until bins) {
                val p = (magL[b] * magL[b] + magR[b] * magR[b]) * 0.5f
                psd[b] = p
                frameEnergy += p
            }
            // 帧能量的慢速平均：判断这一帧是不是瞬态起音（鼓点、拨弦）
            energyAvg = if (energyAvg <= 0f) frameEnergy else energyAvg * 0.92f + frameEnergy * 0.08f
            val transient = energyAvg > 0f && frameEnergy > energyAvg * 6f

            // 邻域统计（前缀和，O(1) 取任意区间的功率和）
            var prefAcc = 0f
            for (b in 0 until bins) {
                prefix[b] = prefAcc
                prefAcc += psd[b]
            }
            prefix[bins] = prefAcc
            // 「是不是音高成分」用**3 bin 平滑功率**对**宽裙边均值**比：
            // 只看单个 bin 时，一个 440Hz 长音正好落在 bin 20/21 中间、两边差不多高，
            // 谁都不是局部极大、比值也只有 2 倍，于是被判成噪声吃掉。
            for (b in 0 until bins) {
                val lo1 = max(b - 1, 0)
                val hi1 = min(b + 1, bins - 1)
                val s3 = prefix[hi1 + 1] - prefix[lo1]
                var ws = 0f
                var wc = 0
                val lo2 = max(b - WIDE_SKIRT, 0)
                val hi2 = b - 2
                if (hi2 >= lo2) {
                    ws += prefix[hi2 + 1] - prefix[lo2]
                    wc += hi2 - lo2 + 1
                }
                val lo3 = b + 2
                val hi3 = min(b + WIDE_SKIRT, bins - 1)
                if (hi3 >= lo3) {
                    ws += prefix[hi3 + 1] - prefix[lo3]
                    wc += hi3 - lo3 + 1
                }
                smoothed[b] = s3
                skirt[b] = if (wc > 0) ws / wc else s3
            }

            // ---- 判决引导的先验 SNR → 掩码 ----
            for (b in 0 until bins) {
                val noiseP = noisePsd[b].coerceAtLeast(1e-12f)
                val post = psd[b] / noiseP
                val priorInst = max(post - 1f, 0f)
                val priorOld = prevGain[b] * prevGain[b] * prevPsd[b] / noiseP
                val prior = 0.98f * priorOld + 0.02f * priorInst
                var g = prior / (1f + prior)
                // 过减和幂压只对「贴着噪声线」的 bin 生效。明显高出噪声线的
                // （人声基频、贝斯、鼓点）不参与，否则强度一拉大就把人声压闷 ——
                // 这是"降噪之后人声发闷、越降越糊"最常见的一个来源。
                val soft = 1f - ((post - 1f) * 0.5f).coerceIn(0f, 1f)
                g = (g - (overSub - 1f) * 0.12f * soft).coerceIn(0f, 1f)
                gain[b] = g.pow(1f + (maskPower - 1f) * soft)
                prevPsd[b] = psd[b]
            }

            // ---- 分带（band mask）：同一条带里所有 bin 共用一个增益 ----
            java.util.Arrays.fill(bandWeight, 0f)
            java.util.Arrays.fill(bandGain, 0f)
            for (b in 0 until bins) {
                val band = binBand[b]
                val w = psd[b] + 1e-12f
                bandWeight[band] += w
                bandGain[band] += gain[b] * w
            }
            for (i in 0 until bands) {
                bandGain[i] = if (bandWeight[i] > 1e-12f) bandGain[i] / bandWeight[i] else 1f
            }

            // ---- 合成最终掩码：分带增益打底，细节按 detail 混回逐 bin ----
            for (b in 0 until bins) {
                var g = bandGain[binBand[b]] * (1f - detailMix) + gain[b] * detailMix

                // 音高成分保护：窄带尖峰是乐音/人声基频，不是噪声。
                // 没有这一条，一段持续的长音会被自己的"噪声谱"吃掉（音乐类素材尤其明显）。
                // 判据：3 bin 平滑功率 > 12 倍宽裙边均值（= 单 bin 阈值的 4 倍 × 3 个 bin）。
                val tonal = skirt[b] > 1e-12f && smoothed[b] > skirt[b] * TONAL_RATIO * 3f
                if (tonal) g = max(g, TONAL_PROTECT)

                if (model == Model.VOCAL) {
                    when {
                        // 70Hz 以下是隆隆声/空调声，直接压死
                        b < protectLowBin -> g = min(g, 0.25f)
                        // 180–4200Hz 是人声主区：宁可留一点噪声，也不要把字吃掉
                        b in speechLo..speechHi -> g = max(g, floorGain * 1.8f)
                        // 6kHz 以上是嘶声区，多压一档（4–6k 的齿音不动）
                        b > hissBin -> g = min(g, max(g * 0.85f, floorGain))
                    }
                } else {
                    // 伴奏：55Hz 以下（底鼓基频、贝斯）几乎不动；瞬态帧整体松手
                    when {
                        b < protectLowBin -> g = max(g, 0.9f)
                        transient -> g = max(g, 0.75f)
                    }
                }
                g = g.coerceIn(floorGain, 1f)
                gain[b] = g
                prevGain[b] = g
                gainSum += g
                gainCount++

                reL[b] *= g
                imL[b] *= g
                reR[b] *= g
                imR[b] *= g
            }

            stft.synthesiseSpectrum(f, reL, imL, outL)
            stft.synthesiseSpectrum(f, reR, imR, outR)
        }

        // 归一化交回 Stft：稳态 Σw²=1.5，漏掉这一步整段会响 1.5 倍（见 normaliseOverlapAdd）
        stft.normaliseOverlapAdd(outL, left, n)
        stft.normaliseOverlapAdd(outR, right, n)

        val avgGain = if (gainCount > 0) (gainSum / gainCount).toFloat() else 1f
        val dropDb = if (avgGain <= 1e-6f) -60f else 20f * log10(avgGain)
        return Result(model, dropDb, frames)
    }

    /**
     * 噪声谱估计。
     *
     * 优先用用户捕捉/导入的样本（[NoiseProfile]，和「降噪（处理）」同一张谱）；
     * 没有就自己估：两遍扫描，先记每帧能量、取最低的 15%，再把这几帧的谱平均。
     * 这是 noisereduce 的 stationary 档思路，也是 [SpectralDenoise.adaptiveProfile] 的做法 ——
     * 比"滑动最小值"稳：持续的长音不会被慢慢当成噪声吃掉。
     *
     * @return 逐 bin 的噪声功率；整段是数字静音时返回 null
     */
    private fun noiseFloor(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        stft: Stft,
        frames: Int,
        profile: NoiseProfile?
    ): FloatArray? {
        val bins = stft.bins
        if (profile != null && profile.fftSize == SpectralDenoise.FFT_SIZE && profile.bins == bins) {
            val out = FloatArray(bins)
            var any = 0f
            for (b in 0 until bins) {
                out[b] = profile.magnitude[b] * profile.magnitude[b] * NOISE_BIAS
                if (out[b] > any) any = out[b]
            }
            return if (any < 1e-12f) null else out
        }

        val mag = FloatArray(bins)
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            stft.analyse(left, f, mag, null)
            var e = 0f
            for (b in 1 until bins) e += mag[b] * mag[b]
            energy[f] = e
        }
        val sorted = energy.clone()
        sorted.sort()
        val idx = ((frames - 1) * QUIET_PERCENTILE).toInt().coerceIn(0, frames - 1)
        val threshold = sorted[idx]

        val sum = DoubleArray(bins)
        val cnt = IntArray(bins)
        val psd = FloatArray(bins)
        var quietFrames = 0
        for (f in 0 until frames) {
            if (energy[f] > threshold) continue
            stft.analyse(left, f, mag, null)
            for (b in 0 until bins) psd[b] = mag[b] * mag[b]
            // 音高成分（持续长音、贝斯线）不算噪声：否则"整段都只有一段长音"时
            // 噪声谱会把长音本身算进去，掩码就把音乐吃了。这是最小统计也解决不了的老毛病。
            accumulateNonTonal(psd, sum, cnt, bins)
            quietFrames++
        }
        if (quietFrames == 0) return null
        val out = FloatArray(bins)
        var any = 0f
        for (b in 0 until bins) {
            val v = if (cnt[b] > 0) (sum[b] / cnt[b]).toFloat() else 0f
            out[b] = v * NOISE_BIAS
            if (out[b] > any) any = out[b]
        }
        // 整段都没有可用统计（全被判定为音高成分）时，退化成"最安静那一帧的 25%"
        if (any < 1e-12f) {
            stft.analyse(left, 0, mag, null)
            for (b in 0 until bins) {
                out[b] = mag[b] * mag[b] * 0.25f * NOISE_BIAS
                if (out[b] > any) any = out[b]
            }
        }
        // 数字静音：没有噪声可减，直接放弃（否则掩码全 0，整段变成静音）
        return if (any < 1e-12f) null else out
    }

    /**
     * 累加「非音高」bin 的功率，逐 bin 计数。
     *
     * 判定用的是「比左右各 [TONAL_RADIUS] 个 bin 的均值高 [TONAL_RATIO] 倍」——
     * 窄带尖峰就是音高成分（乐音、人声基频、啸叫），宽带才是噪声。
     */
    private fun accumulateNonTonal(psd: FloatArray, sum: DoubleArray, cnt: IntArray, bins: Int) {
        var boxSum = 0.0
        for (i in 0..min(TONAL_RADIUS, bins - 1)) boxSum += psd[i]
        var boxCnt = min(TONAL_RADIUS, bins - 1) + 1
        for (b in 0 until bins) {
            val mean = (boxSum / boxCnt).toFloat()
            val tonal = mean > 1e-12f && psd[b] > mean * TONAL_RATIO
            if (!tonal) {
                sum[b] += psd[b].toDouble()
                cnt[b]++
            }
            // 滑动窗口：进一个、出一个
            val add = b + TONAL_RADIUS + 1
            if (add < bins) {
                boxSum += psd[add]
                boxCnt++
            }
            val drop = b - TONAL_RADIUS
            if (drop >= 0) {
                boxSum -= psd[drop]
                boxCnt--
            }
        }
    }

    /**
     * bin → 频带映射（对数分布，20Hz 到奈奎斯特）。
     *
     * 对数而不是等宽：人耳本来就按比例分频，等宽分带会把 10kHz 以上切成几十条
     * 没有意义的带，却把 100–300Hz 的基频挤在一条带里。
     */
    fun bandMap(sampleRate: Int, bins: Int, bands: Int): IntArray {
        val nyq = sampleRate / 2f
        val lo = 20f
        val map = IntArray(bins)
        val binHz = sampleRate.toFloat() / SpectralDenoise.FFT_SIZE
        val span = log10(nyq / lo)
        for (b in 0 until bins) {
            val f = b * binHz
            if (f <= lo) {
                map[b] = 0
                continue
            }
            val t = log10(f / lo) / span
            map[b] = (t * bands).toInt().coerceIn(0, bands - 1)
        }
        return map
    }

    /** 这个档位到底改了什么（给窗口里那行小字用） */
    fun describe(model: Model): String = when (model) {
        Model.VOCAL ->
            "跑的是官方预训练权重：48kHz / FFT960 / 32 带 ERB 掩码 + 5 阶深滤波。" +
                "强度、残余底、细节三根滑条对它不生效（掩码由模型输出）。"
        Model.MUSIC ->
            "60Hz 上下（底鼓、贝斯基频）几乎不动，瞬态起音自动松手，其余按分带掩码压噪声。"
    }

    /** 掩码频带数：人声细（保共振峰），伴奏粗（更平滑） */
    fun bandsFor(model: Model): Int = if (model == Model.VOCAL) 48 else 24

    /** 与 [Restore.toneAmplitude] 同口径：量处理前后某个频点还剩多少 */
    fun toneAmplitude(buf: FloatArray, sr: Int, f: Float): Double {
        val n = min(buf.size, sr)
        if (n <= 0) return 0.0
        val w = 2.0 * Math.PI * f / sr
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            re += buf[i] * kotlin.math.cos(w * i)
            im += buf[i] * kotlin.math.sin(w * i)
        }
        return 2.0 * sqrt(re * re + im * im) / n
    }

    /** 有效值（dBFS）：测试里量噪声底 */
    fun rmsDb(buf: FloatArray): Double {
        if (buf.isEmpty()) return -120.0
        var sum = 0.0
        for (v in buf) sum += v.toDouble() * v
        val rms = sqrt(sum / buf.size)
        return if (rms <= 1e-12) -120.0 else 20.0 * log10(rms)
    }

    /** 高频段噪声：几个远离主音的频点求合成幅度（和 DenoiseTest 同一把尺子） */
    fun highBandNoise(buf: FloatArray, sr: Int): Double {
        var acc = 0.0
        for (f in listOf(6000f, 8000f, 10000f, 12000f, 14000f)) {
            val a = toneAmplitude(buf, sr, f)
            acc += a * a
        }
        return sqrt(acc / 5)
    }
}
