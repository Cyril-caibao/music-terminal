package com.multitrack.app.ui

import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.model.Clip
import com.multitrack.app.model.Project
import com.multitrack.app.model.Track
import com.multitrack.app.ui.theme.Palette
import com.multitrack.app.ui.waveform.drawRuler
import com.multitrack.app.ui.waveform.drawRulerWindow
import com.multitrack.app.ui.waveform.drawWaveform
import kotlin.math.abs
import com.multitrack.app.audio.C
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.max
import kotlin.math.roundToInt

/** 时间轴几何（统一用像素 px 作为坐标系） */
data class Geom(
    val headerPx: Float,
    val rulerPx: Float,
    val lanePx: Float,
    val laneWidthPx: Float,
    val pxPerSecond: Float,   // px / 秒
    val spp: Float,           // 帧 / px
    val timelinePx: Float,
    val playheadPx: Float,
    val density: Float
)

private fun String.toColor(): Color = try {
    Color(AColor.parseColor(this))
} catch (e: Exception) {
    Palette.Brand
}

/** px 转 Dp 的便捷扩展 */
@Composable
private fun Float.pxDp(): Dp = with(LocalDensity.current) { this@pxDp.toDp() }

/** 顶部时间标尺：固定视口，从当前滚动位置铺满整行；点按/拖动定位播放头 */
@Composable
fun RulerView(
    geom: Geom,
    drawKey: Int,
    scrollPx: Float,
    onSeek: (Float) -> Unit,
    onSeekStart: () -> Unit = {},
    onSeekEnd: () -> Unit = {}
) {
    val d = geom.density
    val pxPerSecond = geom.pxPerSecond
    val latestScroll by rememberUpdatedState(scrollPx)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(geom.rulerPx.pxDp())
            .pointerInput(pxPerSecond) {
                detectTapGestures { off ->
                    onSeek(((latestScroll + off.x) / pxPerSecond).coerceAtLeast(0f))
                }
            }
            .pointerInput(pxPerSecond) {
                detectDragGestures(
                    onDragStart = { onSeekStart() },
                    onDragEnd = { onSeekEnd() },
                    onDragCancel = { onSeekEnd() }
                ) { change, _ ->
                    change.consume()
                    onSeek(((latestScroll + change.position.x) / pxPerSecond).coerceAtLeast(0f))
                }
            }
    ) {
        @Suppress("UNUSED_VARIABLE")
        val k = drawKey
        val startSec = if (pxPerSecond > 0f) scrollPx / pxPerSecond else 0f
        drawRulerWindow(size.width, size.height, pxPerSecond, startSec, d)
    }
}

/** 播放头（纯显示）：屏幕坐标 = 内容坐标 - 滚动量；拖动由外层固定容器接管，避免「节点跟着手指移动」造成回跳 */
@Composable
fun PlayheadOverlay(
    xPx: Float,
    heightPx: Float,
    heightDp: Dp
) {
    val density = LocalDensity.current.density
    Box(
        modifier = Modifier
            .offset { IntOffset((xPx - 1f * density).roundToInt(), 0) }
            .width(2.dp)
            .height(heightDp)
            .background(Palette.Playhead)
    )
}

