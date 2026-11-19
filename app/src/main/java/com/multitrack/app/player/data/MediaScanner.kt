package com.multitrack.app.player.data

import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 曲库扫描：以 MediaStore 为唯一入口，批量事务写入。
 *
 * 主路径**不解析标签、不抽封面** —— 那两项放到播放/展示时按需做，
 * 否则 1 万首要跑几分钟，冷启动也会被拖死。
 */
class MediaScanner(
    private val context: Context,
    private val songDao: SongDao,
    private val folderDao: FolderDao
) {

    suspend fun scan(onStep: (String) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        // 排除项全局生效的唯一落点：这里过滤掉的文件根本不会进曲库，
        // 播放页 / 文件夹 / 专辑 / 歌手 / 队列自然全部看不到。改设置后重扫一遍即可。
        val opts = ScanSettingsStore(context).load()
        onStep("读取媒体库…")
        val found = queryMediaStore(opts)
        onStep("比对索引…")

        val existing = songDao.pathMeta().associateBy { it.path }
        val toWrite = ArrayList<SongEntity>(found.size)
        val seenIds = HashSet<Long>(found.size * 2)
        for (s in found) {
            seenIds.add(s.id)
            val old = existing[s.path]
            if (old != null && old.sizeBytes == s.sizeBytes && old.mtime == s.mtime) continue
            // 已知文件内容变了（用户改了标签 / 用别的工具换过文件）：MediaStore 的列
            // 滞后于文件本体，直接拿列覆写曲库会把刚改的标签「复原」——
            // 所以这里回读文件里的真实标签入库（首次扫描 old==null 不解析，保持快）。
            toWrite.add(if (old != null) mergeFileTags(s) else s)
        }
        val missing = existing.values.filter { it.id !in seenIds }.map { it.id }

        onStep("写入 ${toWrite.size} 首…")
        toWrite.chunked(400).forEach { songDao.upsert(it) }
        missing.chunked(400).forEach { songDao.deleteByIds(it) }

        onStep("整理文件夹…")
        folderDao.clear()
        folderDao.upsert(buildFolders(found))

        onStep("完成")
        found.size
    }

    /** 内容变了的已知文件：以文件本体的标签为准覆写（解析失败则退回 MediaStore 列） */
    private fun mergeFileTags(s: SongEntity): SongEntity {
        val t = FileTags.read(s.path) ?: return s
        return s.copy(
            title = titleOf(t.title, s.path),
            artist = clean(t.artist, UNKNOWN),
            album = clean(t.album, UNKNOWN),
            albumArtist = t.albumArtist ?: "",
            composer = t.composer ?: "",
            genre = t.genre ?: "",
            year = t.year ?: s.year,
            track = t.track ?: s.track,
            disc = t.disc ?: s.disc
        )
    }

    private fun queryMediaStore(opts: ScanSettingsStore.Options): List<SongEntity> {
        val out = ArrayList<SongEntity>(1024)
        val seenPath = HashSet<String>(2048)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            COLS,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val s = songFrom(c) ?: continue
                if (s.durationMs < opts.minDurationSec * 1000L) continue
                if (!ScanFilter.includePath(s.path, opts.scanRoots, opts.excludeDirs)) continue
                if (!seenPath.add(s.path)) continue
                out.add(s)
            }
        }
        return out
    }

    // ── 外部打开：「用 CyrilMusic 打开」进来的单个文件 ──────────────────────

    /**
     * 把外部传入的单个 uri 变成曲库里的一行，返回可播放的 SongEntity（识别不了返回 null）。
     * 支持 MediaStore 的 content://media/...、SAF 的 .../document/audio%3A123、file://。
     * 文件还不在系统媒体库里（刚下载完）就先请求系统扫描，再查一次。
     */
    suspend fun importUri(uri: Uri): SongEntity? = withContext(Dispatchers.IO) {
        findSong(uri)?.let { return@withContext persistOne(it) }
        // 不在库里：让系统先扫这个文件，再查一次。最多等 2 秒，别把界面拖死
        pathOf(uri)?.let { path ->
            runCatching {
                val latch = CountDownLatch(1)
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> latch.countDown() }
                latch.await(2, TimeUnit.SECONDS)
                Unit
            }
        }
        findSong(uri)?.let { persistOne(it) }
    }

    private suspend fun persistOne(song: SongEntity): SongEntity {
        // 曲库里已经有这一行（同一条 MediaStore 记录）：直接返回，不要拿 MediaStore 的列
        // 再写一遍 —— 那些列里没有流派/专辑艺术家/碟号，REPLACE 会把用户改好的标签抹平。
        runCatching { songDao.byId(song.id) }.getOrNull()?.let { existing ->
            if (existing.path == song.path) return existing
        }
        runCatching { songDao.upsert(listOf(song)) }
        runCatching { folderDao.upsert(buildFolders(listOf(song))) }
        return song
    }

    private fun findSong(uri: Uri): SongEntity? {
        val id = idFromUri(uri)
        val path = pathOf(uri)
        val (sel, args) = when {
            id != null -> "${MediaStore.Audio.Media._ID} = ?" to arrayOf(id.toString())
            path != null -> "${MediaStore.Audio.Media.DATA} = ?" to arrayOf(path)
            else -> return null
        }
        return runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, COLS, sel, args, null)
                ?.use { c -> if (c.moveToFirst()) songFrom(c) else null }
        }.getOrNull()
    }

    /** content://media/.../media/123、SAF 的 .../document/audio%3A123 都能取出 id */
    private fun idFromUri(uri: Uri): Long? =
        uri.lastPathSegment?.substringAfterLast(':')?.toLongOrNull()

    private fun pathOf(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        val id = idFromUri(uri) ?: return null
        return runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /** 一行 MediaStore 记录 → SongEntity（列表扫描与外部打开共用） */
    private fun songFrom(c: Cursor): SongEntity? {
        val path = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: return null
        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
        val dir = path.substringBeforeLast('/', "")
        return SongEntity(
            id = id,
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(id.toString()).build().toString(),
            path = path,
            folderId = folderIdOf(dir),
            title = titleOf(c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)), path),
            artist = clean(c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)), UNKNOWN),
            album = clean(c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)), UNKNOWN),
            durationMs = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)),
            sizeBytes = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)),
            mtime = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)),
            track = c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)),
            year = c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)),
            dateAdded = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)) * 1000L
        )
    }

    private val COLS = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.YEAR
    )

    /**
     * 目录树：不仅建「直接放歌」的目录，也把它的所有祖先补上，
     * 否则面包屑会跳级、上层目录点不进去。根节点的 parentId 固定为 0。
     */
    private fun buildFolders(songs: List<SongEntity>): List<FolderEntity> {
        val map = LinkedHashMap<String, FolderEntity>()
        // 目录树的根固定为内部存储根（/storage/emulated/0）：只从 "/0/" 之后开始展示，
        // SD 卡、其它挂载点、共享目录都不再进树 —— 那些路径点进去往往是空的。
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        for (s in songs) {
            var dir = s.path.substringBeforeLast('/', "")
            if (!dir.startsWith(root)) continue
            while (dir.length > root.length && !map.containsKey(dir)) {
                val parent = dir.substringBeforeLast('/', "")
                map[dir] = FolderEntity(
                    id = folderIdOf(dir),
                    path = dir,
                    parentId = if (parent.length <= root.length) 0L else folderIdOf(parent),
                    name = dir.substringAfterLast('/')
                )
                dir = parent
            }
        }
        return map.values.toList()
    }

    /** 没标签的文件用文件名兜底，不能显示空白或 MediaStore 的 <unknown>。 */
    private fun titleOf(v: String?, path: String): String {
        val s = v?.trim()
        if (!s.isNullOrEmpty() && !s.equals("<unknown>", true)) return s
        return path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "未命名" }
    }

    private fun clean(v: String?, fallback: String): String {
        val s = v?.trim()
        return if (s.isNullOrEmpty() || s.equals("<unknown>", true)) fallback else s
    }

    companion object {
        const val UNKNOWN = "未知"

        /** 文件夹 id 由路径稳定派生：重扫后 id 不变，用户的目录黑名单/书签不会错位。 */
        fun folderIdOf(path: String): Long {
            if (path.isEmpty()) return 0L
            val h = abs(path.lowercase().hashCode()).toLong()
            return if (h == 0L) 1L else h
        }
    }
}
