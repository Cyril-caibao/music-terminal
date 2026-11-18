package com.multitrack.app.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.data.MediaScanner
import com.multitrack.app.player.data.QueueRepository
import com.multitrack.app.player.data.SongEntity
import kotlinx.coroutines.launch

/**
 * 歌手 / 专辑：**从曲库标签现聚合，不落表**。
 *
 * 之所以不建 artists / albums 两张表再维护：它们完全由 songs 派生，标签一改（批量编辑、
 * 重扫）就得跟着重算，两边迟早对不上；曲库在内存里就几万条，groupBy 一次是毫秒级，
 * 跟着 `observeAll()` 的 Flow 一起刷新反而永远是对的。
 */

/** 一位歌手 + 他名下的歌（按标题排，和「全部歌曲」的默认序一致） */
data class ArtistGroup(val name: String, val songs: List<SongEntity>) {
    val durationMs: Long get() = songs.sumOf { it.durationMs }
}

/**
 * 一张专辑。
 *
 * 按**专辑名**聚合而不是「专辑名 + 歌手」：一张专辑里常有合唱/feat.，歌手字段并不统一，
 * 按组合并会把一张专辑拆成好几张。歌手统一就显示那个名字，不统一才显示「多位歌手」。
 */
data class AlbumGroup(
    val name: String,
    val artist: String,
    val year: Int,
    val songs: List<SongEntity>
) {
    val durationMs: Long get() = songs.sumOf { it.durationMs }
}

internal fun groupByArtist(all: List<SongEntity>): List<ArtistGroup> =
    all.groupBy { it.artist.ifBlank { MediaScanner.UNKNOWN } }
        .map { (name, songs) -> ArtistGroup(name, songs.sortedBy { it.title.lowercase() }) }
        .sortedBy { it.name.lowercase() }

internal fun groupByAlbum(all: List<SongEntity>): List<AlbumGroup> =
    all.groupBy { it.album.ifBlank { MediaScanner.UNKNOWN } }
        .map { (name, songs) ->
            val artists = songs.map { it.artist }.filter { it.isNotBlank() }.distinct()
            val years = songs.map { it.year }.filter { it > 0 }
            AlbumGroup(
                name = name,
                artist = when {
                    artists.size == 1 -> artists[0]
                    artists.isEmpty() -> MediaScanner.UNKNOWN
                    else -> "多位歌手"
                },
                year = years.minOrNull() ?: 0,
                // 专辑内按碟号 / 音轨号排：这才是这张专辑本来的顺序
                songs = songs.sortedWith(
                    compareBy({ it.disc }, { it.track }, { it.title.lowercase() })
                )
            )
        }
        .sortedBy { it.name.lowercase() }

