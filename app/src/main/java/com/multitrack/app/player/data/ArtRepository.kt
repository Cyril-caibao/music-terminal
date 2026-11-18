package com.multitrack.app.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 封面：内嵌图 → 同目录（或上一级）的 folder/cover/album 等标准名图片。
 *
 * **不再向系统要「这张专辑的封面」** —— 那个接口返回什么完全由扫描器决定，会把音频所在目录里
 * 的任意一张图（相册照片、下载的截图）登记成封面。取不到就返回 null，界面显示占位渐变。
 *
 * 抽出来的图统一缩到 ≤256px 存到 filesDir/art/<songId>.jpg，列表只读这个小图。
 */
class ArtRepository(private val context: Context) {

    /** 封面专用文件名。只认这些，避免把目录里的任何图片当成封面 */
    private val COVER_NAMES = setOf(
        "folder", "cover", "album", "albumart", "albumartsmall", "front", "artwork"
    )
    private val COVER_EXTS = setOf("jpg", "jpeg", "png", "webp")

    private val dir = File(context.filesDir, "art").apply { if (!exists()) mkdirs() }
    /** 内存索引。用 ConcurrentHashMap 是因为拼贴墙会一次并发请求几十张封面，串行排队要好几秒 */
    private data class CachedArt(val file: File?)

    private val memo = java.util.concurrent.ConcurrentHashMap<Long, CachedArt>()
    /** 用户在 App 内换的封面：单独放 covers/，清 art 缓存不会连带删掉 */
    private val customDir = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    init {
        // 取图规则一改，旧缓存就是错的（上一版会把相册/同目录的任意照片当封面，缓存里全是错图）。
        // 用一个版本戳，升级后第一次启动把 art/ 清空重抽。
        val stamp = File(dir, ".stamp")
        val want = "art-v4"
        val old = runCatching { stamp.readText() }.getOrNull()
        if (old != want) {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            memo.clear()
            runCatching { stamp.writeText(want) }
        }
    }

    /** 改封面后 +1：让所有读封面的 Composable 重新请求 */
    var revision by mutableStateOf(0)
        private set
    private val lock = Mutex()

    suspend fun artFor(song: SongEntity): File? = withContext(Dispatchers.IO) {
        memo[song.id]?.let { return@withContext it.file }
        // 自己换过的封面优先
        val custom = File(customDir, "${song.id}.jpg")
        if (custom.exists() && custom.length() > 0) {
            memo[song.id] = CachedArt(custom)
            return@withContext custom
        }
        val target = File(dir, "${song.id}.jpg")
        if (target.exists() && target.length() > 0) {
            memo[song.id] = CachedArt(target)
            return@withContext target
        }
        val ok = runCatching { extract(song, target) }.getOrDefault(false)
        val found = if (ok && target.exists() && target.length() > 0) target else null
        memo[song.id] = CachedArt(found)
        found
    }

    /** 改完封面/标签后必须清掉，否则列表还在显示旧图。 */
    suspend fun invalidate(songId: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            memo.remove(songId)
            runCatching { File(dir, "$songId.jpg").delete() }
            revision += 1
            Unit
        }
    }

    /** 把用户选的图片存成本地封面（只影响本 App 的显示，不需要改文件） */
    suspend fun setCustomCover(songId: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withLock false
                val bmp = decode(bytes) ?: return@withLock false
                val scale = 512f / maxOf(bmp.width, bmp.height, 1)
                val out = if (scale < 1f) Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                ) else bmp
                customDir.mkdirs()
                File(customDir, "$songId.jpg").outputStream().use {
                    out.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
                File(dir, "$songId.jpg").delete()
                memo.remove(songId)
                revision += 1
                true
            }.getOrDefault(false)
        }
    }

    private fun extract(song: SongEntity, target: File): Boolean {
        val isContent = song.path.startsWith("content://")
        val uri = if (isContent) runCatching { Uri.parse(song.path) }.getOrNull() else null
        if (!isContent && !File(song.path).exists()) return false
        val mmr = MediaMetadataRetriever()
        return try {
            // 以前一律按 File(path) 判定并把路径喂给 MediaMetadataRetriever，
            // 拿到 content:// 形式的 uri 就整个失效 —— 列表里那批封面就是这么空的。
            if (uri != null) mmr.setDataSource(context, uri) else mmr.setDataSource(song.path)
            val bmp = runCatching { mmr.embeddedPicture?.let { decode(it) } }.getOrNull()
                ?: sidecarBitmap(song)
                ?: return false
            val scale = 256f / maxOf(bmp.width, bmp.height, 1)
            val out = if (scale < 1f) Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1), true
            ) else bmp
            target.parentFile?.mkdirs()
            target.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            target.length() > 0
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 大图按尺寸采样，避免 4000px 内嵌封面直接 OOM（OOM 会连带崩掉整行封面） */
    private fun decode(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }

    /**
     * 同目录（再往上一层）里名字是封面专用名的图片。
     *
     * 只看文件名是不是 folder / cover / album / front 这类，**绝不挑「目录里第一张图」**。
     * 以前那两版就是因为这条兜底，把相册里随手拍的照片、下载目录里的截图统统贴到了歌上。
     */
    private fun sidecarBitmap(song: SongEntity): Bitmap? {
        if (song.path.startsWith("content://")) return null
        var dir = runCatching { File(song.path).canonicalFile.parentFile }.getOrNull() ?: return null
        repeat(2) {
            pickCoverIn(dir)?.let { return it }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun pickCoverIn(dir: File): Bitmap? {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return null
        for (name in COVER_NAMES) {
            val hit = files.firstOrNull { file ->
                file.isFile &&
                    file.name.substringBeforeLast('.', "").lowercase() == name &&
                    file.extension.lowercase() in COVER_EXTS
            } ?: continue
            runCatching { BitmapFactory.decodeFile(hit.absolutePath) }.getOrNull()?.let { return it }
        }
        return null
    }
}
