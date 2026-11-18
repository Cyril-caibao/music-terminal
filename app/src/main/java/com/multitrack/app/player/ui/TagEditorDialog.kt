package com.multitrack.app.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.multitrack.app.player.AppContainer
import com.multitrack.app.player.data.SongEntity
import com.multitrack.app.player.data.TagWriter
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 标签 + 封面编辑。
 * - 单选：字段预填当前值；
 * - 多选（批量）：字段**留空 = 保持原值**，只有填了的字段才写进所有选中歌曲。
 */
@Composable
fun TagEditorDialog(
    container: AppContainer,
    songs: List<SongEntity>,
    onDismiss: () -> Unit
) {
    if (songs.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val single = songs.size == 1
    val first = songs.first()

    var title by remember(first.id, songs.size) { mutableStateOf(if (single) first.title else "") }
    var artist by remember(first.id, songs.size) { mutableStateOf(if (single) first.artist else "") }
    var album by remember(first.id, songs.size) { mutableStateOf(if (single) first.album else "") }
    var albumArtist by remember(first.id, songs.size) { mutableStateOf(if (single) first.albumArtist else "") }
    var genre by remember(first.id, songs.size) { mutableStateOf(if (single) first.genre else "") }
    var year by remember(first.id, songs.size) { mutableStateOf(if (single && first.year > 0) first.year.toString() else "") }
    var track by remember(first.id, songs.size) { mutableStateOf(if (single && first.track > 0) first.track.toString() else "") }
    var disc by remember(first.id, songs.size) { mutableStateOf(if (single && first.disc > 0) first.disc.toString() else "") }

    var lyricsText by remember(first.id, songs.size) { mutableStateOf("") }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val currentCover = if (single) rememberArt(container.art, first) else null

    fun editsOf() = TagWriter.Edits(
        title = title.trim().ifBlank { null },
        artist = artist.trim().ifBlank { null },
        album = album.trim().ifBlank { null },
        albumArtist = albumArtist.trim().ifBlank { null },
        genre = genre.trim().ifBlank { null },
        year = year.toIntOrNull(),
        track = track.toIntOrNull(),
        disc = disc.toIntOrNull(),
        cover = coverUri,
        lyrics = lyricsText.trim().ifBlank { null }
    )

    fun persist() {
        val edits = editsOf()
        busy = true
        scope.launch {
            var ok = 0
            var fail = 0
            var lastError: String? = null
            songs.forEachIndexed { i, song ->
                progress = "写入 ${i + 1}/${songs.size}"
                val r = container.tagWriter.write(song, edits)
                if (r.isSuccess) {
                    ok++
                    runCatching {
                        container.songDao().updateTags(
                            song.id,
                            edits.title ?: song.title,
                            edits.artist ?: song.artist,
                            edits.album ?: song.album,
                            edits.albumArtist ?: song.albumArtist,
                            edits.genre ?: song.genre,
                            edits.year ?: song.year,
                            edits.track ?: song.track,
                            edits.disc ?: song.disc
                        )
                        // 文件确实写成功之后，本地缩略图也换成新的（列表立刻刷新）
                        edits.cover?.let { container.art.setCustomCover(song.id, it) }
                        container.art.invalidate(song.id)
                        container.lyrics.invalidate(song.id)
                    }
                } else {
                    fail++
                    lastError = r.exceptionOrNull()?.message ?: lastError
                }
            }
            busy = false
            if (fail == 0) onDismiss()
            else message = "写入失败 $fail 首" + (lastError?.let { "：$it" } ?: "（该格式或该存储位置不允许改写）")
        }
    }

    val writeAuth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) persist()
        else message = "没有获得这些文件的写权限，标签未修改"
    }

    fun save() {
        message = null
        if (Build.VERSION.SDK_INT >= 29) {
            val activity = context.findActivity()
            if (activity == null) { persist(); return }
            runCatching {
                val pi = MediaStore.createWriteRequest(
                    context.contentResolver,
                    songs.map { Uri.parse(it.uri) }
                )
                writeAuth.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }.onFailure { message = "无法申请写权限：${it.message}" }
        } else {
            persist()
        }
    }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // 只做预览：真正的封面要跟着标签一起写进音频文件
        if (uri != null) coverUri = uri
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(color = PColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    if (single) "编辑标签" else "批量编辑标签（${songs.size} 首）",
                    color = PColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (single) "写在音频文件本身，其它播放器也能读到"
                    else "留空的字段保持原值；填了的字段会写到所有选中歌曲",
                    color = PColors.TextWeak, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                )

                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = PColors.SurfaceAlt) {
                        if (coverUri != null) {
                            AsyncImage(
                                model = coverUri, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp))
                            )
                        } else if (single && currentCover != null) {
                            AsyncImage(
                                model = currentCover, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                Text(if (single) "无封面" else "不改封面", color = PColors.TextWeak, fontSize = 11.sp)
                            }
                        }
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        TextButton(onClick = { pickCover.launch("image/*") }) {
                            Text("选择封面", color = PColors.Accent, fontSize = 13.sp)
                        }
                        if (coverUri != null) {
                            TextButton(onClick = { coverUri = null }) {
                                Text("取消选择", color = PColors.TextDim, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Field("标题", title, if (single) "" else "留空=不改") { title = it }
                    Field("艺术家", artist, if (single) "" else "留空=不改") { artist = it }
                    Field("专辑", album, if (single) "" else "留空=不改") { album = it }
                    Field("专辑艺术家", albumArtist, if (single) "" else "留空=不改") { albumArtist = it }
                    Field("流派", genre, if (single) "" else "留空=不改") { genre = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("年份", year, if (single) "" else "不改", Modifier.weight(1f)) {
                            year = it.filter { ch -> ch.isDigit() }
                        }
                        Field("音轨", track, if (single) "" else "不改", Modifier.weight(1f)) {
                            track = it.filter { ch -> ch.isDigit() }
                        }
                        Field("碟号", disc, if (single) "" else "不改", Modifier.weight(1f)) {
                            disc = it.filter { ch -> ch.isDigit() }
                        }
                    }
                    // 歌词：留空=不改；支持直接贴 LRC，也支持纯文本
                    Text("歌词（LRC 或纯文本）", color = PColors.TextDim, fontSize = 11.sp)
                    OutlinedTextField(
                        value = lyricsText,
                        onValueChange = { lyricsText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 140.dp),
                        placeholder = { Text("留空=不修改歌词；可整段粘贴 LRC", fontSize = 11.sp, color = PColors.TextWeak) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = PColors.Text),
                        singleLine = false
                    )
                }

                message?.let {
                    Text(it, color = PColors.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) {
                        Text(progress.ifBlank { "写入中…" }, color = PColors.TextDim, fontSize = 12.sp,
                            modifier = Modifier.padding(end = 12.dp))
                    }
                    TextButton(onClick = { if (!busy) onDismiss() }) { Text("取消", color = PColors.TextDim) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { if (!busy) save() }) {
                        Text(if (busy) "…" else "保存", color = PColors.Accent, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, fontSize = 12.sp) },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}
