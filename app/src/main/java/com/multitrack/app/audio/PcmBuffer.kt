package com.multitrack.app.audio

/**
 * 工程内部统一的 PCM 表示：Float32 平面立体声、范围 [-1,1]。
 * 大数组不进入 Compose 状态，只存于 AudioBufferStore，Clip 通过 bufferId 引用。
 */
data class PcmBuffer(
    var sampleRate: Int,
    var channels: Int = 2,
    var left: FloatArray,
    var right: FloatArray,
    var frames: Int,
    /** 解码时顺带算出的峰值（mono/L/R），避免导入后再扫一遍 */
    var peaks: Triple<FloatArray, FloatArray, FloatArray>? = null
)

/** 声像左右增益（等功率） */
data class PanGain(val l: Float, val r: Float)

/** 平面立体声缓冲（用于变调/重采样的返回值） */
data class StereoBuffer(val left: FloatArray, val right: FloatArray)
