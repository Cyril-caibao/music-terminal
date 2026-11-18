package com.multitrack.app

import com.multitrack.app.player.playback.Loudness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动响度补偿的算术边界。
 *
 * 这里的每条断言都对应一个「听感事故」：压得不够 → 忽大忽小；提得太多 → 原本不破的歌开始破；
 * 忘了留峰值余量 → 补偿本身把歌推爆。
 */
class LoudnessTest {

    @Test
    fun loudTrackIsTurnedDown() {
        // 综合 -9dBFS：比目标响 5dB，往下压
        assertEquals(-5f, Loudness.gainDb(-9f, -0.2f), 0.01f)
    }

    @Test
    fun quietTrackIsRaisedButNeverPastPeak() {
        // -22dBFS 需要 +8dB，但峰值 -6dB 只剩 5.7dB 余量
        assertEquals(5.7f, Loudness.gainDb(-22f, -6f), 0.01f)
    }

    @Test
    fun veryQuietTrackIsCappedAtTwelveDb() {
        // -40dBFS 理论上要 +26dB，但最多只提 12dB，免得把底噪一起放大
        assertEquals(12f, Loudness.gainDb(-40f, -30f), 0.01f)
    }

    @Test
    fun hotClippedTrackIsCutButNotBeyondFifteen() {
        // 母带已经削顶（RMS 0.5dBFS）：需要 -14.5dB，同时受峰值余量约束，且不会超过 -15dB
        val db = Loudness.gainDb(0.5f, 0.5f)
        assertEquals(-14.5f, db, 0.01f)
        assertTrue(db >= -15f)
    }

    @Test
    fun trackAlreadyAtPeakGetsNoBoost() {
        // 已经在目标电平、峰值顶到 0dB：不许再提，只会略微压 0.3dB 留余量
        val db = Loudness.gainDb(-14f, 0f)
        assertTrue(db <= 0f)
        assertEquals(-0.3f, db, 0.01f)
    }

    @Test
    fun gainConversionIsSymmetric() {
        assertEquals(1f, Loudness.dbToGain(0f), 0.0001f)
        assertEquals(2f, Loudness.dbToGain(6.0206f), 0.001f)
        assertEquals(0.5f, Loudness.dbToGain(-6.0206f), 0.001f)
    }

    @Test
    fun brokenInputFallsBackToUnity() {
        assertEquals(0f, Loudness.gainDb(Float.NaN, -3f), 0.001f)
        assertEquals(0f, Loudness.gainDb(-14f, Float.NEGATIVE_INFINITY), 0.001f)
    }
}
