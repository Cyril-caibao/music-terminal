package com.multitrack.app

import com.multitrack.app.player.data.SongEntity
import com.multitrack.app.player.ui.groupByAlbum
import com.multitrack.app.player.ui.groupByArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌手 / 专辑聚合。
 *
 * 这两块是"看着简单、一改标签就错"的典型：标签缺字段、同一张专辑里歌手不统一、
 * 音轨号缺失时排序乱掉。这里把几种真实曲库的样子摆出来逐个对。
 */
class LibraryGroupingTest {

    private var nextId = 1L

    private fun song(
        title: String,
        artist: String,
        album: String,
        track: Int = 0,
        disc: Int = 0,
        year: Int = 0,
        durationMs: Long = 180_000
    ) = SongEntity(
        id = nextId++,
        uri = "content://song/$title",
        path = "/storage/emulated/0/Music/$album/$title.flac",
        folderId = 0,
        title = title,
        artist = artist,
        album = album,
        year = year,
        track = track,
        disc = disc,
        durationMs = durationMs
    )

    @Test
    fun artistsGroupByNameAndKeepTheirSongs() {
        val all = listOf(
            song("A1", "Radiohead", "OK Computer"),
            song("A2", "Radiohead", "In Rainbows"),
            song("B1", "Coldplay", "Parachutes")
        )
        val g = groupByArtist(all)
        assertEquals(2, g.size)
        assertEquals("Coldplay", g[0].name)          // 按名字排，不是按数量
        assertEquals(2, g[1].songs.size)
        assertEquals(360_000, g[1].durationMs)        // 时长是名下所有歌的合计
    }

    @Test
    fun albumWithMixedArtistsIsNotSplit() {
        // 一张专辑里有合唱：歌手字段不统一，但它是**一张**专辑
        val all = listOf(
            song("T1", "Adele", "25", track = 1),
            song("T2", "Adele feat. Someone", "25", track = 2),
            song("T3", "Adele", "25", track = 3)
        )
        val g = groupByAlbum(all)
        assertEquals("同一张专辑被拆成多张了", 1, g.size)
        assertEquals("多位歌手", g[0].artist)
        assertEquals(3, g[0].songs.size)
    }

    @Test
    fun albumKeepsDiscAndTrackOrder() {
        val all = listOf(
            song("Third", "X", "Live", track = 3),
            song("First", "X", "Live", track = 1),
            song("Second", "X", "Live", track = 2)
        )
        val g = groupByAlbum(all).single()
        // 专辑页必须是这张专辑本来的顺序，不能按标题重排
        assertEquals(listOf("First", "Second", "Third"), g.songs.map { it.title })
    }

    @Test
    fun missingTagsFallBackToUnknown() {
        val all = listOf(
            song("上传的录音", artist = "", album = ""),
            song("另一首", artist = "某人", album = "")
        )
        // 空标签不能各自成一个分组，也不能把空字符串当名字显示
        assertTrue(groupByArtist(all).any { it.name == "未知" })
        assertEquals(1, groupByAlbum(all).size)
        assertEquals("未知", groupByAlbum(all)[0].name)
    }
}
