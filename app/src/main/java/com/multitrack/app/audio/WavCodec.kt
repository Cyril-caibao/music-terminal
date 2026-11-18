package com.multitrack.app.audio
import kotlin.math.ceil

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV（16/24/32bit 整数与 32bit 浮点）的读写编解码。
 * 内部统一 Float32 立体声 [-1,1]，这里只做「字节 ↔ Float32 平面声道」搬运。
 * 【从鸿蒙版 WavCodec.ets 平移，DataView -> java.nio.ByteBuffer（纯 JVM）】
 */
object WavCodec {

    private fun readStr(bb: ByteBuffer, off: Int, len: Int): String {
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append((bb.get(off + i).toInt() and 0xff).toChar())
        return sb.toString()
    }

    private fun writeStr(bb: ByteBuffer, off: Int, s: String) {
        for (i in s.indices) bb.put(off + i, s[i].code.toByte())
    }

    /** 解析 WAV 字节，返回左右声道（单声道复制成双声道，多声道下混到立体声） */
    fun decodeWav(bytes: ByteArray): PcmBuffer? {
        if (bytes.size < 12) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (readStr(bb, 0, 4) != "RIFF" || readStr(bb, 8, 4) != "WAVE") return null

        var offset = 12
        var audioFormat = 0; var channels = 0; var sampleRate = 0
        var bits = 0
        var dataOffset = -1; var dataLength = 0

        while (offset + 8 <= bb.limit()) {
            val id = readStr(bb, offset, 4)
            val size = bb.getInt(offset + 4)
            val body = offset + 8
            if (id == "fmt ") {
                audioFormat = bb.getShort(body).toInt() and 0xffff
                channels = bb.getShort(body + 2).toInt() and 0xffff
                sampleRate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt() and 0xffff
            } else if (id == "data") {
                dataOffset = body
                dataLength = size
                break
            }
            offset = body + size + (size and 1)
        }

        if (dataOffset < 0 || channels <= 0 || bits == 0) return null
        val isFloat = audioFormat == 3
        val bytesPerSample = bits / 8
        val totalSamples = dataLength / bytesPerSample
        val frames = totalSamples / channels
        if (frames <= 0) return null

        val left = FloatArray(frames)
        val right = FloatArray(frames)

        // 快速路径：16bit 立体声用 ShortBuffer + 多线程分片读，长文件快数倍
        if (bits == 16 && channels == 2) {
            bb.position(dataOffset)
            val sb = bb.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val n = minOf(frames, sb.capacity() / 2)
            val buckets = maxOf(1, ceil(n.toDouble() / C.PEAK_BUCKET).toInt())
            val monoP = FloatArray(buckets * 2)
            val lp = FloatArray(buckets * 2)
            val rp = FloatArray(buckets * 2)
            val fillPeaks = { from: Int, to: Int ->
                val b0 = from / C.PEAK_BUCKET
                val b1 = minOf(buckets - 1, (to - 1) / C.PEAK_BUCKET)
                for (b in b0..b1) {
                    val s0 = maxOf(from, b * C.PEAK_BUCKET)
                    val s1 = minOf(to, (b + 1) * C.PEAK_BUCKET)
                    var mn = 0f; var mx = 0f; var mnL = 0f; var mxL = 0f; var mnR = 0f; var mxR = 0f
                    for (i in s0 until s1) {
                        val l = left[i]; val r = right[i]
                        if (l < mnL) mnL = l; if (l > mxL) mxL = l
                        if (r < mnR) mnR = r; if (r > mxR) mxR = r
                        if (l < mn) mn = l; if (l > mx) mx = l
                        if (r < mn) mn = r; if (r > mx) mx = r
                    }
                    monoP[b * 2] = mn; monoP[b * 2 + 1] = mx
                    lp[b * 2] = mnL; lp[b * 2 + 1] = mxL
                    rp[b * 2] = mnR; rp[b * 2 + 1] = mxR
                }
            }
            val threads = minOf(4, maxOf(1, n / (1 shl 18)))
            if (threads <= 1 || n < 65536) {
                for (i in 0 until n) {
                    left[i] = sb.get(i * 2) * 3.0517578e-5f
                    right[i] = sb.get(i * 2 + 1) * 3.0517578e-5f
                }
                fillPeaks(0, n)
            } else {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
                val latch = java.util.concurrent.CountDownLatch(threads)
                val chunk = (n + threads - 1) / threads
                var from = 0
                while (from < n) {
                    val a = from
                    val b = minOf(n, from + chunk)
                    pool.execute {
                        for (i in a until b) {
                            left[i] = sb.get(i * 2) * 3.0517578e-5f
                            right[i] = sb.get(i * 2 + 1) * 3.0517578e-5f
                        }
                        fillPeaks(a, b)
                        latch.countDown()
                    }
                    from = b
                }
                latch.await()
                pool.shutdown()
            }
            return PcmBuffer(sampleRate, 2, left, right, frames, Triple(monoP, lp, rp))
        }

        var pos = dataOffset
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                var v = 0f
                when {
                    bits == 16 -> v = (bb.getShort(pos).toInt()) / 32768f
                    bits == 24 -> {
                        val b0 = bb.get(pos).toInt() and 0xff
                        val b1 = bb.get(pos + 1).toInt() and 0xff
                        val b2 = bb.get(pos + 2).toInt() and 0xff
                        var s = b0 or (b1 shl 8) or (b2 shl 16)
                        if (s and 0x800000 != 0) s = s or (-0x1000000) // 符号扩展
                        v = s / 8388608f
                    }
                    bits == 32 && isFloat -> v = bb.getFloat(pos)
                    bits == 32 -> v = bb.getInt(pos) / 2147483648f
                }
                pos += bytesPerSample
                if (c == 0) left[i] = v
                else if (c == 1) right[i] = v
                else {
                    left[i] += v / (channels - 1)
                    right[i] += v / (channels - 1)
                }
            }
        }

        if (channels == 1) left.copyInto(right)
        return PcmBuffer(sampleRate, 2, left, right, frames)
    }

    /** 编码为 16bit PCM 立体声 WAV（小端），返回可直接写文件的字节 */
    fun encodeWav(left: FloatArray, right: FloatArray, sampleRate: Int): ByteArray {
        val frames = minOf(left.size, right.size)
        val blockAlign = 4 // 2 bytes * 2 channels
        val dataSize = frames * blockAlign
        val buf = ByteArray(44 + dataSize)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

        writeStr(bb, 0, "RIFF")
        bb.putInt(4, 36 + dataSize)
        writeStr(bb, 8, "WAVE")
        writeStr(bb, 12, "fmt ")
        bb.putInt(16, 16)
        bb.putShort(20, 1) // PCM
        bb.putShort(22, 2) // 立体声
        bb.putInt(24, sampleRate)
        bb.putInt(28, sampleRate * blockAlign)
        bb.putShort(32, blockAlign.toShort())
        bb.putShort(34, 16)
        writeStr(bb, 36, "data")
        bb.putInt(40, dataSize)

        var pos = 44
        for (i in 0 until frames) {
            bb.putShort(pos, clampS16(left[i]))
            bb.putShort(pos + 2, clampS16(right[i]))
            pos += 4
        }
        return buf
    }

    private fun clampS16(v: Float): Short {
        var x = v
        if (x > 1f) x = 1f
        if (x < -1f) x = -1f
        return Math.round(x * 32767f).toShort()
    }
}
