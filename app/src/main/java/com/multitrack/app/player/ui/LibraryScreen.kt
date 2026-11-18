package com.multitrack.app.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.data.PlaylistEntity
import com.multitrack.app.player.data.PlaylistItemEntity
import com.multitrack.app.player.data.QueueRepository
import com.multitrack.app.player.data.SongEntity
import kotlinx.coroutines.launch

private enum class SortKey(val label: String) {
    TITLE("标题"), ARTIST("艺术家"), ALBUM("专辑"), ADDED("最近添加"), DURATION("时长")
}

/** 资料库内的二级页面。以前只有「歌单」一个布尔量，加到三个就得用枚举了 */
private enum class LibPane { LIST, ARTISTS, ALBUMS, PLAYLISTS, SETTINGS }

/**
 * 资料库：全部歌曲 + 排序 + 快搜 + **长按多选批处理**。
 * 批处理支持：加入当前队列 / 播完这首后插入 / 新建歌单 / 批量编辑标签。
 */
@Composable
fun LibraryScreen(
    container: AppContainer,
    onPlayed: () -> Unit,
    onEditTags: (List<SongEntity>) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val all by container.songDao().observeAll().collectAsState(emptyList<SongEntity>())
    var sort by remember { mutableStateOf(SortKey.TITLE) }
    var sortOpen by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf("") }

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var batchMenu by remember { mutableStateOf(false) }

    // 歌单已经并进资料库：这里既是入口，也承接「新建歌单并加入」的命名
    var pane by remember { mutableStateOf(LibPane.LIST) }
    var namingPlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    val playlists by container.db.playlistDao().observeAll().collectAsState(emptyList())

    when (pane) {
        LibPane.ARTISTS -> {
            ArtistsScreen(container, onBack = { pane = LibPane.LIST }, onPlayed = onPlayed, onEditTags = onEditTags)
            return
        }
        LibPane.ALBUMS -> {
            AlbumsScreen(container, onBack = { pane = LibPane.LIST }, onPlayed = onPlayed, onEditTags = onEditTags)
            return
        }
        LibPane.PLAYLISTS -> {
            PlaylistsScreen(container, onBack = { pane = LibPane.LIST }, onPlayed = onPlayed)
            return
        }
        LibPane.SETTINGS -> {
            ScanSettingsScreen(container, onBack = { pane = LibPane.LIST })
            return
        }
        else -> Unit
    }

    val sorted = remember(all, sort, quick) {
        val filtered = if (quick.isBlank()) all else all.filter {
            // 错一两个字也认：包含 → 子序列 → 编辑距离，见 SongSearch
            com.multitrack.app.player.data.SongSearch.matches(quick, it.title, it.artist, it.album)
        }
        when (sort) {
            SortKey.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortKey.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            SortKey.ALBUM -> filtered.sortedBy { it.album.lowercase() }
            SortKey.ADDED -> filtered.sortedByDescending { it.dateAdded }
            SortKey.DURATION -> filtered.sortedBy { it.durationMs }
        }
    }

    val picked: List<SongEntity> = remember(selected, all) { all.filter { it.id in selected } }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
        if (selected.isEmpty()) selecting = false
    }

    Column(modifier = Modifier.fillMaxSize().background(PColors.Bg).statusBarsPadding()) {
        if (!selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text("全部歌曲 ${all.size}", color = PColors.Text, fontSize = 17.sp)
                    Text("  v$APP_VERSION", color = PColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
                IconButton(onClick = { container.rescan() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描", tint = PColors.TextDim)
                }
                IconButton(onClick = { pane = LibPane.SETTINGS }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "扫描设置", tint = PColors.TextDim)
                }
                // 菜单必须和按钮在同一个 Box 里：DropdownMenu 是弹出层、不占布局，
                // 直接放在 Row 里当兄弟节点的话，它的「锚点」就变成 Row 的起点 ——
                // 按钮在右上角、菜单却从左上角弹出来（就是那个错位）。
                Box {
                    IconButton(onClick = { sortOpen = true }) {
                        Icon(Icons.Rounded.Sort, contentDescription = null, tint = PColors.TextDim)
                    }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        SortKey.entries.forEach { key ->
                            DropdownMenuItem(
                                text = { Text("按${key.label}", color = if (key == sort) PColors.Accent else PColors.Text) },
                                onClick = { sort = key; sortOpen = false }
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().background(PColors.SurfaceAlt).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已选 ${selected.size}", color = PColors.Accent, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = sorted.map { it.id }.toSet() }) {
                    Text("全选", color = PColors.TextDim, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    selected = sorted.map { it.id }.toSet() - selected
                    if (selected.isEmpty()) selecting = false
                }) {
                    Text("反选", color = PColors.TextDim, fontSize = 12.sp)
                }
                Box {
                    IconButton(onClick = { batchMenu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = PColors.Text)
                    }
                    DropdownMenu(expanded = batchMenu, onDismissRequest = { batchMenu = false }) {
                        DropdownMenuItem(text = { Text("加入当前队列") }, onClick = {
                            batchMenu = false
                            scope.launch {
                                val qid = container.queueRepo.activeQueueIdOnce() ?: return@launch
                                container.queueRepo.append(qid, picked.map { it.id })
                                selecting = false; selected = emptySet()
                            }
                        })
                        DropdownMenuItem(text = { Text("播完这首后插入") }, onClick = {
                            batchMenu = false
                            scope.launch {
                                val qid = container.queueRepo.activeQueueIdOnce() ?: return@launch
                                container.queueRepo.insertAfterCursor(qid, picked.map { it.id })
                                selecting = false; selected = emptySet()
                            }
                        })
                        DropdownMenuItem(text = { Text("新建歌单并加入") }, onClick = {
                            batchMenu = false
                            // 不再自动生成「歌单 12345」：先让用户命名
                            newPlaylistName = ""
                            namingPlaylist = true
                        })
                        DropdownMenuItem(text = { Text("批量编辑标签 / 封面") }, onClick = {
                            batchMenu = false
                            if (picked.isNotEmpty()) onEditTags(picked)
                        })
                        DropdownMenuItem(text = { Text("用选中歌曲建队列") }, onClick = {
                            batchMenu = false
                            scope.launch {
                                val id = container.queueRepo.createFromSongs(
                                    "选中 ${picked.size} 首", QueueRepository.T_MANUAL, 0, picked.map { it.id }
                                )
                                if (id != null) { container.playback.activate(id, true); onPlayed() }
                            }
                        })
                    }
                }
                IconButton(onClick = { selecting = false; selected = emptySet() }) {
                    Icon(Icons.Rounded.Close, contentDescription = "退出多选", tint = PColors.TextDim)
                }
            }
        }

        if (!selecting) {
            // 顶部入口：歌手 / 专辑都是**按标签现聚合**的（不落表，标签一改立刻跟着变），
            // 歌单是自己存的一张表。三个入口同一套行样式，扫一眼就知道曲库长什么样。
            val artistCount = remember(all) { groupByArtist(all).size }
            val albumCount = remember(all) { groupByAlbum(all).size }
            LibraryEntryRow(
                icon = Icons.Rounded.Person,
                title = "歌手",
                subtitle = "$artistCount 位 · 按音乐标签自动归并",
                onClick = { pane = LibPane.ARTISTS }
            )
            LibraryEntryRow(
                icon = Icons.Rounded.Album,
                title = "专辑",
                subtitle = "$albumCount 张 · 按音乐标签自动归并",
                onClick = { pane = LibPane.ALBUMS }
            )
            LibraryEntryRow(
                icon = Icons.Rounded.PlaylistPlay,
                title = "歌单",
                subtitle = "${playlists.size} 个 · 新建 / 导入 M3U / 导出",
                onClick = { pane = LibPane.PLAYLISTS }
            )

            OutlinedTextField(
                value = quick,
                onValueChange = { quick = it },
                placeholder = { Text("在该列表中搜索…", color = PColors.TextWeak, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = PColors.TextWeak) },
                trailingIcon = {
                    if (quick.isNotEmpty()) {
                        IconButton(onClick = { quick = "" }) {
                            Icon(
                                Icons.Rounded.Close, contentDescription = "清空",
                                tint = PColors.TextWeak, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            // 底部导航浮在内容上，最后几行要留出空间
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
        ) {
            items(sorted, key = { it.id }) { song ->
                var menu by remember { mutableStateOf(false) }
                SongRow(
                    song = song,
                    art = container.art,
                    isCurrent = false,
                    selected = selecting && song.id in selected,
                    onClick = {
                        if (selecting) {
                            toggle(song.id)
                        } else {
                            scope.launch {
                                val activeId = container.queueRepo.activeQueueIdOnce()
                                val inQueue = activeId?.let { container.queueRepo.songs(it) } ?: emptyList()
                                val hit = inQueue.indexOfFirst { it.id == song.id }
                                if (activeId != null && hit >= 0) {
                                    container.playback.activate(activeId, true, startSongId = song.id)
                                } else {
                                    val newId = container.queueRepo.createFromSongs(
                                        "全部歌曲", QueueRepository.T_MANUAL, 0, sorted.map { it.id }
                                    ) ?: return@launch
                                    container.playback.activate(newId, true, startSongId = song.id)
                                }
                                onPlayed()
                            }
                        }
                    },
                    onLongClick = {
                        if (!selecting) {
                            selecting = true
                            selected = setOf(song.id)
                        } else {
                            toggle(song.id)
                        }
                    },
                    trailing = {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = PColors.TextWeak)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("编辑标签 / 封面") }, onClick = {
                                    menu = false
                                    onEditTags(listOf(song))
                                })
                                DropdownMenuItem(text = { Text("用全部歌曲生成队列") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        val id = container.queueRepo.createFromSongs(
                                            "全部歌曲", QueueRepository.T_MANUAL, 0, sorted.map { it.id }
                                        )
                                        if (id != null) {
                                            container.playback.activate(id, autoPlay = true)
                                            onPlayed()
                                        }
                                    }
                                })
                                DropdownMenuItem(text = { Text("加入当前队列") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        val active = container.queueRepo.activeQueueIdOnce()
                                        if (active != null) container.queueRepo.append(active, listOf(song.id))
                                    }
                                })
                                DropdownMenuItem(text = { Text("播完这首后插入") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        val active = container.queueRepo.activeQueueIdOnce()
                                        if (active != null) container.queueRepo.insertAfterCursor(active, listOf(song.id))
                                    }
                                })
                                DropdownMenuItem(text = { Text("从这首开始播放") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        val id = container.queueRepo.createFromSongs(
                                            song.album, QueueRepository.T_ALBUM, 0L,
                                            sorted.filter { it.album == song.album }.map { it.id }
                                        )
                                        if (id != null) container.playback.activate(id, autoPlay = true)
                                        onPlayed()
                                    }
                                })
                                DropdownMenuItem(text = { Text("多选…") }, onClick = {
                                    menu = false
                                    selecting = true
                                    selected = setOf(song.id)
                                })
                            }
                        }
                    }
                )
            }
            if (sorted.isEmpty()) item { EmptyHint("没有找到歌曲，右上角刷新可重扫") }
        }
    }

    if (namingPlaylist) {
        AlertDialog(
            onDismissRequest = { namingPlaylist = false },
            title = { Text("新建歌单", color = PColors.Text) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("给歌单起个名字", color = PColors.TextWeak, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newPlaylistName.trim().ifEmpty { "歌单 ${System.currentTimeMillis() % 10000}" }
                    namingPlaylist = false
                    newPlaylistName = ""
                    scope.launch {
                        val dao = container.db.playlistDao()
                        val plId = dao.insert(PlaylistEntity(name = n))
                        dao.insertItems(
                            picked.mapIndexed { i, s -> PlaylistItemEntity(playlistId = plId, songId = s.id, order = i) }
                        )
                        selecting = false
                        selected = emptySet()
                    }
                }) { Text("创建", color = PColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { namingPlaylist = false }) { Text("取消", color = PColors.TextDim) }
            }
        )
    }
}
