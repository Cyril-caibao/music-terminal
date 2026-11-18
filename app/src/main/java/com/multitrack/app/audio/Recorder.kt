package com.multitrack.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 录音模块（边听边录）：AudioCapturer 的安卓等价物 = AudioRecord。
 * 采集麦克风单声道 44100Hz S16LE，转成工程统一的 Float32 立体声，
 * 停止后产出可挂到音轨上的采样数据。
 */
class Recorder {

    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var recording = false
    private val leftChunks = ArrayList<FloatArray>()
    private val rightChunks = ArrayList<FloatArray>()
    private var startFrame = 0

    fun isRecording(): Boolean = recording

    fun start(startFrame: Int): Boolean {
        if (recording) return false
        synchronized(leftChunks) {
            leftChunks.clear()
            rightChunks.clear()
        }
        this.startFrame = kotlin.math.max(0, startFrame)

        val minBuf = AudioRecord.getMinBufferSize(
            C.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        @Suppress("MissingPermission", "Deprecation")
        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            C.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            kotlin.math.max(minBuf, 4096)
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return false
        }
        record = ar
        recording = true
        ar.startRecording()

        val t = Thread {
            val buf = ShortArray(1024)
            while (recording) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                val l = FloatArray(n)
                val r = FloatArray(n)
                for (i in 0 until n) {
                    val v = buf[i] / 32768f
                    l[i] = v; r[i] = v
                }
                synchronized(leftChunks) {
                    leftChunks.add(l); rightChunks.add(r)
                }
            }
        }
        t.isDaemon = true
        thread = t
        t.start()
        return true
    }

    /** 停止录音，拼接数据并写入 AudioBufferStore */
    fun stop(): RecordResult? {
        if (!recording || record == null) return null
        recording = false
        val ar = record
        try {
            ar?.stop()
            ar?.release()
        } catch (e: Exception) {
            // 忽略释放异常
        }
        record = null
        thread = null

        val (lList, rList) = synchronized(leftChunks) {
            ArrayList(leftChunks) to ArrayList(rightChunks)
        }
        var total = 0
        for (c in lList) total += c.size
        val left = FloatArray(total)
        val right = FloatArray(total)
        var off = 0
        for (i in lList.indices) {
            lList[i].copyInto(left, off)
            rList[i].copyInto(right, off)
            off += lList[i].size
        }

        val bufferId = genId("rec")
        AudioBufferStore.put(
            bufferId,
            PcmBuffer(C.SAMPLE_RATE, 2, left, right, total)
        )
        return RecordResult(bufferId, "录音", total, startFrame)
    }

    data class RecordResult(
        val bufferId: String,
        val name: String,
        val frames: Int,
        val startFrame: Int
    )
}
