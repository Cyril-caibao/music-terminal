package com.multitrack.app.audio

/**
 * 工程级常量：整个多轨工程统一 44100Hz / 立体声。
 * 所有导入的音频都会在载入时重采样到该规格，保证混音时各轨对齐。
 */
object C {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val MAX_TRACKS = 20

    /** 基准峰值分辨率：每 512 个采样点预计算一个 min/max 桶 */
    const val PEAK_BUCKET = 512
}

private val ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"

/** 生成不含特殊字符的唯一 id（可作为文件名/缓存 key） */
fun genId(prefix: String = "id"): String {
    val t = System.currentTimeMillis().toString(36)
    val r = (kotlin.random.Random.nextInt(1_000_000)).toString(36)
    return "${prefix}_${t}_${r}"
}
