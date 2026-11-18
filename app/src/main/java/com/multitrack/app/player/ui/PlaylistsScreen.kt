package com.multitrack.app.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
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
import com.multitrack.app.player.data.PlaylistM3u
import android.net.Uri
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen(container: AppContainer, onBack: (() -> Unit)? = null, onPlayed: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val playlists by container.db.playlistDao().observeAll().collectAsState(emptyList<PlaylistEntity>())
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf<PlaylistEntity?>(null) }
    // 重命名：以前菜单里只有「导出 / 删除」，改名字得删了重建，等于没有这个功能
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    // 导出：先弹系统「新建文件」，拿到 uri 再写
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        val p = exporting
        exporting = null
        if (uri != null && p != null) scope.launch {
            val songs = container.db.playlistDao().items(p.id)
                .mapNotNull { container.db.songDao().byId(it.songId) }
            val ok = PlaylistM3u.export(context, uri, songs)
            message = if (ok) "已导出 ${songs.size} 首到 M3U" else "导出失败"
        }
    }

    // 导入：选一个 .m3u/.m3u8，按路径/文件名匹配后建成新歌单
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val all = container.db.songDao().allOnce()
            val matched = PlaylistM3u.import(context, uri, all)
            if (matched.isEmpty()) {
                message = "M3U 里没有匹配到库内的歌曲"
            } else {
                val dao = container.db.playlistDao()
                val n = "导入 ${System.currentTimeMillis() % 100000}"
                val pid = dao.insert(PlaylistEntity(name = n))
                dao.insertItems(matched.mapIndexed { i, sg -> PlaylistItemEntity(playlistId = pid, songId = sg.id, order = i) })
                message = "已导入 ${matched.size} 首到「$n」"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(PColors.Bg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回资料库", tint = PColors.Text)
                }
            }
            Text("歌单", color = PColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Rounded.FileOpen, contentDescription = "导入 M3U", tint = PColors.TextDim)
            }
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = PColors.TextDim)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            // 底部导航浮在内容上，最后几行要留出空间
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
        ) {
            items(playlists, key = { it.id }) { p ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PlaylistPlay, contentDescription = null, tint = PColors.TextDim)
                    Text(
                        p.name, color = PColors.Text, fontSize = 14.sp,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    TextButton(onClick = {
                        scope.launch {
                            val items = container.db.playlistDao().items(p.id).map { it.songId }
                            val id = container.queueRepo.createFromPlaylist(p.id, p.name, items)
                            if (id != null) {
                                container.playback.activate(id, autoPlay = true)
                                onPlayed()
                            }
                        }
                    }) { Text("播放", color = PColors.TextDim, fontSize = 13.sp) }
                    var menu by remember(p.id) { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = PColors.TextWeak)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                menu = false
                                renameText = p.name
                                renaming = p
                            })
                            DropdownMenuItem(text = { Text("导出 M3U") }, onClick = {
                                menu = false
                                exporting = p
                                exportLauncher.launch(p.name.replace('/', '_') + ".m3u")
                            })
                            DropdownMenuItem(text = { Text("删除歌单") }, onClick = {
                                menu = false
                                scope.launch {
                                    container.db.playlistDao().delete(p.id)
                                    container.db.playlistDao().clearItems(p.id)
                                }
                            })
                        }
                    }
                }
            }
            if (playlists.isEmpty()) item { EmptyHint("还没有歌单（右上角可新建 / 导入 M3U）") }
        }
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
            text = { Text(msg, color = PColors.Text) }
        )
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名歌单") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("歌单名", fontSize = 12.sp) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = renameText.trim()
                    renaming = null
                    if (n.isNotEmpty() && n != target.name) scope.launch {
                        container.db.playlistDao().rename(target.id, n)
                        // 这条歌单正好是当前队列的来源：队列名一起改，播放页/队列页才不会还挂着旧名
                        runCatching { container.queueRepo.renameFromPlaylist(target.id, n) }
                        message = "已改名为「$n」"
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    creating = false
                    val n = name.trim()
                    if (n.isNotEmpty()) scope.launch { container.db.playlistDao().insert(PlaylistEntity(name = n)) }
                    name = ""
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } }
        )
    }
}
