package com.multitrack.app.player.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 标签写回。流程刻意做成「临时文件 → 改标签 → 整文件回写」：
 * 1) 原文件是 MediaStore 里的共享文件，直接随机写容易写坏；
 * 2) 临时副本让 jaudiotagger 能按真实文件路径解析容器；
 * 3) 回写走 ContentResolver 输出流，配合 MediaStore 写请求授权，不需要 MANAGE_EXTERNAL_STORAGE。
 */
class TagWriter(private val context: Context) {

    data class Edits(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val track: Int? = null,
        val disc: Int? = null,
        val cover: Uri? = null,
        val lyrics: String? = null
    )

    suspend fun write(song: SongEntity, edits: Edits): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(song.uri)
            // jaudiotagger 是按扩展名选 Reader 的：之前用 .tmp，直接报
            // "No Reader associated with this extension:tmp"，一个标签都写不进去。
            val ext = song.path.substringAfterLast('.', "")
                .takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
                ?: song.uri.substringAfterLast('.', "mp3").take(5).ifBlank { "mp3" }
            val tmp = File(context.cacheDir, "tag_${song.id}.$ext")
            if (tmp.exists()) tmp.delete()

            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: error("无法读取：${song.path}")

            val audio = AudioFileIO.read(tmp)
            val tag = audio.tag ?: audio.createDefaultTag().also { audio.tag = it }

            edits.title?.let { tag.setField(FieldKey.TITLE, it) }
            edits.artist?.let { tag.setField(FieldKey.ARTIST, it) }
            edits.album?.let { tag.setField(FieldKey.ALBUM, it) }
            edits.albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
            edits.genre?.let { tag.setField(FieldKey.GENRE, it) }
            edits.year?.let { if (it > 0) tag.setField(FieldKey.YEAR, it.toString()) }
            edits.track?.let { if (it > 0) tag.setField(FieldKey.TRACK, it.toString()) }
            edits.disc?.let { if (it > 0) tag.setField(FieldKey.DISC_NO, it.toString()) }
            edits.lyrics?.let { runCatching { tag.setField(FieldKey.LYRICS, it) } }

            edits.cover?.let { coverUri ->
                // 不用 ArtworkFactory.createArtworkFromFile：它按 TagOptionSingleton.isAndroid()
                // 选实现，一旦落到 StandardArtwork 就会调 javax.imageio.ImageIO —— Android 上没有
                // 这个类，写封面直接抛 NoClassDefFoundError: Ljavax/imageio/ImageIO;。
                // 这里改用 BitmapFactory 解码、限尺寸、统一压成 JPEG，再通过 Artwork 接口写二进制。
                val raw = context.contentResolver.openInputStream(coverUri)?.use { it.readBytes() }
                    ?: error("无法读取所选封面")
                val jpeg = ByteArrayOutputStream().use { bos ->
                    val src = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        ?: error("封面图片无法解码（格式不支持）")
                    val sw = src.width
                    val sh = src.height
                    val longSide = if (sw > sh) sw else sh
                    val maxSide = 1000
                    val bmp = if (longSide > maxSide) {
                        val k = maxSide.toFloat() / longSide
                        android.graphics.Bitmap.createScaledBitmap(
                            src,
                            (sw * k).toInt().coerceAtLeast(1),
                            (sh * k).toInt().coerceAtLeast(1),
                            true
                        ).also { if (it !== src) src.recycle() }
                    } else src
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bos)
                    bmp.recycle()
                    bos.toByteArray()
                }
                runCatching { tag.deleteArtworkField() }
                val art = ArtworkFactory.getNew()
                art.binaryData = jpeg
                art.mimeType = "image/jpeg"
                art.pictureType = 3 // APIC：Front Cover
                tag.setField(art)
            }

            audio.commit()

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            } ?: error("没有写权限")

            tmp.delete()

            // 让系统媒体库也跟着更新（否则相册/文件管理器里还是旧标签）
            runCatching {
                val values = ContentValues().apply {
                    edits.title?.let { put(MediaStore.Audio.Media.TITLE, it) }
                    edits.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                    edits.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                    edits.genre?.let { put(MediaStore.Audio.Media.GENRE, it) }
                    edits.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
                    edits.track?.let { put(MediaStore.Audio.Media.TRACK, it) }
                }
                if (values.size() > 0) context.contentResolver.update(uri, values, null, null)
            }
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null)
            }
            Unit
        }
    }
}
