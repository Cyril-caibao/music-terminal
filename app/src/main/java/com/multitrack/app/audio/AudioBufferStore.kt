package com.multitrack.app.audio

/**
 * PCM 数据仓储：把真正占内存的 Float32 采样与 UI 用的轻量元数据（Clip）解耦。
 * 以 bufferId 为键，避免把大数组放进响应式状态里导致卡顿。
 *
 * ## 引用计数（3.49 起）
 *
 * 之前这里是「put / remove」的裸地图，`remove` 一调就直接丢数组 —— 但同一个 bufferId
 * 会被多个片段共享：剪贴板连续粘贴两次、分割后又把两段一起复制…… 删掉其中一个片段，
 * 另一个片段的音频就凭空消失了（波形空掉、播放没声），而且没有撤销栈，等于静默毁素材。
 *
 * 现在的规则很简单：
 * - [put] 只是把数组放进来，引用数归 0（谁都不欠它）；
 * - 片段真正引用它时调 [retain]，片段被删时调 [release]；
 * - 引用数减到 0 才真正丢弃。
 *
 * 所以凡是「新建片段 / 给片段换 bufferId」的路径都必须 retain，凡是「删片段」的路径都必须
 * release，不能再用 [remove] 硬删（它只留给临时缓冲清理用）。
 */
object AudioBufferStore {

    private class Entry(var buf: PcmBuffer, var refs: Int = 0)

    private val map = HashMap<String, Entry>()

    /** 放进来（引用数从 0 开始：还需要 [retain] 才算被片段持有） */
    @Synchronized
    fun put(id: String, buf: PcmBuffer) {
        map[id] = Entry(buf)
    }

    @Synchronized
    fun get(id: String): PcmBuffer? = map[id]?.buf

    @Synchronized
    fun has(id: String): Boolean = map.containsKey(id)

    /** 有片段开始引用；返回 false 表示这条 buffer 已经不存在了 */
    @Synchronized
    fun retain(id: String): Boolean {
        val e = map[id] ?: return false
        e.refs++
        return true
    }

    /** 片段释放引用；返回 true 表示引用归零、数组刚刚被丢弃 */
    @Synchronized
    fun release(id: String): Boolean {
        val e = map[id] ?: return false
        e.refs--
        if (e.refs <= 0) {
            map.remove(id)
            return true
        }
        return false
    }

    @Synchronized
    fun refCount(id: String): Int = map[id]?.refs ?: 0

    /**
     * 强制丢弃，不看引用数。
     *
     * 只给「临时缓冲 / 已知独占」的路径用：正常删片段请走 [release]，否则会把
     * 还挂在别的片段上的音频一起删掉（这正是 3.48 及以前的 bug）。
     */
    @Synchronized
    fun remove(id: String) {
        map.remove(id)
    }

    /** 当前存了多少条、总帧数（调试 / 内存提示用） */
    @Synchronized
    fun stats(): Pair<Int, Long> {
        var frames = 0L
        for (e in map.values) frames += e.buf.frames.toLong()
        return map.size to frames
    }

    /** 申请一段静音立体声缓冲（录音占位 / 插入空白） */
    fun createSilence(frames: Int, sampleRate: Int = C.SAMPLE_RATE): PcmBuffer =
        PcmBuffer(sampleRate, 2, FloatArray(frames), FloatArray(frames), frames)

    @Synchronized
    fun clear() {
        map.clear()
    }
}
