package com.multitrack.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.multitrack.app.audio.C
import kotlin.math.roundToInt
import com.multitrack.app.model.Project
import com.multitrack.app.ui.theme.Palette
import com.multitrack.app.ui.theme.CyrilMusicTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * 编辑器主界面：仿 Adobe Audition 多轨视图。
 * - 顶部走带工具栏 / 底部效果栏
 * - 左侧固定轨道头（跟随垂直滚动）+ 顶部标尺（跟随水平滚动）
 * - 中央泳道二维滚动，播放头随内容滚动
 *
 * 滚动同步采用「单一滚动源 + offset 手动同步」：泳道区是唯一持有 ScrollState 的
 * 可滚动组件，标尺与轨道头通过读取 state.value 做位移，避免共享 state 的冲突。
 */
@Composable
fun EditorScreen(state: EditorState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 文件导入（SAF）----
    var exportFormat by remember { mutableStateOf("wav") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) state.exportTo(uri, exportFormat)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { state.importUri(it) } }

    // ---- 歌词 / 封面导入（SAF）----
    val lyricsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { state.importLyrics(it) } }
    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { state.importCover(it) } }

    // ---- 麦克风权限 ----
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) state.toggleRecord()
        else Toast.makeText(context, "需要麦克风权限才能录音", Toast.LENGTH_SHORT).show()
    }

    // ---- 播放头轮询（仿鸿蒙 setInterval 50ms）----
    LaunchedEffect(Unit) {
        while (true) {
            if (state.playing) state.pollPlayhead()
            delay(16)
        }
    }

    // ---- 噪声样本库（捕捉过的样本重启后还在）----
    LaunchedEffect(Unit) { state.refreshNoiseProfiles() }

    // ---- Toast ----
    LaunchedEffect(state.toast) {
        if (state.toast.isNotEmpty()) {
            Toast.makeText(context, state.toast, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose { state.release() }
    }

    CyrilMusicTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        val compact = maxWidth > maxHeight
        val laneHeight = if (compact) 58.dp else UiDimens.LANE_H
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            MenuBarView(
                activeMenu = state.openMenu,
                onMenu = { state.toggleMenu(it) },
                onSettings = { state.toggleSettings() ; state.closeMenu() },
                settingsActive = state.settingsOpen
            )
            TopToolbarView(
                state = state,
                compact = compact,
                onImport = {
                    importLauncher.launch(
                        arrayOf(
                            "audio/*", "audio/mpeg", "audio/flac", "audio/mp4",
                            "audio/aac", "audio/ogg", "audio/x-wav", "application/octet-stream"
                        )
                    )
                },
                onExport = {
                    state.closeMenu()
                    state.toggleExport(true)
                }
            )

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val density = LocalDensity.current.density
                val headerPx = with(LocalDensity.current) { UiDimens.HEADER_W.toPx() }
                val rulerPx = with(LocalDensity.current) { UiDimens.RULER_H.toPx() }
                val lanePx = with(LocalDensity.current) { laneHeight.toPx() }
                // 概览条/波形/滚动共用同一个「真实泳道宽度」：先按布局算，量到实际尺寸后以实测为准。
                // 之前两者各算各的，边缘一旦差几像素，白框右缘和下方音轨就对不齐。
                var laneMeasured by remember { mutableFloatStateOf(0f) }
                val laneWidthPx = (if (laneMeasured > 0f) laneMeasured else maxWidth.value * density - headerPx)
                    .coerceAtLeast(200f)

                val totalSeconds = state.totalSeconds()
                // 先按工程长度收紧放大上限，再算像素/秒，避免小跨度时内容宽度爆炸导致闪退
                state.updateViewportLimits(totalSeconds, laneWidthPx)
                val safeSpan = state.spanLock ?: max(state.secondsPerScreen, state.minViewSpan)
                val pxPerSecond = laneWidthPx / safeSpan
                val spp = C.SAMPLE_RATE / pxPerSecond
                // 末尾留一段可编辑的空白，方便把片段往歌曲结束之后再拖
                val budgetSec = if (pxPerSecond > 0f)
                    ((EditorState.MAX_TIMELINE_PX - totalSeconds * pxPerSecond) / pxPerSecond).coerceAtLeast(0f)
                else 0f
                val tailSec = minOf(maxOf(300f, state.secondsPerScreen * 4f), budgetSec)
                val timelinePx = max(laneWidthPx, (totalSeconds + tailSec) * pxPerSecond)
                    .coerceAtMost(EditorState.MAX_TIMELINE_PX + 64f)
                val playheadPx = (state.playheadFrame.toFloat() / C.SAMPLE_RATE) * pxPerSecond

                val geom = Geom(
                    headerPx, rulerPx, lanePx, laneWidthPx,
                    pxPerSecond, spp, timelinePx, playheadPx, density
                )

                // 读取 version：工程数据/峰值变化时触发重组与波形重绘
                val drawKey = Project.version

                val hState = rememberScrollState()
                val vState = rememberScrollState()

                // ===== 播放跟随：白线走动时视口自动跟，手动拖动可以强行接管 =====
                // 手动滚动（拖动/惯性）时暂停跟随，停手 2.6 秒后自动恢复
                var followPlayhead by remember { mutableStateOf(true) }
                var manualHoldUntil by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    snapshotFlow { hState.isScrollInProgress }.collect { scrolling ->
                        manualHoldUntil = SystemClock.uptimeMillis() +
                            if (scrolling) 3600L else 2600L
                    }
                }
                val ppsFollow by rememberUpdatedState(pxPerSecond)
                LaunchedEffect(state.playing, followPlayhead) {
                    if (!state.playing) return@LaunchedEffect
                    while (state.playing) {
                        delay(16)
                        if (!state.playing || !followPlayhead) break
                        if (SystemClock.uptimeMillis() < manualHoldUntil) continue
                        if (state.draggingClipId.isNotEmpty() || state.seeking) continue
                        val w = laneWidthPx
                        if (w <= 0f) continue
                        val headX = (state.playheadFrame.toFloat() / C.SAMPLE_RATE) * ppsFollow
                        val left = hState.value.toFloat()
                        val right = left + w
                        // 白线越过右侧 75% 就前跳，让唱到的位置落在屏幕 55% 处
                        if (headX > left + w * 0.75f) {
                            hState.scrollTo((headX - w * 0.55f).toInt().coerceIn(0, hState.maxValue))
                        } else if (headX < left + w * 0.12f) {
                            hState.scrollTo((headX - w * 0.12f).toInt().coerceIn(0, hState.maxValue))
                        }
                    }
                }

                // 手指在视口内的坐标一律由「内容坐标」反算，不依赖 onGloballyPositioned 的窗口坐标：
                // 视口内的 0 点在轨道头右侧，所以 lx = 片段左沿 + 已拖动量 + 抓取偏移 - 当前滚动量。
                // 这一口径 3.19~3.21 一直在用，贴左边缘能正常触发；此前只是拿它去比「整屏宽度」才错的。
                val laneWNow by rememberUpdatedState(laneWidthPx)
                val sppNow by rememberUpdatedState(spp)

                // 手指贴住视口边缘时持续滚动：用「视口局部坐标」判定，滚动量按实际消费补偿
                LaunchedEffect(state.draggingClipId) {
                    if (state.draggingClipId.isEmpty()) return@LaunchedEffect
                    val edgeH = 34f * density
                    val edgeV = 28f * density
                    val dirHoldMs = 90L
                    while (state.draggingClipId.isNotEmpty()) {
                        // 用 delay 而不是 withFrameNanos：后者在某些机器上拿不到帧回调时整段循环挂住
                        delay(16L)
                        // 视口宽只认 laneWidthPx（即时间轴可视宽度，等于整屏宽减去 172dp 轨道头），
                        // 竖屏/横屏/分屏都由布局自己算好，不需要再读窗口几何
                        val vpW = laneWNow
                        if (vpW <= 0f) continue
                        val clip = Project.tracks
                            .asSequence()
                            .flatMap { it.clips.asSequence() }
                            .firstOrNull { it.id == state.draggingClipId }
                        if (clip == null) {
                            // 拖动中的片段被删掉/被别处改掉了：不能 continue —— 那样这个循环会一直转，
                            // 而 draggingClipId 永远不清，横向滚动就被永久锁死（看起来就是「拖不动也滚不动」）
                            state.draggingClipId = ""
                            state.dragActive = false
                            break
                        }
                        val lx = clip.timelineStart.toFloat() / sppNow +
                            state.dragDx + state.dragGrabX - hState.value
                        val vpH = state.viewportHeightPx
                        val ly = if (vpH > 0f) state.dragPointerY - state.viewportTopPx else 0f
                        val fresh = SystemClock.uptimeMillis() - state.dragDirAtMs <= dirHoldMs
                        val dirX = if (fresh) state.dragDirX else 0f
                        val dirY = if (fresh) state.dragDirY else 0f

                        // 水平：越贴边越快，反向移动立即停（判定逻辑见 EdgeScroll，有单测）
                        val stepX = EdgeScroll.horizontal(lx, vpW, edgeH, dirX)
                        if (stepX != 0f) {
                            // dispatchRawDelta 返回真正被消费的量：滚到工程两端不会虚增位移
                            val used = hState.dispatchRawDelta(stepX)
                            if (used != 0f) {
                                state.dragScrollComp += used
                                // 每帧重算，别累加：dragDx = 手指真实位移 + 已滚过的量
                                state.dragDx = state.dragRootDeltaX + state.dragScrollComp
                            }
                        }

                        // 垂直：只有长按跨轨拖动才做，避免横拖时把轨道一起滚走
                        if (state.dragCrossTrack && vpH > 0f) {
                            val stepY = EdgeScroll.vertical(ly, vpH, edgeV, dirY)
                            if (stepY != 0f) {
                                val used = vState.dispatchRawDelta(stepY)
                                if (used != 0f) {
                                    state.dragScrollCompY += used
                                    state.dragDy = state.dragRootDeltaY + state.dragScrollCompY
                                }
                            }
                        }
                    }
                }

                // 缩放后把视图定位到请求的位置（等一帧让宽度测量完成）
                // 视口落位：常驻协程消费请求。以前用「key 上挂 secondsPerScreen/pendingScrollSec」的
                // LaunchedEffect，拖动时每个事件都会把它取消重建 —— 中间那两个 delay 永远走不完，
                // 滚动就一直不落地，表现就是白框「边变长边跑」、缩放抽搐。
                val laneWLatest by rememberUpdatedState(laneWidthPx)
                LaunchedEffect(Unit) {
                    snapshotFlow { state.pendingScrollSec }.collect { sec ->
                        if (sec == null) return@collect
                        state.consumeScrollRequest()
                        val span = state.secondsPerScreen.coerceAtLeast(1e-3f)
                        val ppsNow = laneWLatest / span
                        val target = (sec * ppsNow).toInt()
                        hState.scrollTo(target.coerceIn(0, hState.maxValue))
                        // 内容宽度要等这一帧测量完才更新，再校一次，避免贴到旧的 maxValue 上
                        kotlinx.coroutines.delay(16)
                        hState.scrollTo(target.coerceIn(0, hState.maxValue))
                    }
                }
                val contentHeightPx = (Project.tracks.size + 1) * lanePx

                Row(modifier = Modifier.fillMaxSize()) {
                    // ===== 左侧：轨道头（水平固定，垂直跟随 vState）=====
                    Column(
                        modifier = Modifier
                            .width(UiDimens.HEADER_W)
                            .fillMaxHeight()
                    ) {
                        Box(
                            modifier = Modifier
                                .height(UiDimens.NAV_H)
                                .fillMaxWidth()
                                .background(Palette.NavBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("概览", fontSize = 10.5.sp, color = Palette.TextWeak)
                        }
                        Box(
                            modifier = Modifier
                                .height(UiDimens.RULER_H)
                                .fillMaxWidth()
                                .background(Palette.Panel),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("音轨", fontSize = 11.sp, color = Palette.TextDim)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clipToBounds()
                        ) {
                            Column(
                                modifier = Modifier.offset { IntOffset(0, -vState.value) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(laneHeight)
                                        .background(
                                            if (state.focusMix) Palette.Brand.copy(alpha = 0.16f) else Palette.MixBg
                                        )
                                        .clickable { state.focusMixBus() },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text("混合", color = Color(0xFFE8C97A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("总线（点击聚焦 · 只读）", color = Palette.TextWeak, fontSize = 9.5.sp)
                                    }
                                }
                                for (t in Project.tracks) {
                                    TrackHeaderView(t, state, geom)
                                }
                            }
                        }
                    }

                    // ===== 右侧：标尺 + 泳道 =====
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        NavigatorView(
                            state = state,
                            geom = geom,
                            pxPerSecond = pxPerSecond,
                            scrollPx = hState.value.toFloat(),
                            laneWidthPx = laneWidthPx,
                            contentSeconds = timelinePx / pxPerSecond,
                            totalSeconds = totalSeconds,
                            drawKey = drawKey,
                            onScrollTo = { target ->
                                scope.launch { hState.scrollTo(target.coerceIn(0, hState.maxValue)) }
                            }
                        )

                        // 标尺 + 泳道 + 播放头（播放头画在滚动区外层，屏幕坐标随滚动实时换算）
                        val playheadScreenX = playheadPx - hState.value
                        val latestLineX by rememberUpdatedState(playheadScreenX)
                        val latestScroll by rememberUpdatedState(hState.value.toFloat())
                        val latestPps by rememberUpdatedState(pxPerSecond)
                        val hitRadius = with(LocalDensity.current) { 18.dp.toPx() }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .onSizeChanged { laneMeasured = it.width.toFloat() }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        // 在 Initial 阶段先于滚动/长按判断：只有按在白线上才接管
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        if (abs(down.position.x - latestLineX) > hitRadius) return@awaitEachGesture
                                        down.consume()
                                        state.seeking = true
                                        fun secAt(x: Float) = ((latestScroll + x) / latestPps).coerceAtLeast(0f)
                                        fun frameAt(x: Float) = (secAt(x) * C.SAMPLE_RATE).toInt()
                                        fun seekToX(x: Float) = state.seekTo(secAt(x))
                                        seekToX(down.position.x)
                                        // 0=未定 1=普通拖动白线 2=长按后拉选区
                                        var mode = 0
                                        val slop = viewConfiguration.touchSlop
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!c.pressed) { c.consume(); break }
                                            c.consume()
                                            val dist = kotlin.math.hypot(
                                                (c.position.x - down.position.x).toDouble(),
                                                (c.position.y - down.position.y).toDouble()
                                            ).toFloat()
                                            if (mode == 0) {
                                                if (dist > slop) {
                                                    // 先移动了就锁定为普通拖白线，之后按多久都不会变选区
                                                    mode = 1
                                                } else if (c.uptimeMillis - down.uptimeMillis >= 420L) {
                                                    mode = 2
                                                    state.beginSelection(frameAt(down.position.x))
                                                }
                                            }
                                            when (mode) {
                                                1 -> seekToX(c.position.x)
                                                2 -> state.updateSelection(frameAt(c.position.x))
                                            }
                                        }
                                        state.seeking = false
                                        if (mode == 2) state.playheadTouched = true
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // 标尺：固定视口，按当前滚动位置铺满整行
                                RulerView(
                                    geom, drawKey, hState.value.toFloat(),
                                    onSeek = { sec -> state.seekTo(sec) },
                                    onSeekStart = { state.seeking = true },
                                    onSeekEnd = { state.seeking = false }
                                )

                                // 双指缩放期间：两个方向的滚动都关掉，手势只做缩放一件事
                                var pinching by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        // 记录滚动视口在窗口里的真实位置/尺寸：横竖屏、分屏都会自动更新
                                        .onGloballyPositioned { coords ->
                                            val p = coords.positionInRoot()
                                            state.viewportLeftPx = p.x
                                            state.viewportTopPx = p.y
                                            state.viewportWidthPx = coords.size.width.toFloat()
                                            state.viewportHeightPx = coords.size.height.toFloat()
                                        }
                                        // 缩放改成「上下拉伸」：两指在竖直方向张开/合拢。
                                        // 横向捏合一律忽略 —— 之前横着捏会偶发把视口甩到末尾，
                                        // 而且和左右滚动抢手势，十次里有一两次没反应。
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                var lastY = -1f
                                                var lastX = -1f
                                                while (true) {
                                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                                    val pts = ev.changes.filter { it.pressed }
                                                    if (pts.size >= 2) {
                                                        var applied = false
                                                        val ySpan = abs(pts[0].position.y - pts[1].position.y)
                                                        val xSpan = abs(pts[0].position.x - pts[1].position.x)
                                                        if (lastY > 0f && ySpan > 0f) {
                                                            val dY = ySpan - lastY
                                                            val dX = xSpan - lastX
                                                            // 只有「竖直方向」明显变化才算缩放手势；阈值 4px 防抖
                                                            if (abs(dY) >= 4f && abs(dY) > abs(dX)) {
                                                                // 单次变化限幅，杜绝一帧把跨度甩到极限（就是那个「跳到末尾」）
                                                                val factor = (ySpan / lastY).coerceIn(0.90f, 1.10f)
                                                                if (abs(factor - 1f) > 0.004f) {
                                                                    // 锚在「当前视口中心」：缩放时画面只变疏密，不会跟着手指平移。
                                                                    // 之前锚在手指中心，手一挪素材就滑走 —— 看起来就像「又在缩放又在滚动」。
                                                                    val ppsNow = laneWLatest / state.secondsPerScreen.coerceAtLeast(1e-3f)
                                                                    val centerSec = (hState.value + laneWLatest / 2f) / ppsNow
                                                                    val before = state.secondsPerScreen
                                                                    state.pinchZoom(factor, centerSec)
                                                                    // 到达缩放上下限时跨度不会再变；这时不能把事件吃掉，
                                                                    // 否则就是「缩不动也不给滚」——手感直接死住。
                                                                    applied = abs(state.secondsPerScreen - before) > 1e-3f
                                                                }
                                                            }
                                                        }
                                                        lastY = ySpan
                                                        lastX = xSpan
                                                        pinching = applied
                                                        if (applied) {
                                                            // 这一下确实缩放了：事件全部吃掉，只缩放不滚动
                                                            ev.changes.forEach { it.consume() }
                                                        }
                                                    } else {
                                                        lastY = -1f
                                                        lastX = -1f
                                                        pinching = false
                                                    }
                                                    if (!ev.changes.any { it.pressed }) { pinching = false; break }
                                                }
                                            }
                                        }
                                        .horizontalScroll(hState, enabled = state.draggingClipId.isEmpty() && !pinching)
                                        .verticalScroll(vState, enabled = !pinching)
                                ) {
                                    Column(
                                        modifier = Modifier.width(
                                            with(LocalDensity.current) { timelinePx.toDp() }
                                        )
                                    ) {
                                        MixLaneView(state, geom, drawKey)
                                        for (t in Project.tracks) {
                                            TrackLaneView(t, state, geom, drawKey)
                                        }
                                    }
                                }
                            }

                            DragGhostLayer(
                                state = state,
                                geom = geom,
                                scrollX = hState.value.toFloat(),
                                scrollY = vState.value.toFloat()
                            )

                            if (state.hasSelection) {
                                val selL = (state.selectionLo().toFloat() / C.SAMPLE_RATE * pxPerSecond - hState.value)
                                val selR = (state.selectionHi().toFloat() / C.SAMPLE_RATE * pxPerSecond - hState.value)
                                val x1 = selL.coerceAtLeast(0f)
                                val x2 = selR.coerceAtMost(laneWidthPx)
                                if (x2 > x1) {
                                    Box(
                                        modifier = Modifier
                                            .offset { IntOffset(x1.roundToInt(), 0) }
                                            .width(with(androidx.compose.ui.platform.LocalDensity.current) { (x2 - x1).toDp() })
                                            .fillMaxHeight()
                                            .background(Color(0x333D7BFF))
                                            .border(1.dp, Palette.Brand, RoundedCornerShape(1.dp))
                                    )
                                }
                            }
                            val overlayX = playheadPx - hState.value
                            if (overlayX >= -28f && overlayX <= laneWidthPx + 28f) {
                                PlayheadOverlay(
                                    xPx = overlayX,
                                    heightPx = (rulerPx + contentHeightPx),
                                    heightDp = with(LocalDensity.current) { (rulerPx + contentHeightPx).toDp() }
                                )
                            }
                        }
                    }
                }
            }

            if (state.playheadTouched) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Palette.Panel)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TBtn("复制", compact = true) { state.copySelection() }
                    TBtn("剪切", compact = true) { state.cutSelection() }
                    TBtn("粘贴", compact = true) { state.pasteClipboard() }
                    if (state.hasSelection) {
                        Text(
                            "选区 %.2fs · %s".format(
                                (state.selectionHi() - state.selectionLo()) / C.SAMPLE_RATE.toFloat(),
                                state.focusedTrackName()
                            ),
                            color = Palette.TextWeak, fontSize = 10.sp
                        )
                        TBtn("取消选区", compact = true) { state.clearSelection() }
                    } else {
                        Text(
                            if (state.clipboardLabel.isEmpty()) "拖动白线后可用" else "剪贴板：${state.clipboardLabel}",
                            color = Palette.TextWeak, fontSize = 10.sp
                        )
                    }
                }
            }

            LyricsStrip(state)

            // 底部主输出电平（AU 那块「电平」表）：取渲染线程算出的总线真实峰值
            LevelMeterBar(state)

            TransportBarView(state, compact = compact) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (state.recording || granted) state.toggleRecord()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // 顶部下拉菜单
        val menu = state.openMenu
        if (menu != null && menu != "效果" && !state.eqOpen) {
            Box(modifier = Modifier.fillMaxSize().clickable { state.closeMenu() })
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 96.dp, start = 8.dp)
            ) {
                EditorMenuPanel(
                    menu = menu,
                    state = state,
                    onImport = { state.closeMenu(); importLauncher.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onLyrics = {
                        state.closeMenu()
                        lyricsLauncher.launch(arrayOf("text/*", "application/octet-stream", "application/x-subrip", "*/*"))
                    },
                    onCover = {
                        state.closeMenu()
                        coverLauncher.launch(arrayOf("image/*"))
                    },
                    onExport = {
                        state.closeMenu()
                        val msg = state.exportMix() ?: "导出失败"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    onRecord = {
                        state.closeMenu()
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (state.recording || granted) {
                            state.toggleRecord()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }
        }

        // 「效果」顶层导航栏下拉窗口（仿 AU：从菜单栏向下展开）
        if (state.openMenu == "效果" && !state.eqOpen) {
            Box(modifier = Modifier.fillMaxSize().clickable { state.closeMenu() })
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 84.dp, start = 8.dp)
                    .zIndex(20f)
            ) {
                EffectMenuWindow(state) { state.closeMenu() }
            }
        }

        // 「设置」窗口（帮助 + 黑白模式）
        if (state.settingsOpen && !state.eqOpen) {
            Box(modifier = Modifier.fillMaxSize().clickable { state.toggleSettings(false) })
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 84.dp, end = 8.dp)
                    .zIndex(20f)
            ) {
                SettingsWindow(state) { state.toggleSettings(false) }
            }
        }

        // 「导出」格式窗口
        if (state.exportOpen && !state.eqOpen) {
            Box(modifier = Modifier.fillMaxSize().clickable { state.toggleExport(false) })
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 84.dp, start = 8.dp)
                    .zIndex(20f)
            ) {
                ExportWindow(state) { fmt ->
                    exportFormat = fmt
                    state.toggleExport(false)
                    exportLauncher.launch(
                        when (fmt) {
                            "wav" -> "mix.wav"
                            "m4a" -> "mix.m4a"
                            "flac" -> "mix.flac"
                            else -> "mix.wav"
                        }
                    )
                }
            }
        }

        // 后台处理中的提示：降噪要跑几万帧 FFT，期间不能让用户以为没反应
        if (state.fxBusy) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp)
                    .background(Color(0xE6202020), RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.DropdownBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .zIndex(50f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Palette.Brand
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在处理：${state.fxBusyLabel}…",
                        color = Palette.Text, fontSize = 11.5.sp
                    )
                }
            }
        }

        // 单个效果的参数窗口（AU 风格：可拖可缩放；点窗口外空白不关闭）
        if (state.fxDialog != null && !state.eqOpen) {
            EffectDialogWindow(state) { state.closeFx() }
        }

        // Pro-Q 均衡器（小窗口，可拖动移动）
        if (state.eqOpen) {
            EqWindow(state) { state.closeEq() }
        }
        }
    }
}

