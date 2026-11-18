package com.multitrack.app.player.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.multitrack.app.MainActivity
import com.multitrack.app.R
import com.multitrack.app.player.NotificationCheck

/**
 * 前台播放服务。播放器只有这一个实例：
 * 通知、锁屏、蓝牙线控、Android Auto 全部由 MediaSession 统一对外，不另写一套。
 *
 * 通知栏那个「极简播放器」就是这里的 MediaSession 通知：
 * 封面 + 曲名 / 艺人 + 上一首 / 播放暂停 / 下一首，点一下回到 App。
 *
 * 这一版补的是「通知根本不出现」：
 * 1. 渠道自己建（DEFAULT 而不是 LOW）—— 鸿蒙沙盒 / 部分 ROM 会把低重要性渠道折叠掉，
 *    而且渠道一旦建好，重要性就再也改不了，所以换了新 id（旧机上残留的 LOW 渠道不再复用）；
 * 2. 播放开始 1.8 秒后自检一次：前台服务要是被系统拦下（Media3 内部只打一条日志），
 *    就用同一套会话自己补一条媒体通知，保证「在放歌」这件事在通知栏一定看得见；
 * 3. 用户从菜单里「重发通知」走 [ACTION_REPOST]，直接让 Media3 再走一次 startForeground。
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        /**
         * 渠道 id 带版本号：v1 在旧安装里是用 IMPORTANCE_LOW 建的，
         * 渠道的重要性创建后不可修改，只能换一个新 id 重新建。
         */
        const val CHANNEL_ID = "cyril_playback_v2"
        const val NOTIFICATION_ID = 1001

        /** 从菜单里手动重发一次通知 */
        const val ACTION_REPOST = "com.multitrack.app.action.REPOST_NOTIFICATION"
        /** 兜底通知上的三个按钮（不经过 MediaSession 的 media button 广播，直接进服务） */
        const val ACTION_PREV = "com.multitrack.app.action.NOTIF_PREV"
        const val ACTION_TOGGLE = "com.multitrack.app.action.NOTIF_TOGGLE"
        const val ACTION_NEXT = "com.multitrack.app.action.NOTIF_NEXT"
    }

    /** 通知到底有没有挂上去：前台服务被拦时 Media3 只写日志，界面上完全看不出来 */
    private val verifyNotification = Runnable {
        val s = session ?: return@Runnable
        // Media3 建渠道时用的是 LOW，可能把我们刚建好的 DEFAULT 又压回去 —— 这里再抬一次
        ensureChannel()
        val player = s.player
        if (!player.isPlaying && !player.playWhenReady) return@Runnable
        if (!NotificationCheck.enabled(this)) return@Runnable
        if (isNotificationVisible()) return@Runnable
        FallbackNotification.post(this, s, CHANNEL_ID, NOTIFICATION_ID)
    }

    private fun scheduleVerify(delayMs: Long) {
        handler.removeCallbacks(verifyNotification)
        handler.postDelayed(verifyNotification, delayMs)
    }

    private fun isNotificationVisible(): Boolean = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.activeNotifications.any { it.id == NOTIFICATION_ID }
    }.getOrDefault(false)

    override fun onCreate() {
        super.onCreate()
        // 必须在 Media3 建渠道之前：它会用 LOW 建同名渠道，先建好 DEFAULT 的，它就不会再降级
        ensureChannel()

        val player = ExoPlayer.Builder(this)
            // 音效链挂在自己这里：float 计算、不限幅，见 FxAudioProcessor
            .setRenderersFactory(FxRenderersFactory(this))
            // 明确声明「这是媒体播放」：Media3 的系统媒体通知、锁屏卡片、音频焦点
            // 都靠这个 usage 判断，不给的话有些 ROM 干脆不出媒体通知
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            // 拔耳机 / 断蓝牙时自动暂停，不要直接外放
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // 音频会话号交给音效层：均衡器/低频/3D/混响都挂在同一个 session 上
        AudioFx.bindContext(this)
        AudioFx.attach(player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) scheduleVerify(1_800)
            }
        })

        // 点通知 / 锁屏封面 → 打开 App（单实例，回到原来的界面而不是重开一个）
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(contentIntent)
            .build()

        // 极简播放器：只有封面、标题、艺人和三个走带按钮
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        provider.setSmallIcon(R.drawable.ic_stat_music)
        setMediaNotificationProvider(provider)
        // provider 构造时又用 LOW 建了同一个渠道，这里补一次，把它抬回 DEFAULT
        ensureChannel()
    }

    /**
     * 自己的通知渠道：默认重要性 + 静音 + 不上角标 + 锁屏可见。
     *
     * 用 LOW 的话部分 ROM（以及鸿蒙的安卓兼容层）会把通知折成一条很窄的灰条、甚至不显示图标；
     * 但媒体通知本来就不该响，所以音效全关。
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance >= NotificationManager.IMPORTANCE_DEFAULT) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPOST -> {
                // 手动重发：让 Media3 再走一次 startForeground；要是 1 秒后还没有，兜底自己发
                runCatching { session?.let { onUpdateNotification(it, true) } }
                scheduleVerify(1_000)
                return START_STICKY
            }
            ACTION_PREV -> runCatching { session?.player?.seekToPrevious() }
            ACTION_TOGGLE -> runCatching { session?.player?.let { if (it.isPlaying) it.pause() else it.play() } }
            ACTION_NEXT -> runCatching { session?.player?.seekToNext() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    /**
     * 把 [FxAudioProcessor] 插进播放器的音频处理链。
     *
     * 注意：这里**不打开** float 输出。AudioTrack 的 float 通道在部分机型上
     * 会在初始化阶段直接失败，那会让整个播放服务起不来（表现为「歌都放不了」）。
     * FxAudioProcessor 内部仍按 float 计算，只在最后一步落到 16bit：
     * 推过头就是硬削波（破音），而不是把整体增益悄悄压回去。
     */
    private class FxRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(*arrayOf(FxAudioProcessor()))
                )
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(false)
                .build()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
