package com.multitrack.app.player

import com.multitrack.app.player.data.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排除项「全局生效」的关键行为：过滤只发生在扫描入口（不进曲库），
 * 这组测试钉住 [ScanFilter] 的语义，UI 怎么改都不能破坏它们。
 */
class ScanFilterTest {

    private val roots = setOf("/storage/emulated/0/Music", "/storage/emulated/0/Download")
    private val excludes = setOf("/storage/emulated/0/Music/录音")

    @Test
    fun `no roots means scan everything`() {
        assertTrue(ScanFilter.includePath("/storage/emulated/0/随便/歌.mp3", emptySet(), emptySet()))
        assertTrue(ScanFilter.includePath("/a/b/c.flac", emptySet(), setOf("/x")))
    }

    @Test
    fun `roots restrict scan to those trees`() {
        assertTrue(ScanFilter.includePath("/storage/emulated/0/Music/a.mp3", roots, emptySet()))
        assertTrue(ScanFilter.includePath("/storage/emulated/0/Music/子目录/b.flac", roots, emptySet()))
        assertFalse(ScanFilter.includePath("/storage/emulated/0/DCIM/c.mp3", roots, emptySet()))
    }

    @Test
    fun `exclude wins even inside scan roots`() {
        assertFalse(ScanFilter.includePath("/storage/emulated/0/Music/录音/x.mp3", roots, excludes))
    }

    @Test
    fun `exclude covers subdirectories too`() {
        assertFalse(
            ScanFilter.includePath("/storage/emulated/0/Music/录音/2026/深/x.mp3", roots, excludes)
        )
    }

    @Test
    fun `trailing slash and case of dirs are normalized`() {
        // 存成 "/Music/" 和 "/music" 必须匹配同一个目录
        val r = setOf("/storage/emulated/0/Music/")
        assertTrue(ScanFilter.includePath("/storage/emulated/0/Music/a.mp3", r, emptySet()))
        assertTrue(ScanFilter.includePath("/storage/emulated/0/Music/a.mp3", setOf("/storage/emulated/0/music"), emptySet()))
        assertFalse(ScanFilter.includePath("/storage/emulated/0/MusicX/a.mp3", setOf("/storage/emulated/0/Music"), emptySet()))
    }

    @Test
    fun `similar prefix is not treated as inside`() {
        assertFalse(ScanFilter.includePath("/storage/emulated/0/MusicBackup/a.mp3", roots, emptySet()))
    }

    @Test
    fun `duration parse`() {
        assertEquals(150, ScanFilter.parseDurationSec("2", "30"))
        assertEquals(0, ScanFilter.parseDurationSec("", ""))
        assertEquals(0, ScanFilter.parseDurationSec("0", "0"))
        assertEquals(60, ScanFilter.parseDurationSec("1", ""))
        // 负数与垃圾输入按 0
        assertEquals(0, ScanFilter.parseDurationSec("-3", "x"))
    }

    @Test
    fun `normalizeDir`() {
        assertEquals("/a/b", ScanFilter.normalizeDir("/a/b/"))
        assertEquals("/a/b", ScanFilter.normalizeDir(" /a/b "))
        assertEquals("", ScanFilter.normalizeDir("  "))
        assertEquals("", ScanFilter.normalizeDir("/"))
    }
}
