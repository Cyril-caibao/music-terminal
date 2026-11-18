package com.multitrack.app.player.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * 多队列的唯一真相源。播放器只是它的投影：
 * 切队列 = 保存当前队列游标 → 把目标队列的曲目塞给播放器 → 从它自己的 cursor 复位。
 * 编辑非活动队列全程只碰数据库，播放器一点都不用动。
 */
class QueueRepository(
    private val db: PlayerDb,
    private val queueDao: QueueDao = db.queueDao(),
    private val songDao: SongDao = db.songDao()
) {

    companion object {
        const val MAX_QUEUES = 20
        const val T_ALBUM = "album"
        const val T_FOLDER = "folder"
        const val T_ARTIST = "artist"
        const val T_PLAYLIST = "playlist"
        const val T_MANUAL = "manual"
    }

    private val q = db.queueDao()

    fun observeQueues(): Flow<List<QueueEntity>> = q.observeQueues()
    fun observeQueueSongs(queueId: Long): Flow<List<SongEntity>> = q.observeSongs(queueId)
    fun observeActive(): Flow<QueueEntity?> = q.observeActive()

    suspend fun songs(queueId: Long): List<SongEntity> = q.songs(queueId)

    suspend fun activeQueueIdOnce(): Long? = q.activeQueue()?.id
    suspend fun anyQueueIdOnce(): Long? = q.queuesOnce().firstOrNull()?.id
    suspend fun queue(queueId: Long): QueueEntity? = q.queue(queueId)
    suspend fun count(): Int = q.queueCount()

    suspend fun createManual(name: String = "新建队列"): Long? =
        if (q.queueCount() >= MAX_QUEUES) null else q.insertQueue(
            QueueEntity(name = name, sourceType = T_MANUAL, sourceId = 0)
        )

    suspend fun createFromSongs(name: String, sourceType: String, sourceId: Long, songs: List<Long>): Long? {
        if (q.queueCount() >= MAX_QUEUES) return null
        if (songs.isEmpty()) return null
        return db.withTransaction {
            val id = q.insertQueue(QueueEntity(name = name, sourceType = sourceType, sourceId = sourceId))
            q.insertItems(songs.mapIndexed { i, s -> QueueItemEntity(queueId = id, songId = s, order = i) })
            id
        }
    }

    suspend fun createFromAlbum(album: String): Long? {
        val songs = songDao.byAlbum(album).map { it.id }
        return createFromSongs(album, T_ALBUM, MediaScanner.folderIdOf("album:$album"), songs)
    }

    suspend fun createFromArtist(artist: String): Long? {
        val songs = songDao.byArtist(artist).map { it.id }
        return createFromSongs(artist, T_ARTIST, MediaScanner.folderIdOf("artist:$artist"), songs)
    }

    suspend fun createFromFolder(folderId: Long, name: String): Long? {
        val songs = songDao.allOnce().filter { it.folderId == folderId }.map { it.id }
        return createFromSongs(name, T_FOLDER, folderId, songs)
    }

    suspend fun createFromPlaylist(playlistId: Long, name: String, items: List<Long>): Long? =
        createFromSongs(name, T_PLAYLIST, playlistId, items)

    /** 追加到队尾 */
    suspend fun append(queueId: Long, songIds: List<Long>) = db.withTransaction {
        var next = (q.maxOrder(queueId) ?: -1) + 1
        q.insertItems(songIds.map { QueueItemEntity(queueId = queueId, songId = it, order = next++) })
        q.updateQueue(q.queue(queueId)?.copy(updatedAt = System.currentTimeMillis()) ?: return@withTransaction)
    }

    /** 播完当前这首之后插入（严格插在游标后面） */
    suspend fun insertAfterCursor(queueId: Long, songIds: List<Long>) = db.withTransaction {
        val queue = q.queue(queueId) ?: return@withTransaction
        val tail = q.itemsFrom(queueId, queue.cursorIndex + 1)
        val next = queue.cursorIndex + 1
        // 先把后面的整体后移，腾出位置
        tail.asReversed().forEach { q.setOrder(it.id, it.order + songIds.size) }
        q.insertItems(songIds.mapIndexed { i, s -> QueueItemEntity(queueId = queueId, songId = s, order = next + i) })
        q.updateQueue(queue.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * 从活动队列删歌：若删掉的位置在游标之前，游标左移，否则会出现「删了前面一首就跳曲」。
     */
    suspend fun removeSong(queueId: Long, songId: Long) = db.withTransaction {
        val queue = q.queue(queueId) ?: return@withTransaction
        val items = q.items(queueId)
        val target = items.firstOrNull { it.songId == songId } ?: return@withTransaction
        q.removeSong(queueId, songId)
        val shifted = items.filter { it.id != target.id }
        shifted.forEachIndexed { i, it -> if (it.order != i) q.setOrder(it.id, i) }
        val cursor = when {
            target.order < queue.cursorIndex -> (queue.cursorIndex - 1).coerceAtLeast(0)
            target.order == queue.cursorIndex -> queue.cursorIndex.coerceAtMost((shifted.size - 1).coerceAtLeast(0))
            else -> queue.cursorIndex
        }
        q.saveCursor(queueId, cursor, 0, System.currentTimeMillis())
    }

    suspend fun moveItem(queueId: Long, from: Int, to: Int) = db.withTransaction {
        val items = q.items(queueId).toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return@withTransaction
        val moved = items.removeAt(from)
        items.add(to, moved)
        items.forEachIndexed { i, it -> q.setOrder(it.id, i) }
        val cursor = q.queue(queueId)?.cursorIndex ?: 0
        q.saveCursor(queueId, cursor.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0, System.currentTimeMillis())
    }

    suspend fun clear(queueId: Long) = db.withTransaction {
        q.clearItems(queueId)
        q.saveCursor(queueId, 0, 0, System.currentTimeMillis())
    }

    suspend fun rename(queueId: Long, name: String) {
        q.queue(queueId)?.let { q.updateQueue(it.copy(name = name, updatedAt = System.currentTimeMillis())) }
    }

    /**
     * 歌单改名后，把由它生成的那些队列一起改名。
     * 队列名是建队列时的快照，不改的话队列页里还挂着旧名字，看起来像「改名没生效」。
     */
    suspend fun renameFromPlaylist(playlistId: Long, name: String) {
        q.queuesOnce()
            .filter { it.sourceType == T_PLAYLIST && it.sourceId == playlistId }
            .forEach { q.updateQueue(it.copy(name = name, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun delete(queueId: Long) = db.withTransaction {
        q.clearItems(queueId)
        q.deleteQueue(queueId)
    }

    suspend fun clearOthers(keepId: Long) = db.withTransaction {
        q.queuesOnce().filter { it.id != keepId }.forEach {
            q.clearItems(it.id)
            q.deleteQueue(it.id)
        }
    }

    suspend fun activate(queueId: Long) = db.withTransaction {
        q.activate(queueId, System.currentTimeMillis())
        q.deactivateOthers(queueId)
        q.queue(queueId)
    }

    suspend fun saveCursor(queueId: Long, index: Int, posMs: Long) {
        q.saveCursor(queueId, index, posMs, System.currentTimeMillis())
    }

    suspend fun appendToQueueBySongIds(queueId: Long, songIds: List<Long>) = append(queueId, songIds)
}
