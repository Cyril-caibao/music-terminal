package com.multitrack.app.player.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.data.QueueEntity
import com.multitrack.app.player.data.SongEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 队列页（对标截图 1）：顶部队列选择器 + 播放控制行 + 计数，
 * 列表带拖拽柄、当前曲目绿色描边，底部「在该队列中搜索」。
 */
@Composable
fun QueueScreen(container: AppContainer, onJumpToPlayer: () -> Unit, onEditTags: (List<SongEntity>) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val queues by container.queueRepo.observeQueues().collectAsState(emptyList<QueueEntity>())
    val playback by container.playback.state.collectAsState()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var selectorOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf("") }

    LaunchedEffect(queues) {
        if (selectedId == null || queues.none { it.id == selectedId }) {
            selectedId = queues.firstOrNull { it.isActive }?.id ?: queues.firstOrNull()?.id
        }
    }

    val currentId = selectedId
    val queue = queues.firstOrNull { it.id == currentId }
    val songsFlow = remember(currentId) {
        currentId?.let { container.queueRepo.observeQueueSongs(it) } ?: flowOf(emptyList<SongEntity>())
    }
    val songs by songsFlow.collectAsState(emptyList())

    Column(modifier = Modifier.fillMaxSize().background(PColors.Bg).statusBarsPadding()) {
        // 搜索框放在最顶上：原来在列表下方，会被悬浮的半透明底部导航栏挡住。
        OutlinedTextField(
            value = quick,
            onValueChange = { quick = it },
            placeholder = { Text("在该队列中搜索…", color = PColors.TextWeak, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = PColors.TextWeak) },
            trailingIcon = {
                if (quick.isNotEmpty()) {
                    IconButton(onClick = { quick = "" }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清空",
                            tint = PColors.TextWeak,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(onClick = { selectorOpen = true }) {
                    Text(
                        queue?.name ?: "还没有队列",
                        color = PColors.Text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("  ▾", color = PColors.TextDim, fontSize = 14.sp)
                }
                DropdownMenu(expanded = selectorOpen, onDismissRequest = { selectorOpen = false }) {
                    if (queues.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("去资料库长按歌曲生成队列") },
                            onClick = { selectorOpen = false }
                        )
                    }
                    queues.forEach { q ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    q.name + if (q.isActive) "  · 播放中" else "",
                                    color = if (q.id == currentId) PColors.Accent else PColors.Text
                                )
                            },
                            onClick = {
                                selectorOpen = false
                                selectedId = q.id
                                scope.launch { container.playback.activate(q.id, autoPlay = false) }
                            }
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = PColors.TextDim)
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("清空其它队列") }, onClick = {
                        moreOpen = false
                        currentId?.let { id -> scope.launch { container.queueRepo.clearOthers(id) } }
                    })
                    DropdownMenuItem(text = { Text("清空本队列") }, onClick = {
                        moreOpen = false
                        currentId?.let { id -> scope.launch { container.queueRepo.clear(id) } }
                    })
                    DropdownMenuItem(text = { Text("删除本队列") }, onClick = {
                        moreOpen = false
                        currentId?.let { id ->
                            scope.launch {
                                container.queueRepo.delete(id)
                                selectedId = null
                            }
                        }
                    })
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                currentId?.let { id -> scope.launch { container.playback.activate(id, autoPlay = true) } }
            }) {
                Icon(
                    if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null, tint = PColors.Text
                )
            }
            Icon(
                Icons.Rounded.DragHandle, contentDescription = null,
                tint = PColors.TextWeak, modifier = Modifier.size(18.dp)
            )
            Text(
                "${if (songs.isEmpty()) 0 else (playback.index + 1).coerceAtMost(songs.size)}/${songs.size}",
                color = PColors.Text,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                currentId?.let { id ->
                    scope.launch { container.queueRepo.saveCursor(id, playback.index, playback.positionMs) }
                }
            }) { Icon(Icons.Rounded.Save, contentDescription = null, tint = PColors.TextDim) }
            IconButton(onClick = onJumpToPlayer) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = PColors.TextDim)
            }
        }

        val filtered = remember(songs, quick) {
            songs.withIndex().filter { (_, s) ->
                quick.isBlank() || com.multitrack.app.player.data.SongSearch.matches(
                    quick, s.title, s.artist, s.album
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            // 底部导航浮在内容上，最后几行要留出空间
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
        ) {
            // 队列里同一首歌可以出现多次（同一 songId 重复条目），
            // 用 songId 做 key 会撞键、用 indexOfFirst 取下标会指到第一条 —— 都会让「点的和播的不是同一首」。
            items(filtered, key = { "${it.index}-${it.value.id}" }) { (index, song) ->
                var menu by remember { mutableStateOf(false) }
                val isCurrent = playback.index == index
                SongRow(
                    song = song,
                    art = container.art,
                    isCurrent = isCurrent,
                    onClick = {
                        currentId?.let { id ->
                            scope.launch {
                                container.playback.activate(
                                    id, autoPlay = true, startIndex = index, startSongId = song.id
                                )
                            }
                        }
                    },
                    showHandle = true,
                    trailing = {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = null,
                                    tint = if (isCurrent) PColors.Accent else PColors.TextWeak
                                )
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("编辑标签 / 封面") }, onClick = {
                                    menu = false
                                    onEditTags(listOf(song))
                                })
                                DropdownMenuItem(text = { Text("立即播放") }, onClick = {
                                    menu = false
                                    currentId?.let { id -> scope.launch { container.playback.activate(id, autoPlay = true) } }
                                })
                                DropdownMenuItem(text = { Text("上移") }, onClick = {
                                    menu = false
                                    if (index > 0) currentId?.let { id ->
                                        scope.launch { container.queueRepo.moveItem(id, index, index - 1) }
                                    }
                                })
                                DropdownMenuItem(text = { Text("下移") }, onClick = {
                                    menu = false
                                    if (index in 0 until songs.size - 1) currentId?.let { id ->
                                        scope.launch { container.queueRepo.moveItem(id, index, index + 1) }
                                    }
                                })
                                DropdownMenuItem(text = { Text("从队列移除") }, onClick = {
                                    menu = false
                                    currentId?.let { id -> scope.launch { container.queueRepo.removeSong(id, song.id) } }
                                })
                            }
                        }
                    }
                )
            }
            if (filtered.isEmpty()) {
                item { EmptyHint(if (queues.isEmpty()) "还没有队列，去资料库长按歌曲生成" else "队列是空的") }
            }
        }

    }
}
