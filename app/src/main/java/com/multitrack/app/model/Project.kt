package com.multitrack.app.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multitrack.app.audio.AudioBufferStore
import com.multitrack.app.audio.C
import com.multitrack.app.audio.PcmBuffer
import com.multitrack.app.audio.PeakExtractor

/**
 * 工程状态管理（单例）：音轨/片段增删改、长度计算、峰值重算。
 * 每次改动 version++，UI（尤其 Canvas 波形）读取 version 触发重组/重绘。
 */
object Project {
    val tracks = mutableStateListOf<Track>()
    val sampleRate: Int = C.SAMPLE_RATE
    var version by mutableStateOf(0)
        private set

    fun addTrack(): Track? {
        if (tracks.size >= C.MAX_TRACKS) return null
        val t = Track(tracks.size)
        tracks.add(t)
        notifyChanged()
        return t
    }

    fun removeTrack(track: Track) {
        if (!tracks.contains(track)) return
        // release 而不是 remove：同一个 bufferId 可能还被别的片段（同轨/异轨/剪贴板）用着
        for (c in track.clips) AudioBufferStore.release(c.bufferId)
        tracks.remove(track)
        notifyChanged()
    }

    /** 向某轨添加片段（按时间轴位置有序插入） */
    fun addClip(
        track: Track,
        bufferId: String,
        name: String,
        frames: Int,
        timelineStart: Int,
        color: String
    ): Clip {
        val clip = Clip().apply {
            this.bufferId = bufferId
            this.name = name
            this.length = frames
            this.timelineStart = kotlin.math.max(0, timelineStart)
            this.color = color
        }
        AudioBufferStore.get(bufferId)?.let {
            // WAV/压缩解码时已顺带算好峰值，直接复用；只有没有缓存时才扫一遍
            val all = it.peaks ?: PeakExtractor.computeAllParallel(it)
            clip.peaks = all.first
            clip.peaksR = all.third
        }
        // 片段持有一次引用：这条 buffer 从此不会被别的片段删除而连带清掉
        AudioBufferStore.retain(bufferId)
        var i = 0
        while (i < track.clips.size && track.clips[i].timelineStart < clip.timelineStart) i++
        track.clips.add(i, clip)
        notifyChanged()
        return clip
    }

    fun removeClip(track: Track, clip: Clip) {
        if (!track.clips.contains(clip)) return
        track.clips.remove(clip)
        // 只减引用：共享同一条 buffer 的其它片段（比如剪贴板粘贴了两次）不会被连坐
        AudioBufferStore.release(clip.bufferId)
        notifyChanged()
    }

    /** 移动片段到新时间轴起点（保持长度不变），并重排所属轨 */
    /** 轨道上下移动（长按轨道头拖动排序） */
    fun moveTrack(from: Int, to: Int) {
        if (from == to) return
        val list = tracks
        if (from !in list.indices || to !in list.indices) return
        val t = list.removeAt(from)
        list.add(to, t)
        notifyChanged()
    }

    /** 把片段拖到另一条轨道（同一时间位置） */
    fun moveClipToTrack(clip: Clip, to: Track) {
        val from = findTrackOf(clip) ?: return
        if (from === to) return
        from.clips.remove(clip)
        to.clips.add(clip)
        to.clips.sortBy { it.timelineStart }
        notifyChanged()
    }

    fun moveClip(clip: Clip, newStart: Int) {
        clip.timelineStart = kotlin.math.max(0, newStart)
        findTrackOf(clip)?.clips?.sortBy { it.timelineStart }
        notifyChanged()
    }

    /** 复制用的一段音频：offset 相对选区起点 */
    class ClipSeg(
        val bufferId: String,
        val offset: Int,
        val length: Int,
        val name: String,
        val color: String,
        val gain: Float,
        val pan: Float
    )

    /** 把 [startFrame,endFrame) 区间内该轨所有片段抽成独立缓冲（不改变工程） */
    fun copyRange(track: Track, startFrame: Int, endFrame: Int): List<ClipSeg> {
        val a = kotlin.math.min(startFrame, endFrame)
        val b = kotlin.math.max(startFrame, endFrame)
        if (b <= a) return emptyList()
        val out = ArrayList<ClipSeg>()
        for (clip in track.clips) {
            val cs = kotlin.math.max(a, clip.timelineStart)
            val ce = kotlin.math.min(b, clip.endFrame)
            if (ce <= cs) continue
            val buf = AudioBufferStore.get(clip.bufferId) ?: continue
            val relS = cs - clip.timelineStart
            val relE = ce - clip.timelineStart
            val len = relE - relS
            if (len <= 0) continue
            val id = "${clip.bufferId}_cp${System.nanoTime()}"
            AudioBufferStore.put(id, PcmBuffer(
                buf.sampleRate, 2,
                buf.left.copyOfRange(relS, relE),
                buf.right.copyOfRange(relS, relE),
                len
            ))
            out.add(ClipSeg(id, cs - a, len, clip.name, clip.color, clip.gain, clip.pan))
        }
        return out
    }

