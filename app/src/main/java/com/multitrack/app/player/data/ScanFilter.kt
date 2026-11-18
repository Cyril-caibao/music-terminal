package com.multitrack.app.player.data

/**
 * 扫描过滤的**纯逻辑**，不碰 Android 类 —— 这样「排除是否全局生效」这种关键行为可以直接单测。
 *
 * 全局生效的机制只有一条、也必须只有一条：**不进曲库**。
 * 排除掉的文件在扫描入口就被丢弃，于是播放页、文件夹、专辑、歌手、队列、
 * 播放全部自然看不到它 —— 在每个视图里各自过滤一遍迟早漏掉一处。
 */
object ScanFilter {

    /** 目录路径统一成不带尾斜杠的形式，"/a/b/" 与 "/a/b" 必须是同一个目录 */
    fun normalizeDir(path: String): String =
        path.trim().trimEnd('/').let { if (it.isBlank()) "" else it }

    /**
     * 一个文件路径是否允许进曲库。
     * @param scanRoots 允许扫描的根目录；**空集合 = 全库**（默认行为）
     * @param excludeDirs 排除目录；排除优先于允许 —— 即使在扫描根下面也不行，
     *        而且**子目录一起排除**（与截图里那个应用的行为一致）
     */
    fun includePath(
        path: String,
        scanRoots: Set<String>,
        excludeDirs: Set<String>
    ): Boolean {
        val file = path.substringBeforeLast('/', "")
        if (excludeDirs.any { dirIn(file, it) }) return false
        if (scanRoots.isEmpty()) return true
        return scanRoots.any { dirIn(file, it) }
    }

    /** dir 等于 root，或在 root 下面。比较不区分大小写 —— Android 的存储（FAT/exFAT）本来就不敏感 */
    private fun dirIn(dir: String, root: String): Boolean {
        val d = normalizeDir(dir).lowercase()
        val r = normalizeDir(root).lowercase()
        if (r.isEmpty()) return false
        return d == r || d.startsWith("$r/")
    }

    /** "2" / "30" → 150 秒；空、负数、非数字都按 0 处理（0 = 不排除任何文件） */
    fun parseDurationSec(min: String, sec: String): Int {
        val m = min.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val s = sec.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        return m * 60 + s
    }
}
