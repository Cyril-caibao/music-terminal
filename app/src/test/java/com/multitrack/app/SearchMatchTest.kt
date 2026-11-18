package com.multitrack.app

import com.multitrack.app.player.data.SongSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索容错：手机上单手打字，错一两个字、漏打一个字都应该还能搜到；
 * 但也不能宽容到「什么都匹配」—— 否则等于没有搜索。
 */
class SearchMatchTest {

    private fun hit(query: String, title: String, artist: String = "", album: String = "") =
        SongSearch.matches(query, title, artist, album)

    @Test
    fun exactAndPrefixStillWin() {
        assertTrue(hit("晴天", "晴天"))
        assertTrue(hit("晴", "晴天"))
        assertTrue(hit("qing", "Qing Tian"))
        assertTrue(SongSearch.score("晴天", "晴天") > SongSearch.score("晴", "晴天"))
    }

    @Test
    fun oneTypoInShortChineseTitle() {
        // 富士山下 / 富士山夏：只错一个字
        assertTrue(hit("富士山夏", "富士山下"))
        // 七里香 / 七里乡
        assertTrue(hit("七里乡", "七里香"))
        // 夜曲 / 夜区
        assertTrue(hit("夜区", "夜曲"))
    }

    @Test
    fun oneTypoInEnglishTitle() {
        assertTrue(hit("Helo", "Hello"))
        assertTrue(hit("Bohemian Raphsody", "Bohemian Rhapsody"))
    }

    @Test
    fun missingOrExtraCharacterIsTolerated() {
        assertTrue(hit("富士山下", "富士山"))
        assertTrue(hit("富士山", "富士山下"))
    }

    @Test
    fun subsequenceMatchesInitials() {
        assertTrue(hit("zjl", "Zhou Jie Lun"))
        assertTrue(hit("jaychou", "Jay Chou"))
    }

    @Test
    fun multiTokenAcrosFields() {
        // 艺人 + 歌名分开打
        assertTrue(hit("周杰伦 稻香", "稻香", artist = "周杰伦"))
        // 连在一起打（拼起来的串兜底）
        assertTrue(hit("周杰伦稻香", "稻香", artist = "周杰伦"))
    }

    @Test
    fun unrelatedQueryDoesNotMatch() {
        assertFalse(hit("晴天", "七里香", "周杰伦"))
        assertFalse(hit("abcdef", "Hello", "Adele"))
        assertFalse(hit("海阔天空", "富士山下", "陈奕迅"))
    }

    @Test
    fun normalizeHandlesWidthAndPunctuation() {
        assertEquals("abc123", SongSearch.normalize("ＡＢＣ 1-2_3"))
        assertEquals("七里香live", SongSearch.normalize("七里香（Live）"))
        assertTrue(hit("七里香", "七里香 (Live)"))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(SongSearch.matches("", "任意"))
        assertTrue(SongSearch.matches("   ", "任意"))
    }
}