    /** 删除 [startFrame,endFrame) 区间；保留下来的部分各自成为新片段 */
    fun deleteRange(track: Track, startFrame: Int, endFrame: Int) {
        val a = kotlin.math.min(startFrame, endFrame)
        val b = kotlin.math.max(startFrame, endFrame)
        if (b <= a) return
        val affected = track.clips.filter { it.timelineStart < b && it.endFrame > a }
        for (clip in affected) {
            val buf = AudioBufferStore.get(clip.bufferId) ?: continue
            val relS = (a - clip.timelineStart).coerceIn(0, clip.length)
            val relE = (b - clip.timelineStart).coerceIn(0, clip.length)
            val keepLeft = relS > 0
            val keepRight = relE < clip.length
            val leftId: String?
            val rightId: String?
            if (keepLeft) {
                leftId = "${clip.bufferId}_kl${System.nanoTime()}"
                AudioBufferStore.put(leftId, PcmBuffer(
                    buf.sampleRate, 2,
                    buf.left.copyOfRange(0, relS), buf.right.copyOfRange(0, relS), relS
                ))
            } else leftId = null
            if (keepRight) {
                rightId = "${clip.bufferId}_kr${System.nanoTime()}"
                AudioBufferStore.put(rightId, PcmBuffer(
                    buf.sampleRate, 2,
                    buf.left.copyOfRange(relE, clip.length), buf.right.copyOfRange(relE, clip.length),
                    clip.length - relE
                ))
            } else rightId = null
            val name = clip.name
            val color = clip.color
            val gain = clip.gain
            val pan = clip.pan
            val start = clip.timelineStart
            track.clips.remove(clip)
            // 原片段让出引用（若别处还引用着同一条 buffer，它不会消失）
            AudioBufferStore.release(clip.bufferId)
            if (leftId != null) {
                val c = addClip(track, leftId, name, relS, start, color)
                c.gain = gain; c.pan = pan
            }
            if (rightId != null) {
                val c = addClip(track, rightId, name, clip.length - relE, start + relE, color)
                c.gain = gain; c.pan = pan
            }
        }
        notifyChanged()
    }

    /** 把剪贴板片段贴到 [frame] 起 */
    fun pasteAt(track: Track, frame: Int, segs: List<ClipSeg>): List<Clip> {
        val out = ArrayList<Clip>()
        for (s in segs) {
            val c = addClip(track, s.bufferId, s.name, s.length, frame + s.offset, s.color)
            c.gain = s.gain
            c.pan = s.pan
            out.add(c)
        }
        notifyChanged()
        return out
    }

    /** 只渲染 [startFrame,endFrame) 的混合总线，避免为复制一小段而分配整曲缓冲 */
    fun renderMixRange(startFrame: Int, endFrame: Int): PcmBuffer? {
        val a = kotlin.math.max(0, kotlin.math.min(startFrame, endFrame))
        val b = kotlin.math.max(startFrame, endFrame)
        if (b <= a) return null
        val len = b - a
        val outL = FloatArray(len)
        val outR = FloatArray(len)
        val anySolo = tracks.any { it.solo }
        for (t in tracks) {
            if (t.mute || (anySolo && !t.solo)) continue
            for (c in t.clips) {
                val cs = kotlin.math.max(a, c.timelineStart)
                val ce = kotlin.math.min(b, c.endFrame)
                if (ce <= cs) continue
                val buf = AudioBufferStore.get(c.bufferId) ?: continue
                // 和 AudioEngine.accumulate 同一套：录一次平衡，不是两层等功率相乘
                val pg = com.multitrack.app.audio.panBalance((t.pan + c.pan).coerceIn(-1f, 1f))
                val base = c.timelineStart
                for (g in cs until ce) {
                    val rel = g - base
                    if (rel < 0 || rel >= buf.frames) continue
                    var env = 1f
                    if (c.fadeIn > 0 && rel < c.fadeIn) env *= rel.toFloat() / c.fadeIn
                    val tail = c.length - rel
                    if (c.fadeOut > 0 && tail < c.fadeOut) env *= tail.toFloat() / c.fadeOut
                    val l = buf.left[rel] * env * c.gain * pg.l * t.volume
                    val r = buf.right[rel] * env * c.gain * pg.r * t.volume
                    outL[g - a] += l
                    outR[g - a] += r
                }
            }
        }
        return PcmBuffer(C.SAMPLE_RATE, 2, outL, outR, len)
    }

