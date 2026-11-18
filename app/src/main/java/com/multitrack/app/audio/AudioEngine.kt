package com.multitrack.app.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.multitrack.app.model.Clip
import com.multitrack.app.model.Project
import com.multitrack.app.model.Track
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 实时多轨混音播放引擎（对标 Audacity / waveform-playlist 的播放引擎）。
 *
 * 每条音轨 PCM 已统一为 44100Hz 立体声；播放线程按「块」把未静音/被独奏的轨叠加成
 * 立体声写入 AudioTrack。编辑后无需重新渲染整曲即可立即试听。
 *
 * ## 3.49 的两个重写（都是为了不再出事）
 *
 * **1. AudioTrack 只归渲染线程一个人碰（除了释放）。**
 * 上一版 `seek()` 在 UI 线程上 `pause()/flush()` 一个正在被渲染线程 `write()` 的 AudioTrack，
 * `stopRenderer()` 也是直接 `release()` —— 这两条路在真实设备上会抛
 * `IllegalStateException: Unable to ... after release`，而且抛在非主线程，整个进程直接死。
 * 现在 seek 只写一个 `pendingSeek` 标记，真正的 flush 由渲染线程在块边界自己做；
 * 所有 `write/playbackHeadPosition` 都包了 try，释放前先 `join` 渲染线程。
 *
 * **2. 混音循环里不再分配对象、不再加锁。**
 * 旧实现是「逐帧 × 逐轨」：每采样调两次 panGain（每次 new 一个 data class）、
 * 每采样 `AudioBufferStore.get()`（`@Synchronized`）。20 轨 44.1kHz 下就是
 * 每秒 176 万个临时对象 + 88 万次加锁，GC 一抖就是爆音——这才是「多轨一多就卡」的根因。
 * 现在改成「逐片段 × 逐块」：每条 clip 每块只查一次 buffer、只算一次声像/增益，
 * 然后用局部 float 变量乘加，块缓冲复用，零分配零加锁。
 */
class AudioEngine {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    @Volatile var playheadFrame: Int = 0
    @Volatile private var playing = false
    @Volatile private var loop = false
    /** 预览区间的结束帧（-1 表示播到工程末尾），用于「预览选中片段 / 试听」 */
    @Volatile private var stopAt = -1
    /** 主输出电平（dBFS），给界面电平表用 */
    @Volatile var levelDb = -60f
    /** 左右声道各自的电平。声像偏了、一边过载一边正常，单条电平是看不出来的 */
    @Volatile var levelDbL = -60f
    @Volatile var levelDbR = -60f
    /**
     * 过载锁存：本块只要有一个采样到满幅（|x| ≥ 1.0 = 0dBFS）就点亮，**只能手动复位**。
     * 不做自动熄灭：瞬态过载只持续几毫秒，肉眼根本追不上，灯一闪而过等于没做。
     */
    @Volatile var clipL = false
    @Volatile var clipR = false

    fun clearClip() {
        clipL = false
        clipR = false
    }
    /** 渲染线程当前从哪里开始取数（仅渲染线程写；[start] 在启动前写） */
    @Volatile private var fromFrame = 0
    /** UI 线程投递的 seek 目标；-1 表示没有。由渲染线程消费 */
    @Volatile private var pendingSeek = -1

    /**
     * 主输出增益。默认 1.0：**不再为「避免削波」预先压低**。
     * 多轨叠加过载就让它过载（用户要的就是这个），需要保守时自己在界面上调小。
     */
    var masterGain = 1.0f

    /** 播放自然结束时回调（已切到主线程） */
    var onStopped: (() -> Unit)? = null

    /** 懒初始化：纯离线渲染（renderMix）不需要 Looper，单元测试才能直接跑真实混音 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 当前 AudioTrack 是否走 float 输出（不支持的老设备回退 16bit） */
    @Volatile private var useFloatOut = true

    /** 主总线 EQ（Pro-Q 内核），保留滤波器状态保证块间连续 */
    private val eqProcessor = ProQProcessor()
    /** 离线导出用的独立 EQ 实例：导出时不会把实时播放的滤波器状态搅乱 */
    private val exportEq = ProQProcessor()

    // 块缓冲全部复用：这些只在渲染线程里读写
    private val mixL = FloatArray(BLOCK_FRAMES)
    private val mixR = FloatArray(BLOCK_FRAMES)
    /** 本块是否已经播到结尾/预览终点 */
    @Volatile private var blockEnded = false
    /** 本块渲染完之后，下一个待渲染帧（循环时会绕回 0 附近） */
    private var blockNext = 0

