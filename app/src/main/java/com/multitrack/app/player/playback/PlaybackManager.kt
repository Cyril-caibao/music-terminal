package com.multitrack.app.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.multitrack.app.player.data.QueueRepository
import com.multitrack.app.player.data.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackUi(
    val ready: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val index: Int = 0,
    val count: Int = 0,
    val songId: Long = 0L,
    val speed: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val abStartMs: Long = -1L,
    val abEndMs: Long = -1L,
    val sleepAtMs: Long = 0L,
    /** 播放器报错时的短描述：界面直接显示，免得「放不了」只剩一句黑话 */
    val error: String? = null
)

/**
 * 播放器与 UI 之间的唯一通道：内部持有 MediaController，把播放器状态折成 StateFlow。
 * 连接是**懒**的 —— 冷启动不碰 MediaSession，用户第一次点播放才建立。
 */
class PlaybackManager(
    private val context: Context,
    private val queueRepo: QueueRepository,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(PlaybackUi())
    val state: StateFlow<PlaybackUi> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private var activeQueueId: Long? = null
    private var ticker: Job? = null
    private var queueCursor = 0
    private var songs: List<SongEntity> = emptyList()
    /** 队列的原始顺序（未打散）；持久化游标、关随机时都要用它 */
    private var queueOrder: List<SongEntity> = emptyList()
    /** 自己实现随机：ExoPlayer 的 shuffle 会把当前曲目重排到别处，正是「点这首播那首」的来源 */
    private var manualShuffle = false
    private var abStart = -1L
    private var abEnd = -1L
    private var sleepAt = 0L
    private var pending: Pending? = null

    /** 自动响度补偿：记住已经处理过的歌，避免 50ms 的 ticker 反复设音量 */
    private var autoGainSongId = -1L


    private data class Pending(
        val list: List<SongEntity>,
        val index: Int,
        val pos: Long,
        val autoPlay: Boolean,
        val repeat: Int,
        val shuffle: Boolean
    )

    val activeQueue: Long? get() = activeQueueId

    /** 点了但播放器还没真正切过去的那首；null 表示界面直接跟播放器 */
    private var expectedIndex: Int? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                expectedIndex = null
                queueCursor = originalIndexOfCurrent(player)
                persistCursor(0L, resetPosition = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 把错误码直接摆到界面上：否则「歌放不了」在日志之外完全不可见
            _state.value = _state.value.copy(
                isPlaying = false,
                error = "播放失败 ${error.errorCodeName}" +
                    (error.cause?.let { "：${it.javaClass.simpleName}" } ?: "")
            )
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error == null) _state.value = _state.value.copy(error = null)
        }
    }

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { controller = future.get().also { it.addListener(listener) } }
                .onFailure { t ->
                    _state.value = _state.value.copy(
                        error = "播放服务没起来：${t.javaClass.simpleName}"
                    )
                }
            connecting = false
            _state.value = _state.value.copy(ready = controller != null)
            controller?.let { c ->
                publish(c)
                pending?.let { p ->
                    pending = null
                    apply(c, p)
                }
            }
            startTicker()
        }, MoreExecutors.directExecutor())
    }

    /**
     * 激活一条队列。[startIndex] 不为空时从这一首的开头播放（列表里点哪首就播哪首），
     * 否则沿用队列自己存的 cursorIndex / cursorPositionMs。
     */
    suspend fun activate(
        queueId: Long,
        autoPlay: Boolean = true,
        startIndex: Int? = null,
        startSongId: Long? = null
    ) {
        val queue = queueRepo.queue(queueId) ?: return
        val list = queueRepo.songs(queueId)
        if (list.isEmpty()) return
        connect()
        activeQueueId = queueId
        songs = list
        queueOrder = list
        // 优先按 songId 定位：调用方拿到的列表顺序未必和队列里的顺序一致（比如文件夹页按标题排、
        // 队列按插入顺序），拿下标会点这首播那首。songId 才是稳定标识。
        val byId = startSongId?.let { sid -> list.indexOfFirst { it.id == sid } } ?: -1
        val hinted = startIndex?.coerceIn(0, list.size - 1)
        // 下标优先、但要和 songId 对得上（队列里同一首歌可以出现多次，下标才分得清是哪一条）；
        // 下标对不上（比如列表按标题排、队列按插入序）才退回按 songId 找第一条。
        val idx = when {
            hinted != null && (startSongId == null || list[hinted].id == startSongId) -> hinted
            byId >= 0 -> byId
            hinted != null -> hinted
            else -> queue.cursorIndex.coerceIn(0, list.size - 1)
        }
        queueCursor = idx
        val explicit = byId >= 0 || startIndex != null
        val pos = if (explicit) 0L else queue.cursorPositionMs
        if (explicit) runCatching { queueRepo.saveCursor(queueId, idx, 0L) }
        val cfg = Pending(
            list = list,
            index = idx,
            pos = pos,
            autoPlay = autoPlay,
            repeat = queue.repeatMode,
            shuffle = queue.shuffleMode >= 3
        )
        expectedIndex = idx
        // 先把 UI 切到用户点的那首：setMediaItems 是异步命令，等它回来再刷新的话，
        // 中间这一两百毫秒界面还停在上一首 —— 看起来就像「点的和播的不是同一首」。
        _state.value = _state.value.copy(
            index = idx,
            count = list.size,
            songId = list[idx].id,
            positionMs = 0L,
            durationMs = list[idx].durationMs
        )
        val c = controller
        if (c == null) {
            pending = cfg          // 控制器还没连上：连上后立刻接棒
        } else {
            apply(c, cfg)
        }
    }

    fun toggle() {
        val c = controller
        if (c == null) { connect(); return }
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun prev() { controller?.seekToPreviousMediaItem() }

    /**
     * 让服务把媒体通知再挂一次。
     *
     * 系统有时会在服务已经被绑住的情况下拒掉 `startForeground`（鸿蒙的安卓兼容层尤其容易），
     * 那条通知就再也不会出现；给用户一个手动重试的入口，比让他重装应用靠谱。
     */
    fun repostNotification() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        runCatching {
            context.startService(
                android.content.Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_REPOST)
            )
        }
    }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }

    fun seekRelative(ms: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + ms).coerceIn(0, c.duration.coerceAtLeast(0)))
    }

    fun setSpeed(v: Float) {
        controller?.setPlaybackSpeed(v)
        _state.value = _state.value.copy(speed = v)
    }

    /** 自己实现随机：随机时把当前曲提到第 0 位，其余打散；关随机按原队列顺序恢复，当前位置保留 */
    fun toggleShuffle() {
        val c = controller
        manualShuffle = !manualShuffle
        if (c == null) {
            _state.value = _state.value.copy(shuffle = manualShuffle)
            return
        }
        val curIdx = c.currentMediaItemIndex
        val cur = songs.getOrNull(curIdx) ?: return
        val pos = c.currentPosition.coerceAtLeast(0)
        val playing = c.isPlaying
        val rest = if (manualShuffle) {
            songs.filterIndexed { i, _ -> i != curIdx }.shuffled()
        } else {
            val oi = queueOrder.indexOfFirst { it.id == cur.id }.takeIf { it >= 0 } ?: 0
            queueOrder.filterIndexed { i, _ -> i != oi }
        }
        val ordered = listOf(cur) + rest
        songs = ordered
        c.setMediaItems(ordered.map { it.toMediaItem() }, 0, pos)
        c.prepare()
        if (playing) c.play()
        _state.value = _state.value.copy(shuffle = manualShuffle, index = 0, songId = cur.id)
    }

    // ===== A-B 片段循环 =====
    /** A 点取当前位置；B 点必须晚于 A（否则先清掉旧的 A）。 */
    fun markA() {
        val pos = state.value.positionMs
        abStart = pos
        if (abEnd <= abStart) abEnd = -1L
        syncAb()
    }

    fun markB() {
        val pos = state.value.positionMs
        if (abStart < 0 || pos <= abStart + 500) {
            // 用户先点了 B：把 A 放到 5 秒前，避免循环区只有几毫秒
            abStart = (pos - 5000).coerceAtLeast(0)
        }
        abEnd = pos
        syncAb()
    }

    fun clearAb() {
        abStart = -1L
        abEnd = -1L
        syncAb()
    }

    private fun syncAb() {
        _state.value = _state.value.copy(abStartMs = abStart, abEndMs = abEnd)
    }

    // ===== 睡眠定时 =====
    fun setSleepMinutes(minutes: Int) {
        sleepAt = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepAtMs = sleepAt)
    }

    fun remainingSleepMinutes(): Int =
        if (sleepAt <= 0L) 0
        else (((sleepAt - System.currentTimeMillis()) / 60_000L) + 1).toInt().coerceAtLeast(0)

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun apply(c: MediaController, p: Pending) {
        // 随机由我们自己打散：播放器的 shuffle 会重排时间轴，下标与歌曲对应关系全乱。
        // 点哪首就把它放在第 0 位，其余随机排后面；播放器一侧永远保持 shuffle = false。
        val target = p.list[p.index]
        val ordered: List<SongEntity>
        val startIdx: Int
        if (p.shuffle) {
            ordered = listOf(target) + p.list.filterIndexed { i, _ -> i != p.index }.shuffled()
            startIdx = 0
        } else {
            ordered = p.list
            startIdx = p.index
        }
        songs = ordered
        manualShuffle = p.shuffle
        c.repeatMode = if (p.repeat == 0) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        c.shuffleModeEnabled = false
        c.setMediaItems(ordered.map { it.toMediaItem() }, startIdx, p.pos)
        c.prepare()
        if (p.autoPlay) c.play() else c.playWhenReady = false
        // 兜底校验：随机模式/异步命令都可能让播放器落到别的条目上。
        // 150ms 后按 mediaId 核对，错了就按 mediaId 找回正确条目再 seek —— 不再靠推断。
        val targetId = ordered[startIdx].id
        scope.launch {
            delay(150)
            val c = controller ?: return@launch
            val real = c.currentMediaItem?.mediaId?.toLongOrNull()
            if (real == targetId) return@launch
            val idx = (0 until c.mediaItemCount).firstOrNull {
                runCatching { c.getMediaItemAt(it).mediaId.toLongOrNull() == targetId }.getOrDefault(false)
            } ?: return@launch
            runCatching { c.seekToDefaultPosition(idx) }
            expectedIndex = idx
            publish(c)
        }
        // 不再立刻 publish：命令是异步的，这时候读 currentMediaItemIndex 还是旧值，
        // 会把 activate() 刚做的乐观状态又盖回去（界面上就变成「点了 A 显示 B」）。
        // 真正的状态由播放器的 onMediaItemTransition / onEvents 回调刷新。
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                // 50ms 一刷（20fps）：歌词高亮、进度条、播放头都要跟得上声音。
                // 之前 120ms 仍然能看出「字比声音慢」—— 120ms 加上一帧的重组延迟，观感差不多 200ms 了。
                delay(50)
                controller?.let { c ->
                    // A-B：越过 B 点就回到 A 点
                    if (abStart >= 0 && abEnd > abStart) {
                        val pos = c.currentPosition
                        if (pos >= abEnd) c.seekTo(abStart)
                    }
                    // 睡眠定时：到点暂停（留 2 秒，别切在字上）
                    if (sleepAt > 0L && System.currentTimeMillis() >= sleepAt) {
                        sleepAt = 0L
                        c.pause()
                        _state.value = _state.value.copy(sleepAtMs = 0L)
                    }
                    publish(c)
                }
            }
        }
    }

    private fun publish(p: Player) {
        // 命令是异步的：刚 setMediaItems 时 currentMediaItemIndex 可能还是旧的那首。
        // 在播放器真正切到目标条目前，界面按 expectedIndex 显示，避免「点了 A 却显示 B」。
        val real = p.currentMediaItemIndex
        val shown = expectedIndex ?: real
        if (expectedIndex != null && real == expectedIndex) expectedIndex = null
        val shownSong = songs.getOrNull(shown)
        val id = shownSong?.id ?: (p.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L)
        val next = PlaybackUi(
            ready = true,
            isPlaying = p.isPlaying,
            positionMs = if (shown != real) 0L else p.currentPosition.coerceAtLeast(0),
            durationMs = shownSong?.durationMs ?: p.duration.coerceAtLeast(0),
            index = shown,
            count = p.mediaItemCount,
            songId = id,
            speed = p.playbackParameters.speed,
            shuffle = manualShuffle,
            repeatMode = p.repeatMode,
            abStartMs = abStart,
            abEndMs = abEnd,
            sleepAtMs = sleepAt,
            // 错误状态由 onPlayerError / onPlayerErrorChanged 负责设置和清除，
            // 这里全量重建时别把它抹掉（否则错误提示闪一下就被下一帧覆盖）
            error = _state.value.error
        )
        queueCursor = originalIndexOfCurrent(p)
        applyAutoGain(p)
        val prev = _state.value
        // 除位置外全部相同、而且位置几乎没动（暂停、缓冲、刚 seek 完）就不发新状态：
        // 50ms 的 ticker 如果每次都推一个新对象，整页会跟着白重组一遍。
        val sameExceptPosition = next.copy(positionMs = 0L) == prev.copy(positionMs = 0L)
        if (sameExceptPosition && kotlin.math.abs(next.positionMs - prev.positionMs) < 20L) return
        _state.value = next
    }

    /** 把游标写回队列表；切队列、退到后台、被杀掉都能原样恢复。 */
    /** 当前播放的这首在**原始队列顺序**里的下标；随机模式下播放器下标是打散后的，不能直接存 */
    private fun originalIndexOfCurrent(c: Player): Int {
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return 0
        return queueOrder.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
    }

    /**
     * 让每首歌的响度大致拉平（不同专辑母带电平差得远，否则一首接一首会忽大忽小）。
     *
     * 增益乘在播放器音量上，不动 DSP —— 用户自己推的 EQ / 超低音该破音还是破音，
     * 不会被这里"压回去"。没有测量结果时先按 1.0 播，测完再用 240ms 的坡度补上。
     */
    private fun applyAutoGain(p: Player) {
        val song = songs.getOrNull(p.currentMediaItemIndex) ?: return
        if (song.id == autoGainSongId) return
        autoGainSongId = song.id
        if (!Loudness.enabled(context)) {
            runCatching { p.volume = 1f }
            return
        }
        val known = Loudness.cachedGain(context, song)
        if (known != null) {
            rampVolume(p, known, song.id)
        } else {
            runCatching { p.volume = 1f }
            Loudness.measureAsync(context, song) { gain ->
                if (gain != null && autoGainSongId == song.id) rampVolume(controller, gain, song.id)
            }
        }
        // 下一首先测好：轮到时直接有值，不用听前面半秒的"原音量"
        songs.getOrNull(p.currentMediaItemIndex + 1)?.let { Loudness.prefetch(context, it) }
    }

    private fun rampVolume(p: Player?, target: Float, songId: Long) {
        if (p == null) return
        val from = runCatching { p.volume }.getOrDefault(1f)
        if (kotlin.math.abs(from - target) < 0.01f) {
            runCatching { p.volume = target }
            return
        }
        scope.launch {
            val steps = 12
            for (i in 1..steps) {
                if (autoGainSongId != songId) return@launch
                runCatching { p.volume = from + (target - from) * (i.toFloat() / steps) }
                delay(20)
            }
        }
    }

    fun isAutoGainOn(): Boolean = Loudness.enabled(context)

    fun setAutoGain(on: Boolean) {
        Loudness.setEnabled(context, on)
        autoGainSongId = -1L
        controller?.let { applyAutoGain(it) }
    }

    fun persistCursor(positionMs: Long = state.value.positionMs, resetPosition: Boolean = false) {
        val qid = activeQueueId ?: return
        val idx = queueCursor
        val pos = if (resetPosition) 0L else positionMs
        scope.launch(Dispatchers.IO) { runCatching { queueRepo.saveCursor(qid, idx, pos) } }
    }

    fun onAppBackground() = persistCursor()

    private fun SongEntity.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                // 通知栏/锁屏/车机的封面就从这里来。构建 MediaItem 是同步的，
                // 所以只做两次 exists() 判断，真正的抓图还是留给 ArtRepository 在后台做。
                .setArtworkUri(artworkUri(this))
                .build()
        )
        .build()

    /**
     * 通知栏封面：优先用户自己换过的 covers/<id>.jpg，其次 art/<id>.jpg。
     * 两个都只是本地文件，不会去读相册，也不会碰系统 albumart（那条路会挑错图）。
     */
    private fun artworkUri(song: SongEntity): android.net.Uri? {
        val custom = java.io.File(context.filesDir, "covers/${song.id}.jpg")
        if (custom.exists() && custom.length() > 0) return android.net.Uri.fromFile(custom)
        val cached = java.io.File(context.filesDir, "art/${song.id}.jpg")
        if (cached.exists() && cached.length() > 0) return android.net.Uri.fromFile(cached)
        return null
    }
}
