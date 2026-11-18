package com.multitrack.app.player.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 曲目索引。id 直接沿用 MediaStore 的 _id，重扫/增量都对得上。 */
@Entity(tableName = "songs", indices = [Index("path", unique = true), Index("folderId"), Index("artist"), Index("album")])
data class SongEntity(
    @PrimaryKey val id: Long,
    val uri: String,
    val path: String,
    val folderId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val composer: String = "",
    val genre: String = "",
    val year: Int = 0,
    val track: Int = 0,
    val disc: Int = 0,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val mtime: Long = 0,
    val artworkPath: String? = null,
    val hasEmbeddedLyrics: Boolean = false,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long = 0,
    val dateAdded: Long = 0
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: Long,
    val path: String,
    val parentId: Long,
    val name: String
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_items", indices = [Index("playlistId")])
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val order: Int
)

/** 每条队列各自维护游标与位置，isActive 只会有一条为 true。 */
@Entity(tableName = "queues")
data class QueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceType: String,          // album | folder | artist | playlist | manual
    val sourceId: Long,
    val cursorIndex: Int = 0,
    val cursorPositionMs: Long = 0,
    val shuffleMode: Int = 0,        // 0 关 / 1 单曲循环 / 2 全部循环 / 3 随机
    val repeatMode: Int = 1,
    val isActive: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "queue_items", indices = [Index("queueId"), Index("songId")])
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queueId: Long,
    val songId: Long,
    val order: Int
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val songId: Long,
    val positionMs: Long,
    val note: String = ""
)

@Entity(tableName = "eq_profiles")
data class EqProfileEntity(
    @PrimaryKey val deviceKey: String,
    val bandCount: Int,
    val gains: String,               // 逗号分隔
    val presetName: String = "",
    val headroomDb: Float = 0f
)

@Entity(tableName = "stat_events", indices = [Index("songId")])
data class StatEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val type: String,                // play | skip | complete
    val at: Long = System.currentTimeMillis()
)
