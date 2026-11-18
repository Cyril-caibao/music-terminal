package com.multitrack.app.player.data

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            toWrite.add(s)
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

    private fun queryMediaStore(opts: ScanSettingsStore.Options): List<SongEntity> {
        val cols = arrayOf(
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
        val out = ArrayList<SongEntity>(1024)
        val seenPath = HashSet<String>(2048)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cols,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iMtime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                val dur = c.getLong(iDur)
                if (dur < opts.minDurationSec * 1000L) continue
                if (!ScanFilter.includePath(path, opts.scanRoots, opts.excludeDirs)) continue
                if (!seenPath.add(path)) continue
                val id = c.getLong(iId)
                val dir = path.substringBeforeLast('/', "")
                out.add(
                    SongEntity(
                        id = id,
                        uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                            .appendPath(id.toString()).build().toString(),
                        path = path,
                        folderId = folderIdOf(dir),
                        title = titleOf(c.getString(iTitle), path),
                        artist = clean(c.getString(iArtist), UNKNOWN),
                        album = clean(c.getString(iAlbum), UNKNOWN),
                        durationMs = dur,
                        sizeBytes = c.getLong(iSize),
                        mtime = c.getLong(iMtime),
                        track = c.getInt(iTrack),
                        year = c.getInt(iYear),
                        dateAdded = c.getLong(iAdded) * 1000L
                    )
                )
            }
        }
        return out
    }

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
