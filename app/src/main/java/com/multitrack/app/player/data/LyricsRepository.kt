package com.multitrack.app.player.data

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * 歌词：优先同目录同名 .lrc（纯离线、用户自己放的文件），
 * 其次读音频内嵌歌词（ID3 USLT / Vorbis LYRICS）。
 * 没时间轴的行按普通文本展示，有时间轴的行按当前播放位置高亮。
 */
class LyricsRepository(private val context: Context) {

    /** atMs < 0 表示这一行没有时间戳（纯文本歌词） */
    data class Line(val atMs: Long, val text: String)

    /**
     * 一段歌词：双语 LRC 里同一条时间戳下通常有两行（原文 + 译文）。
     * 必须把它们当成一个整体高亮，否则「上一行原文、下一行译文」中被点亮的永远是译文。
     * lines[0] 是原文（主行），其余是译文/音译。
     */
    data class Stanza(val atMs: Long, val lines: List<String>)

    private val cache = LruCache<Long, List<Line>>(64)
    private val empty = emptyList<Line>()

    suspend fun lines(song: SongEntity): List<Line> = withContext(Dispatchers.IO) {
        cache.get(song.id)?.let { return@withContext it }
        val parsed = runCatching { sidecar(song) ?: embedded(song) }.getOrNull() ?: empty
        if (parsed.isNotEmpty()) cache.put(song.id, parsed)
        parsed
    }

    fun invalidate(songId: Long) = cache.remove(songId)

    private fun sidecar(song: SongEntity): List<Line>? {
        val src = realFile(song) ?: return null
        val parent = src.parentFile ?: return null
        val base = src.nameWithoutExtension
        val title = song.title.substringBeforeLast('.')
        // 精确同名 → 大小写不敏感同名 → 用歌曲标题命名（很多人的 lrc 是按标题存的）
        val names = listOf("$base.lrc", "${song.title}.lrc", "$title.lrc")
        for (n in names) {
            val f = File(parent, n)
            if (f.exists() && f.length() in 1L..2_000_000L) parseOf(f)?.let { return it }
        }
        val lrcFiles = parent.listFiles { f ->
            f.isFile && f.length() in 1L..2_000_000L && f.extension.equals("lrc", true)
        } ?: return null
        val hit = lrcFiles.firstOrNull {
            it.nameWithoutExtension.equals(base, true) || it.nameWithoutExtension.equals(title, true)
        } ?: lrcFiles.firstOrNull {
            // 最后一档：文件名里带歌名（如「周杰伦 - 千里之外.lrc」）
            it.nameWithoutExtension.contains(title, true) || it.name.contains(base, true)
        } ?: return null
        return parseOf(hit)
    }

    private fun parseOf(f: File): List<Line>? {
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return parse(text).takeIf { it.isNotEmpty() }
    }

    private fun embedded(song: SongEntity): List<Line>? {
        val f = realFile(song) ?: return null
        if (!f.exists()) return null
        val raw = runCatching {
            AudioFileIO.read(f).tag?.getFirst(FieldKey.LYRICS)
        }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return parse(raw)
    }

    /** content:// 的条目先把真实路径问出来（MediaStore 的 _data），否则 .lrc/内嵌歌词都找不着 */
    private fun realFile(song: SongEntity): File? {
        if (!song.path.startsWith("content://")) return File(song.path)
        val uri = runCatching { android.net.Uri.parse(song.path) }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { File(it) } else null }
        }.getOrNull()
    }

    private val ts = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    private fun parse(text: String): List<Line> {
        val out = ArrayList<Line>(64)
        var sawTime = false
        for (rawLine in text.replace("\r", "").split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val stamps = ts.findAll(line).toList()
            val body = line.replace(ts, "").trim()
            if (stamps.isEmpty()) {
                // 跳过 [ti:] [ar:] 这类元数据行
                if (line.startsWith("[") && line.endsWith("]")) continue
                out.add(Line(-1, line))
            } else {
                sawTime = true
                for (m in stamps) {
                    val mm = m.groupValues[1].toLongOrNull() ?: 0L
                    val ss = m.groupValues[2].toLongOrNull() ?: 0L
                    val frac = m.groupValues[3].let { g ->
                        when (g.length) {
                            0 -> 0L
                            1 -> g.toLong() * 100
                            2 -> g.toLong() * 10
                            else -> g.take(3).toLong()
                        }
                    }
                    if (body.isNotEmpty()) out.add(Line(mm * 60_000 + ss * 1000 + frac, body))
                }
            }
        }
        // 有时间轴的按时间排序；纯文本保持原顺序
        if (sawTime) out.sortBy { it.atMs } else if (out.isEmpty()) return empty
        return if (out.isEmpty()) empty else out
    }

    /** 当前应当高亮的行下标；纯文本歌词返回 -1 */
    fun currentIndex(lines: List<Line>, positionMs: Long): Int {
        if (lines.isEmpty() || lines.first().atMs < 0) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].atMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }

    companion object {
        /**
         * 把逐行歌词合并成「段」：相邻且时间戳相同的行归为一段。
         * 没有时间戳的纯文本继续保持一行一段。
         */
        fun stanzas(lines: List<Line>): List<Stanza> {
            if (lines.isEmpty()) return emptyList()
            if (lines.first().atMs < 0) {
                return lines.map { Stanza(-1L, listOf(it.text)) }
            }
            val out = ArrayList<Stanza>(lines.size)
            var i = 0
            while (i < lines.size) {
                val at = lines[i].atMs
                val group = ArrayList<String>(2)
                while (i < lines.size && lines[i].atMs == at) {
                    group.add(lines[i].text)
                    i++
                }
                out.add(Stanza(at, group))
            }
            return out
        }
    }
}
