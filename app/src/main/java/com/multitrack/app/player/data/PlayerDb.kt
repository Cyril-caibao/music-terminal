package com.multitrack.app.player.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class SongPathMeta(val id: Long, val path: String, val sizeBytes: Long, val mtime: Long)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE folderId = :folderId ORDER BY title COLLATE NOCASE ASC")
    fun observeByFolder(folderId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC LIMIT 500")
    fun observeRecent(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun allOnce(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun byId(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY disc, track")
    suspend fun byAlbum(album: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY title COLLATE NOCASE")
    suspend fun byArtist(artist: String): List<SongEntity>

    @Query("SELECT id, path, sizeBytes, mtime FROM songs")
    suspend fun pathMeta(): List<SongPathMeta>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Update
    suspend fun update(song: SongEntity)

    @Query("UPDATE songs SET title=:title, artist=:artist, album=:album, albumArtist=:albumArtist, " +
        "genre=:genre, year=:year, track=:track, disc=:disc WHERE id=:id")
    suspend fun updateTags(
        id: Long, title: String, artist: String, album: String, albumArtist: String,
        genre: String, year: Int, track: Int, disc: Int
    )
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE parentId = :parentId ORDER BY name COLLATE NOCASE")
    fun observeChildren(parentId: Long): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY path")
    suspend fun allOnce(): List<FolderEntity>

    @Query("SELECT * FROM folders ORDER BY path")
    fun observeAll(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: List<FolderEntity>)

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int

    @Query("DELETE FROM folders")
    suspend fun clear()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    /** 重命名。歌单名只是展示用，队列里存的是当时的名字（建队列时快照），改这里不影响在播队列 */
    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :id")
    suspend fun itemCount(id: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :id AND songId = :songId")
    suspend fun countSong(id: Long, songId: Long): Int

    @Query("SELECT MAX(`order`) FROM playlist_items WHERE playlistId = :id")
    suspend fun maxOrder(id: Long): Int?

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY `order`")
    suspend fun items(id: Long): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY `order`")
    fun observeItems(id: Long): Flow<List<PlaylistItemEntity>>

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    suspend fun clearItems(id: Long)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queues ORDER BY updatedAt DESC")
    fun observeQueues(): Flow<List<QueueEntity>>

    @Query("SELECT * FROM queues ORDER BY updatedAt DESC")
    suspend fun queuesOnce(): List<QueueEntity>

    @Query("SELECT * FROM queues WHERE id = :id")
    suspend fun queue(id: Long): QueueEntity?

    @Query("SELECT * FROM queues WHERE isActive = 1 LIMIT 1")
    suspend fun activeQueue(): QueueEntity?

    @Query("SELECT * FROM queues WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<QueueEntity?>

    @Insert
    suspend fun insertQueue(q: QueueEntity): Long

    @Update
    suspend fun updateQueue(q: QueueEntity)

    @Query("UPDATE queues SET isActive = 0 WHERE id != :keepId")
    suspend fun deactivateOthers(keepId: Long)

    @Query("UPDATE queues SET isActive = 1, updatedAt = :at WHERE id = :id")
    suspend fun activate(id: Long, at: Long)

    @Query("DELETE FROM queues WHERE id = :id")
    suspend fun deleteQueue(id: Long)

    @Query("DELETE FROM queue_items WHERE queueId = :id")
    suspend fun clearItems(id: Long)

    @Query("SELECT * FROM queue_items WHERE queueId = :id ORDER BY `order`")
    suspend fun items(id: Long): List<QueueItemEntity>

    @Query("SELECT * FROM queue_items WHERE queueId = :id ORDER BY `order`")
    fun observeItems(id: Long): Flow<List<QueueItemEntity>>

    @Query("SELECT s.* FROM queue_items qi INNER JOIN songs s ON s.id = qi.songId WHERE qi.queueId = :id ORDER BY qi.`order`")
    suspend fun songs(id: Long): List<SongEntity>

    @Query("SELECT s.* FROM queue_items qi INNER JOIN songs s ON s.id = qi.songId WHERE qi.queueId = :id ORDER BY qi.`order`")
    fun observeSongs(id: Long): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM queue_items WHERE queueId = :id")
    suspend fun count(id: Long): Int

    @Query("SELECT COUNT(*) FROM queues")
    suspend fun queueCount(): Int

    @Insert
    suspend fun insertItems(items: List<QueueItemEntity>)

    @Insert
    suspend fun insertItem(item: QueueItemEntity): Long

    @Query("DELETE FROM queue_items WHERE queueId = :id AND songId = :songId")
    suspend fun removeSong(id: Long, songId: Long)

    @Query("SELECT MAX(`order`) FROM queue_items WHERE queueId = :id")
    suspend fun maxOrder(id: Long): Int?

    @Query("UPDATE queue_items SET `order` = :order WHERE id = :itemId")
    suspend fun setOrder(itemId: Long, order: Int)

    @Query("UPDATE queues SET cursorIndex = :index, cursorPositionMs = :pos, updatedAt = :at WHERE id = :id")
    suspend fun saveCursor(id: Long, index: Int, pos: Long, at: Long)

    @Query("SELECT * FROM queue_items WHERE queueId = :id AND `order` >= :order ORDER BY `order`")
    suspend fun itemsFrom(id: Long, order: Int): List<QueueItemEntity>
}

@Dao
interface StatDao {
    @Insert
    suspend fun insert(e: StatEventEntity)

    @Query("SELECT COUNT(*) FROM stat_events WHERE songId = :songId AND type = 'play'")
    suspend fun plays(songId: Long): Int
}

@Database(
    entities = [
        SongEntity::class, FolderEntity::class, PlaylistEntity::class, PlaylistItemEntity::class,
        QueueEntity::class, QueueItemEntity::class, BookmarkEntity::class, EqProfileEntity::class,
        StatEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PlayerDb : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun folderDao(): FolderDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao
    abstract fun statDao(): StatDao
}
