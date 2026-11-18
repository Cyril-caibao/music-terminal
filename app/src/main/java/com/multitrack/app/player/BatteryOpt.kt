package com.multitrack.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 忽略电池优化（省电白名单）。
 *
 * 不申请的话，国产 ROM 的省电策略会在锁屏后把进程降频甚至冻结：
 * 解码线程拿不到 CPU，声音就开始一顿一顿，一秒卡两三下。
 * 进了白名单系统才允许后台正常跑（前台服务 + WAKE_LOCK 仍然照用，这只是最后一层保险）。
 */
object BatteryOpt {

    /** 已经忽略电池优化了吗（系统太老、或者根本问不到，就当是已允许） */
    fun isIgnoring(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /**
     * 弹出系统的「允许忽略电池优化」确认框。
     * 有些 ROM 把这个弹窗禁掉了，那就退到「电池优化列表」让用户自己找本应用。
     */
    fun request(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pkg = Uri.parse("package:${context.packageName}")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(list) }.isSuccess
    }
}
