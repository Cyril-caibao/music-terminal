package com.multitrack.app.model

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.multitrack.app.audio.genId

/**
 * 多轨工程数据模型：Clip（片段）、Track（音轨）。
 * 大 PCM 数据存于 AudioBufferStore（以 bufferId 关联），这里只保留轻量元数据 + 波形峰值。
 * 【对应鸿蒙版 @Observed / @ObjectLink，改用 Compose mutableStateOf 实现双向绑定】
 */
class Clip {
    val id: String = genId("clip")
    var name: String by mutableStateOf("音频")
    /** 关联 AudioBufferStore 中的 PCM 数据 */
    var bufferId: String by mutableStateOf("")
    /** 项目采样率下的帧数（已重采样） */
    var length: Int by mutableStateOf(0)
    /** 时间轴起始帧 */
    var timelineStart: Int by mutableStateOf(0)
    var fadeIn: Int by mutableStateOf(0)
    var fadeOut: Int by mutableStateOf(0)
    var gain: Float by mutableStateOf(1.0f)
    var pan: Float by mutableStateOf(0.0f)
    var color: String by mutableStateOf("#3d7bff")
    /** 基准分辨率波形峰值（interleaved min,max）；非响应，改动后靠 Project.version 触发重绘 */
    var peaks: FloatArray = FloatArray(0)
    /** 右声道峰值（双轨波形显示用） */
    var peaksR: FloatArray = FloatArray(0)

    val endFrame: Int get() = timelineStart + length
}

private val PALETTE = listOf(
    "#3d7bff", "#ff6b6b", "#21c08b", "#ffb020", "#a06bff",
    "#ff7ac0", "#36c5f0", "#9ccc65", "#ff8a65", "#7986cb",
    "#4db6ac", "#fff176", "#ba68c8", "#4fc3f7", "#aed581",
    "#ffb74d", "#f06292", "#9575cd", "#4dd0e1", "#81c784"
)

class Track(index: Int) {
    val id: String = genId("trk")
    var name: String by mutableStateOf("音轨 ${index + 1}")
    var color: String by mutableStateOf(PALETTE[index % PALETTE.size])
    var volume: Float by mutableStateOf(1.0f)
    var pan: Float by mutableStateOf(0.0f)
    var mute: Boolean by mutableStateOf(false)
    var solo: Boolean by mutableStateOf(false)
    /** 录音时是否武装 */
    var armed: Boolean by mutableStateOf(false)
    val clips = mutableStateListOf<Clip>()
}