/** 底部歌词 / 封面条：导入 .lrc 后按播放位置滚动显示当前句与下一句 */
@Composable
private fun LyricsStrip(state: EditorState) {
    val lines = state.lyrics
    val cover = state.cover
    if (lines.isEmpty() && cover == null) return
    val sec = state.playheadFrame.toFloat() / C.SAMPLE_RATE
    val idx = com.multitrack.app.lyrics.currentLineIndex(lines, sec)
    val cur = if (idx >= 0) lines[idx].text else (lines.firstOrNull()?.text ?: "")
    val next = if (idx + 1 in lines.indices) lines[idx + 1].text else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(Palette.TimecodeBg)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = "封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (cur.isNotEmpty()) cur else "（封面已载入）",
                color = Palette.Text,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (next.isNotEmpty()) {
                Text(
                    next,
                    color = Palette.TextWeak,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (state.lyricsName.isNotEmpty()) {
            Text(state.lyricsName, color = Palette.TextDim, fontSize = 10.5.sp, maxLines = 1)
        }
    }
}

/** 顶部菜单下拉面板：条目直接落到编辑器动作上；「效果」菜单按 AU 分类集中列出 */
internal data class MenuRow(val label: String, val action: (() -> Unit)? = null)

@Composable
internal fun EditorMenuPanel(
    menu: String,
    state: EditorState,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onRecord: () -> Unit,
    onLyrics: () -> Unit = {},
    onCover: () -> Unit = {}
) {
    val entries: List<MenuRow> = when (menu) {
        "文件" -> listOf(
            MenuRow("导入音频…", onImport),
            MenuRow("导出混音…", onExport),
            MenuRow("导入歌词 (.lrc)…", onLyrics),
            MenuRow("导入封面图片…", onCover),
            MenuRow(if (state.lyrics.isEmpty()) "清除歌词（无）" else "清除歌词") { state.clearLyrics() },
            MenuRow(if (state.cover == null) "清除封面（无）" else "清除封面") { state.clearCover() },
            MenuRow("录音", onRecord),
            MenuRow("工程信息") {
                val clips = Project.tracks.sumOf { it.clips.size }
                state.showToast("${Project.tracks.size} 轨 / $clips 个片段")
            }
        )
        "编辑" -> listOf(
            MenuRow("分割选中片段") { state.splitSelected() },
            MenuRow("删除选中片段") { state.deleteSelected() },
            MenuRow("取消选择") { state.selectedClipId = "" }
        )
        "选择" -> listOf(
            MenuRow("全部") { state.selectAll() },
            MenuRow("当前片段范围") { state.selectCurrentClipRange() },
            MenuRow("从播放头到结束") { state.selectFromPlayheadToEnd() },
            MenuRow("取消选择") { state.clearSelection() },
            MenuRow("用选区作为噪声样本") {
                state.showToast(state.captureNoiseFromSelection())
            }
        )
        "多轨" -> listOf(
            MenuRow("添加音轨") { state.addTrack() },
            MenuRow("全部取消独奏") {
                for (t in Project.tracks) t.solo = false
                Project.notifyChanged()
            }
        )
        "视图" -> listOf(
            MenuRow("放大时间轴") { state.zoomIn() },
            MenuRow("缩小时间轴") { state.zoomOut() },
            MenuRow("打开均衡器") { state.openEq() }
        )
        "效果" -> listOf(
            MenuRow("★ 均衡器"),
            MenuRow("参数均衡器（Pro-Q）") { state.openEq() },
            MenuRow("图形均衡器（10 段）") { state.showToast(state.applyFx("graphicEq")) },
            MenuRow("★ 振幅与压限"),
            MenuRow("强制限幅") { state.showToast(state.applyFx("limiter")) },
            MenuRow("单频段压缩器") { state.showToast(state.applyFx("compressor")) },
            MenuRow("★ 延迟与回声"),
            MenuRow("延迟（140ms / 30%）") { state.showToast(state.applyFx("delay")) },
            MenuRow("★ 诊断"),
            MenuRow("静音检测") { state.showToast(state.applyFx("silenceDetect")) },
            MenuRow("删除静音") { state.showToast(state.applyFx("removeSilence")) },
            MenuRow("★ 调制"),
            MenuRow("和声") { state.showToast(state.applyFx("chorus")) },
            MenuRow("颤音") { state.showToast(state.applyFx("tremolo")) },
            MenuRow("★ 降噪 / 恢复"),
            MenuRow("消除齿音") { state.showToast(state.applyFx("deesser")) },
            MenuRow("自适应降噪") { state.showToast(state.applyFx("denoise")) },
            MenuRow("★ 混响"),
            MenuRow("室内混响") { state.showToast(state.applyFx("reverb")) },
            MenuRow("★ 立体声声像"),
            MenuRow("立体声扩展器") { state.showToast(state.applyFx("widen")) },
            MenuRow("★ 时间与变调"),
            MenuRow("音高换档器 +2") { state.showToast(state.applyFx("pitchUp")) },
            MenuRow("伸缩 0.8 倍速") { state.showToast(state.applyFx("stretch")) },
            MenuRow("★ 特殊效果"),
            MenuRow("扭曲") { state.showToast(state.applyFx("distort")) },
            MenuRow("人声增强") { state.showToast(state.applyFx("vocal")) },
            MenuRow("★ 基础"),
            MenuRow("归一化") { state.applyEffect("normalize") },
            MenuRow("反转") { state.applyEffect("reverse") }
        )
        "窗口" -> listOf(
            MenuRow("回到工程起点") { state.seekTo(0f) },
            MenuRow("全屏均衡器") { state.openEq() }
        )
        "帮助" -> listOf(
            MenuRow("关于 CyrilMusic") { state.showToast("CyrilMusic 2.2 · 多轨音频编辑器 · 内置 Pro-Q 风格均衡器") },
            MenuRow("操作提示") { state.showToast("双指缩放时间轴 · 拖概览条拉伸范围 · 效果在「效果」菜单里") }
        )
        else -> emptyList()
    }

    LazyColumn(
        modifier = Modifier
            .width(214.dp)
            .heightIn(max = 430.dp)
            .background(Color(0xFF2B2B2B), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF4A4A4A), RoundedCornerShape(4.dp))
            .padding(vertical = 4.dp)
    ) {
        item {
            Text(
                menu,
                color = Palette.TextWeak,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        items(entries) { row ->
            val action = row.action
            if (action == null) {
                Text(
                    row.label,
                    color = Color(0xFF7FA6C8),
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        .clickable {
                            state.closeMenu()
                            action()
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        row.label,
                        color = Palette.Text,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF3A3A3A))
                )
            }
        }
    }
}
