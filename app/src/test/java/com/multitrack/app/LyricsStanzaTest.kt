package com.multitrack.app

import com.multitrack.app.player.data.LyricsRepository
import com.multitrack.app.player.data.LyricsRepository.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双语 LRC 的高亮单位：同一时间戳的原文/译文必须合成一段，
 * 否则按「行」二分时命中的永远是最后一行 —— 也就是译文。
 */
class LyricsStanzaTest {

    @Test
    fun mergesSameTimestampIntoOneStanza() {
        val lines = listOf(
            Line(1_000, "Traded for some faces that I never know"),
            Line(1_000, "来换一些我不认识的人"),
            Line(3_000, "Maybe I should try to find the old me"),
            Line(3_000, "也许我应该试着找到那个老了的我")
        )
        val stanzas = LyricsRepository.stanzas(lines)
        assertEquals(2, stanzas.size)
        // 原文在前（主行），译文在后
        assertEquals("Traded for some faces that I never know", stanzas[0].lines.first())
        assertEquals("来换一些我不认识的人", stanzas[0].lines[1])
        assertEquals(3_000L, stanzas[1].atMs)
        assertEquals(2, stanzas[1].lines.size)
    }

    @Test
    fun plainTextStaysLineByLine() {
        val lines = listOf(Line(-1, "第一行"), Line(-1, "第二行"))
        val stanzas = LyricsRepository.stanzas(lines)
        assertEquals(2, stanzas.size)
        assertTrue(stanzas.all { it.lines.size == 1 })
    }

    @Test
    fun singleLanguageKeepsOneLinePerStanza() {
        val lines = listOf(Line(0, "A"), Line(2_000, "B"), Line(4_000, "C"))
        val stanzas = LyricsRepository.stanzas(lines)
        assertEquals(3, stanzas.size)
        assertEquals(listOf("A"), stanzas[0].lines)
    }

    @Test
    fun emptyIsEmpty() {
        assertTrue(LyricsRepository.stanzas(emptyList()).isEmpty())
    }
}
