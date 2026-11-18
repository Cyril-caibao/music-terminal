package com.multitrack.app.ui

import kotlinx.coroutines.withContext

import kotlinx.coroutines.launch

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multitrack.app.audio.*
import com.multitrack.app.audio.dfn3.Dfn3
import com.multitrack.app.audio.dfn3.Dfn3Assets
import com.multitrack.app.model.Clip
import com.multitrack.app.model.Project
import com.multitrack.app.model.Track
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.min

/**
 * 编辑器状态与业务逻辑：从鸿蒙版 Index.ets 平移。
 * 持有走带、片段操作、效果处理、导入/录音/导出等；Context 用 applicationContext 避免泄漏。
 */
class EditorState(private val appContext: Context) {

    val engine = AudioEngine()
    val recorder = Recorder()

    var playheadFrame by mutableStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var loop by mutableStateOf(false)
        private set
    var selectedClipId by mutableStateOf("")
    var secondsPerScreen by mutableStateOf(30f)
    /** 拖动片段期间锁定时间轴比例，避免工程变长触发整条时间轴缩放 */
    var spanLock by mutableStateOf<Float?>(null)
        private set
    /** 拖动片段时手指在时间轴内容坐标里的 X（px），用于只在贴着屏幕边缘时才自动滚动 */
    var dragPointerX by mutableStateOf(0f)
    /** 最近一次指针移动的水平方向（正=右，负=左，0=按住不动） */
    var dragDirX by mutableStateOf(0f)
    /** 按下时手指在片段内部的 X（px），用于推算手指在屏幕上的位置 */
    var dragGrabX by mutableStateOf(0f)
    /** 自动滚动补偿到拖动位移里的累计像素，保证片段跟手且提交位置正确 */
    var dragScrollComp by mutableStateOf(0f)
    /** 手指相对按下点的真实位移（根坐标口径，不含滚动漂移） */
    var dragRootDeltaX by mutableStateOf(0f)
    var dragRootDeltaY by mutableStateOf(0f)
    /** 竖直方向的同类补偿（跨轨拖动时用） */
    var dragScrollCompY by mutableStateOf(0f)
    /** 最近一次指针移动的垂直方向（正=下，负=上，0=按住不动） */
    var dragDirY by mutableStateOf(0f)
    /** 最近一次方向变化的时刻（uptimeMillis）；按住不动超过阈值后方向自动归零 */
    var dragDirAtMs by mutableStateOf(0L)
    /** 是否为「长按后跨轨拖动」，只有它为 true 才做纵向边缘滚动 */
    var dragCrossTrack by mutableStateOf(false)
    /** 手指在窗口坐标系里的 Y（px） */
    var dragPointerY by mutableStateOf(0f)

    /**
     * 时间轴滚动视口在窗口坐标系中的位置与尺寸（px）。
     * 用真实视口而非整屏宽度做边缘判定：竖屏/横屏、折叠屏分栏都能自动跟着变。
     */
    var viewportLeftPx by mutableStateOf(0f)
    var viewportTopPx by mutableStateOf(0f)
    var viewportWidthPx by mutableStateOf(0f)
    var viewportHeightPx by mutableStateOf(0f)

    fun lockSpan() {
        val cur = maxOf(secondsPerScreen, minViewSpan)
        spanLock = cur.coerceIn(MIN_SPAN, MAX_SPAN)
    }

    fun clearSpanLock() { spanLock = null }
    var recording by mutableStateOf(false)
        private set
    var toast by mutableStateOf("")
        private set

    /** 当前展开的顶部菜单（null 表示收起） */
    var openMenu by mutableStateOf<String?>(null)
        private set

    fun toggleMenu(name: String) { openMenu = if (openMenu == name) null else name }
    fun closeMenu() { openMenu = null }

    /** Pro-Q 均衡器界面是否打开（全屏覆盖） */
    var eqOpen by mutableStateOf(false)
        private set

    private var recTrack: Track? = null

    init {
        engine.onStopped = {
            playing = false
            previewing = false
            playheadFrame = engine.getPosition()
        }
        // 预置 3 条空轨（仿 AU 打开时的空工程）；加判断避免 Activity 重建后重复添加
        if (Project.tracks.isEmpty()) {
            for (i in 0 until 3) Project.addTrack()
        }
    }

    // ---------------- 派生量 ----------------
    fun totalFrames(): Int = Project.projectLength()
    fun totalSeconds(): Float = totalFrames().toFloat() / C.SAMPLE_RATE

    fun fmtTime(frame: Int): String {
        val sec = frame.toFloat() / C.SAMPLE_RATE
        val m = (sec / 60).toInt()
        val s = (sec % 60).toInt()
        val cs = ((sec - sec.toInt()) * 100).toInt()
        return "$m:${s.toString().padStart(2, '0')}.${cs.toString().padStart(2, '0')}"
    }

    fun showToast(msg: String) { toast = msg }

    /** 轮询引擎播放头，由 UI 定时调用 */
    /** 拖动播放头期间挂起轮询，避免引擎旧位置把白线拉回去 */
    var seeking by mutableStateOf(false)

    // 播放头用「单调时钟」推进：起点帧 + 经过时间，绝不回跳
    private var anchorFrame = 0
    private var anchorNs = 0L

    fun markPlayAnchor(frame: Int = playheadFrame) {
        anchorFrame = frame
        anchorNs = System.nanoTime()
    }

    fun pollPlayhead() {
        if (seeking) return
        if (!playing) {
            playheadFrame = engine.getPosition()
            return
        }
        if (anchorNs == 0L) markPlayAnchor()
        val elapsedSec = (System.nanoTime() - anchorNs) / 1_000_000_000.0
        var est = anchorFrame + elapsedSec * C.SAMPLE_RATE
        val total = totalFrames()
        if (total > 0 && est >= total) {
            if (loop) {
                markPlayAnchor(0)
                est = 0.0
            } else {
                est = total.toDouble()
            }
        }
        playheadFrame = est.toInt()
    }

