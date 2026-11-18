package com.multitrack.app.player.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * 封面位图的内存缓存（按字节算容量）。
 *
 * 拼贴墙在平移时会不断有新卡进入组合。如果每张卡都靠 AsyncImage 异步加载，新卡的第一帧
 * 一定是空白 —— 一次滑进来二三十张时，整屏就像「没有渲染」。这里把解码好的位图留在内存，
 * 新卡组合的同一帧就能拿到图。
 *
 * 键是歌曲 id，另记一份文件修改时间：用户换过封面后文件会被重写，时间戳变了就重新解码。
 */
internal object ArtImageCache {
    private const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<Long, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: Long, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val stamps = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    fun get(songId: Long): ImageBitmap? = cache.get(songId)

    fun load(songId: Long, file: File): ImageBitmap? {
        val stamp = runCatching { file.lastModified() }.getOrDefault(0L)
        val hit = cache.get(songId)
        if (hit != null && stamps[songId] == stamp) return hit
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return hit
        val image = bitmap.asImageBitmap()
        cache.put(songId, image)
        stamps[songId] = stamp
        return image
    }
}
