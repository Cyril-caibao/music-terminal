package com.multitrack.app.audio

import android.content.Context
import android.net.Uri

/**
 * 音频导入工具：把外部文件解码为工程统一的 Float32 立体声，并统一重采样到 44100Hz。
 * - WAV：纯 Kotlin 解码（WavCodec）
 * - MP3/AAC/M4A/...：MediaCodec 系统解码（CompressedDecoder），开箱即用
 */
object ImportAudio {

    data class ImportResult(val pcm: PcmBuffer, val displayName: String)

    /** 17MB 以上进入分片模式（WAV 按帧分片并行解码；压缩格式解码缓冲内部并行转换 + 进度上报） */
    const val BIG_FILE_BYTES = 17L * 1024 * 1024

    fun import(
        context: Context,
        uri: Uri,
        displayName: String,
        sizeBytes: Long = 0L,
        onProgress: (Float) -> Unit = {}
    ): ImportResult? {
        var pcm: PcmBuffer? = null
        val name = displayName.lowercase()

        // WAV：读字节进内存解析
        if (name.endsWith(".wav")) {
            onProgress(0.02f)
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            onProgress(0.25f)
            if (bytes != null && bytes.isNotEmpty()) {
                pcm = WavCodec.decodeWav(bytes)
            }
        }

        // 其余格式 / WAV 解析失败：尝试系统解码器（带进度）
        if (pcm == null) {
            pcm = CompressedDecoder.decode(context, uri) { p ->
                onProgress(0.05f + p * 0.85f)
            }
        }
        if (pcm == null || pcm.frames <= 0) return null
        onProgress(0.92f)

        // 统一重采样到工程采样率
        if (pcm.sampleRate != C.SAMPLE_RATE) {
            val res = resampleToRate(pcm.left, pcm.right, pcm.sampleRate, C.SAMPLE_RATE)
            pcm = PcmBuffer(C.SAMPLE_RATE, 2, res.left, res.right, res.left.size)
        }
        // 压缩格式在这里把峰值也算好（复用并行分桶），UI 拿到即可直接画波形
        if (pcm.peaks == null) {
            pcm.peaks = PeakExtractor.computeAllParallel(pcm)
        }
        onProgress(1f)
        return ImportResult(pcm, displayName)
    }
}