    // ---------------- 歌词 / 封面 ----------------
    var lyrics by mutableStateOf<List<com.multitrack.app.lyrics.LrcLine>>(emptyList())
    var lyricsName by mutableStateOf("")
        private set
    var cover by mutableStateOf<android.graphics.Bitmap?>(null)
        private set
    var coverName by mutableStateOf("")
        private set
    var showLyrics by mutableStateOf(true)

    fun importLyrics(uri: android.net.Uri) {
        try {
            val text = appContext.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return
            val lines = com.multitrack.app.lyrics.parseLrc(text)
            lyrics = lines
            lyricsName = queryName(uri) ?: "lyrics.lrc"
            showLyrics = true
            showToast(if (lines.isEmpty()) "没有解析到歌词行" else "已导入歌词 ${lines.size} 行")
        } catch (e: Exception) {
            showToast("歌词导入失败：${e.message}")
        }
    }

    fun importCover(uri: android.net.Uri) {
        try {
            val name = queryName(uri) ?: "cover"
            val bmp = loadBitmap(uri) ?: run {
                showToast("这个图片格式解不开")
                return
            }
            cover = bmp
            coverName = name
            Project.notifyChanged()
            showToast("已载入封面 ${bmp.width}×${bmp.height}")
        } catch (e: Exception) {
            showToast("封面载入失败：${e.message}")
        }
    }

    fun clearLyrics() { lyrics = emptyList(); lyricsName = "" }
    fun clearCover() { cover = null; coverName = "" }

