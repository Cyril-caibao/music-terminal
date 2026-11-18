package com.multitrack.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 混音导出：WAV（纯 Kotlin）、M4A(AAC) 与 FLAC（系统 MediaCodec + MediaMuxer）。
 * 不支持时返回 null，由界面提示「该格式不被设备支持」。
 */
object ExportAudio {

    fun encode(context: Context, left: FloatArray, right: FloatArray, sampleRate: Int, format: String): ByteArray? {
        val frames = minOf(left.size, right.size)
        if (frames <= 0) return null
        return when (format.lowercase()) {
            "wav" -> WavCodec.encodeWav(left, right, sampleRate)
            "m4a" -> encodeWithCodec(context, left, right, frames, sampleRate, "audio/mp4a-latm", "audio/mp4")
            "flac" -> encodeWithCodec(context, left, right, frames, sampleRate, "audio/flac", "audio/flac")
            else -> null
        }
    }

    private fun encodeWithCodec(
        context: Context,
        left: FloatArray,
        right: FloatArray,
        frames: Int,
        sampleRate: Int,
        mime: String,
        muxerFormat: String
    ): ByteArray? {
        val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}.tmp")
        var muxer: MediaMuxer? = null
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createEncoderByType(mime)
            val fmt = MediaFormat.createAudioFormat(mime, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                if (mime == "audio/flac") {
                    setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                }
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false
            var inPos = 0
            var eosQueued = false
            val pcm = ByteBuffer.allocateDirect(2048 * 4).order(java.nio.ByteOrder.nativeOrder())

            while (true) {
                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: continue
                        buf.clear()
                        val n = minOf(1024, frames - inPos)
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            pcm.clear()
                            for (i in 0 until n) {
                                pcm.putShort(pcm16(left[inPos + i]))
                                pcm.putShort(pcm16(right[inPos + i]))
                            }
                            pcm.flip()
                            buf.put(pcm)
                            codec.queueInputBuffer(inIdx, 0, n * 4, 0, 0)
                            inPos += n
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer!!.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)
                        if (out != null && info.size > 0 && muxerStarted) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, out, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }
            codec.stop()
            codec.release(); codec = null
            muxer.stop()
            muxer.release(); muxer = null
            return tmp.readBytes()
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
            tmp.delete()
        }
    }
}