    /** 在指定帧把片段一分为二（分割/裁剪） */
    fun splitClipAt(track: Track, clip: Clip, frame: Int): Clip? {
        if (frame <= clip.timelineStart || frame >= clip.endFrame) return null
        val buf = AudioBufferStore.get(clip.bufferId) ?: return null
        val relSplit = frame - clip.timelineStart
        val leftLen = relSplit
        val rightLen = clip.length - relSplit

        val leftBuf = PcmBuffer(
            buf.sampleRate, 2,
            buf.left.copyOfRange(0, leftLen),
            buf.right.copyOfRange(0, leftLen),
            leftLen
        )
        val rightBuf = PcmBuffer(
            buf.sampleRate, 2,
            buf.left.copyOfRange(relSplit, clip.length),
            buf.right.copyOfRange(relSplit, clip.length),
            rightLen
        )
        // id 必须唯一：用 nanoTime 而不是固定的 _L/_R 后缀，
        // 否则同一片段被连续分割两次时，第二次会覆盖第一次的缓冲。
        val stamp = System.nanoTime()
        val leftId = "${clip.bufferId}_L$stamp"
        val rightId = "${clip.bufferId}_R$stamp"
        AudioBufferStore.put(leftId, leftBuf)
        AudioBufferStore.put(rightId, rightBuf)

        val leftPeaks = PeakExtractor.computeAllParallel(leftBuf)
        val rightPeaks = PeakExtractor.computeAllParallel(rightBuf)

        val leftClip = Clip().apply {
            bufferId = leftId; name = clip.name; length = leftLen
            timelineStart = clip.timelineStart
            fadeIn = clip.fadeIn; fadeOut = 0
            gain = clip.gain; pan = clip.pan; color = clip.color
            peaks = leftPeaks.first
            peaksR = leftPeaks.third
        }
        val rightClip = Clip().apply {
            bufferId = rightId; name = clip.name; length = rightLen
            timelineStart = frame
            fadeIn = 0; fadeOut = clip.fadeOut
            gain = clip.gain; pan = clip.pan; color = clip.color
            peaks = rightPeaks.first
            peaksR = rightPeaks.third
        }
        AudioBufferStore.retain(leftId)
        AudioBufferStore.retain(rightId)

        val idx = track.clips.indexOf(clip)
        AudioBufferStore.release(clip.bufferId)
        track.clips[idx] = leftClip
        track.clips.add(idx + 1, rightClip)
        notifyChanged()
        return rightClip
    }

    fun findTrackOf(clip: Clip): Track? = tracks.firstOrNull { it.clips.contains(clip) }

    /** 工程总时长（帧） */
    fun projectLength(): Int {
        var max = 0
        for (t in tracks) for (c in t.clips) if (c.endFrame > max) max = c.endFrame
        return max
    }

    /** 重算片段波形峰值（编辑效果后调用） */
    fun recomputePeaks(clip: Clip) {
        AudioBufferStore.get(clip.bufferId)?.let {
            val all = PeakExtractor.computeAllParallel(it)
            clip.peaks = all.first
            clip.peaksR = all.third
        }
    }

    fun getClipPcm(clip: Clip): PcmBuffer? = AudioBufferStore.get(clip.bufferId)

    /** 全曲波形缩略（混合轨/概览条用）：按工程时间轴聚合，一次算好缓存，绘制时只做等比采样 */
    fun buildOverview(resolution: Int = 2048, respectMute: Boolean = false): FloatArray {
        val out = FloatArray(resolution)
        val total = projectLength().coerceAtLeast(1)
        for (t in tracks) {
            if (respectMute && t.mute) continue
            for (c in t.clips) {
                if (c.peaks.isEmpty() || c.length <= 0) continue
                val startB = ((c.timelineStart.toDouble() / total) * resolution).toInt().coerceIn(0, resolution - 1)
                val endB = (((c.timelineStart + c.length).toDouble() / total) * resolution).toInt().coerceIn(startB + 1, resolution)
                val span = endB - startB
                val agg = PeakExtractor.aggregatePeaks(c.peaks, c.length, 0, c.length, span)
                for (i in 0 until span) {
                    val idx = startB + i
                    if (idx >= resolution) break
                    val a = maxOf(kotlin.math.abs(agg[i * 2]), kotlin.math.abs(agg[i * 2 + 1]))
                    if (respectMute) out[idx] += a else if (a > out[idx]) out[idx] = a
                }
            }
        }
        if (respectMute) {
            val p = out.maxOrNull() ?: 0f
            if (p > 1f) for (i in out.indices) out[i] = out[i] / p
        }
        return out
    }

    fun clearAll() {
        for (t in tracks) for (c in t.clips) AudioBufferStore.release(c.bufferId)
        tracks.clear()
        notifyChanged()
    }

    fun notifyChanged() { version++ }
}