/** 左侧轨道头：名称 + M/S/R + 音量 + 声像（AU 风格，自己画的细长控件，避开 Material 最小触控尺寸） */
@Composable
fun TrackHeaderView(track: Track, state: EditorState, geom: Geom) {
    // 横屏时轨道更矮，隐藏音量/声像条，避免被裁切
    val compact = geom.lanePx / geom.density < 70f
    // 长按拖动：拖动中只做视觉位移，松手才真正换位（拖动中改列表会打断手势）
    var dragDy by remember(track.id) { mutableStateOf(0f) }
    var dragging by remember(track.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(geom.headerPx.pxDp())
            .height(geom.lanePx.pxDp())
            .background(
                if (!state.focusMix && state.focusedTrackIndex == Project.tracks.indexOf(track))
                    Palette.Brand.copy(alpha = 0.16f)
                else Palette.PanelAlt
            )
            .border(1.dp, Palette.Border)
            .graphicsLayer { translationY = dragDy; shadowElevation = if (dragging) 6f else 0f }
            .zIndex(if (dragging) 2f else 0f)
            .clickable { state.focusTrack(track) }
            .pointerInput(track.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        dragDy = 0f
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        dragDy += drag.y
                    },
                    onDragEnd = {
                        val idx = Project.tracks.indexOf(track)
                        if (idx >= 0) {
                            var steps = (dragDy / geom.lanePx).roundToInt()
                            if (steps == 0 && kotlin.math.abs(dragDy) > geom.lanePx * 0.6f) {
                                steps = if (dragDy > 0) 1 else -1
                            }
                            val to = (idx + steps).coerceIn(0, Project.tracks.size - 1)
                            if (to != idx) {
                                Project.moveTrack(idx, to)
                                state.focusTrackByIndex(to)
                            }
                        }
                        dragDy = 0f
                        dragging = false
                    },
                    onDragCancel = {
                        dragDy = 0f
                        dragging = false
                    }
                )
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.name,
                fontSize = 11.sp,
                color = Palette.Text,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            // 兜底：长按拖动之外，也可以用两个小箭头上下挪轨
            Box(
                modifier = Modifier.width(16.dp).height(14.dp)
                    .clickable { state.moveTrackBy(track, -1) },
                contentAlignment = Alignment.Center
            ) { Text("▲", fontSize = 10.sp, color = Palette.TextWeak) }
            Box(
                modifier = Modifier.width(16.dp).height(14.dp)
                    .clickable { state.moveTrackBy(track, 1) },
                contentAlignment = Alignment.Center
            ) { Text("▼", fontSize = 10.sp, color = Palette.TextWeak) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MSRButton(track.mute, "M", Palette.Mute) {
                track.mute = !track.mute; Project.notifyChanged()
            }
            MSRButton(track.solo, "S", Palette.Solo) {
                track.solo = !track.solo; Project.notifyChanged()
            }
            MSRButton(track.armed, "R", Palette.Mute) {
                state.onArm(track)
            }
        }

        if (!compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniSlider(
                value = track.volume,
                min = 0f,
                max = 1.5f,
                onChange = { track.volume = it; Project.notifyChanged() },
                modifier = Modifier.weight(1f)
            )
            Text("${(track.volume * 100).toInt()}", fontSize = 9.5.sp, color = Palette.TextDim,
                modifier = Modifier.width(26.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("P", fontSize = 10.sp, color = Palette.TextDim, modifier = Modifier.width(12.dp))
            MiniSlider(
                value = track.pan,
                min = -1f,
                max = 1f,
                onChange = { track.pan = it; Project.notifyChanged() },
                modifier = Modifier.weight(1f)
            )
        }
        }
    }
}

@Composable
private fun MSRButton(active: Boolean, label: String, activeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(18.dp)
            .background(if (active) activeColor else Palette.BtnBg, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            .border(1.dp, if (active) activeColor else Color(0xFF4A4A4A), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 10.sp, color = Color.White)
    }
}

/** 轨道头滑杆：20dp 高、4px 轨、18px 手指 —— 之前 14dp / 2px / 6px，按不准也看不清 */
@Composable
private fun MiniSlider(
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .height(20.dp)
            .pointerInput(min, max) {
                detectTapGestures { off -> onChange(min + (off.x / size.width) * (max - min)) }
            }
            .pointerInput(min, max) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(min + (change.position.x / size.width) * (max - min))
                }
            }
    ) {
        val cy = size.height / 2f
        val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
        drawLine(Color(0xFF4A4A4A), Offset(0f, cy), Offset(size.width, cy), 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Palette.Brand, Offset(0f, cy), Offset(size.width * frac, cy), 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Palette.Brand, radius = 9f, center = Offset(size.width * frac, cy))
        drawCircle(Color.White, radius = 3.5f, center = Offset(size.width * frac, cy))
    }
}

/** 单条泳道：背景 + 该轨所有片段（按时间轴定位）+ 底部分隔线 */
@Composable
fun TrackLaneView(
    track: Track,
    state: EditorState,
    geom: Geom,
    drawKey: Int
) {
    val d = geom.density
    Box(
        modifier = Modifier
            .width(geom.timelinePx.pxDp())
            .height(geom.lanePx.pxDp())
            .background(Palette.Lane)
            .border(width = 1.dp, color = Palette.Border)
            .zIndex(if (state.draggingClipId.isNotEmpty() && track.clips.any { it.id == state.draggingClipId }) 4f else 0f)
    ) {
        // 触发重绘
        @Suppress("UNUSED_VARIABLE")
        val k = drawKey
        for (clip in track.clips) {
            androidx.compose.runtime.key(clip.id) {
                val xPx = clip.timelineStart / geom.spp
                val wPx = max(2f, clip.length / geom.spp)
                WaveClipView(clip, state, geom, wPx, d, xPx)
            }
        }
    }
}