    /**
     * 每个渲染块的峰值，按**帧号区间**记下来。
     *
     * 为什么需要这个：`fillBlock` 算的是「刚写进 AudioTrack 的那一块」，而人耳朵听到的是
     * 缓冲里更早的一块 —— 两者差着整个缓冲区（40~100ms）。直接拿它驱动电平表，
     * 表就会抢在声音前面亮，也就是「对不上音乐」。
     * 渲染线程每轮都按白线（playheadFrame，本身就跟 `playbackHeadPosition` 走）
     * 去环里取那一块的峰值，表和白线于是共用同一个时钟。
     */
    private class PeakSlot {
        /** 单调递增的序号：用来分辨「这是新的一块」还是「同一块又看了一遍」 */
        @Volatile var id = -1
        @Volatile var fromFrame = -1
        @Volatile var toFrame = -1
        @Volatile var peakL = 0f
        @Volatile var peakR = 0f
    }

    private val peakRing = Array(64) { PeakSlot() }
    private var peakSlot = 0
    private var peakSeq = 0
    /** 已经拿来判过过载的块序号，避免「点掉灯又被同一块点亮」 */
    private var lastClipSeq = -1

    fun isPlaying(): Boolean = playing
    fun getPosition(): Int = playheadFrame
    fun setLoop(v: Boolean) { loop = v }

    /** 从指定帧开始播放 */
    @Synchronized
    fun start(from: Int, loopEnabled: Boolean, endAt: Int = -1) {
        stopRenderer()
        loop = loopEnabled
        stopAt = endAt
        val end = Project.projectLength()
        if (end <= 0) return
        val f = max(0, from).coerceAtMost(end)
        pendingSeek = -1
        fromFrame = f
        playheadFrame = f
        levelDb = -60f
        levelDbL = -60f
        levelDbR = -60f
        // 新的播放从干净的灯开始：上一轮留下的红点是上一轮的事
        clearClip()
        clearPeakRing()
        eqProcessor.reset()
        SpectrumAnalyzer.clear()

        val at = createAudioTrack() ?: return
        track = at
        running = true
        playing = true
        runCatching { at.play() }
        val t = Thread({ renderLoop(at) }, "cyril-mix-render")
        t.isDaemon = true
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    /** 优先 32bit float 输出：多轨相加很容易超过 0dBFS，16bit 到这里就被截断到破音 */
    private fun createAudioTrack(): AudioTrack? {
        val floatOk = runCatching {
            AudioTrack.getMinBufferSize(
                C.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            ) > 0
        }.getOrDefault(false)
        val encoding = if (floatOk) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val bytesPerSample = if (floatOk) 4 else 2
        useFloatOut = floatOk
        return runCatching {
            @Suppress("Deprecation")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                C.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                encoding,
                max(
                    AudioTrack.getMinBufferSize(C.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, encoding),
                    BLOCK_FRAMES * 2 * bytesPerSample
                ),
                AudioTrack.MODE_STREAM
            )
        }.getOrNull()
    }