    private fun loadBitmap(uri: android.net.Uri): android.graphics.Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        // API 28+ 优先 ImageDecoder，支持 HEIC/AVIF 等更多格式
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val src = android.graphics.ImageDecoder.createSource(appContext.contentResolver, uri)
                return android.graphics.ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.setTargetSampleSize(2)
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Throwable) {
                // 回退 BitmapFactory
            }
        }
        val stream = appContext.contentResolver.openInputStream(uri)
        val bmp = stream?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        return bmp
    }

    // ---------------- 走带 ----------------
    fun togglePlay() {
        if (playing) {
            engine.pause()
            playing = false
            playheadFrame = engine.getPosition()
            return
        }
        if (totalFrames() > 0 && playheadFrame >= totalFrames()) playheadFrame = 0
        engine.setLoop(loop)
        engine.start(playheadFrame, loop)
        playing = true
        markPlayAnchor(playheadFrame)
    }

    fun stopPlay() {
        engine.stop()
        playing = false
        playheadFrame = 0
        markPlayAnchor(0)
        playheadFrame = 0
    }

    fun seekTo(second: Float) {
        playheadTouched = true
        val frame = (second * C.SAMPLE_RATE).toInt().coerceAtLeast(0)
        playheadFrame = frame
        markPlayAnchor(frame)
        engine.seek(frame)
    }

    fun setLoopEnabled(v: Boolean) { loop = v; engine.setLoop(v) }

    /** 预览：有选中片段就试听该片段，否则试听整条工程；播到区间末尾自动停 */
    var previewing by mutableStateOf(false)
        private set

    fun previewSelected() {
        if (previewing) { engine.stop(); previewing = false; playing = false; return }
        val clip = findSelectedClip()
        val start: Int
        val end: Int
        if (clip != null) {
            start = clip.timelineStart
            end = clip.endFrame
        } else {
            start = 0
            end = totalFrames()
        }
        if (end <= start) { showToast("没有可预览的内容，先导入或录音"); return }
        engine.start(start, false, end)
        playing = true
        markPlayAnchor(start)
        previewing = true
    }

    /** EQ 界面里的试听开关：循环播整条工程，方便边听边调 */
    fun eqAudition() {
        if (playing) { engine.stop(); playing = false } else {
            if (totalFrames() <= 0) { showToast("还没有音频可试听"); return }
            engine.start(0, true)
            playing = true
        }
    }

    // ---------------- 轨道 / 片段 ----------------
    fun addTrack() {
        if (Project.addTrack() == null) showToast("最多 ${C.MAX_TRACKS} 轨")
    }

    fun selectClip(clip: Clip) {
        selectedClipId = clip.id
        val t = Project.findTrackOf(clip)
        if (t != null) focusedTrackIndex = Project.tracks.indexOf(t)
    }

    // ===== 时间选区 / 剪贴板（长按白线拖出选区；拖完白线后出现复制剪切粘贴） =====
    var selStartFrame by mutableStateOf(-1)
    var selEndFrame by mutableStateOf(-1)
    /** 拖动白线或划出选区后，底部剪辑按钮自动显现 */
    var playheadTouched by mutableStateOf(false)

    val hasSelection: Boolean
        get() = selStartFrame >= 0 && selEndFrame >= 0 && selStartFrame != selEndFrame

    fun beginSelection(frame: Int) {
        selStartFrame = frame.coerceAtLeast(0)
        selEndFrame = selStartFrame
        playheadTouched = true
    }

    fun updateSelection(frame: Int) {
        if (selStartFrame < 0) return
        selEndFrame = frame.coerceAtLeast(0)
    }

    fun clearSelection() {
        selStartFrame = -1
        selEndFrame = -1
    }

    /** AU「选择 → 全部」 */
    fun selectAll() {
        val end = Project.projectLength()
        if (end <= 0) return
        selStartFrame = 0
        selEndFrame = end
        playheadTouched = true
    }

    /** AU「选择 → 在时间轴中」：把当前片段的范围变成选区（降噪捕捉样本常用） */
    fun selectCurrentClipRange() {
        val c = findSelectedClip() ?: focusedClip() ?: return
        selStartFrame = c.timelineStart
        selEndFrame = c.endFrame
        playheadTouched = true
    }

    /** AU「选择 → 从播放头到结束」 */
    fun selectFromPlayheadToEnd() {
        val end = Project.projectLength()
        if (end <= playheadFrame) return
        selStartFrame = playheadFrame
        selEndFrame = end
        playheadTouched = true
    }

    fun selectionLo(): Int = minOf(selStartFrame, selEndFrame)
    fun selectionHi(): Int = maxOf(selStartFrame, selEndFrame)

    fun focusedClip(): Clip? {
        if (selectedClipId.isNotEmpty()) {
            for (t in Project.tracks) for (c in t.clips) if (c.id == selectedClipId) return c
        }
        return Project.tracks.firstOrNull()?.clips?.firstOrNull()
    }

    var clipboard by mutableStateOf<List<Project.ClipSeg>>(emptyList())
        private set
    var clipboardLabel by mutableStateOf("")

    /** 复制：有选区复制选区，无选区复制聚焦片段 */
    fun copySelection(): Boolean {
        if (focusMix) {
            val start = if (hasSelection) selectionLo() else 0
            val end = if (hasSelection) selectionHi() else Project.projectLength()
            if (end <= start) return false
            val buf = Project.renderMixRange(start, end) ?: return false
            val id = "mix_${System.nanoTime()}"
            com.multitrack.app.audio.AudioBufferStore.put(id, buf)
            clipboard = listOf(Project.ClipSeg(id, 0, buf.frames, "混合", "#E8C97A", 1f, 0f))
            clipboardLabel = if (hasSelection) "混合选区 ${(end - start) / 44100f}s" else "混合总线"
            return true
        }
        val track = focusedTrack() ?: return false
        val segs = if (hasSelection) {
            Project.copyRange(track, selectionLo(), selectionHi())
        } else {
            val clip = focusedClip() ?: return false
            Project.copyRange(track, clip.timelineStart, clip.endFrame)
        }
        if (segs.isEmpty()) return false
        clipboard = segs
        clipboardLabel = if (hasSelection) "选区 ${(selectionHi() - selectionLo()) / 44100f}s" else "片段"
        return true
    }

    /** 剪切：复制后删除；无选区时删除整个聚焦片段 */
    fun cutSelection(): Boolean {
        if (focusMix) { showToast("混合总线只读，不能剪切"); return false }
        val track = focusedTrack() ?: return false
        if (hasSelection) {
            if (!copySelection()) return false
            Project.deleteRange(track, selectionLo(), selectionHi())
        } else {
            val clip = focusedClip() ?: return false
            if (!Project.copyRange(track, clip.timelineStart, clip.endFrame).let { segs ->
                    clipboard = segs
                    segs.isNotEmpty()
                }) return false
            clipboardLabel = "片段"
            Project.removeClip(track, clip)
            selectedClipId = ""
        }
        return true
    }

    /** 粘贴到当前白线位置（聚焦轨） */
    fun pasteClipboard(): Boolean {
        if (focusMix) { showToast("混合总线只读，不能粘贴"); return false }
        val track = focusedTrack() ?: return false
        if (clipboard.isEmpty()) return false
        Project.pasteAt(track, playheadFrame, clipboard)
        return true
    }
    fun moveClip(clip: Clip, newStart: Int) = Project.moveClip(clip, newStart)

    fun onArm(track: Track) { track.armed = !track.armed; Project.notifyChanged() }

    fun findSelectedClip(): Clip? {
        for (t in Project.tracks) for (c in t.clips) if (c.id == selectedClipId) return c
        return null
    }

    // ---------------- 聚焦轨（单击轨道头选中，导入落到该轨） ----------------
    var focusedTrackIndex by mutableStateOf(0)
        private set

    var settingsOpen by mutableStateOf(false)
        private set
    var exportOpen by mutableStateOf(false)
        private set

    fun toggleSettings(open: Boolean = !settingsOpen) { settingsOpen = open }
    fun toggleExport(open: Boolean = !exportOpen) { exportOpen = open }

    fun moveTrackBy(track: Track, delta: Int) {
        val idx = Project.tracks.indexOf(track)
        val to = (idx + delta).coerceIn(0, Project.tracks.size - 1)
        if (idx >= 0 && to != idx) {
            Project.moveTrack(idx, to)
            focusedTrackIndex = to
        }
    }

    fun focusTrackByIndex(index: Int) {
        focusMix = false
        focusedTrackIndex = index.coerceIn(0, (Project.tracks.size - 1).coerceAtLeast(0))
    }

    fun focusTrack(track: Track) {
        val idx = Project.tracks.indexOf(track)
        if (idx >= 0) {
            focusMix = false
            focusedTrackIndex = idx
            selectedClipId = ""
        }
    }

    /** 混合总线聚焦（只读）：可框选/复制混合音频，不可剪切或粘贴 */
    var focusMix by mutableStateOf(false)
        private set

    fun focusMixBus() {
        focusMix = true
        selectedClipId = ""
    }

    fun focusedTrack(): Track? {
        val list = Project.tracks
        if (list.isEmpty()) return null
        return list[focusedTrackIndex.coerceIn(0, list.size - 1)]
    }

    fun focusedTrackName(): String {
        if (focusMix) return "混合（只读）"
        focusedTrack()?.let { t ->
            val n = Project.tracks.indexOf(t) + 1
            return "音轨 $n"
        }
        return "无音轨"
    }

    fun getSelectedTrack(): Track? {
        findSelectedClip()?.let { return Project.findTrackOf(it) }
        return focusedTrack() ?: Project.tracks.firstOrNull()
    }

    fun splitSelected() {
        val clip = findSelectedClip() ?: run { showToast("请先选中一个片段"); return }
        val track = Project.findTrackOf(clip) ?: return
        if (playheadFrame <= clip.timelineStart || playheadFrame >= clip.endFrame) {
            showToast("播放头需位于片段内部")
            return
        }
        Project.splitClipAt(track, clip, playheadFrame)
    }

    fun deleteSelected() {
        val clip = findSelectedClip() ?: run { showToast("请先选中一个片段"); return }
        val track = Project.findTrackOf(clip) ?: return
        Project.removeClip(track, clip)
        selectedClipId = ""
    }

    // ---------------- 导入 ----------------
    /** 正在导入（大文件解码要几秒，界面用这个状态提示，避免卡死感） */
    var importing by mutableStateOf(false)
    var importProgress by mutableStateOf(0f)
    // 正在被拖动换轨的片段（用于把该轨整体抬到最上层，避免向下拖被下面的轨道盖住）
    var draggingClipId by mutableStateOf("")
    /** 片段拖动中的视觉位移（px），由波形层与浮层共用，避免重复绘制产生残影 */
    var dragDx by mutableStateOf(0f)
    var dragDy by mutableStateOf(0f)
    /**
     * 手指已经真正拉开（超过 touchSlop）并进入拖动。
     *
     * 按下就写 [draggingClipId] 是为了第一时间锁住外层滚动，但那时还没动，
     * 不能因此把原片段藏起来、换成浮层 —— 否则轻点一下片段会「闪一下」。
     * 所以「隐藏原片段 + 画浮层」只认这个标记。
     */
    var dragActive by mutableStateOf(false)
    // 延迟与回声 / 变调 的可调参数（用户在效果窗口里自己拧）
    var delayMs by mutableStateOf(140f)
    var delayFeedback by mutableStateOf(0.3f)
    var delayMix by mutableStateOf(0.35f)
    var pitchSemitones by mutableStateOf(2f)
    // 单个效果的参数窗口（AU 风格：先弹窗调参数，点「应用」才改动音频）
    var fxDialog by mutableStateOf<String?>(null)
    val fxParams = androidx.compose.runtime.mutableStateMapOf<String, Float>()

    // ---- 降噪：噪声样本 + 强度（AU「降噪/恢复」那一套）----
    /** 当前选中的噪声样本；为 null 时降噪走「自适应」（自动从最低能量帧估） */
    var noiseProfile by mutableStateOf<NoiseProfile?>(null)
        private set
    /** 噪声样本库（落盘，重启还在） */
    var noiseProfiles by mutableStateOf<List<NoiseProfile>>(emptyList())
        private set
    /** 当前样本的来源说明，显示在降噪窗口里 */
    var noiseSourceLabel by mutableStateOf("")
        private set

    /** AI 降噪当前档位（人声 / 伴奏），窗口里用胶囊切换 */
    var aiModel by mutableStateOf(AiDenoise.Model.VOCAL)
        private set

    /** 切档：同时把档位写进 fxParams，让滑杆与窗口重启后仍是同一档 */
    fun chooseAiModel(m: AiDenoise.Model) {
        aiModel = m
        fxParams["aiModel"] = if (m == AiDenoise.Model.MUSIC) 1f else 0f
    }

    /** 后台处理中（降噪要跑几万帧 FFT，不能挂在主线程） */
    var fxBusy by mutableStateOf(false)
        private set
    var fxBusyLabel by mutableStateOf("")
        private set

    fun fxParam(key: String, def: Float): Float = fxParams[key] ?: def

    fun openFx(kind: String) {
        fxDialog = kind
        // 打开时把该效果的默认值写进参数表（用户上次调过的值保留）
        when (kind) {
            "graphicEq" -> for (i in 0 until 10) fxParams.putIfAbsent("g$i", 0f)
            "limiter" -> {
                fxParams.putIfAbsent("ceiling", -3f)
                fxParams.putIfAbsent("inputBoost", 0f)
                fxParams.putIfAbsent("lookahead", 5f)
                fxParams.putIfAbsent("limitRelease", 80f)
            }
            "compressor" -> {
                fxParams.putIfAbsent("compThreshold", -18f)
                fxParams.putIfAbsent("compRatio", 4f)
                fxParams.putIfAbsent("compAttack", 5f)
                fxParams.putIfAbsent("compRelease", 120f)
                fxParams.putIfAbsent("compOut", 0f)
            }
            "chorus" -> {
                fxParams.putIfAbsent("chRate", 0.8f)
                fxParams.putIfAbsent("chDepth", 3f)
                fxParams.putIfAbsent("chMix", 0.35f)
            }
            "tremolo" -> {
                fxParams.putIfAbsent("trRate", 4f)
                fxParams.putIfAbsent("trDepth", 0.4f)
            }
            "deesser" -> fxParams.putIfAbsent("deesserThr", -26f)
            "denoise" -> {
                fxParams.putIfAbsent("dnStrength", 60f)
                fxParams.putIfAbsent("dnBand", 120f)
                fxParams.putIfAbsent("dnFloor", -24f)
            }
            // AI 降噪：两档（人声 / 伴奏），参数少但每个都直接影响听感
            "aiDenoise" -> {
                fxParams.putIfAbsent("aiStrength", 65f)
                fxParams.putIfAbsent("aiFloor", -20f)
                fxParams.putIfAbsent("aiDetail", 55f)
                fxParams.putIfAbsent("aiModel", if (aiModel == AiDenoise.Model.MUSIC) 1f else 0f)
            }
            "dehum" -> {
                fxParams.putIfAbsent("dehumBase", 0f)      // 0=自动，50/60=强制
                fxParams.putIfAbsent("dehumOrder", 8f)
            }
            "declick" -> fxParams.putIfAbsent("declickSens", 0.5f)
            "deess" -> fxParams.putIfAbsent("deessAmount", 12f)
            "dereverb" -> fxParams.putIfAbsent("rvReduce", 0.5f)
            "reverb" -> {
                fxParams.putIfAbsent("rvSize", 0.5f)
                fxParams.putIfAbsent("rvWet", 0.25f)
            }
            "widen" -> fxParams.putIfAbsent("widenAmount", 1.4f)
            "distort" -> {
                fxParams.putIfAbsent("dstDrive", 2.2f)
                fxParams.putIfAbsent("dstMix", 0.8f)
            }
            "stretch" -> fxParams.putIfAbsent("stretchRatio", 0.8f)
            "pitchApply" -> fxParams.putIfAbsent("pitchSemi", pitchSemitones)
            "delay" -> {
                fxParams.putIfAbsent("delayMs", delayMs)
                fxParams.putIfAbsent("delayFb", delayFeedback)
                fxParams.putIfAbsent("delayMix", delayMix)
            }
        }
    }

    fun resetFxParams() {
        val kind = fxDialog ?: return
        fxParams.clear()
        openFx(kind)
    }

    fun closeFx() { fxDialog = null }

    /** Pro-Q 窗口尺寸（dp），0 表示自动 */
    var eqWinW by mutableStateOf(0f)
    var eqWinH by mutableStateOf(0f)

    /** 点「应用」才真正处理音频；降到后台线程，避免几万帧 FFT 把界面卡死 */
    fun commitFxDialog() {
        val kind = fxDialog ?: return
        fxDialog = null
        applyFxAsync(kind)
    }

    fun applyFxAsync(kind: String) {
        if (fxBusy) { showToast("正在处理「$fxBusyLabel」，请稍候"); return }
        fxBusy = true
        fxBusyLabel = specOf(kind).title.substringBefore(" ·").substringBefore("（")
        ioScope.launch {
            val msg = try {
                applyFx(kind)
            } catch (t: Throwable) {
                "处理失败：${t.message ?: t.javaClass.simpleName}"
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                fxBusy = false
                showToast(msg)
            }
        }
    }

    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    /** 处理 SAF 选中的音频 Uri：解码/重采样/算峰值全部放到 IO 线程，完成后回主线程挂到轨道上 */
    fun importUri(uri: Uri) {
        val name = queryName(uri) ?: "音频"
        if (importing) return
        importing = true
        importProgress = 0f
        showToast("正在解码：$name")
        ioScope.launch {
            val t0 = System.currentTimeMillis()
            val size = querySize(uri)
            val res = try {
                ImportAudio.import(appContext, uri, name, size) { p ->
                    importProgress = p
                }
            } catch (e: Exception) {
                null
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                importing = false
                importProgress = 1f
                if (res == null) {
                    showToast("无法解码该音频文件")
                    return@withContext
                }
                val id = genId("buf")
                AudioBufferStore.put(id, res.pcm)
                val track = getSelectedTrack() ?: Project.addTrack()
                if (track != null) {
                    Project.addClip(track, id, res.displayName, res.pcm.frames, playheadFrame, track.color)
                    val sec = (System.currentTimeMillis() - t0) / 1000.0
                    showToast("已导入：$name（%.1f 秒）".format(sec))
                }
            }
        }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 录音（边听边录）----------------
    fun toggleRecord() {
        if (recording) {
            val res = recorder.stop()
            engine.stop()
            recording = false
            playing = false
            if (res != null && recTrack != null) {
                Project.addClip(recTrack!!, res.bufferId, "录音", res.frames, res.startFrame, recTrack!!.color)
                showToast("录音已加入轨道")
            }
            return
        }
        val track = Project.tracks.firstOrNull { it.armed } ?: getSelectedTrack() ?: Project.addTrack()
        if (track == null) return
        recTrack = track
        engine.setLoop(loop)
        engine.start(playheadFrame, loop)
        playing = true
        markPlayAnchor(playheadFrame)
        if (!recorder.start(playheadFrame)) {
            engine.stop()
            playing = false
            showToast("录音启动失败（检查麦克风权限）")
            return
        }
        recording = true
    }

    // ---------------- 导出 ----------------

    /**
     * 混音的真峰值（dBFS）。导出前报一个数：**如果超过 0dBFS，16bit 出口必然削波** ——
     * 与其让用户把文件拿到 AU 里才发现"怎么超载了"，不如这里直接说。
     */
    private fun mixPeakDb(mix: AudioEngine.MixResult): Float {
        var p = 0f
        for (v in mix.left) { val a = kotlin.math.abs(v); if (a > p) p = a }
        for (v in mix.right) { val a = kotlin.math.abs(v); if (a > p) p = a }
        return if (p <= 1e-6f) -120f else (20.0 * kotlin.math.log10(p.toDouble())).toFloat()
    }

    private fun peakNote(mix: AudioEngine.MixResult): String {
        val pk = mixPeakDb(mix)
        return if (pk > 0f) " · 峰值 ${"%+.1f".format(pk)}dBFS，已削波"
        else " · 峰值 ${"%.1f".format(pk)}dBFS"
    }

    fun exportMix(): String? {
        val mix = engine.renderMix() ?: run { showToast("没有可导出的内容"); return null }
        val wav = WavCodec.encodeWav(mix.left, mix.right, C.SAMPLE_RATE)
        return try {
            val dir = File(appContext.getExternalFilesDir(null), "export").apply { mkdirs() }
            val f = File(dir, "mix_${System.currentTimeMillis()}.wav")
            f.writeBytes(wav)
            "已导出：${f.absolutePath}${peakNote(mix)}"
        } catch (e: Exception) {
            null
        }
    }

    /** 导出混音到用户选择的 Uri；format = wav / m4a / flac */
    fun exportTo(uri: Uri, format: String) {
        ioScope.launch {
            val mix = engine.renderMix()
            if (mix == null) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { showToast("没有可导出的内容") }
                return@launch
            }
            val bytes = try {
                ExportAudio.encode(appContext, mix.left, mix.right, C.SAMPLE_RATE, format)
            } catch (e: Exception) {
                null
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                exportOpen = false
                if (bytes == null) {
                    showToast("导出失败：该格式不被设备支持")
                    return@withContext
                }
                try {
                    appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    showToast(
                        "已导出：${format.uppercase()}（${"%.1f".format(bytes.size / 1024.0 / 1024.0)} MB）" +
                            peakNote(mix)
                    )
                } catch (e: Exception) {
                    showToast("写入失败：${e.message}")
                }
            }
        }
    }

    // ---------------- 效果 ----------------
    fun applyEffect(kind: String) {
        val clip = findSelectedClip() ?: run { showToast("请先选中一个片段"); return }
        val buf = AudioBufferStore.get(clip.bufferId) ?: return
        val half = (0.5f * C.SAMPLE_RATE).toInt()
        when (kind) {
            "fadein" -> clip.fadeIn = min(clip.length, half)
            "fadeout" -> clip.fadeOut = min(clip.length, half)
            "gainup" -> clip.gain *= 1.25f
            "gaindown" -> clip.gain *= 0.8f
            "normalize" -> normalize(buf.left, buf.right)
            "reverse" -> reverse(buf.left, buf.right)
            "pitchup" -> {
                val r = pitchShift(buf.left, buf.right, 2f)
                buf.left = r.left; buf.right = r.right
                clip.length = r.left.size; clip.fadeIn = 0; clip.fadeOut = 0
            }
            "pitchdown" -> {
                val r = pitchShift(buf.left, buf.right, -2f)
                buf.left = r.left; buf.right = r.right
                clip.length = r.left.size; clip.fadeIn = 0; clip.fadeOut = 0
            }
            "eq" -> { eqOpen = true; return }
            "reverb" -> applyReverb(buf.left, buf.right, C.SAMPLE_RATE, ReverbSettings(0.5f, 0.25f))
            "denoise" -> applyDenoise(buf.left, buf.right, C.SAMPLE_RATE, DenoiseSettings(80f, -50f))
            else -> return
        }
        Project.recomputePeaks(clip)
        Project.notifyChanged()
        showToast("已应用效果")
    }

    // ---------------- 噪声样本 ----------------

    fun refreshNoiseProfiles() {
        noiseProfiles = NoiseProfileStore.list(appContext)
        if (noiseProfile == null) noiseProfile = noiseProfiles.firstOrNull()?.also {
            noiseSourceLabel = if (it.source.isNotEmpty()) it.source else "已保存"
        }
    }

    /** 选中音轨在 [lo,hi) 上的样本（只叠加片段增益，噪声统计不需要淡变/声像） */
    private fun collectRange(track: Track, lo: Int, hi: Int): Pair<FloatArray, FloatArray>? {
        val len = hi - lo
        if (len <= 0) return null
        val l = FloatArray(len)
        val r = FloatArray(len)
        var any = false
        for (c in track.clips) {
            val s = max(lo, c.timelineStart)
            val e = min(hi, c.endFrame)
            if (e <= s) continue
            val buf = AudioBufferStore.get(c.bufferId) ?: continue
            any = true
            for (g in s until e) {
                val rel = g - c.timelineStart
                if (rel < 0 || rel >= buf.frames) continue
                l[g - lo] += buf.left[rel] * c.gain
                r[g - lo] += buf.right[rel] * c.gain
            }
        }
        return if (any) l to r else null
    }

    /**
     * 「捕捉噪声样本」：优先用时间轴选区，没选区就用选中片段（或聚焦片段）。
     * 与 AU 一致 —— 先选一段只有噪声的地方，再捕捉，之后整段降噪都用这张谱。
     */
    fun captureNoiseFromSelection(): String {
        if (focusMix) return "混合总线没有原始波形，请先点一下具体音轨"
        val track = getSelectedTrack() ?: return "没有可用的音轨"
        val clip = findSelectedClip() ?: focusedClip()
        val lo: Int
        val hi: Int
        if (hasSelection) {
            lo = selectionLo()
            hi = selectionHi()
        } else if (clip != null) {
            // 整首当样本会把音乐也算进噪声谱，降噪就会"吃"掉人声
            if (clip.length > C.SAMPLE_RATE * 10) {
                return "片段有 ${clip.length / C.SAMPLE_RATE} 秒，太长；请先拖出一段纯噪声的选区再捕捉"
            }
            lo = clip.timelineStart
            hi = clip.endFrame
        } else return "请先拖出一段纯噪声的选区"
        val (l, r) = collectRange(track, lo, hi) ?: return "这段区间里没有音频"
        if (hi - lo < C.SAMPLE_RATE / 7) return "样本太短（至少 0.15 秒），请选长一点"
        val seconds = (hi - lo) / C.SAMPLE_RATE.toFloat()
        val name = if (clip != null && !hasSelection) "片段·${clip.name}" else "选区·%.2fs".format(seconds)
        val p = SpectralDenoise.profileFrom(l, r, C.SAMPLE_RATE, name.take(24), "选区 %.2fs".format(seconds))
            ?: return "这段几乎全是数字静音，做不出噪声谱"
        noiseProfile = p
        noiseSourceLabel = "选区 %.2fs · %s".format(seconds, focusedTrackName())
        noiseProfiles = NoiseProfileStore.save(appContext, p)
        return "已捕捉噪声样本：${p.name}"
    }

    /** 从文件导入噪声样本（wav/mp3/m4a…），解码后取整段做平均谱 */
    fun importNoiseProfile(uri: Uri) {
        if (fxBusy) { showToast("正在处理「$fxBusyLabel」，请稍候"); return }
        val rawName = queryName(uri) ?: "噪声样本"
        showToast("正在读取噪声样本：$rawName")
        fxBusy = true
        fxBusyLabel = "导入噪声样本"
        ioScope.launch {
            val res = try {
                ImportAudio.import(appContext, uri, rawName)
            } catch (e: Exception) {
                null
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                fxBusy = false
                if (res == null) { showToast("这个文件读不出音频"); return@withContext }
                val buf = res.pcm
                val p = SpectralDenoise.profileFrom(
                    buf.left, buf.right, C.SAMPLE_RATE,
                    rawName.substringBeforeLast('.').take(24), "导入：$rawName"
                )
                if (p == null) { showToast("文件太短或几乎无声，做不出噪声谱"); return@withContext }
                noiseProfile = p
                noiseSourceLabel = "导入：$rawName"
                noiseProfiles = NoiseProfileStore.save(appContext, p)
                showToast("已导入噪声样本：${p.name}")
            }
        }
    }

    fun selectNoiseProfile(name: String) {
        val p = noiseProfiles.firstOrNull { it.name == name } ?: return
        noiseProfile = p
        noiseSourceLabel = if (p.source.isNotEmpty()) p.source else "样本库"
    }

    fun useAdaptiveNoise() {
        noiseProfile = null
        noiseSourceLabel = "自适应（从片段里自动估计）"
    }

    fun deleteNoiseProfile(name: String) {
        noiseProfiles = NoiseProfileStore.remove(appContext, name)
        if (noiseProfile?.name == name) {
            noiseProfile = null
            noiseSourceLabel = ""
        }
    }

    fun openEq() { eqOpen = true }
    fun closeEq() { eqOpen = false }

    // ---------------- 效果（「效果」菜单调用，直接作用到选中片段） ----------------
    /** 应用到选中片段；返回给界面的提示文字 */
    fun applyFx(kind: String): String {
        val clip = findSelectedClip() ?: return "请先在时间轴上选中一个片段"
        val buf = AudioBufferStore.get(clip.bufferId) ?: return "片段音频数据丢失"
        val sr = C.SAMPLE_RATE
        when (kind) {
            "limiter" -> Fx.limiter(
                buf.left, buf.right, sr,
                ceilingDb = fxParam("ceiling", -3f),
                inputBoostDb = fxParam("inputBoost", 0f),
                lookaheadMs = fxParam("lookahead", 5f),
                releaseMs = fxParam("limitRelease", 80f)
            )
            "compressor" -> Fx.compressor(
                buf.left, buf.right, sr,
                thresholdDb = fxParam("compThreshold", -18f),
                ratio = fxParam("compRatio", 4f),
                attackMs = fxParam("compAttack", 5f),
                releaseMs = fxParam("compRelease", 120f),
                outputGainDb = fxParam("compOut", 0f)
            )
            "delay" -> Fx.delay(
                buf.left, buf.right, sr,
                fxParam("delayMs", delayMs),
                fxParam("delayFb", delayFeedback),
                fxParam("delayMix", delayMix)
            )
            "graphicEq" -> {
                val gains = FloatArray(10) { i -> fxParam("g$i", 0f) }
                Fx.graphicEq(buf.left, buf.right, sr, gains)
            }
            "chorus" -> Fx.chorus(
                buf.left, buf.right, sr,
                fxParam("chRate", 0.8f), fxParam("chDepth", 3f), fxParam("chMix", 0.35f)
            )
            "tremolo" -> Fx.tremolo(
                buf.left, buf.right, sr,
                fxParam("trRate", 4f), fxParam("trDepth", 0.4f)
            )
            "deesser" -> Fx.deEsser(buf.left, buf.right, sr, fxParam("deesserThr", -26f))
            "denoise" -> {
                val cfg = DenoiseConfig(
                    strength = fxParam("dnStrength", 60f),
                    bandWidthHz = fxParam("dnBand", 120f),
                    floorDb = fxParam("dnFloor", -24f)
                )
                // 没有样本就现场估：整段里能量最低的那批帧当底噪（AU「自适应降噪」）
                val profile = noiseProfile
                    ?: SpectralDenoise.adaptiveProfile(buf.left, buf.right, sr)
                    ?: return "片段太短，无法估计噪声；请先选中一段纯噪声再捕捉样本"
                SpectralDenoise.apply(buf.left, buf.right, sr, profile, cfg)
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return "已降噪：${profile.name} · 强度 ${cfg.strength.roundToInt()}%（约 ${cfg.reductionDb.roundToInt()}dB）"
            }
            "aiDenoise" -> {
                // 档位存在 state 里（胶囊切换），强度/底噪/细节走滑条
                val model = if (fxParam("aiModel", 0f) >= 0.5f) AiDenoise.Model.MUSIC else AiDenoise.Model.VOCAL
                val cfg = AiDenoise.Config(
                    strength = fxParam("aiStrength", 65f),
                    floorDb = fxParam("aiFloor", -20f),
                    detail = fxParam("aiDetail", 55f)
                )
                val t0 = System.currentTimeMillis()
                // 人声档优先走真权重（DeepFilterNet3 的三个 ONNX 图，见 audio/dfn3/）。
                // 模型没打进包 / 加载失败时，安静地退回统计掩码 —— 功能不因此消失，
                // 只是效果差一档，返回文案里会写清楚走的是哪条路。
                if (model == AiDenoise.Model.VOCAL && Dfn3Assets.prepare()) {
                    val r = Dfn3.processInPlace(buf.left, buf.right, sr) { p ->
                        fxBusyLabel = "AI 降噪 · 人声 ${(p * 100).roundToInt()}%"
                    }
                    val ms = System.currentTimeMillis() - t0
                    Project.recomputePeaks(clip)
                    Project.notifyChanged()
                    if (r.frames == 0) return "这段音频太短，DeepFilterNet3 至少要 30ms"
                    return "AI 降噪 · 人声（DeepFilterNet3 真权重）：平均压低 " +
                        "${(-r.averageDropDb).roundToInt()}dB · ${r.frames} 帧 · ${ms}ms"
                }
                // 伴奏档（以及人声档的兜底）走统计掩码
                val profile = noiseProfile
                val r = AiDenoise.process(buf.left, buf.right, sr, model, cfg, profile)
                val ms = System.currentTimeMillis() - t0
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                val src = if (profile != null) "样本「${profile.name}」" else "自动估噪声底"
                val why = if (model == AiDenoise.Model.VOCAL) " · 模型未就绪，走统计掩码" else ""
                return "AI 降噪 · ${model.label}（$src$why）：平均压低 ${(-r.averageDropDb).roundToInt()}dB · " +
                    "${r.frames} 帧 · ${ms}ms"
            }
            "dehum" -> {
                val forced = fxParam("dehumBase", 0f)
                val base = if (forced == 50f || forced == 60f) forced
                else Restore.detectHumBase(buf.left, buf.right, sr)
                Restore.dehum(buf.left, buf.right, sr, base, fxParam("dehumOrder", 8f).toInt())
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return "已消除 ${base.roundToInt()}Hz 工频嗡声（${fxParam("dehumOrder", 8f).toInt()} 次谐波）"
            }
            "declick" -> {
                val n = Restore.declick(buf.left, buf.right, sr, fxParam("declickSens", 0.5f))
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return if (n == 0) "没有检测到咔嗒声" else "已修复 $n 处咔嗒/爆音"
            }
            "deess" -> {
                Restore.deSibilance(buf.left, buf.right, sr, fxParam("deessAmount", 12f))
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return "已降低嘶声：${fxParam("deessAmount", 12f).roundToInt()}dB"
            }
            "dereverb" -> {
                Restore.deReverb(buf.left, buf.right, sr, fxParam("rvReduce", 0.5f))
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return "已减少混响：${(fxParam("rvReduce", 0.5f) * 100).roundToInt()}%"
            }
            "normalize" -> {
                normalize(buf.left, buf.right)
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return "已归一化到满刻度"
            }
            "reverse" -> {
                reverse(buf.left, buf.right)
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return "已反转"
            }
            "unsupported" -> return "本版本暂不支持的 AU 效果"
            "reverb" -> applyReverb(
                buf.left, buf.right, sr,
                ReverbSettings(fxParam("rvSize", 0.5f), fxParam("rvWet", 0.25f))
            )
            "widen" -> Fx.stereoWiden(buf.left, buf.right, fxParam("widenAmount", 1.4f))
            "distort" -> Fx.distort(
                buf.left, buf.right,
                fxParam("dstDrive", 2.2f), fxParam("dstMix", 0.8f)
            )
            "vocal" -> Fx.vocalEnhance(buf.left, buf.right, sr)
            "pitchApply" -> {
                val r = pitchShift(buf.left, buf.right, fxParam("pitchSemi", pitchSemitones))
                buf.left = r.left; buf.right = r.right
                clip.length = r.left.size
                clip.fadeIn = 0; clip.fadeOut = 0
            }
            "stretch" -> {
                val r = Fx.stretch(buf.left, buf.right, fxParam("stretchRatio", 0.8f))
                buf.left = r.first; buf.right = r.second
                clip.length = r.first.size
                clip.fadeIn = 0; clip.fadeOut = 0
            }
            "silenceDetect" -> {
                val spans = Fx.detectSilence(buf.left, buf.right, sr)
                val total = spans.sumOf { it.end - it.start } / sr.toFloat()
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return "检测到 ${spans.size} 段静音，共 ${String.format("%.2f", total)} 秒"
            }
            "removeSilence" -> {
                val spans = Fx.detectSilence(buf.left, buf.right, sr)
                if (spans.isEmpty()) return "没有检测到可删除的静音段"
                val r = Fx.removeSilence(buf.left, buf.right, sr, spans)
                buf.left = r.first.first; buf.right = r.first.second
                clip.length = r.second
                clip.fadeIn = 0; clip.fadeOut = 0
                val cut = (spans.sumOf { it.end - it.start }) / sr.toFloat()
                Project.recomputePeaks(clip)
                Project.notifyChanged()
                return "删除 ${spans.size} 段静音，缩短 ${String.format("%.2f", cut)} 秒"
            }
            else -> return "未知效果"
        }
        Project.recomputePeaks(clip)
        Project.notifyChanged()
        return "已应用效果"
    }

    /** 缩放后待应用的滚动位置（秒），由界面在下一帧消费 */
    var pendingScrollSec by mutableStateOf<Float?>(null)
        private set

    fun requestScrollTo(sec: Float) { pendingScrollSec = sec }
    fun consumeScrollRequest(): Float? { val v = pendingScrollSec; pendingScrollSec = null; return v }

    /** 双指缩放：factor>1 放大（时间窗变小），锚定 centerSec */
    private fun safeSpan(v: Float): Float {
        val lo = minViewSpan.coerceIn(MIN_SPAN, MAX_SPAN)
        val x = if (v.isFinite()) v else secondsPerScreen
        return x.coerceIn(lo, MAX_SPAN)
    }

    fun pinchZoom(factor: Float, centerSec: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        spanLock = null
        val span = safeSpan(secondsPerScreen / factor)
        setViewport(centerSec - span / 2f, span)
    }

    /** 同时设置可视窗口：起点秒 + 跨度秒；滚动位置延后一帧应用，避免被旧的 maxValue 截断 */
    fun setViewport(startSec: Float, spanSec: Float) {
        spanLock = null
        secondsPerScreen = safeSpan(spanSec)
        pendingScrollSec = if (startSec.isFinite()) startSec.coerceAtLeast(0f) else 0f
    }

    companion object {
        const val MIN_SPAN = 0.15f
        const val MAX_SPAN = 600f
        /** 时间轴内容最大像素宽度：超过这个值再放大就会 OOM/测量溢出，直接限制放大倍率 */
        const val MAX_TIMELINE_PX = 60000f
    }

    /** 由界面按工程长度/可视宽度算出的最小跨度（防止放大到内容宽度爆掉闪退） */
    var minViewSpan by mutableStateOf(MIN_SPAN)
        private set

    fun updateViewportLimits(totalSeconds: Float, laneWidthPx: Float) {
        // 预留至少 90 秒尾部空白，保证曲尾之后始终能继续摆放素材
        val needSec = totalSeconds.coerceAtLeast(1f) + 120f
        val safe = (needSec * laneWidthPx / MAX_TIMELINE_PX)
        if (safe.isFinite() && safe > 0f) {
            minViewSpan = safe.coerceIn(MIN_SPAN, MAX_SPAN)
            // 工程变长时只保证内容不超出像素上限，不把用户视野突然拉远
        }
    }

    fun zoomIn() {
        spanLock = null
        secondsPerScreen = max(minViewSpan, secondsPerScreen * 0.6f)
        Project.notifyChanged()
    }

    fun zoomOut() {
        spanLock = null
        secondsPerScreen = min(MAX_SPAN, secondsPerScreen * 1.6f)
        Project.notifyChanged()
    }

    fun release() {
        engine.stop()
        recorder.stop()
    }
}
