package com.multitrack.app.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.data.FolderEntity
import com.multitrack.app.player.data.QueueRepository
import com.multitrack.app.player.data.SongEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 文件夹线性/层级（M1 只做层级 + 面包屑）。根目录 = 没有被任何文件夹当父节点的那些。
 */
@Composable
fun FoldersScreen(
    container: AppContainer,
    onPlayed: () -> Unit,
    onEditTags: (List<com.multitrack.app.player.data.SongEntity>) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    // 用 Flow 订阅：扫描还在后台跑的时候，目录树会自己长出来
    val folders by container.folderDao().observeAll().collectAsState(emptyList())
    val prefs = container.prefs
    // 上次停在哪个文件夹要记住：以前每次冷启动/切页都弹回根目录，等于每次重走一遍路径
    var currentId by remember {
        mutableStateOf(prefs.getLong("lastFolder", -1L).takeIf { it >= 0L })
    }
    LaunchedEffect(folders, currentId) {
        if (folders.isEmpty()) return@LaunchedEffect
        val id = currentId
        if (id != null && folders.none { it.id == id }) {
            currentId = null
            prefs.edit().remove("lastFolder").apply()
        } else {
            prefs.edit().putLong("lastFolder", id ?: -1L).apply()
        }
    }
    // 多选：长按进入；底部出现「加入歌单 / 新建歌单」
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var joinAsk by remember { mutableStateOf(false) }
    var newAsk by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val playlists by container.db.playlistDao().observeAll()
        .collectAsState(emptyList<com.multitrack.app.player.data.PlaylistEntity>())

    val ids = remember(folders) { folders.map { it.id }.toSet() }
    val current = currentId?.let { id -> folders.firstOrNull { it.id == id } }
    val children = remember(folders, currentId, ids) {
        if (currentId == null) folders.filter { it.parentId !in ids }
        else folders.filter { it.parentId == currentId }
    }
    val songsFlow = remember(currentId) {
        currentId?.let { container.songDao().observeByFolder(it) } ?: flowOf(emptyList<SongEntity>())
    }
    val songs by songsFlow.collectAsState(emptyList())

    val crumbs = remember(current, folders) {
        val list = ArrayList<FolderEntity>()
        var node = current
        while (node != null) {
            list.add(0, node)
            node = folders.firstOrNull { it.id == node!!.parentId }
        }
        list
    }

    Column(modifier = Modifier.fillMaxSize().background(PColors.Bg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (current != null) {
                IconButton(onClick = { currentId = folders.firstOrNull { it.id == current.parentId }?.id }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = PColors.TextDim)
                }
            }
            Text(
                text = if (crumbs.isEmpty()) "文件夹" else crumbs.joinToString(" / ") { it.name },
                color = PColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (current != null && songs.isNotEmpty()) {
                TextButton(onClick = {
                    // 整个文件夹一次导成歌单
                    scope.launch {
                        val dao = container.db.playlistDao()
                        val pid = dao.insert(com.multitrack.app.player.data.PlaylistEntity(name = current.name))
                        dao.insertItems(
                            songs.mapIndexed { i, sg ->
                                com.multitrack.app.player.data.PlaylistItemEntity(
                                    playlistId = pid, songId = sg.id, order = i
                                )
                            }
                        )
                    }
                }) { Text("整夹→歌单", color = PColors.Accent, fontSize = 12.sp) }
            }
            if (songs.isNotEmpty()) {
                TextButton(onClick = {
                    if (selecting) {
                        selecting = false; selected = emptySet()
                    } else selecting = true
                }) { Text(if (selecting) "取消" else "多选", color = PColors.TextDim, fontSize = 12.sp) }
            }
            if (current != null) {
                IconButton(onClick = {
                    scope.launch {
                        val id = container.queueRepo.createFromFolder(current.id, current.name)
                        if (id != null) {
                            container.playback.activate(id, autoPlay = true)
                            onPlayed()
                        }
                    }
                }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = PColors.TextDim)
                }
            }
        }

        // 多选操作条
        if (selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已选 ${selected.size} 首", color = PColors.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { joinAsk = true }
                ) { Text("加入歌单", color = if (selected.isNotEmpty()) PColors.Accent else PColors.TextWeak, fontSize = 12.sp) }
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { newName = current?.name ?: "新歌单"; newAsk = true }
                ) { Text("新建歌单", color = if (selected.isNotEmpty()) PColors.Accent else PColors.TextWeak, fontSize = 12.sp) }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            // 底部导航浮在内容上，最后几行要留出空间
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
        ) {
            items(children, key = { it.id }) { folder ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .clickable { currentId = folder.id }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = PColors.TextDim)
                    Text(
                        folder.name, color = PColors.Text, fontSize = 14.sp,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("›", color = PColors.TextWeak, fontSize = 16.sp)
                }
            }
            items(songs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    art = container.art,
                    isCurrent = false,
                    selected = selecting && selected.contains(song.id),
                    onLongClick = {
                        if (selecting) {
                            selected = if (selected.contains(song.id)) selected - song.id else selected + song.id
                        } else {
                            selecting = true
                            selected = setOf(song.id)
                        }
                    },
                    onClick = tap@{
                        if (selecting) {
                            selected = if (selected.contains(song.id)) selected - song.id else selected + song.id
                            return@tap
                        }
                        scope.launch {
                            val folder = current ?: return@launch
                            // 已经在这个文件夹的队列里就直接复用，否则新建一条
                            val activeId = container.queueRepo.activeQueueIdOnce()
                            val activeQ = activeId?.let { container.queueRepo.queue(it) }
                            val sameFolder = activeQ != null &&
                                activeQ.sourceType == QueueRepository.T_FOLDER &&
                                activeQ.sourceId == folder.id
                            val qid = if (sameFolder) activeId else
                                container.queueRepo.createFromFolder(folder.id, folder.name)
                            if (qid != null) {
                                // 关键：按 songId 定位，不传下标（列表按标题排、队列按插入序，下标会错位）
                                container.playback.activate(qid, autoPlay = true, startSongId = song.id)
                                onPlayed()
                            }
                        }
                    },
                    trailing = {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = PColors.TextWeak)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("编辑标签 / 封面") }, onClick = {
                                    menu = false
                                    onEditTags(listOf(song))
                                })
                            }
                        }
                    }
                )
            }
            if (children.isEmpty() && songs.isEmpty()) {
                item { EmptyHint("这个文件夹里没有音频") }
            }
        }
    }

    if (joinAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { joinAsk = false },
            title = { Text("加入哪个歌单？", color = PColors.Text) },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (playlists.isEmpty()) Text("还没有歌单，先新建一个", color = PColors.TextDim, fontSize = 12.sp)
                    playlists.forEach { p ->
                        TextButton(onClick = {
                            joinAsk = false
                            scope.launch {
                                val dao = container.db.playlistDao()
                                val start = dao.items(p.id).size
                                dao.insertItems(
                                    selected.mapIndexed { i, sid ->
                                        com.multitrack.app.player.data.PlaylistItemEntity(
                                            playlistId = p.id, songId = sid, order = start + i
                                        )
                                    }
                                )
                                selecting = false; selected = emptySet()
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(p.name, color = PColors.Text, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { joinAsk = false }) { Text("取消", color = PColors.TextDim) } }
        )
    }

    if (newAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { newAsk = false },
            title = { Text("新建歌单", color = PColors.Text) },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    newAsk = false
                    val n = newName.trim()
                    if (n.isNotEmpty()) scope.launch {
                        val dao = container.db.playlistDao()
                        val pid = dao.insert(com.multitrack.app.player.data.PlaylistEntity(name = n))
                        dao.insertItems(
                            selected.mapIndexed { i, sid ->
                                com.multitrack.app.player.data.PlaylistItemEntity(
                                    playlistId = pid, songId = sid, order = i
                                )
                            }
                        )
                        selecting = false; selected = emptySet()
                    }
                }) { Text("创建", color = PColors.Accent) }
            },
            dismissButton = { TextButton(onClick = { newAsk = false }) { Text("取消", color = PColors.TextDim) } }
        )
    }
}