    /**
     * 渲染线程主循环。
     *
     * 这里**独占**对 AudioTrack 的操作（play / pause / flush / write / playbackHeadPosition），
     * 只有 [stopRenderer] 会在 join 之后 release —— 顺序保证不会出现「写到已释放对象」。
     */
    private fun renderLoop(at: AudioTrack) {
        val fbuf = FloatArray(BLOCK_FRAMES * 2)
        val sbuf = if (useFloatOut) null else ShortArray(BLOCK_FRAMES * 2)
        var localFrom = fromFrame
        var segStart = localFrom
        var segHead = runCatching { at.playbackHeadPosition }.getOrDefault(0)
        runCatching { at.play() }

        while (running) {
            // ---- seek：由本线程执行，保证不和时间轴的 write 抢 AudioTrack ----
            val seek = pendingSeek
            if (seek >= 0) {
                pendingSeek = -1
                localFrom = seek
                segStart = seek
                runCatching { at.pause() }
                runCatching { at.flush() }
                segHead = runCatching { at.playbackHeadPosition }.getOrDefault(0)
                runCatching { at.play() }
                eqProcessor.reset()
                clearPeakRing()
                playheadFrame = seek
            }

            val consumed = fillBlock(fbuf, localFrom)
            if (!running) break

            val written = try {
                if (useFloatOut) {
                    at.write(fbuf, 0, fbuf.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    val sb = sbuf!!
                    for (i in fbuf.indices) sb[i] = clampS16(fbuf[i])
                    at.write(sb, 0, sb.size)
                }
            } catch (t: Throwable) {
                // 设备被抢走 / 已释放：安静退出，绝不让异常逃出线程（那等于进程猝死）
                -1
            }
            if (written < 0) break

            // 白线跟「已经放出来的声音」走，而不是「已经写进缓冲的声音」
            val head = runCatching { at.playbackHeadPosition }.getOrDefault(segHead)
            val played = ((head.toLong() - segHead.toLong()) and 0xFFFFFFFFL).toInt()
            val end = Project.projectLength()
            var pos = segStart + max(0, played)
            if (end > 0 && pos > end) pos -= end
            playheadFrame = pos
            // 电平和白线共用同一个时钟：取 pos 处正在响的那一块
            publishAudibleLevel(pos)

            localFrom = blockNext
            if (blockEnded) {
                val e = stopAt.takeIf { it > 0 } ?: end
                playheadFrame = e
                running = false
                playing = false
                mainHandler.post { stopRenderer(); onStopped?.invoke() }
                break
            }
        }
    }

    fun pause() {
        if (!playing) return
        stopRenderer()
        playing = false
    }

    fun resume() {
        if (playing) return
        start(playheadFrame, loop)
    }

    fun stop() {
        stopRenderer()
        playing = false
        playheadFrame = 0
        fromFrame = 0
    }

    /**
     * 轻量 seek：只投递目标帧，不碰 AudioTrack。
     * 旧实现每次 seek 都 `pause+flush` 正在写的 track（会崩），还会重建对象（拖动时一卡一卡）。
     */
    @Synchronized
    fun seek(frame: Int) {
        val f = max(0, frame)
        playheadFrame = f
        if (playing && running) {
            pendingSeek = f
        } else {
            fromFrame = f
            pendingSeek = -1
        }
    }

    /** 停止并释放。先让渲染线程退出（join），再释放对象 —— 顺序反了就会「写到已释放」 */
    private fun stopRenderer() {
        running = false
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(RENDER_JOIN_MS) }
        }
        val at = track
        track = null
        pendingSeek = -1
        if (at != null) {
            runCatching {
                at.pause()
                at.flush()
                at.stop()
                at.release()
            }
        }
    }

    /**
     * 填一块交错立体声 float；返回真正渲染了多少帧（到结尾时可能不满一块）。
     *
     * 循环播放时如果一块跨过了工程末尾，会在同一块里从头继续填 —— 不在块边界留静音缝。
     */
    /**
     * 把「[fromFrame, toFrame) 这一段的真实峰值」记进环里。
     * 只在渲染线程调用，所以不需要同步；读侧只读 [PeakSlot] 的 volatile 字段。
     */
    private fun recordPeak(fromFrame: Int, toFrame: Int, l: Float, r: Float) {
        if (toFrame <= fromFrame) return
        val s = peakRing[peakSlot]
        s.fromFrame = fromFrame
        s.toFrame = toFrame
        s.peakL = l
        s.peakR = r
        s.id = peakSeq++
        peakSlot = (peakSlot + 1) % peakRing.size
    }

    private fun clearPeakRing() {
        for (s in peakRing) s.fromFrame = -1
        peakSlot = 0
        lastClipSeq = peakSeq - 1
    }

    /**
     * 把电平上报成**[pos] 这一刻真正在响的那一块**的峰值。
     *
     * 没有这一层，表就永远比声音早一个缓冲（40~100ms）：鼓点还没出来，灯先跳了。
     */
    private fun publishAudibleLevel(pos: Int) {
        for (s in peakRing) {
            if (s.fromFrame >= 0 && pos >= s.fromFrame && pos < s.toFrame) {
                // 弹道放在引擎里：起振瞬时（峰值一个采样也不漏），回落按 dB/秒 走。
                // 以前引擎只给瞬时值、界面再做一次平滑，两层叠加就慢半拍，
                // 看起来「表和声音不是一回事」。
                val dt = (s.toFrame - s.fromFrame).toFloat() / C.SAMPLE_RATE
                levelDbL = meterStep(levelDbL, dbOf(s.peakL), dt)
                levelDbR = meterStep(levelDbR, dbOf(s.peakR), dt)
                levelDb = max(levelDbL, levelDbR)
                // 灯也跟着"正在响的那一块"走：写在前面的块是还没播到的，
                // 拿它点灯等于又抢先了——这正是上一版对不上的原因之一。
                if (s.id != lastClipSeq) {
                    lastClipSeq = s.id
                    if (s.peakL >= 1f) clipL = true
                    if (s.peakR >= 1f) clipR = true
                }
                return
            }
        }
        // 找不到对应块（刚好在块缝上 / 已经播完）：按弹道回落，别冻在高位也别闪回 -60
        val dt = BLOCK_FRAMES.toFloat() / C.SAMPLE_RATE
        levelDbL = meterStep(levelDbL, -60f, dt)
        levelDbR = meterStep(levelDbR, -60f, dt)
        levelDb = max(levelDbL, levelDbR)
    }

    private fun dbOf(peak: Float): Float =
        if (peak <= 0.00001f) -60f else (20.0 * log10(peak.toDouble())).toFloat()

    private fun fillBlock(out: FloatArray, start: Int): Int {
        val frames = BLOCK_FRAMES
        java.util.Arrays.fill(mixL, 0f)
        java.util.Arrays.fill(mixR, 0f)
        blockEnded = false
        blockNext = start

        val end = Project.projectLength()
        if (end <= 0) {
            java.util.Arrays.fill(out, 0f)
            blockEnded = true
            levelDb = -60f
            levelDbL = -60f
            levelDbR = -60f
            return 0
        }

        val stopMark = if (stopAt in 1..end) min(stopAt, end) else end
        val canWrap = loop && stopAt !in 1..end
        var g = start
        var filled = 0
        var peakL = 0f
        var peakR = 0f
        while (filled < frames) {
            if (g >= stopMark) {
                if (canWrap) {
                    g = 0
                } else {
                    blockEnded = true
                    break
                }
            }
            val n = min(frames - filled, stopMark - g)
            if (n <= 0) {
                blockEnded = true
                break
            }
            accumulate(g, n, mixL, mixR, filled)
            // EQ 按「连续片」逐段做：滤波器状态本来就是顺序推进的，结果与整块处理完全等价，
            // 但这样每一片都能各自带一个真实峰值进环里。循环跨越末尾时一块其实覆盖两段
            // （[start,end) 和 [0,g)），以前记成一个区间，白线绕回 0 后表就再也找不到块了。
            eqProcessor.process(mixL, mixR, filled, n, C.SAMPLE_RATE)
            var sL = 0f
            var sR = 0f
            for (i in filled until filled + n) {
                val l = abs(mixL[i])
                val r = abs(mixR[i])
                if (l > sL) sL = l
                if (r > sR) sR = r
            }
            if (sL > peakL) peakL = sL
            if (sR > peakR) peakR = sR
            recordPeak(g, g + n, sL, sR)
            filled += n
            g += n
        }
        blockNext = g

        if (filled < frames) {
            java.util.Arrays.fill(mixL, filled, frames, 0f)
            java.util.Arrays.fill(mixR, filled, frames, 0f)
        }
        if (filled > 0) {
            SpectrumAnalyzer.push(mixL, mixR, filled)
        }

        levelDbL = dbOf(peakL)
        levelDbR = dbOf(peakR)
        levelDb = max(levelDbL, levelDbR)
        // 过载锁存也在 publishAudibleLevel 里做：只有「已经播出来的块」才有点灯资格，
        // 否则写在前面的块会提前 40~100ms 把灯点亮。

        // 不 clamp：这里就是「总线上到底有多热」的真实样子
        for (i in 0 until filled) {
            out[i * 2] = mixL[i]
            out[i * 2 + 1] = mixR[i]
        }
        for (i in filled until frames) {
            out[i * 2] = 0f
            out[i * 2 + 1] = 0f
        }
        return filled
    }

    /**
     * 把 [startFrame, startFrame+count) 的混音累加进 outL/outR 的 [offset] 处。
     *
     * 逐片段而不是逐采样：clips 是按 timelineStart 有序的，所以一条 clip 与这一块的
     * 交集可以一次算出来；声像/音量/增益/淡变系数都在块级别算好，
     * 内层只剩 `+= 采样 * 系数`。没有任何对象分配和同步。
     */
    private fun accumulate(startFrame: Int, count: Int, outL: FloatArray, outR: FloatArray, offset: Int) {
        val tracks = Project.tracks
        if (tracks.isEmpty() || count <= 0) return
        var anySolo = false
        for (t in tracks) {
            if (t.solo) {
                anySolo = true
                break
            }
        }
        val mg = masterGain
        val blockEnd = startFrame + count

        for (ti in tracks.indices) {
            val tr: Track = tracks[ti]
            if (tr.mute) continue
            if (anySolo && !tr.solo) continue
            if (tr.clips.isEmpty()) continue

            val vol = tr.volume
            if (vol == 0f) continue

            for (ci in tr.clips.indices) {
                val c: Clip = tr.clips[ci]
                val cs = c.timelineStart
                if (cs >= blockEnd) continue
                val ce = cs + c.length
                if (ce <= startFrame) continue
                val a = max(startFrame, cs)
                val b = min(blockEnd, ce)
                if (b <= a) continue

                val buf = AudioBufferStore.get(c.bufferId) ?: continue
                val bl = buf.left
                val br = buf.right
                val limit = min(buf.frames, c.length)

                // 音轨 pan 与片段 pan 先合成一个，再走一次平衡法则。
                // 以前是两层等功率相乘（0.707×0.707 = 0.5），默认设置下整条总线白白 -6dB，
                // 于是「AU 里 0dBFS 的歌」在 App 里只到 -6，表看着永远不够热。
                val pg = panBalance((tr.pan + c.pan).coerceIn(-1f, 1f))
                val gl = pg.l * c.gain * vol * mg
                val gr = pg.r * c.gain * vol * mg
                val fadeIn = c.fadeIn
                val fadeOut = c.fadeOut
                val len = c.length
                val o = offset - startFrame

                for (g in a until b) {
                    val rel = g - cs
                    if (rel >= limit) break
                    var env = 1f
                    if (fadeIn > 0 && rel < fadeIn) env = rel.toFloat() / fadeIn
                    if (fadeOut > 0) {
                        val tail = len - rel
                        if (tail < fadeOut) env *= tail.toFloat() / fadeOut
                    }
                    val i = o + g
                    outL[i] += bl[rel] * gl * env
                    outR[i] += br[rel] * gr * env
                }
            }
        }
    }

    /**
     * 离线渲染整曲混音（用于导出 WAV / 复制混合总线）。
     *
     * 和实时混音共用 [accumulate]，所以导出与试听永远一致（包括主总线 EQ 与 masterGain）。
     */
    fun renderMix(): MixResult? {
        val end = Project.projectLength()
        if (end <= 0) return null
        if (Project.tracks.isEmpty()) return null
        val left = FloatArray(end)
        val right = FloatArray(end)
        val bufL = FloatArray(OFFLINE_CHUNK)
        val bufR = FloatArray(OFFLINE_CHUNK)
        var pos = 0
        while (pos < end) {
            val n = min(OFFLINE_CHUNK, end - pos)
            java.util.Arrays.fill(bufL, 0f)
            java.util.Arrays.fill(bufR, 0f)
            accumulate(pos, n, bufL, bufR, 0)
            System.arraycopy(bufL, 0, left, pos, n)
            System.arraycopy(bufR, 0, right, pos, n)
            pos += n
        }
        exportEq.reset()
        exportEq.processBuffer(left, right, C.SAMPLE_RATE)
        return MixResult(left, right, end)
    }

    private fun clampS16(v: Float): Short {
        var x = v
        if (x > 1f) x = 1f
        else if (x < -1f) x = -1f
        return (x * 32767).roundToInt().toShort()
    }

    data class MixResult(val left: FloatArray, val right: FloatArray, val frames: Int)

    companion object {
        private const val BLOCK_FRAMES = 1024
        private const val OFFLINE_CHUNK = 8192
        /** 峰值表的回落速度（dB/秒）。20~24 是机械表头的常见手感，太慢会糊成一片 */
        private const val METER_RELEASE_DB_PER_SEC = 22f

        /**
         * 电平表的一步弹道：**起振瞬时**（raw 比当前高就直接跳上去，一个采样也不漏），
         * 回落按固定 dB/秒。抽成纯函数是为了能单测——表准不准，不该靠肉眼看。
         */
        internal fun meterStep(prevDb: Float, rawDb: Float, dtSec: Float): Float =
            max(rawDb, prevDb - METER_RELEASE_DB_PER_SEC * dtSec).coerceAtLeast(-60f)

        /** 等渲染线程退出的上限：一块 1024 帧约 23ms，留足缓冲但别把 UI 冻住 */
        private const val RENDER_JOIN_MS = 300L
    }
}
