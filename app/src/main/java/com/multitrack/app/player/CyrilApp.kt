package com.multitrack.app.player

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.multitrack.app.player.data.ArtRepository
import com.multitrack.app.player.data.MediaScanner
import com.multitrack.app.player.data.PlayerDb
import com.multitrack.app.player.data.QueueRepository
import com.multitrack.app.player.data.LyricsRepository
import com.multitrack.app.player.data.ScanSettingsStore
import com.multitrack.app.player.data.TagWriter
import com.multitrack.app.player.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CyrilApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 关键：jaudiotagger 默认按桌面 JVM 走 StandardArtwork/StandardImageHandler，
        // 它们内部用 javax.imageio.ImageIO —— Android 上没有这个类，写封面时会抛
        // NoClassDefFoundError: Ljavax/imageio/ImageIO;
        // 打开 Android 分支后，ArtworkFactory 才会返回 AndroidArtwork（BitmapFactory 解码）。
        runCatching { org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true) }
        // AI 降噪的模型在 assets/dfn3 里，这里只装上下文，真正的 ONNX 会话等第一次用到再建
        com.multitrack.app.audio.dfn3.Dfn3Assets.install(this)
        container = AppContainer(this)
    }
}

fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

data class ScanUi(
    val running: Boolean = false,
    val message: String = "",
    val count: Int = 0,
    val permissionDenied: Boolean = false
)

/**
 * 依赖容器。所有重活都是 lazy：
 * 冷启动只构造对象，不打开数据库、不扫曲库、不连播放器 —— 首屏先出来，
 * 索引和恢复上次播放放到后台线程（这正是「分开加载」的落点）。
 */
class AppContainer(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 轻量 UI 状态（记住上次进的文件夹等） */
    val prefs by lazy { app.getSharedPreferences("cyril_ui", android.content.Context.MODE_PRIVATE) }

    val db: PlayerDb by lazy {
        Room.databaseBuilder(app, PlayerDb::class.java, "cyril_player.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    fun songDao() = db.songDao()
    fun folderDao() = db.folderDao()

    val queueRepo: QueueRepository by lazy { QueueRepository(db) }
    val art: ArtRepository by lazy { ArtRepository(app) }
    val tagWriter: TagWriter by lazy { TagWriter(app) }
    val lyrics: LyricsRepository by lazy { LyricsRepository(app) }
    val playback: PlaybackManager by lazy { PlaybackManager(app, queueRepo, scope) }
    private val scanner: MediaScanner by lazy { MediaScanner(app, db.songDao(), db.folderDao()) }
    val scanSettings: ScanSettingsStore by lazy { ScanSettingsStore(app) }

    private val _scan = MutableStateFlow(ScanUi())
    val scan: StateFlow<ScanUi> = _scan.asStateFlow()

    private var scanJob: Job? = null
    private var restored = false

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /**
     * 建索引。**没权限时绝不跑**（否则查询返回空、被当成「曲库就是空的」，
     * 用户后来授权了也不会再扫 —— 这就是之前「识别不出歌曲」的根因）。
     * 有权限时每次都跑一遍增量比对：MediaStore 查询 + 事务写，1 万首也在毫秒级。
     */
    fun ensureIndexed(force: Boolean = false) {
        if (!hasAudioPermission()) {
            _scan.value = ScanUi(permissionDenied = true)
            return
        }
        if (scanJob?.isActive == true && !force) return
        if (force) scanJob?.cancel()
        scanJob = scope.launch {
            _scan.value = ScanUi(running = true, message = "正在索引本机音乐…")
            val n = runCatching {
                scanner.scan { msg -> _scan.value = _scan.value.copy(message = msg) }
            }.getOrElse {
                _scan.value = ScanUi(running = false, message = "索引失败：${it.message}")
                return@launch
            }
            _scan.value = ScanUi(running = false, message = "已索引 $n 首", count = n)
            if (!restored) {
                restoreLastPlayback()
                restored = true
            }
        }
    }

    fun rescan() {
        scanJob?.cancel()
        scanJob = null
        ensureIndexed(force = true)
    }

    private suspend fun restoreLastPlayback() = runCatching {
        val active = db.queueDao().activeQueue() ?: return@runCatching
        if (db.queueDao().count(active.id) == 0) return@runCatching
        playback.activate(active.id, autoPlay = false)
    }

    fun shutdown() {
        runCatching { playback.onAppBackground() }
    }
}