/** 单个波形片段：Canvas 波形 + 点按选中 + 水平拖动移动 */
@Composable
private fun WaveClipView(
    clip: Clip,
    state: EditorState,
    geom: Geom,
    wPx: Float,
    density: Float,
    xPx: Float
) {
    val selected = state.selectedClipId == clip.id
    // 长按后拖动：水平改时间位置，垂直跨轨换轨道；拖动中只做视觉位移，松手才提交
    val dragState = remember(clip.id) { floatArrayOf(0f, 0f) }
    var ghostX by remember(clip.id) { mutableStateOf(0f) }
    var ghostY by remember(clip.id) { mutableStateOf(0f) }
    var originX by remember(clip.id) { mutableStateOf(0f) }
    var originY by remember(clip.id) { mutableStateOf(0f) }

    // 记录节点坐标：手指位置一律换算到「根坐标」再算位移。
    // 直接缓存 positionInRoot 会在内容滚动后过期（滚动不会触发重新布局），
    // 所以每次都用 LayoutCoordinates 现算 localToRoot。
    val coordsRef = remember(clip.id) { mutableStateOf<LayoutCoordinates?>(null) }

    fun rootOf(p: Offset): Offset {
        val c = coordsRef.value
        return if (c != null && c.isAttached) c.localToRoot(p) else Offset(originX + p.x, originY + p.y)
    }

    Canvas(
        modifier = Modifier
            .onGloballyPositioned {
                coordsRef.value = it
                val p = it.positionInRoot()
                originX = p.x
                originY = p.y
            }
            .offset { IntOffset(xPx.roundToInt(), 2) }
            .width(wPx.pxDp())
            .height((geom.lanePx - 4f).pxDp())
            .zIndex(if (state.draggingClipId == clip.id) 2f else 0f)
            .pointerInput(clip.id, geom.spp) {
                awaitEachGesture {
                    // 关键：在 Initial 阶段（早于所有祖先的 Main 阶段拖动检测）就把按下吃掉。
                    // 以前是「谁先跨过 touchSlop 谁赢」，外层 horizontalScroll / verticalScroll 和片段
                    // 是赛跑关系：手指一斜、或者先把滚动带起来，片段就整段手势都收不到 ——
                    // 表现就是「有时候怎么拖都不动」。先在 Initial 阶段认领，滚动只能让路。
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 白线 / 选区已经在 Initial 阶段接管了这次手势：让给它们
                    if (down.isConsumed) return@awaitEachGesture
                    down.consume()

                    // 上一次手势如果被系统砍断（横竖屏、切后台、被别的手势抢走），
                    // draggingClipId 会留在某个 id 上。外层水平滚动正是靠它来禁用的，
                    // 不清掉的话：滚动滚不动、片段也拖不动，整块时间轴像死了一样。
                    if (state.draggingClipId.isNotEmpty()) state.draggingClipId = ""
                    state.dragActive = false

                    // 按下即选中、即锁跨度：这一次手势就能拖，不再要求「先点一下再拖第二次」
                    state.selectClip(clip)
                    // 跨度锁只在「真的开始拖」时才上：纯点一下也锁的话，
                    // 之后 minViewSpan 变了（导入更长的素材）视野却还被钉住
                    state.draggingClipId = clip.id
                    state.dragGrabX = down.position.x
                    val downRoot = rootOf(down.position)
                    val downRootX = downRoot.x
                    val downRootY = downRoot.y
                    state.dragPointerX = downRootX
                    state.dragPointerY = downRootY
                    state.dragScrollComp = 0f
                    state.dragScrollCompY = 0f
                    state.dragDx = 0f
                    state.dragDy = 0f
                    state.dragRootDeltaX = 0f
                    state.dragRootDeltaY = 0f
                    state.dragDirX = 0f
                    state.dragDirY = 0f
                    state.dragDirAtMs = 0L
                    state.dragCrossTrack = false
                    val slop = viewConfiguration.touchSlop
                    var mode = 0 // 0=还没定 2=横向移时间 3=竖向跨轨
                    var dx = 0f
                    var dy = 0f
                    var lastRootX = downRootX
                    var lastRootY = downRootY
                    var settled = false

                    fun resetDragState() {
                        state.dragDx = 0f
                        state.dragDy = 0f
                        state.dragRootDeltaX = 0f
                        state.dragRootDeltaY = 0f
                        state.dragPointerX = 0f
                        state.dragPointerY = 0f
                        state.dragScrollComp = 0f
                        state.dragScrollCompY = 0f
                        state.dragDirX = 0f
                        state.dragDirY = 0f
                        state.dragCrossTrack = false
                        state.dragActive = false
                        state.draggingClipId = ""
                        ghostX = 0f
                        ghostY = 0f
                    }

                    // 松手、以及手势被系统取消（横竖屏切换会重建 pointerInput）都走这里：
                    // 把当前位移真正提交掉，避免浮层卡在半路、横向滚动一直被锁住
                    fun settleDrag() {
                        settled = true
                        if (state.draggingClipId != clip.id) {
                            resetDragState()
                            return
                        }
                        val effDx = state.dragDx
                        val effDy = state.dragDy
                        val newStart = max(0f, clip.timelineStart.toFloat() + effDx * geom.spp).roundToInt()
                        if (mode == 3) {
                            val steps = (effDy / geom.lanePx).roundToInt()
                            if (steps != 0) {
                                val tracks = Project.tracks
                                val from = tracks.indexOfFirst { t -> t.clips.contains(clip) }
                                if (from >= 0) {
                                    val to = (from + steps).coerceIn(0, tracks.size - 1)
                                    if (to != from) Project.moveClipToTrack(clip, tracks[to])
                                }
                            }
                        }
                        // 纯点按（mode==0）不改时间位置：否则每点一下片段都会触发一次全时间轴重绘
                        val movedX = kotlin.math.abs(effDx * geom.spp) >= 0.5f
                        if (mode != 0 && (movedX || mode == 3)) state.moveClip(clip, newStart)
                        resetDragState()
                    }

                    try {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            // 一旦认领，整段手势都归片段：所有移动都在 Initial 阶段吃掉，
                            // 滚动/长按/双击都不可能中途把手势抢走
                            c.consume()
                            // 一律用「根坐标」算位移。节点内局部坐标会被内容滚动带偏，
                            // 拿局部坐标再叠加滚动补偿量，等于把同一段滚动算了两遍。
                            val root = rootOf(c.position)
                            val stepX = root.x - lastRootX
                            val stepY = root.y - lastRootY
                            lastRootX = root.x
                            lastRootY = root.y
                            dx = root.x - downRootX
                            dy = root.y - downRootY
                            if (mode == 2 || mode == 3) {
                                // 方向只认「真实位移超过 0.5px」的帧，并且带时间戳：
                                // 手指停住后方向会自动失效，贴边静止时依然持续滚动
                                if (kotlin.math.abs(stepX) > 0.5f) {
                                    state.dragDirX = if (stepX > 0f) 1f else -1f
                                    state.dragDirAtMs = c.uptimeMillis
                                }
                                if (kotlin.math.abs(stepY) > 0.5f) {
                                    state.dragDirY = if (stepY > 0f) 1f else -1f
                                    state.dragDirAtMs = c.uptimeMillis
                                }
                            } else {
                                state.dragDirX = 0f
                                state.dragDirY = 0f
                            }
                            val held = c.uptimeMillis - down.uptimeMillis
                            if (mode == 0) {
                                if (held >= 450L && kotlin.math.hypot(dx, dy) < slop) {
                                    // 按住不动 450ms：也是跨轨拖动
                                    mode = 3
                                    state.lockSpan()
                                    state.draggingClipId = clip.id
                                    state.dragCrossTrack = true
                                    state.dragActive = true
                                } else if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                                    // 直接开拖：上下为主就是换轨，左右为主就是移时间。
                                    // 之前只有「长按」才能跨轨，手指往上一推变成纯水平移动 —— 看起来就是「上下拖不动」。
                                    val verticalFirst = kotlin.math.abs(dy) > slop &&
                                        kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.05f
                                    mode = if (verticalFirst) 3 else 2
                                    state.lockSpan()
                                    state.dragCrossTrack = verticalFirst
                                    state.dragActive = true
                                }
                            }
                            if (mode == 2 || mode == 3) {
                                // 左边到 0 就停住，不做负数回弹；右边不设上限
                                val minDx = -clip.timelineStart.toFloat() / geom.spp
                                dx = dx.coerceAtLeast(minDx)
                                ghostX = dx
                                ghostY = if (mode == 3) dy else 0f
                                state.dragPointerX = root.x
                                state.dragPointerY = root.y
                                // 片段在内容坐标里的位移 = 手指真实位移（rootDelta）+ 自动滚过的量（comp）
                                state.dragRootDeltaX = dx
                                state.dragRootDeltaY = if (mode == 3) dy else 0f
                                state.dragDx = dx + state.dragScrollComp
                                state.dragDy = if (mode == 3) dy + state.dragScrollCompY else 0f
                            }
                        }
                        if (mode == 0) {
                            // 纯点按：按下时已经选中了，这里什么都不用做
                            settled = true
                        }
                        settleDrag()
                    } finally {
                        if (!settled) settleDrag()
                    }
                }
            }
    ) {
        // 只有真正拉开之后才隐藏原片段（否则轻点一下会闪成浮层）
        if (state.dragActive && state.draggingClipId == clip.id) return@Canvas
        runCatching {
        drawWaveform(
            peaks = clip.peaks,
            totalFrames = clip.length,
            cWidth = size.width,
            cHeight = size.height,
            color = clip.color.toColor(),
            fadeInPx = clip.fadeIn / geom.spp,
            fadeOutPx = clip.fadeOut / geom.spp,
            selected = selected,
            name = clip.name,
            density = density,
            peaksRight = clip.peaksR,
            stacked = geom.lanePx / geom.density > 90f
        )
        }
    }
}

