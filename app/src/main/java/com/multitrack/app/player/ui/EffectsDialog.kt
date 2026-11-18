package com.multitrack.app.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.multitrack.app.player.playback.AudioFx

/**
 * 音效面板：10 段均衡 + 超低重音 + 3D 宽度 + 混响（湿声/房间大小/扩散）。
 * 设备不支持的效果会置灰，并在标题后标注。
 */
@Composable
fun EffectsDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = PColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("音效", color = PColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "本机 DSP 实时计算（32bit float，全程不限幅）· 不联网",
                    color = PColors.TextWeak, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                )

                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Section("均衡器 10 段（dB，±12）")
                    for (i in 0 until 10) {
                        SliderRow(
                            label = AudioFx.bandCenterLabel(i),
                            value = AudioFx.bands[i].toFloat(),
                            range = -1200f..1200f,
                            enabled = true,
                            valueText = String.format("%+.1f", AudioFx.bands[i] / 100f)
                        ) { AudioFx.setBand(i, it.toInt()) }
                    }

                    Section("低频 / 立体声 / 输出")
                    SliderRow("超低重音", AudioFx.bassStrength.toFloat(), 0f..1000f, true,
                        String.format("%+.1f dB", AudioFx.bassStrength / 1000f * 18f)) { AudioFx.setBass(it.toInt()) }
                    SliderRow("3D 宽度", AudioFx.spatialWidth.toFloat(), 0f..1000f, true,
                        "${AudioFx.spatialWidth / 10}%") { AudioFx.setSpatial(it.toInt()) }
                    SliderRow("输出增益", AudioFx.outputGainDb, -12f..18f, true,
                        String.format("%+.1f dB", AudioFx.outputGainDb)) { AudioFx.setOutputGain(it) }
                    Text(
                        "整条链不压缩、不限幅：推得过头就是失真，这是有意的",
                        color = PColors.TextWeak, fontSize = 10.sp,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                    )

                    Section("混响" + if (!AudioFx.hasRev) "（本机不支持）" else "")
                    SliderRow("湿声强度", AudioFx.reverbWetLevel.toFloat(), 0f..1000f, AudioFx.hasRev,
                        "${AudioFx.reverbWetLevel / 10}%") { AudioFx.setReverbWet(it.toInt()) }
                    SliderRow("房间大小", AudioFx.reverbRoomSize.toFloat(), 0f..1000f, AudioFx.hasRev,
                        "${AudioFx.reverbRoomSize / 10}%") { AudioFx.setReverbRoom(it.toInt()) }
                    SliderRow("扩散（房间宽度）", AudioFx.reverbDiffusionValue.toFloat(), 0f..1000f, AudioFx.hasRev,
                        "${AudioFx.reverbDiffusionValue / 10}%") { AudioFx.setReverbDiffusion(it.toInt()) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { AudioFx.resetAll() }) { Text("全部复位", color = PColors.TextDim) }
                    TextButton(onClick = onDismiss) { Text("关闭", color = PColors.Accent) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = PColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = if (enabled) PColors.Text else PColors.TextWeak,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 6.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = PColors.Accent,
                activeTrackColor = PColors.Accent.copy(alpha = 0.6f),
                inactiveTrackColor = PColors.Border
            ),
            modifier = Modifier.weight(1f)
        )
        Text(valueText, color = PColors.TextWeak, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
    }
}
