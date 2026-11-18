package com.multitrack.app

import com.multitrack.app.audio.Fx
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LimiterTest {
    @Test
    fun neverExceedsCeiling() {
        val sr = 44100
        val n = sr
        val l = FloatArray(n) { (sin(2 * PI * 220 * it / sr) * 1.8f).toFloat() }
        val r = FloatArray(n) { (sin(2 * PI * 330 * it / sr) * 1.5f).toFloat() }
        Fx.limiter(l, r, sr, ceilingDb = -6f, inputBoostDb = 3f, lookaheadMs = 5f, releaseMs = 80f)
        var peak = 0f
        for (i in 0 until n) peak = maxOf(peak, abs(l[i]), abs(r[i]))
        assertTrue("peak=$peak", peak <= 0.52f)
        assertTrue("should still have signal", peak > 0.3f)
    }

    @Test
    fun compressorOutputGain() {
        val sr = 44100
        val n = sr / 2
        val l = FloatArray(n) { 0.5f }
        val r = FloatArray(n) { 0.5f }
        Fx.compressor(l, r, sr, thresholdDb = -20f, ratio = 4f, attackMs = 1f, releaseMs = 50f, outputGainDb = -6f)
        var peak = 0f
        for (i in 0 until n) peak = maxOf(peak, abs(l[i]))
        assertTrue("compressed peak=$peak", peak < 0.5f)
    }
}