/** 拖动中的片段浮层：全时间轴只画这一份，原片段隐藏，彻底避免残影 */
@Composable
fun DragGhostLayer(
    state: EditorState,
    geom: Geom,
    scrollX: Float,
    scrollY: Float
) {
    val id = state.draggingClipId
    // 按下去还没拉开时不画浮层：原片段还在原位，画浮层等于同一张波形叠两遍
    if (id.isEmpty() || !state.dragActive) return
    var clip: Clip? = null
    var track: Track? = null
    for (t in Project.tracks) for (c in t.clips) if (c.id == id) { clip = c; track = t }
    val c = clip ?: return
    val t = track ?: return
    val idx = Project.tracks.indexOf(t)
    if (idx < 0) return
    val x = c.timelineStart.toFloat() / geom.spp - scrollX + state.dragDx
    val y = geom.rulerPx + geom.lanePx * (idx + 1) - scrollY + 2f + state.dragDy
    val w = max(2f, c.length / geom.spp)

    // 视口大小的浮层：只画可见的那一段，避免拖动长片段时生成超大 Canvas 图层
    Canvas(modifier = Modifier.fillMaxSize().zIndex(30f)) {
        val top = y
        val bottom = y + geom.lanePx - 4f
        val left = x
        val right = x + w
        val visL = left.coerceAtLeast(0f)
        val visR = right.coerceAtMost(size.width)
        val visT = top.coerceAtLeast(0f)
        val visB = bottom.coerceAtMost(size.height)
        if (visR <= visL || visB <= visT) return@Canvas
        clipRect(visL, visT, visR, visB) {
            translate(left, top) {
                runCatching {
                    drawWaveform(
                        peaks = c.peaks,
                        totalFrames = c.length,
                        cWidth = w,
                        cHeight = (geom.lanePx - 4f),
                        color = c.color.toColor(),
                        fadeInPx = c.fadeIn / geom.spp,
                        fadeOutPx = c.fadeOut / geom.spp,
                        selected = true,
                        name = c.name,
                        density = geom.density,
                        peaksRight = c.peaksR,
                        stacked = geom.lanePx / geom.density > 90f
                    )
                }
            }
        }
    }
}

