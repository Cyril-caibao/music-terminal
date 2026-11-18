package com.multitrack.app.lyrics

/** 一行 LRC 歌词：起始秒 + 文本 */
data class LrcLine(val atSec: Float, val text: String)

/**
 * 解析标准 LRC：
 *  [mm:ss.xx] 或 [mm:ss.xxx]，一行可带多个时间标签
 *  忽略 [ti:]/[ar:]/[al:]/[offset:...] 等元标签（offset 会应用到时间）
 */
fun parseLrc(raw: String): List<LrcLine> {
    val out = ArrayList<LrcLine>()
    var offsetMs = 0L
    val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    for (line0 in raw.lineSequence()) {
        val line = line0.trim()
        if (line.isEmpty()) continue
        val meta = Regex("""^\[(ti|ar|al|by|offset):(.*)]$""", RegexOption.IGNORE_CASE)
        val mm = meta.find(line)
        if (mm != null) {
            if (mm.groupValues[1].equals("offset", true)) {
                offsetMs = mm.groupValues[2].trim().toLongOrNull() ?: 0L
            }
            continue
        }
        val times = ArrayList<Float>()
        var cursor = 0
        while (true) {
            val m = tag.find(line, cursor) ?: break
            if (m.range.first != cursor) break
            val min = m.groupValues[1].toIntOrNull() ?: 0
            val sec = m.groupValues[2].toIntOrNull() ?: 0
            val fracRaw = m.groupValues[3]
            val frac = when (fracRaw.length) {
                0 -> 0f
                1 -> fracRaw.toFloat() / 10f
                2 -> fracRaw.toFloat() / 100f
                else -> fracRaw.take(3).toFloat() / 1000f
            }
            times.add(min * 60f + sec + frac + offsetMs / 1000f)
            cursor = m.range.last + 1
        }
        val text = line.substring(cursor).trim()
        if (times.isEmpty()) continue
        for (t in times) out.add(LrcLine(t.coerceAtLeast(0f), text))
    }
    return out.sortedBy { it.atSec }
}

/** 当前播放秒数对应的歌词下标（-1 表示还没到第一句） */
fun currentLineIndex(lines: List<LrcLine>, sec: Float): Int {
    if (lines.isEmpty()) return -1
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (lines[mid].atSec <= sec) {
            ans = mid
            lo = mid + 1
        } else hi = mid - 1
    }
    return ans
}
