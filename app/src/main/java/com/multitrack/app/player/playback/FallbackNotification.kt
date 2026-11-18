package com.multitrack.app.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.multitrack.app.MainActivity
import com.multitrack.app.R

/**
 * 兜底通知：只在「Media3 自己想挂通知但没挂上」时用。
 *
 * 正常路径是 Media3 的 [androidx.media3.session.DefaultMediaNotificationProvider]，
 * 但它把通知交给 `startForeground()`；鸿蒙的安卓兼容层、部分激进省电 ROM 会直接抛
 * `ForegroundServiceStartNotAllowedException`，Media3 只写一条日志，界面上就是「通知栏什么都没有」。
 * 这里绕开前台服务那一步，用普通的 `notify()` 把同一条媒体通知发出去 ——
 * 通知本身还带着 MediaSession 的 token，系统的媒体卡片、锁屏控制照样能接管。
 */
internal object FallbackNotification {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var artKey: String? = null
    private var art: Bitmap? = null

    fun post(context: Context, session: MediaSession, channelId: String, id: Int) {
        runCatching {
            val player = session.player
            if (player.mediaItemCount == 0) return
            NotificationManagerCompat.from(context)
                .notify(id, build(context, session, channelId))
            loadArtwork(context, session, channelId, id)
        }
    }

    private fun build(context: Context, session: MediaSession, channelId: String): android.app.Notification {
        val player = session.player
        val metadata = player.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)
        val subtitle = (metadata.artist ?: metadata.albumTitle)?.toString()?.takeIf { it.isNotBlank() }
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setLargeIcon(art)
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_prev, "上一首",
                serviceAction(context, PlaybackService.ACTION_PREV, 1)
            )
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "暂停" else "播放",
                serviceAction(context, PlaybackService.ACTION_TOGGLE, 2)
            )
            .addAction(
                R.drawable.ic_next, "下一首",
                serviceAction(context, PlaybackService.ACTION_NEXT, 3)
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        val duration = player.duration
        if (duration != C.TIME_UNSET && duration > 0) {
            val position = player.currentPosition.coerceIn(0L, duration)
            builder.setProgress(1000, (position * 1000 / duration).toInt(), false)
        }
        return builder.build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context,
            code,
            Intent(context, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 封面：只在切歌后异步读一次（兜底路径本来就很短，不必上图片库） */
    private fun loadArtwork(context: Context, session: MediaSession, channelId: String, id: Int) {
        val uri = session.player.mediaMetadata.artworkUri ?: return
        val key = uri.toString()
        if (key == artKey) return
        artKey = key
        Thread {
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { decode ->
                        val side = 512
                        val scale = side.toFloat() / maxOf(decode.width, decode.height).coerceAtLeast(1)
                        if (scale >= 1f) decode else Bitmap.createScaledBitmap(
                            decode,
                            (decode.width * scale).toInt().coerceAtLeast(1),
                            (decode.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                    }
                }
            }.getOrNull() ?: return@Thread
            art = bitmap
            // ExoPlayer 的 getter 只能在它自己的线程（主线程）上调，解码完必须切回去重建
            mainHandler.post {
                runCatching {
                    NotificationManagerCompat.from(context).notify(id, build(context, session, channelId))
                }
            }
        }.start()
    }
}