@Composable
fun ArtistsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    onEditTags: (List<SongEntity>) -> Unit = {}
) {
    val all by container.songDao().observeAll().collectAsState(emptyList<SongEntity>())
    val artists = remember(all) { groupByArtist(all) }
    var query by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<ArtistGroup?>(null) }

    BackHandler(enabled = opened != null) { opened = null }
    opened?.let { g ->
        GroupSongsPane(
            container = container,
            title = g.name,
            subtitle = "${g.songs.size} 首 · ${formatTime(g.durationMs)}",
            songs = g.songs,
            sourceType = QueueRepository.T_ARTIST,
            onBack = { opened = null },
            onPlayed = onPlayed,
            onEditTags = onEditTags
        )
        return
    }

    val shown = remember(artists, query) {
        if (query.isBlank()) artists else artists.filter { it.name.contains(query, true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(PColors.Bg).statusBarsPadding()) {
        GroupTopBar(title = "歌手", count = "共 ${artists.size} 位", onBack = onBack)
        FilterField(query) { query = it }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            items(shown, key = { it.name }) { g ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { opened = g }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InitialBadge(text = g.name)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            g.name, color = PColors.Text, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${g.songs.size} 首 · ${formatTime(g.durationMs)}",
                            color = PColors.TextWeak, fontSize = 11.sp
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight, contentDescription = null,
                        tint = PColors.TextWeak, modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (shown.isEmpty()) item {
                EmptyHint(if (all.isEmpty()) "曲库还是空的，右上角刷新可重扫" else "没有匹配的歌手")
            }
        }
    }
}

@Composable
fun AlbumsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    onEditTags: (List<SongEntity>) -> Unit = {}
) {
    val all by container.songDao().observeAll().collectAsState(emptyList<SongEntity>())
    val albums = remember(all) { groupByAlbum(all) }
    var query by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<AlbumGroup?>(null) }

    BackHandler(enabled = opened != null) { opened = null }
    opened?.let { g ->
        GroupSongsPane(
            container = container,
            title = g.name,
            subtitle = listOfNotNull(
                g.artist,
                if (g.year > 0) "${g.year}" else null,
                "${g.songs.size} 首 · ${formatTime(g.durationMs)}"
            ).joinToString(" · "),
            songs = g.songs,
            sourceType = QueueRepository.T_ALBUM,
            onBack = { opened = null },
            onPlayed = onPlayed,
            onEditTags = onEditTags
        )
        return
    }

    val shown = remember(albums, query) {
        if (query.isBlank()) albums
        else albums.filter { it.name.contains(query, true) || it.artist.contains(query, true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(PColors.Bg).statusBarsPadding()) {
        GroupTopBar(title = "专辑", count = "共 ${albums.size} 张", onBack = onBack)
        FilterField(query) { query = it }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            items(shown, key = { it.name }) { g ->
                val cover = rememberArt(container.art, g.songs.firstOrNull())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { opened = g }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverArt(file = cover, size = 52.dp)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            g.name, color = PColors.Text, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOfNotNull(
                                g.artist,
                                if (g.year > 0) "${g.year}" else null,
                                "${g.songs.size} 首"
                            ).joinToString(" · "),
                            color = PColors.TextDim, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight, contentDescription = null,
                        tint = PColors.TextWeak, modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (shown.isEmpty()) item {
                EmptyHint(if (all.isEmpty()) "曲库还是空的，右上角刷新可重扫" else "没有匹配的专辑")
            }
        }
    }
}

/** 二级页：一个歌手 / 一张专辑的歌单，顶部带「播放全部」 */
@Composable
private fun GroupSongsPane(
    container: AppContainer,
    title: String,
    subtitle: String,
    songs: List<SongEntity>,
    sourceType: String,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    onEditTags: (List<SongEntity>) -> Unit
) {
    val scope = rememberCoroutineScope()

    fun playFrom(startId: Long?) {
        scope.launch {
            val id = container.queueRepo.createFromSongs(title, sourceType, 0L, songs.map { it.id })
            if (id != null) {
                container.playback.activate(id, autoPlay = true, startSongId = startId)
                onPlayed()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(PColors.Bg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = PColors.Text)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title, color = PColors.Text, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(subtitle, color = PColors.TextWeak, fontSize = 11.sp)
            }
            IconButton(onClick = { playFrom(null) }) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "播放全部", tint = PColors.Accent)
            }
        }
        TextButton(
            onClick = { playFrom(null) },
            modifier = Modifier.padding(start = 8.dp)
        ) { Text("播放全部 ${songs.size} 首", color = PColors.Accent, fontSize = 13.sp) }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            items(songs, key = { it.id }) { song ->
                var menu by remember { mutableStateOf(false) }
                SongRow(
                    song = song,
                    art = container.art,
                    isCurrent = false,
                    onClick = { playFrom(song.id) },
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
                            }
                        }
                    }
                )
            }
            if (songs.isEmpty()) item { EmptyHint("这个分组下没有歌了") }
        }
    }
}

@Composable
private fun GroupTopBar(title: String, count: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回资料库", tint = PColors.Text)
        }
        Text(title, color = PColors.Text, fontSize = 17.sp)
        Text(
            "  $count", color = PColors.TextWeak, fontSize = 11.sp,
            modifier = Modifier.weight(1f).padding(top = 4.dp)
        )
    }
}

/** 列表上方那个筛选框。和资料库的搜索框同一套样式，减少视觉噪音 */
@Composable
internal fun FilterField(value: String, hint: String = "筛选…", onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(hint, color = PColors.TextWeak, fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = PColors.TextWeak) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Rounded.Close, contentDescription = "清空",
                        tint = PColors.TextWeak, modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/**
 * 歌手的圆形首字徽标：绝大多数人没有艺人照片，与其显示一排一样的灰人头，
 * 不如用名字首字 + 稳定色相 —— 同一支乐队永远是同一个颜色，扫一眼就能定位。
 */
@Composable
private fun InitialBadge(text: String) {
    val hue = (text.hashCode() and 0x7FFFFFFF) % 360
    val bg = androidx.compose.ui.graphics.Color.hsv(hue.toFloat(), 0.42f, 0.55f)
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(bg, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.take(1).uppercase(),
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 17.sp
        )
    }
}

/** 资料库顶部那一排入口（歌手 / 专辑 / 歌单）共用的行样式 */
@Composable
internal fun LibraryEntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PColors.Accent, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = PColors.Text, fontSize = 14.sp)
            Text(subtitle, color = PColors.TextWeak, fontSize = 11.sp)
        }
        Icon(
            Icons.Rounded.ChevronRight, contentDescription = null,
            tint = PColors.TextWeak, modifier = Modifier.size(20.dp)
        )
    }
}
