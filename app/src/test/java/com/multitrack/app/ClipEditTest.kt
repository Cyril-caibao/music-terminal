package com.multitrack.app

import com.multitrack.app.audio.AudioBufferStore
import com.multitrack.app.audio.PcmBuffer
import com.multitrack.app.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipEditTest {
    @Test
    fun copyCutPaste() {
        Project.clearAll()
        AudioBufferStore.clear()
        val track = Project.addTrack()!!
        val buf = PcmBuffer(44100, 2, FloatArray(1000) { 0.4f }, FloatArray(1000) { 0.4f }, 1000)
        AudioBufferStore.put("b1", buf)
        val clip = Project.addClip(track, "b1", "test", 1000, 0, "#ffffff")

        val segs = Project.copyRange(track, 200, 600)
        assertEquals(1, segs.size)
        assertEquals(400, segs[0].length)
        assertEquals(0, segs[0].offset)

        val pasted = Project.pasteAt(track, 1500, segs)
        assertEquals(1, pasted.size)
        assertEquals(1500, pasted[0].timelineStart)
        assertEquals(400, pasted[0].length)

        Project.deleteRange(track, 100, 900)
        val left = track.clips.filter { it.id == clip.id || it.timelineStart == 0 }
        assertTrue(track.clips.isNotEmpty())
        val total = track.clips.sumOf { it.length }
        assertTrue("remaining total=$total", total >= 100)
    }
}
