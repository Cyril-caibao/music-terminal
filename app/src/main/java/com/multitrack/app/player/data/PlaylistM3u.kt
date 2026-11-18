package com.multitrack.app.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M3U / M3U8 导入导出。
 * - 导出：标准 #EXTM3U + #EXTINF:时长,标题 + 文件绝对路径（别的播放器也能直接用）
 * - 导入：忽略注释行；先按完整路径匹配，再按文件名匹配（兼容从别处拷来的列表）
 */
object PlaylistM3u {

    suspend fun export(context: Context, uri: Uri, songs: List<SongEntity>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val sb = StringBuilder("#EXTM3U\n")
                for (s in songs) {
                    val dur = (s.durationMs / 1000L).coerceAtLeast(0L)
                    sb.append("#EXTINF:").append(dur).append(',').append(s.title).append('\n')
                    sb.append(s.path).append('\n')
                }
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(sb.toString().toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
        }

    /** 返回按 M3U 顺序匹配到的歌曲；matchByName 兜底时会按文件名找 */
    suspend fun import(context: Context, uri: Uri, all: List<SongEntity>): List<SongEntity> =
        withContext(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return@withContext emptyList()

            val byPath = all.associateBy { it.path }
            val byName = all.groupBy { it.path.substringAfterLast('/') }

            val out = ArrayList<SongEntity>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val decoded = runCatching { Uri.decode(line) }.getOrDefault(line)
                val hit = byPath[decoded]
                    ?: byPath[decoded.replace('\\', '/')]
                    ?: byName[decoded.substringAfterLast('/')]?.firstOrNull()
                if (hit != null) out.add(hit)
            }
            out
        }
}
