package com.multitrack.app

import com.multitrack.app.lyrics.currentLineIndex
import com.multitrack.app.lyrics.parseLrc
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcTest {
    @Test
    fun parsesBasicLrc() {
        val raw = """
            [ti:测试]
            [ar:某人]
            [00:12.34]第一句
            [00:25.5]第二句
            [01:03.123][01:30.00]重复句
        """.trimIndent()
        val lines = parseLrc(raw)
        assertEquals(4, lines.size)
        assertEquals(12.34f, lines[0].atSec, 0.01f)
        assertEquals("第一句", lines[0].text)
        assertEquals(63.123f, lines[2].atSec, 0.01f)
        assertEquals("重复句", lines[3].text)
    }

    @Test
    fun indexLookup() {
        val lines = parseLrc("[00:10.00]A\n[00:20.00]B")
        assertEquals(-1, currentLineIndex(lines, 5f))
        assertEquals(0, currentLineIndex(lines, 10f))
        assertEquals(1, currentLineIndex(lines, 25f))
    }

    @Test
    fun offsetTag() {
        val lines = parseLrc("[offset:-500]\n[00:10.00]A")
        assertEquals(9.5f, lines[0].atSec, 0.01f)
    }
}
