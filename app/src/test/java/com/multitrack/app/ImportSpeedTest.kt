package com.multitrack.app

import com.multitrack.app.audio.WavCodec
import org.junit.Test
import java.io.File
import kotlin.math.sin

/** 纯 JVM 计时：生成 5 分钟 44.1k 立体声 WAV，测解码+峰值耗时（不含 Android UI） */
class ImportSpeedTest {
    @Test
    fun decodeFiveMinutes() {
        val sr = 44100
        val seconds = 300
        val frames = sr * seconds
        val bytes = ByteArray(44 + frames * 4)
        fun putStr(off: Int, s: String) = s.forEachIndexed { i, c -> bytes[off + i] = c.code.toByte() }
        putStr(0, "RIFF")
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, 36 + frames * 4)
        putStr(8, "WAVE"); putStr(12, "fmt ")
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(16, 16); bb.putShort(20, 1); bb.putShort(22, 2); bb.putInt(24, sr)
        bb.putInt(28, sr * 4); bb.putShort(32, 4); bb.putShort(34, 16)
        putStr(36, "data"); bb.putInt(40, frames * 4)
        var p = 44
        for (i in 0 until frames) {
            val v = (sin(i * 0.01) * 0.6 * 32767).toInt().toShort()
            bb.putShort(p, v); bb.putShort(p + 2, v)
            p += 4
        }
        val t0 = System.currentTimeMillis()
        val pcm = WavCodec.decodeWav(bytes)
        val ms = System.currentTimeMillis() - t0
        println("DECODE5MIN ms=$ms frames=${pcm?.frames} peaks=${pcm?.peaks != null}")
        check(pcm != null && pcm.peaks != null)
    }
}
