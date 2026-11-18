package com.multitrack.app.player.data

import android.content.Context

/**
 * 扫描设置的落盘（SharedPreferences 就够：几条路径 + 一个秒数）。
 *
 * 每一项都「全局生效」：[MediaScanner] 每次扫描都从这里读，
 * 改完设置触发重扫，不符合的条目直接从曲库消失。
 */
class ScanSettingsStore(private val context: Context) {

    data class Options(
        val scanRoots: Set<String> = emptySet(),
        val excludeDirs: Set<String> = emptySet(),
        val minDurationSec: Int = 1,
        val folderCovers: Boolean = true
    )

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(): Options = Options(
        scanRoots = prefs.getStringSet(K_ROOTS, emptySet())?.map(ScanFilter::normalizeDir)?.toSet() ?: emptySet(),
        excludeDirs = prefs.getStringSet(K_EXCLUDE, emptySet())?.map(ScanFilter::normalizeDir)?.toSet() ?: emptySet(),
        minDurationSec = prefs.getInt(K_MIN_SEC, 1),
        folderCovers = prefs.getBoolean(K_FOLDER_COVERS, true)
    )

    fun saveRoots(dirs: Set<String>) = edit(K_ROOTS, dirs)
    fun saveExcludes(dirs: Set<String>) = edit(K_EXCLUDE, dirs)

    fun saveMinDurationSec(sec: Int) {
        prefs.edit().putInt(K_MIN_SEC, sec.coerceIn(0, 3600)).apply()
    }

    fun saveFolderCovers(enabled: Boolean) {
        prefs.edit().putBoolean(K_FOLDER_COVERS, enabled).apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private fun edit(key: String, dirs: Set<String>) {
        // 目录统一去尾斜杠再存，比较时才不会漏
        prefs.edit().putStringSet(key, dirs.map(ScanFilter::normalizeDir).filter { it.isNotBlank() }.toSet()).apply()
    }

    companion object {
        private const val PREF = "cyril_scan"
        private const val K_ROOTS = "scan_roots"
        private const val K_EXCLUDE = "exclude_dirs"
        private const val K_MIN_SEC = "min_duration_sec"
        private const val K_FOLDER_COVERS = "folder_covers"
    }
}
