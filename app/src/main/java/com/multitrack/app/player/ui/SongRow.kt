package com.multitrack.app.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.player.data.ArtRepository
import com.multitrack.app.player.data.SongEntity

/**
 * 统一列表行：拖拽柄 + 封面 + 三行文字 + 右侧时长 / 操作区。
 * 正在播放的那一首带 2dp 青绿描边（和 Musicolet 一致）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongEntity,
    art: ArtRepository,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    selected: Boolean = false,
    showHandle: Boolean = false,
    trailing: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cover = rememberArt(art, song)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (isCurrent) Modifier.border(2.dp, PColors.Accent, RoundedCornerShape(6.dp))
                else Modifier
            )
            .background(if (isCurrent) PColors.SurfaceAlt else PColors.Bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Text("✓", color = PColors.Accent, fontSize = 16.sp, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (showHandle) {
            Text(
                "≡",
                color = PColors.TextWeak,
                fontSize = 20.sp,
                modifier = Modifier.width(26.dp)
            )
        }
        CoverArt(file = cover, size = 54.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                song.title,
                color = if (isCurrent) PColors.Accent else PColors.Text,
                fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artist,
                color = PColors.TextDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.album,
                color = PColors.TextWeak,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(formatTime(song.durationMs), color = PColors.TextWeak, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            trailing()
        }
    }
}

@Composable
fun SectionHeader(title: String, trail: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = PColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        trail()
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PColors.TextWeak, fontSize = 13.sp)
    }
}
