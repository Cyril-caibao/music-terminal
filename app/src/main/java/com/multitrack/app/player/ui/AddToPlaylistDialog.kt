package com.multitrack.app.player.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.data.PlaylistEntity
import com.multitrack.app.player.data.PlaylistItemEntity
import com.multitrack.app.player.data.SongEntity
import kotlinx.coroutines.launch

/**
 * 「加入歌单」浮层。
 *
 * 播放页左下角原来是收藏/点赞（点击没有任何效果），换成了这个入口：
 * 选一个已有歌单，或者当场新建一个名字，把当前这首（或多首）塞进去。
 * 同名歌曲允许重复加入 —— 歌单本来就是可以放同一首多次的列表；
 * 这里只在「已经加过」时提示一下，不强行去重。
 */
@Composable
fun AddToPlaylistDialog(
    container: AppContainer,
    songs: List<SongEntity>,
    onDismiss: () -> Unit
) {
    if (songs.isEmpty()) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playlists by container.db.playlistDao().observeAll().collectAsState(emptyList<PlaylistEntity>())
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    fun addTo(playlistId: Long, playlistName: String) {
        scope.launch {
            val dao = container.db.playlistDao()
            val start = (dao.maxOrder(playlistId) ?: -1) + 1
            dao.insertItems(
                songs.mapIndexed { i, s ->
                    PlaylistItemEntity(playlistId = playlistId, songId = s.id, order = start + i)
                }
            )
            val msg = if (songs.size == 1) "已加入「$playlistName」" else "已加入 ${songs.size} 首到「$playlistName」"
            toast(msg)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PColors.SurfaceAlt, RoundedCornerShape(16.dp))
                .padding(vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PlaylistAdd, null, tint = PColors.Accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("加入歌单", color = PColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (songs.size == 1) songs.first().title else "已选 ${songs.size} 首",
                        color = PColors.TextWeak, fontSize = 11.sp, maxLines = 1
                    )
                }
                TextButton(onClick = onDismiss) { Text("关闭", color = PColors.TextDim, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(4.dp))

            if (creating) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("新歌单名", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val n = name.trim()
                        if (n.isEmpty()) return@TextButton
                        scope.launch {
                            val dao = container.db.playlistDao()
                            val id = dao.insert(PlaylistEntity(name = n))
                            dao.insertItems(
                                songs.mapIndexed { i, s ->
                                    PlaylistItemEntity(playlistId = id, songId = s.id, order = i)
                                }
                            )
                            toast("已新建「$n」并加入 ${songs.size} 首")
                            onDismiss()
                        }
                    }) { Text("创建并加入", color = PColors.Accent, fontSize = 13.sp) }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, null, tint = PColors.Accent, modifier = Modifier.size(18.dp))
                    Text(
                        "新建歌单并加入",
                        color = PColors.Accent, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }

            if (playlists.isEmpty() && !creating) {
                Text(
                    "还没有歌单。点上面「新建歌单并加入」就能建一个。",
                    color = PColors.TextWeak, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { addTo(p.id, p.name) }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Rounded.PlaylistPlay, null, tint = PColors.TextDim, modifier = Modifier.size(18.dp))
                            Text(p.name, color = PColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Rounded.Add, null,
                                tint = PColors.TextWeak, modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
