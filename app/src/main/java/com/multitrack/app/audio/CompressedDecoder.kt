package com.multitrack.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 压缩音频解码（MP3 / AAC / M4A / OGG / FLAC 等）。
 *
 * 提速策略：
 * 1) 大文件按时间轴切成 N 段，每段独立 MediaExtractor + MediaCodec 并行解码（N = CPU 核数，上限 4）；
 * 2) 每个输出缓冲一次性 bulk 拷成 short[]，再顺序转 float，避免逐样本 JNI 边界访问；
 * 3) 所有分段直接写进预分配的大数组，按 PTS 精确定位，不再二次拷贝、不再每个缓冲新建线程池；
 * 4) 进度按已写入帧数实时上报。
 */
object CompressedDecoder {

    private const val S = 3.0517578e-5f // 1/32768

    private data class TrackInfo(val mime: String, val sampleRate: Int, val durationUs: Long, val channels: Int)

    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): PcmBuffer? {
        val info = probe(context, uri) ?: return null
        val sr = if (info.sampleRate > 0) info.sampleRate else C.SAMPLE_RATE
        var totalFrames = if (info.durationUs > 0) ((info.durationUs.toDouble() / 1_000_000.0) * sr).toInt() else 0
        if (totalFrames <= 0) return decodeSingle(context, uri, 0, 0, onProgress, null, null)
        // 留 0.5s 余量，防止 PTS 抖动越界
        totalFrames += sr / 2

        val left = FloatArray(totalFrames)
        val right = FloatArray(totalFrames)
        val cores = Runtime.getRuntime().availableProcessors()
        val shards = if (totalFrames > sr * 20 && cores > 1) minOf(6, cores) else 1

        if (shards == 1) {
            return decodeSingle(context, uri, 0, totalFrames, onProgress, left, right)
        }

        val latch = java.util.concurrent.CountDownLatch(shards)
        val segFrames = totalFrames / shards
        for (i in 0 until shards) {
            val startF = i * segFrames
            val endF = if (i == shards - 1) totalFrames else (i + 1) * segFrames
            Thread {
                try {
                    decodeSingle(context, uri, startF, endF, { p ->
                        val g = (i.toFloat() + p) / shards
                        if (g < 0.995f) onProgress(g)
                    }, left, right)
                } catch (e: Throwable) {
                    // 单段失败不影响其它段
                } finally {
                    latch.countDown()
                }
            }.apply { priority = Thread.NORM_PRIORITY - 1 }.start()
        }
        latch.await()

        // 找到实际写入的末尾
        var end = totalFrames
        while (end > 0 && left[end - 1] == 0f && right[end - 1] == 0f) end--
        if (end <= 0) return null
        val l = if (end == totalFrames) left else left.copyOf(end)
        val r = if (end == totalFrames) right else right.copyOf(end)
        onProgress(1f)
        return PcmBuffer(sr, 2, l, r, end)
    }

    private fun probe(context: Context, uri: Uri): TrackInfo? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            var idx = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) { idx = i; break }
            }
            if (idx < 0) return null
            val f = ex.getTrackFormat(idx)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: return null
            val sr = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else C.SAMPLE_RATE
            val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            TrackInfo(mime, sr, dur, ch)
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    /**
     * 解一段 [startFrame, endFrame)。left/right 为 null 时自己分配并返回。
     * 输出按 PTS 定位写入，段与段之间重叠也安全。
     */
    private fun decodeSingle(
        context: Context,
        uri: Uri,
        startFrame: Int,
        endFrame: Int,
        onProgress: (Float) -> Unit,
        outLeft: FloatArray?,
        outRight: FloatArray?
    ): PcmBuffer? {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(context, uri, null) } catch (e: Exception) {
            extractor.release(); return null
        }
        var trackIdx = -1; var mime = ""
        for (i in 0 until extractor.trackCount) {
            val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("audio/")) { trackIdx = i; mime = m; break }
        }
        if (trackIdx < 0) { extractor.release(); return null }
        extractor.selectTrack(trackIdx)
        val inFormat = extractor.getTrackFormat(trackIdx)
        val sampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else C.SAMPLE_RATE
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION))
            inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val estFrames = if (outLeft == null) {
            if (durationUs > 0) ((durationUs.toDouble() / 1_000_000.0) * sampleRate).toInt() + 4096 else 1 shl 16
        } else 0

        // 多段模式：先 seek 到段起点，可能落到前一个关键帧，按 PTS 过滤
        if (outLeft != null && startFrame > 0) {
            val seekUs = (startFrame.toLong() * 1_000_000L) / sampleRate
            extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        var left = outLeft ?: FloatArray(estFrames)
        var right = outRight ?: FloatArray(estFrames)
        var writePos = if (outLeft != null) startFrame else 0

        val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
            extractor.release(); return null
        }
        try { codec.configure(inFormat, null, null, 0); codec.start() } catch (e: Exception) {
            codec.release(); extractor.release(); return null
        }

        val info = MediaCodec.BufferInfo()
        val timeoutUs = 1_000L
        var inputEos = false
        var outputEos = false
        var outChannels = 2
        var isFloat = false
        var tmpShort = ShortArray(0)

        try {
            while (!outputEos) {
                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4 // ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            val ch = outChannels.coerceAtLeast(1)
                            val bytesPerSample = if (isFloat) 4 else 2
                            val frames = info.size / (bytesPerSample * ch)
                            // 本段的目标写入位置：优先按 PTS，段内连续则顺序写
                            var dst = if (outLeft != null) {
                                if (info.presentationTimeUs > 0)
                                    ((info.presentationTimeUs.toDouble() / 1_000_000.0) * sampleRate).toInt()
                                else writePos
                            } else writePos

                            if (outLeft == null) {
                                if (writePos + frames > left.size) {
                                    val ns = maxOf(left.size * 2, writePos + frames + 4096)
                                    left = left.copyOf(ns); right = right.copyOf(ns)
                                }
                                dst = writePos
                            }

                            // 段模式下过滤落在段外的缓冲（seek 到前一个同步帧会带回段前音频）
                            val segStartLimit = if (outLeft != null) startFrame else 0
                            val segEndLimit = if (outLeft != null) endFrame else Int.MAX_VALUE
                            if (dst < segEndLimit && dst + frames > segStartLimit) {
                                val fromFrame = maxOf(0, segStartLimit - dst)
                                val toFrame = minOf(frames, segEndLimit - dst)
                                val count = toFrame - fromFrame
                                if (count > 0) {
                                    if (isFloat) {
                                        val fb = outBuf.asFloatBuffer()
                                        var i = fromFrame * ch
                                        var f = 0
                                        val base = dst + fromFrame
                                        while (f < count) {
                                            val l = fb.get(i)
                                            val r = if (ch >= 2) fb.get(i + 1) else l
                                            left[base + f] = l
                                            right[base + f] = r
                                            i += ch; f++
                                        }
                                    } else {
                                        val sb = outBuf.asShortBuffer()
                                        if (tmpShort.size < frames * ch) tmpShort = ShortArray(frames * ch)
                                        val tmp = tmpShort
                                        sb.get(tmp, 0, frames * ch)
                                        var i = fromFrame * ch
                                        var f = 0
                                        val base = dst + fromFrame
                                        while (f < count) {
                                            val l = tmp[i] * S
                                            val r = if (ch >= 2) tmp[i + 1] * S else l
                                                left[base + f] = l
                                            right[base + f] = r
                                            i += ch; f++
                                        }
                                    }
                                }
                            }
                            if (outLeft != null) {
                                writePos = maxOf(writePos, dst + frames)
                                // 关键：解到本段末尾就收工，后面共享数组已由后面的段负责
                                if (dst + frames >= endFrame) outputEos = true
                            } else writePos = dst + frames

                            if (frames > 0 && info.presentationTimeUs > 0) {
                                val totalUs = if (durationUs > 0) durationUs else 1L
                                onProgress((info.presentationTimeUs.toFloat() / totalUs).coerceIn(0f, 0.99f))
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                }
            }
        } catch (e: Exception) {
            // 返回已解码部分
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            codec.release(); extractor.release()
        }

        if (outLeft != null) return null
        if (writePos == 0) return null
        val l = if (left.size == writePos) left else left.copyOf(writePos)
        val r = if (right.size == writePos) right else right.copyOf(writePos)
        onProgress(1f)
        return PcmBuffer(sampleRate, 2, l, r, writePos)
    }
}
