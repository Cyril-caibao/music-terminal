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

    /**
     * 写回标签。
     * 返回 Result.success(note)：note 为 null 表示全部写入成功；
     * 非 null 是一条**部分成功**的说明 —— 最常见的是该格式的标签不支持内嵌封面
     * （jaudiotagger 对 GenericTag / ID3v1 等调 setField(artwork) 会直接抛
     * "Not implemented for this format"）。此时文字标签照常写盘，
     * 封面由调用方落成应用内封面（covers/<id>.jpg，显示优先于内嵌）。
     */
    suspend fun write(song: SongEntity, edits: Edits): Result<String?> = withContext(Dispatchers.IO) {
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

            var coverNote: String? = null
            var coverEmbedded = false
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
                try {
                    runCatching { tag.deleteArtworkField() }
                    val art = ArtworkFactory.getNew()
                    art.binaryData = jpeg
                    art.mimeType = "image/jpeg"
                    art.pictureType = 3 // APIC：Front Cover
                    tag.setField(art)
                    coverEmbedded = true
                } catch (e: Exception) {
                    // 该格式的标签不支持内嵌（GenericTag / ID3v1 …）：文字标签别跟着陪葬，
                    // 封面走应用内封面的路子（调用方 setCustomCover 落盘，显示优先于内嵌）
                    coverNote = "封面未能写进文件（此格式不支持内嵌封面），已保存为应用内封面"
                }
            }

            try {
                audio.commit()
            } catch (e: Exception) {
                if (!coverEmbedded) throw e
                // 带着封面 commit 失败（个别格式写容器时不认新封面原子）：
                // 摘掉封面重试一次，保住文字标签
                runCatching { tag.deleteArtworkField() }
                audio.commit()
                coverNote = "封面未能写进文件（此格式写入封面失败），已保存为应用内封面"
            }

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            } ?: error("没有写权限")

            tmp.delete()

            // ── 回读验证：改完必须回读文件本体确认，不能嘴上说成功 ──
            // 文件改没改成只有文件自己知道：写流程任何一环出问题（权限、容器、磁盘），
            // 之前都会被包装成「已写入」，重启重扫后用户才发现标签复原。
            val notes = ArrayList<String>()
            val textEdits = listOfNotNull(
                edits.title?.let { "标题" to it },
                edits.artist?.let { "歌手" to it },
                edits.album?.let { "专辑" to it },
                edits.albumArtist?.let { "专辑艺术家" to it },
                edits.genre?.let { "流派" to it }
            )
            if (textEdits.isNotEmpty()) {
                val vt = readBackTags(song, uri, ext)
                when {
                    vt == null -> notes.add("文件已写入（本机不允许回读验证）")
                    else -> {
                        val bad = mutableListOf<String>()
                        if (edits.title != null && vt.title != edits.title) bad.add("标题")
                        if (edits.artist != null && vt.artist != edits.artist) bad.add("歌手")
                        if (edits.album != null && vt.album != edits.album) bad.add("专辑")
                        if (edits.albumArtist != null && vt.albumArtist != edits.albumArtist) bad.add("专辑艺术家")
                        if (edits.genre != null && vt.genre != edits.genre) bad.add("流派")
                        if (bad.isNotEmpty()) {
                            error("写入未生效，文件内容没有变化（${bad.joinToString("、")}被存储位置拒绝改写）")
                        }
                    }
                }
            }

            // 让系统媒体库的列也跟上（否则相册/文件管理器里还是旧标签）。
            // GENRE 列是 Android 11（API 30）才加进 Audio.Media 的，低版本写了必抛。
            runCatching {
                val values = ContentValues().apply {
                    edits.title?.let { put(MediaStore.Audio.Media.TITLE, it) }
                    edits.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                    edits.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        edits.genre?.let { put(MediaStore.Audio.Media.GENRE, it) }
                    }
                    edits.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
                    edits.track?.let { put(MediaStore.Audio.Media.TRACK, it) }
                }
                if (values.size() > 0) context.contentResolver.update(uri, values, null, null)
            }.onFailure {
                notes.add("系统媒体库列未同步（${it.message ?: it.javaClass.simpleName}），已请求重扫")
            }
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null)
            }

            coverNote?.let { notes.add(it) }
            notes.joinToString("；").ifEmpty { null }
        }
    }

    /**
     * 回读文件里的真实标签。Android 11+ 允许带音频读权限直接按路径读媒体文件；
     * Android 10 不允许路径访问，就复制一份再读 —— 都失败就返回 null（无法验证）。
     */
    private fun readBackTags(song: SongEntity, uri: Uri, ext: String): FileTags.Tags? {
        FileTags.read(song.path)?.let { return it }
        val tmp = File(context.cacheDir, "tag_verify_${song.id}.$ext")
        return runCatching {
            if (tmp.exists()) tmp.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return null
            FileTags.read(tmp.absolutePath)
        }.getOrNull().also { tmp.delete() }
    }
}