/** 播放头（红色竖线），叠在泳道内容坐标系内，随内容一起滚动 */
@Composable
fun PlayheadView(geom: Geom, heightPx: Float, onSeek: (Float) -> Unit = {}) {
    Box(
        modifier = Modifier
            // 线要正好落在 playheadPx 上：16dp 的触摸区，内容居中，中心对齐像素位置。
            // 之前用 CenterStart + 偏 7dp，线实际画在 playheadPx-7dp 处 —— 放大后就是明显的「白线不在波形上」。
            .offset { IntOffset((geom.playheadPx - 8f * geom.density).roundToInt(), 0) }
            .width(16.dp)
            .height(heightPx.pxDp())
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val x = geom.playheadPx - 8f * geom.density + off.x
                    onSeek(x / geom.pxPerSecond)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onSeek((geom.playheadPx + drag.x) / geom.pxPerSecond)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Palette.Playhead))
    }
}

/**
 * 顶部导航条（仿 AU 的时间轴概览）。
 * - 波形缩略用 Project.buildOverview 事先聚合好（每帧只做等比采样，不重复扫 PCM）
 * - 手势用 rememberUpdatedState 读最新值，pointerInput(Unit) 不会因滚动/缩放重启
 * - 平移只改滚动位置（即时生效）；拉伸才改跨度，并立即按新像素/秒定位一次
 */
