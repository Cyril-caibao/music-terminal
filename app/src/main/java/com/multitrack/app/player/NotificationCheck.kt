package com.multitrack.app.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.multitrack.app.player.playback.PlaybackService

/**
 * 通知栏播放器的可用性检查。
 *
 * Media3 的系统媒体通知（也就是「顶部状态栏 / 锁屏那个播放器」）依赖两件事：
 * 1. 应用的通知总开关是开的（用户可以单独关掉，跟运行时权限是两回事）；
 * 2. Android 13+ 的 POST_NOTIFICATIONS 已经授予。
 *
 * 任意一条不满足，MediaSession 照样在跑，但通知根本不出现 —— 界面上完全看不出来，
 * 所以这里要给用户一个明确的入口。
 */
object NotificationCheck {

    /** Android 13+ 的运行时权限（POST_NOTIFICATIONS）有没有给 */
    fun runtimeGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun enabled(context: Context): Boolean {
        if (!runtimeGranted(context)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 那条播放通知此刻是不是真的挂在通知栏上。
     *
     * 权限、总开关都开着却还是查不到，说明前台服务被系统拦了 —— 这正是「设置里明明开着，
     * 通知栏却什么都没有」的情形，界面要能把这一种单独区分出来，并给一个重发按钮。
     */
    fun mediaShown(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.activeNotifications.any { it.id == PlaybackService.NOTIFICATION_ID }
    }.getOrDefault(false)

    /** 跳到本应用的通知设置；部分 ROM 没有这个页面时退到应用详情 */
    fun openSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
    }
}
