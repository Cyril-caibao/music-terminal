package com.multitrack.app.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 播放速度：一个滑块 + 一个确认按钮，自己拖。
 *
 * 之前是菜单里列死的 0.8 / 1.0 / 1.25 / 1.5 / 2.0 五档，想听 1.1 或 0.9 就没得选。
 * 现在 0.25×–3.00× 连续可调，拖动时就生效（ExoPlayer 的 Sonic 会跟着改，不用重启播放）。
 */
@Composable
fun SpeedDialog(speed: Float, onSet: (Float) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableFloatStateOf(speed.coerceIn(0.25f, 3f)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = PColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("播放速度", color = PColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "拖动即时生效，范围 0.25× – 3.00×",
                    color = PColors.TextWeak, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                )

                Text(
                    String.format(Locale.US, "%.2f×", v),
                    color = PColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(top = 14.dp)
                )

                Slider(
                    value = v,
                    onValueChange = { nv ->
                        // 保留两位小数，避免出现 1.2374× 这种显示
                        v = Math.round(nv * 100f) / 100f
                        onSet(v)
                    },
                    valueRange = 0.25f..3f,
                    colors = SliderDefaults.colors(
                        thumbColor = PColors.Accent,
                        activeTrackColor = PColors.Accent.copy(alpha = 0.6f),
                        inactiveTrackColor = PColors.Border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { v = 1f; onSet(1f) }) {
                        Text("恢复 1.00×", color = PColors.TextDim)
                    }
                    TextButton(onClick = onDismiss) { Text("完成", color = PColors.Accent) }
                }
            }
        }
    }
}

/**
 * 定时关闭：一个滑块（1–180 分钟）+ 一个「开始计时」按钮。
 * 已经在计时的话，同一处显示剩余时间并提供「取消定时」。
 */
@Composable
fun SleepTimerDialog(
    remainingMinutes: Int,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var minutes by remember { mutableFloatStateOf(30f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = PColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("定时关闭", color = PColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (remainingMinutes > 0) "正在计时：还有 $remainingMinutes 分钟"
                    else "到点后暂停播放（不退出 App）",
                    color = PColors.TextWeak, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                )

                Text(
                    "${minutes.toInt()} 分钟",
                    color = PColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(top = 14.dp)
                )

                Slider(
                    value = minutes,
                    onValueChange = { minutes = it.roundToInt().toFloat() },
                    valueRange = 1f..180f,
                    colors = SliderDefaults.colors(
                        thumbColor = PColors.Accent,
                        activeTrackColor = PColors.Accent.copy(alpha = 0.6f),
                        inactiveTrackColor = PColors.Border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (remainingMinutes > 0) {
                        TextButton(onClick = { onCancel(); onDismiss() }) {
                            Text("取消定时", color = PColors.TextDim)
                        }
                    }
                    TextButton(onClick = { onStart(minutes.toInt()); onDismiss() }) {
                        Text(if (remainingMinutes > 0) "重新计时" else "开始计时", color = PColors.Accent)
                    }
                }
            }
        }
    }
}