@Composable
fun NavigatorView(
    state: EditorState,
    geom: Geom,
    pxPerSecond: Float,
    scrollPx: Float,
    laneWidthPx: Float,
    contentSeconds: Float,
    totalSeconds: Float,
    drawKey: Int,
    onScrollTo: (Int) -> Unit
) {
    val d = geom.density
    val pps = if (pxPerSecond.isFinite() && pxPerSecond > 0f) pxPerSecond else 1f
    val projectSec = if (totalSeconds.isFinite()) totalSeconds.coerceAtLeast(0.3f) else 0.3f
    // 关键：概览条映射的是「整条可滚动时间轴（含尾部空白）」，不是工程长度。
    // 否则把片段拖进尾部后，实际视口已经超出概览能表示的范围 —— 白框会顶在最右、拖动方向也会反。
    val contentSec = if (contentSeconds.isFinite()) contentSeconds.coerceAtLeast(projectSec) else projectSec
    val navSec = contentSec.coerceAtLeast(state.minViewSpan)
    val spanSec = (laneWidthPx / pps).coerceIn(state.minViewSpan, navSec)
    val startSec = (scrollPx / pps).coerceIn(0f, (navSec - spanSec).coerceAtLeast(0f))

    val overview = remember(drawKey, projectSec) { Project.buildOverview(2048) }

    val navS = rememberUpdatedState(navSec)
    val startS = rememberUpdatedState(startSec)
    val spanS = rememberUpdatedState(spanSec)
    val ppsS = rememberUpdatedState(pps)
    val minSpanS = rememberUpdatedState(state.minViewSpan)
    val onScrollS = rememberUpdatedState(onScrollTo)

    val mode = remember { intArrayOf(0) }
    val g = remember { floatArrayOf(0f, 0f, 0f) }   // startSec / spanSec / touchX
    // 拖动把手时，白框直接用「手指要求的视口」绘制。
    // 滚动位要等下一帧才落地，如果白框也跟着滚动位走，就会慢半拍 —— 看起来正是「边变长边移动」。
    val previewing = remember { androidx.compose.runtime.mutableStateOf(false) }
    val previewStart = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val previewSpan = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(UiDimens.NAV_H)
            .background(Palette.NavBg)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val mw = size.width.toFloat().coerceAtLeast(1f)
                    val nav = navS.value
                    val span = spanS.value
                    val target = ((off.x / mw) * nav - span / 2f)
                        .coerceIn(0f, (nav - span).coerceAtLeast(0f))
                    onScrollS.value((target * ppsS.value).toInt())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        val mw = size.width.toFloat().coerceAtLeast(1f)
                        val nav = navS.value
                        g[0] = startS.value
                        g[1] = spanS.value
                        g[2] = off.x
                        val winStartX = g[0] / nav * mw
                        val winEndX = (g[0] + g[1]) / nav * mw
                        // 概览条只做「拖动平移」：缩放交给泳道双指捏合，白边拉伸整体砍掉。
                        mode[0] = 0
                        previewing.value = false
                        previewStart.value = g[0]
                        previewSpan.value = g[1]
                    },
                    onDragCancel = { previewing.value = false },
                    onDragEnd = { previewing.value = false }
                ) { change, _ ->
                    change.consume()
                    val mw = size.width.toFloat().coerceAtLeast(1f)
                    val nav = navS.value
                    val dxSec = (change.position.x - g[2]) / mw * nav
                    val s0 = g[0]
                    val sp0 = g[1]
                    val minSpan = minSpanS.value.coerceAtLeast(0.05f)
                    val ns = (s0 + dxSec).coerceIn(0f, (nav - sp0).coerceAtLeast(0f))
                    previewing.value = true
                    previewStart.value = ns
                    previewSpan.value = sp0
                    onScrollS.value((ns * ppsS.value).toInt())
                }
            }
    ) {
        val mw = size.width
        val mh = size.height

        // 工程波形只画到工程结束处；右侧尾部空白区保持空，方便看出「可拖入区域」
        val endFrac = (projectSec / navSec).coerceIn(0f, 1f)
        val drawW = (mw * endFrac).toInt().coerceAtLeast(1)
        val res = overview.size
        for (x in 0 until drawW) {
            val idx = ((x.toFloat() / drawW) * res).toInt().coerceIn(0, res - 1)
            val a = overview[idx]
            if (a <= 0.004f) continue
            val h = (a * (mh * 0.86f)).coerceAtMost(mh * 0.9f)
            drawLine(
                Color(0xFF5C8FD6),
                Offset(x.toFloat(), mh / 2f - h / 2f),
                Offset(x.toFloat(), mh / 2f + h / 2f),
                1f
            )
        }

        // 可视窗口：位置/宽度与实际时间轴视口同源（scrollPx / laneWidthPx / pxPerSecond）
        val eStart = if (previewing.value) previewStart.value else startSec
        val eSpan = if (previewing.value) previewSpan.value else spanSec
        val winX = (eStart / navSec * mw).coerceIn(0f, mw)
        val maxWinW = (mw - winX).coerceAtLeast(0f)
        val rawWinW = (eSpan / navSec * mw)
        val winW = if (rawWinW.isFinite()) rawWinW.coerceIn(0f, maxWinW) else maxWinW
        drawRect(Color(0x1AFFFFFF), topLeft = Offset(winX, 0f), size = Size(winW, mh))
        drawRect(
            Color(0xFF4FA8E0),
            topLeft = Offset(winX, 0f), size = Size(winW, mh),
            style = Stroke(width = 1.5f)
        )
        // 不再画两侧把手（缩放已交给双指捏合）
        // 播放头
        val totalSecForHead = navSec
        val phFrac = (state.playheadFrame.toFloat() / (totalSecForHead * C.SAMPLE_RATE)).coerceIn(0f, 1f)
        drawLine(Color(0xFFEDEDED), Offset(phFrac * mw, 0f), Offset(phFrac * mw, mh), 1f)

        val tp = android.graphics.Paint().apply {
            isAntiAlias = true
            color = 0x66A7B2C0.toInt()
            textSize = 8f * d
        }
        drawContext.canvas.nativeCanvas.drawText("全曲概览 · 拖窗口平移 / 两端把手缩放", 6f * d, 11f * d, tp)
    }
}

