package com.multitrack.app.player.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.multitrack.app.player.data.SongEntity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 曲目响度测量 + 自动补偿。
 *
 * 问题：不同专辑/年代的母带电平差得很多 —— 一首 -9 dBFS 的流行歌后面接一首 -20 dBFS 的老录音，
 * 不调音量就会「忽大忽小」。这里给每首歌算一个**静态增益**（不是压缩、不是限幅，不会改变动态），
 * 播放时乘在播放器音量上，让整张队列的响度大致拉平。
 *
 * 三条硬约束：
 * 1. **不新增削波**：提升量不超过「峰值到 0dBFS 的余量」；
 * 2. **只削不糊**：太响的歌往下压最多 15dB，太轻的往上提最多 12dB，避免把底噪一起放大；
 * 3. **不影响手动音效**：增益作用在播放器音量上（DSP 之后），用户自己推的 EQ / 超低音 /
 *    极限外放仍然是「推过头就破音」，不会被自动补偿抵消。
 *
 * 测量只在后台做一次，结果按 `songId:时长` 缓存在 SharedPreferences 里；
 * 当前歌没有缓存时先按 1.0 播放，测完再用短坡度补上，不会中途突然跳一下。
 */
object Loudness {

    /** 目标综合 RMS（dBFS）：大致对应「主流流媒体」的听感电平 */
    private const val TARGET_RMS_DB = -14f
    private const val MAX_BOOST_DB = 12f
    private const val MAX_CUT_DB = -15f
    /** 峰值至少留 0.3dB 余量，补偿不会把原来不破的歌推破 */
    private const val PEAK_MARGIN_DB = 0.3f

    private const val PREFS = "loudness"
    private const val KEY_ENABLED = "auto_gain"

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cyril-loudness").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()
    /** 懒初始化：单元测试是纯 JVM，没有 Looper，不能放在对象初始化里 */
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(song: SongEntity) = "g${song.id}:${song.durationMs}"

    /** 已测过的线性增益；null 表示还没测（调用方先用 1.0） */
    fun cachedGain(context: Context, song: SongEntity): Float? {
        val raw = prefs(context).getString(key(song), null) ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val rms = parts[0].toFloatOrNull() ?: return null
        val peak = parts[1].toFloatOrNull() ?: return null
        return dbToGain(gainDb(rms, peak))
    }

    /** 后台测一首，测完（带缓存）回调到主线程；同一首不会重复测 */
    fun measureAsync(context: Context, song: SongEntity, onDone: ((Float?) -> Unit)? = null) {
        val app = context.applicationContext
        if (cachedGain(app, song) != null) {
            onDone?.let { main.post { it(cachedGain(app, song)) } }
            return
        }
        val k = key(song)
        val guard = inFlight.computeIfAbsent(k) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return
        worker.execute {
            var gain: Float? = null
            runCatching {
                val measured = measure(app, Uri.parse(song.uri)) ?: return@runCatching
                val db = gainDb(measured[0], measured[1])
                prefs(app).edit().putString(k, "${measured[0]},${measured[1]}").apply()
                gain = dbToGain(db)
            }
            guard.set(false)
            inFlight.remove(k)
            onDone?.let { cb -> main.post { cb(gain) } }
        }
    }

    /** 只为下一首预热，不回调 */
    fun prefetch(context: Context, song: SongEntity) = measureAsync(context, song, null)

    // ---------------- 纯计算（单测直接打这些） ----------------

    /**
     * 需要补多少 dB。
     *
     * [rmsDb] 综合电平，[peakDb] 真峰值。目标是 [TARGET_RMS_DB]，但提升量不得超过峰值余量，
     * 最后再夹到 [-15, +12]。
     */
    internal fun gainDb(rmsDb: Float, peakDb: Float): Float {
        if (!rmsDb.isFinite() || !peakDb.isFinite()) return 0f
        val need = TARGET_RMS_DB - rmsDb
        val headroom = -PEAK_MARGIN_DB - peakDb
        return need.coerceAtMost(headroom).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
    }

    internal fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    // ---------------- 测量 ----------------

    /** 返回 [rmsDb, peakDb]；解不出来返回 null（这时候宁可不动音量） */
    private fun measure(context: Context, uri: Uri): FloatArray? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44_100)
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            var pcmEncoding = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 24)
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                else AudioFormat.ENCODING_PCM_16BIT
            }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)

            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
            // 跳过前 10%：很多歌开头是几秒静音，算进去会把电平压低
            val skipUs = if (durationUs > 0) (durationUs * 10 / 100) else 0L
            // 最多测 40 秒：足够代表整首的响度，又不会让后台线程跑几十秒
            val budgetSamples = 40L * sampleRate

            var sum = 0.0
            var count = 0L
            var peak = 0f
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 8_000

            while (!outputDone && count < budgetSamples && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(20_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        if (buf == null) {
                            inputDone = true
                        } else {
                            buf.clear()
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 20_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    pcmEncoding = runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 24)
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                    }.getOrDefault(pcmEncoding)
                } else if (outIdx >= 0) {
                    val buf = decoder.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 && info.presentationTimeUs >= skipUs) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val result = accumulate(buf, pcmEncoding, channels)
                        sum += result[0]
                        count += result[1].toLong()
                        if (result[2] > peak) peak = result[2]
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (count <= 0) return null
            val rms = sqrt(sum / count).toFloat().coerceAtLeast(1e-6f)
            val rmsDb = (20f * log10(rms)).coerceIn(-70f, 0f)
            val peakDb = if (peak > 1e-6f) (20f * log10(peak)).coerceIn(-70f, 6f) else -70f
            return floatArrayOf(rmsDb, peakDb)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /** 返回 [平方和, 样本数, 峰值]；多声道按等权混成单声道 */
    private fun accumulate(buf: java.nio.ByteBuffer, encoding: Int, channels: Int): FloatArray {
        val ch = channels.coerceIn(1, 8)
        var sum = 0.0
        var n = 0L
        var peak = 0f
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val frames = fb.remaining() / ch
            for (i in 0 until frames) {
                var v = 0f
                for (c in 0 until ch) v += fb.get(i * ch + c)
                v /= ch
                val a = kotlin.math.abs(v)
                if (a > peak) peak = a
                sum += v.toDouble() * v
                n++
            }
        } else {
            val sb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = sb.remaining() / ch
            for (i in 0 until frames) {
                var v = 0f
                for (c in 0 until ch) v += sb.get(i * ch + c) / 32768f
                v /= ch
                val a = kotlin.math.abs(v)
                if (a > peak) peak = a
                sum += v.toDouble() * v
                n++
            }
        }
        return floatArrayOf(sum.toFloat(), n.toFloat(), peak)
    }
}
