package com.multitrack.app.player.data

import java.util.Locale

/**
 * 曲库 / 队列的搜索匹配。
 *
 * 之前的实现是 `title.contains(query)`：少打一个字、打错一个字就一条都搜不到 ——
 * 手机上单手打字，这几乎是必然发生的。这里分四档：
 *
 * 1. 完全相等 / 前缀 / 包含（最快，命中也最准）；
 * 2. 子序列（"zjl" 能搜到 "Zhou Jie Lun"，跳着打也认）；
 * 3. 编辑距离（错一两个字仍然算中，长度越长容忍度越高）；
 * 4. 多关键词：按空格拆开，每个词都得命中某一栏（标题 / 艺人 / 专辑）。
 *
 * 纯函数，不依赖 Android，单测能直接把「错字」这类输入钉死。
 */
object SongSearch {

    /**
     * 归一化结果缓存：搜索是每敲一个字都要跑一遍全库，
     * 标题/艺人/专辑反复归一化会白白产生几万次字符串分配。
     */
    private val normCache = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 8192
    }

    /** 全角 → 半角，去掉空白与标点，只留字母 / 数字 / 汉字 */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            var c = ch
            if (c.code in 0xFF01..0xFF5E) c = (c.code - 0xFEE0).toChar()
            if (c == '\u3000') continue
            when {
                c.isWhitespace() -> continue
                c.isLetterOrDigit() -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }

    private fun normalized(raw: String): String {
        if (raw.isEmpty()) return ""
        synchronized(normCache) { normCache[raw]?.let { return it } }
        val value = normalize(raw)
        synchronized(normCache) { normCache[raw] = value }
        return value
    }

    /** 查询串按空格拆词；每个词单独匹配，全部命中才算命中 */
    private fun tokens(query: String): List<String> =
        query.split(' ', '\u3000', '\t')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

    /**
     * 命中程度，0 表示不匹配。分值只用于同一次查询内部排序，不跨查询比较。
     *
     * [fields] 传标题、艺人、专辑；另有一条「拼起来」的串，用来处理跨栏输入
     * （比如「周杰伦稻香」这种把艺人和歌名连在一起打的）。
     */
    fun score(query: String, vararg fields: String): Int {
        val terms = tokens(query)
        if (terms.isEmpty()) return 0
        val normalized = fields.map { normalized(it) }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return 0
        val joined = normalized.joinToString("")

        var total = 0
        for (term in terms) {
            var best = 0
            for (field in normalized) {
                best = maxOf(best, fieldScore(term, field))
                if (best >= 1000) break
            }
            if (best == 0) best = fieldScore(term, joined)
            if (best == 0) return 0
            total += best
        }
        return total
    }

    fun matches(query: String, vararg fields: String): Boolean {
        if (query.isBlank()) return true
        return score(query, *fields) > 0
    }

    /** 单栏打分：包含 > 子序列 > 编辑距离 */
    private fun fieldScore(term: String, field: String): Int {
        if (term.isEmpty()) return 0
        if (field == term) return 1000
        if (field.startsWith(term)) return 900 - (field.length - term.length).coerceAtMost(80)
        val at = field.indexOf(term)
        if (at >= 0) return 800 - at.coerceAtMost(80)
        if (isSubsequence(term, field)) return 600 - (field.length - term.length).coerceAtMost(120)

        val limit = allowedDistance(term.length)
        if (limit == 0) return 0
        val d = bestWindowDistance(term, field, limit) ?: return 0
        return 520 - d * 90
    }

    /** 误字容忍度：两三个字允许错一个，长一点的允许错两个，再长允许三个 */
    private fun allowedDistance(length: Int): Int = when {
        length <= 1 -> 0
        length <= 4 -> 1
        length <= 9 -> 2
        else -> 3
    }

    private fun isSubsequence(term: String, field: String): Boolean {
        var i = 0
        for (c in field) {
            if (i < term.length && c == term[i]) i++
            if (i == term.length) return true
        }
        return i == term.length
    }

    /**
     * 在 field 里找一段与 term 编辑距离最近的窗口。
     *
     * 只在「首字出现的位置」附近开窗：既省算力，也避免在长专辑名里瞎配。
     * 允许窗口长一点或短一点（多打/漏打一个字），由 Levenshtein 自己消化。
     */
    private fun bestWindowDistance(term: String, field: String, limit: Int): Int? {
        if (field.length < term.length - limit) return null
        var best = limit + 1
        val maxStart = minOf(field.length - 1, 80)
        val first = term[0]
        for (start in 0..maxStart) {
            if (field[start] != first) continue
            for (extra in -limit..limit) {
                val end = (start + term.length + extra).coerceIn(start + 1, field.length)
                val window = field.substring(start, end)
                if (kotlin.math.abs(window.length - term.length) > limit) continue
                val d = boundedDistance(term, window, minOf(limit, best - 1))
                if (d < best) best = d
                if (best == 0) return 0
            }
        }
        return if (best <= limit) best else null
    }

    /** Levenshtein，超过 [limit] 就提前放弃（算到一半行最小值已经超了） */
    private fun boundedDistance(a: String, b: String, limit: Int): Int {
        if (limit < 0) return limit + 1
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return limit + 1
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }

    /** 便于界面显示：按匹配度排序后再按用户选的字段排 */
    fun rank(query: String, vararg fields: String): Int = score(query, *fields)

    @Suppress("unused")
    private fun lower(text: String) = text.lowercase(Locale.ROOT)
}
