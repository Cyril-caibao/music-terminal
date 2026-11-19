package com.multitrack.app.player.data

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * 从文件本体读真实标签（绕开系统媒体库的列）。
 *
 * MediaStore 的 TITLE/ARTIST 列要等系统重扫才会跟上文件内容；如果重扫时拿旧列
 * 覆写曲库，用户刚改完的标签就会「复原」。所以刷新已知曲目、验证写入结果时，
 * 一律以文件本体为准 —— 这里的读取失败只返回 null，不抛异常。
 */
object FileTags {

    data class Tags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val composer: String?,
        val genre: String?,
        val year: Int?,
        val track: Int?,
        val disc: Int?
    )

    /** 读失败（文件不存在 / 无权限 / 格式解析不了）返回 null */
    fun read(path: String): Tags? = runCatching {
        val tag = AudioFileIO.read(File(path)).tag ?: return@runCatching null
        fun s(k: FieldKey): String? = runCatching {
            tag.getFirst(k)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) }
        }.getOrNull()
        fun i(k: FieldKey): Int? = s(k)?.takeWhile { it.isDigit() }?.toIntOrNull()
        Tags(
            title = s(FieldKey.TITLE),
            artist = s(FieldKey.ARTIST),
            album = s(FieldKey.ALBUM),
            albumArtist = s(FieldKey.ALBUM_ARTIST),
            composer = s(FieldKey.COMPOSER),
            genre = s(FieldKey.GENRE),
            year = i(FieldKey.YEAR),
            track = i(FieldKey.TRACK),
            disc = i(FieldKey.DISC_NO)
        )
    }.getOrNull()
}