/** 混合轨道（仿 AU 的 Mix 轨）：各轨峰值叠加的总线波形（静音轨不计入） */
@Composable
fun MixLaneView(state: EditorState, geom: Geom, drawKey: Int) {
    val overview = remember(drawKey) { Project.buildOverview(2048, respectMute = true) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { geom.lanePx.toDp() })
            .background(if (state.focusMix) Palette.Brand.copy(alpha = 0.14f) else Palette.MixBg)
            .clickable { state.focusMixBus() }
    ) {
        val projectPx = (Project.projectLength().toFloat() / C.SAMPLE_RATE) * geom.pxPerSecond
        val px = minOf(size.width, projectPx).toInt().coerceAtLeast(1)
        val res = overview.size
        val mid = size.height / 2f
        val halfH = size.height / 2f - 2f
        for (x in 0 until px) {
            val idx = ((x.toFloat() / px) * res).toInt().coerceIn(0, res - 1)
            val a = overview[idx].coerceIn(0f, 1f)
            if (a <= 0.004f) continue
            val h = a * halfH
            drawLine(
                Color(0xFFE8C97A),
                Offset(x.toFloat(), mid - h),
                Offset(x.toFloat(), mid + h),
                1f
            )
        }
        drawLine(Color(0x33FFFFFF), Offset(0f, mid), Offset(size.width, mid), 1f)
    }
}
