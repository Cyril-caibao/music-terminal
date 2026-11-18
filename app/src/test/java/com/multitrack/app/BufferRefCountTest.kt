package com.multitrack.app

import com.multitrack.app.audio.AudioBufferStore
import com.multitrack.app.audio.PcmBuffer
import com.multitrack.app.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bufferId 引用计数（3.49 修的 P0）。
 *
 * 之前的实现是「删片段就 remove 掉 buffer」，而同一个 bufferId 会被多个片段共享：
 * 剪贴板连粘两次、复制一段再粘贴、分割后再复制…… 删掉其中一个片段，
 * 另一个片段的音频就凭空消失（波形空、播放没声），而且没有撤销栈。
 */
class BufferRefCountTest {

    private fun buf(frames: Int = 1000, v: Float = 0.4f) =
        PcmBuffer(44100, 2, FloatArray(frames) { v }, FloatArray(frames) { v }, frames)

    private fun reset() {
        Project.clearAll()
        AudioBufferStore.clear()
    }

    @Test
    fun twoClipsSharingOneBufferBothHoldAReference() {
        reset()
        val track = Project.addTrack()!!
        AudioBufferStore.put("shared", buf())
        Project.addClip(track, "shared", "a", 1000, 0, "#fff")
        Project.addClip(track, "shared", "b", 1000, 2000, "#fff")

        assertEquals("两个片段应该各持一次引用", 2, AudioBufferStore.refCount("shared"))

        // 删掉第一个：第二个必须还能出声
        Project.removeClip(track, track.clips.first { it.timelineStart == 0 })
        assertNotNull("共享的 buffer 被连坐删掉了", AudioBufferStore.get("shared"))
        assertEquals(1, AudioBufferStore.refCount("shared"))
        assertEquals("另一个片段的音频没了", 1000, AudioBufferStore.get("shared")!!.frames)

        // 删掉第二个：这时候才真正释放
        Project.removeClip(track, track.clips.first())
        assertNull("引用归零后应该释放", AudioBufferStore.get("shared"))
    }

    @Test
    fun pastingTheSameClipboardTwiceKeepsBoth() {
        reset()
        val track = Project.addTrack()!!
        AudioBufferStore.put("src", buf())
        val original = Project.addClip(track, "src", "src", 1000, 0, "#fff")

        val segs = Project.copyRange(track, 0, 1000)
        assertEquals(1, segs.size)
        Project.pasteAt(track, 2000, segs)
        Project.pasteAt(track, 4000, segs)
        assertEquals("两次粘贴共用同一条 buffer，各持一次引用", 2, AudioBufferStore.refCount(segs[0].bufferId))

        Project.removeClip(track, original)
        assertEquals(2, track.clips.size)
        assertNotNull("剪贴板的 buffer 不该被删", AudioBufferStore.get(segs[0].bufferId))

        // 粘贴出来的两条都还引用着它
        Project.removeClip(track, track.clips.first())
        assertNotNull("删掉一次粘贴就把另一条也弄哑了", AudioBufferStore.get(segs[0].bufferId))
        Project.removeClip(track, track.clips.first())
        assertNull(AudioBufferStore.get(segs[0].bufferId))
    }

    @Test
    fun splitProducesTwoPlayableHalvesWithPeaks() {
        reset()
        val track = Project.addTrack()!!
        AudioBufferStore.put("whole", buf())
        val clip = Project.addClip(track, "whole", "whole", 1000, 0, "#fff")

        val right = Project.splitClipAt(track, clip, 400)
        assertNotNull(right)
        assertEquals(2, track.clips.size)

        val leftClip = track.clips.first { it.timelineStart == 0 }
        assertEquals(400, leftClip.length)
        assertEquals(600, right!!.length)
        assertTrue("左半边的右声道波形丢了", leftClip.peaksR.isNotEmpty())
        assertTrue("右半边的右声道波形丢了", right.peaksR.isNotEmpty())
        assertNotNull(AudioBufferStore.get(leftClip.bufferId))
        assertNotNull(AudioBufferStore.get(right.bufferId))
        assertNull("原 buffer 应该已经让出引用", AudioBufferStore.get("whole"))

        // 再分割一次：id 不能把上一轮的缓冲覆盖掉
        // 注意第二个参数是**时间轴**帧（和播放头同口径），不是片段内偏移：
        // right 从 400 开始、长 600，所以这里传 600 才是把它切成 200 + 400。
        val right2 = Project.splitClipAt(track, right, 600)
        assertNotNull(right2)
        assertEquals(3, track.clips.size)
        // 三块必须各有一条独立缓冲：第二次分割的 id 若还是固定的 _L/_R 后缀，
        // 就会把第一次分割出来的那两块覆盖掉，波形和声音一起错位。
        val ids = track.clips.map { it.bufferId }.toSet()
        assertEquals("三个片段共用了同一条 buffer", 3, ids.size)
        for (c in track.clips) {
            assertNotNull("${c.timelineStart} 处的片段没有音频", AudioBufferStore.get(c.bufferId))
            assertTrue("${c.timelineStart} 处的片段没有波形", c.peaks.isNotEmpty())
        }
    }

    @Test
    fun deleteRangeKeepsUntouchedPartAlive() {
        reset()
        val track = Project.addTrack()!!
        AudioBufferStore.put("b", buf())
        Project.addClip(track, "b", "b", 1000, 0, "#fff")
        Project.deleteRange(track, 200, 800)

        assertEquals("应该切成左右两段", 2, track.clips.size)
        for (c in track.clips) {
            assertNotNull("第 ${c.timelineStart} 帧的片段没有音频", AudioBufferStore.get(c.bufferId))
        }
        assertNull(AudioBufferStore.get("b"))
    }

    @Test
    fun removingATrackReleasesEveryClip() {
        reset()
        val track = Project.addTrack()!!
        AudioBufferStore.put("x", buf())
        AudioBufferStore.put("y", buf(frames = 500))
        Project.addClip(track, "x", "x", 1000, 0, "#fff")
        Project.addClip(track, "y", "y", 500, 2000, "#fff")
        Project.removeTrack(track)
        assertNull(AudioBufferStore.get("x"))
        assertNull(AudioBufferStore.get("y"))
    }
}
