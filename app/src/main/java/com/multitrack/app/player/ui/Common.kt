package com.multitrack.app.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.multitrack.app.player.data.ArtRepository
import com.multitrack.app.player.data.SongEntity
import java.io.File

/** 界面上要能看到版本号：装没装上新包，一眼就能确认 */
const val APP_VERSION = "3.51"

/** 播放器配色：纯黑底 + 青绿强调，来自 Musicolet 截图。 */
object PColors {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF0B0B0B)
    val SurfaceAlt = Color(0xFF141414)
    val Border = Color(0xFF2A2A2A)
    val Accent = Color(0xFF00C853)
    val Text = Color(0xFFF2F2F2)
    val TextDim = Color(0xFFB3B3B3)
    val TextWeak = Color(0xFF7C7C7C)
    val Danger = Color(0xFFFF5252)
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun rememberArt(art: ArtRepository, song: SongEntity?): File? {
    val key = song?.id ?: 0L
    val rev = art.revision          // 改过封面 → revision 变化 → 重新取图
    val file by produceState<File?>(initialValue = null, key, rev) {
        value = song?.let { art.artFor(it) }
    }
    return file
}

@Composable
fun CoverArt(
    file: File?,
    size: Dp,
    corner: Dp = 4.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(PColors.SurfaceAlt),
        contentAlignment = Alignment.Center
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = PColors.TextWeak,
                modifier = Modifier.size(size / 2.2f)
            )
        }
    }
}

@Composable
fun DimText(text: String, size: Int = 12, color: Color = PColors.TextWeak) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
}
