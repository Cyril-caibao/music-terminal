package com.multitrack.app.player.ui.wall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.multitrack.app.player.data.ArtRepository
import com.multitrack.app.player.data.LyricsRepository
import com.multitrack.app.player.data.SongEntity
import com.multitrack.app.player.playback.PlaybackUi
import com.multitrack.app.player.ui.PColors
import com.multitrack.app.player.ui.ArtImageCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * 无限拼贴墙本体（照搬 Folia 的 PosterWall）。
 *
 * 相机 = 世界原点在屏幕上的位移，世界本身没有边界：拖动只改相机，块坐标可正可负，
 * 所以上下左右都能一直翻下去。每帧只更新 layer 的平移量（不重组），
 * 只有相机走出一小段距离才重算一次「视口里有哪些卡」。
 * 点一张卡：它吃掉块内 6×6 的齿位、邻居按让位表重新咬合，相机把这它送到屏幕中央。
 */
@Composable
internal fun LatticeWall(
    songs: List<SongEntity>,
    art: ArtRepository,
    lyricsOf: suspend (SongEntity) -> List<LyricsRepository.Stanza>,
    playback: State<PlaybackUi>,
    currentSongId: Long,
    active: WallInstance?,
    onTapCard: (WallInstance) -> Unit,
    /** 切歌时把展开窗口交给新歌那张卡（原版：长大的窗口跟着正在播放的那首走） */
    onFollowActive: (WallInstance) -> Unit,
    onTapBlank: () -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier,
    /** 关掉过渡（系统关了动画 / 截图测试），所有重排立即到位 */
    reduceMotion: Boolean = false
) {
    if (songs.isEmpty()) return
    val total = songs.size

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val vw = maxWidth.value
        val vh = maxHeight.value
        // 视野范围：一屏横向约 7.6 格。旧版是「一块 = 屏宽」（12 格），卡片小、只能竖着滚；
        // 这里按上游手机断点（128px 单元 × 0.52）折算，卡片明显变大，四个方向都能翻。
        val cell = min(vw / 7.6f, vh / 9.2f).coerceIn(34f, 68f)
        val metrics = remember(cell) { WallMetrics(cell, (cell * 0.09f).coerceAtLeast(3f)) }
        val geometry = remember(total, metrics) { latticeGeometry(total, metrics) }

        val scope = rememberCoroutineScope()
        val camera = remember { WallCamera() }

        // 四边余量按「格」算（与上游 OVERSCAN / cellSize 同比例），横竖同一个值
        val overscan = overscanFor(metrics)
        val overscanX = overscan
        val overscanY = overscan
        val keepX = keepFor(metrics)
        val keepY = keepFor(metrics)
        val stepX = refineStepFor(metrics)
        val stepY = refineStepFor(metrics)

        val store = remember(geometry) { WallInstanceStore() }
        // 首帧就要有内容：先按当前相机同步算一次，之后才交给 snapshotFlow 增量刷新
        // （否则第一帧是空的，看起来像闪了一下）
        var instances by remember(geometry, total, vw, vh) {
            mutableStateOf(
                store.seed(
                    layoutLattice(
                        geometry, total,
                        worldBounds(camera.x, camera.y, vw, vh),
                        overscanX, overscanY, metrics
                    )
                )
            )
        }

        // 相机走动 → 补算。只增不删（除了跑出保留区的旧卡），所以正常平移不会重建任何一张卡。
        LaunchedEffect(geometry, total, vw, vh) {
            var lastX = Float.NaN
            var lastY = Float.NaN
            snapshotFlow { camera.x to camera.y }.collect { cam ->
                val due = lastX.isNaN() ||
                    abs(cam.first - lastX) >= stepX || abs(cam.second - lastY) >= stepY
                if (!due) return@collect
                lastX = cam.first
                lastY = cam.second
                val view = worldBounds(cam.first, cam.second, vw, vh)
                val fresh = layoutLattice(geometry, total, view, overscanX, overscanY, metrics)
                val keep = WallBounds(
                    view.left - keepX, view.top - keepY, view.right + keepX, view.bottom + keepY
                )
                if (store.merge(fresh, keep)) instances = store.snapshot()
            }
        }

        // 进场：把正在播放的那首歌的某一份副本摆到屏幕中间（不做动画，否则像莫名漂移）
        var seeded by remember(geometry, total) { mutableStateOf(false) }
        LaunchedEffect(geometry, total, currentSongId) {
            if (seeded) return@LaunchedEffect
            seeded = true
            if (currentSongId == 0L) return@LaunchedEffect
            val index = songs.indexOfFirst { it.id == currentSongId }
            if (index < 0) return@LaunchedEffect
            val instance = locateNearestInstance(
                geometry, total, index,
                (vw / 2f - camera.x), (vh / 2f - camera.y), metrics
            ) ?: return@LaunchedEffect
            val (tx, ty) = cameraToCenter(instance.rect, vw, vh)
            camera.set(tx, ty)
        }

        // 展开：相机把放大后的那张卡送到正中（0.42s，缓出，和上游一致）
        LaunchedEffect(active?.instanceId, geometry) {
            val current = active ?: return@LaunchedEffect
            val rect = layoutExpandedBlock(geometry, total, current, metrics)[current.instanceId]
                ?: return@LaunchedEffect
            val (tx, ty) = cameraToCenter(rect, vw, vh)
            if (reduceMotion) camera.set(tx, ty)
            else camera.panTo(scope, tx, ty, tween(420, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        }

        // 跟着播放走：展开着的时候切歌（点下一首 / 自动切 / 通知栏切），
        // 窗口不能留在原地换一张封面 —— 原版是「正在播放的那张卡长大」。
        // 所以这里把窗口整体交给新歌离窗口最近的那张卡：旧卡缩回自己的格子，
        // 新卡按让位表吃掉 6×6，上面的相机 effect 再把它送到正中。
        val follow = rememberUpdatedState(onFollowActive)
        LaunchedEffect(currentSongId, active?.instanceId, geometry) {
            val current = active ?: return@LaunchedEffect
            if (currentSongId == 0L) return@LaunchedEffect
            val index = songs.indexOfFirst { it.id == currentSongId }
            if (index < 0 || index == current.queueIndex) return@LaunchedEffect
            val rect = layoutExpandedBlock(geometry, total, current, metrics)[current.instanceId]
                ?: current.rect
            val next = followInstance(geometry, total, index, rect, metrics) ?: return@LaunchedEffect
            if (next.instanceId != current.instanceId) follow.value(next)
        }

        val reflows = remember(active?.instanceId, geometry) {
            active?.let { layoutExpandedBlock(geometry, total, it, metrics) } ?: emptyMap()
        }
        // 展开中的卡可能已经被裁出当前实例集（一边展开一边拖走），补回来
        val drawn = remember(instances, active) {
            if (active != null && instances.none { it.instanceId == active.instanceId }) instances + active
            else instances
        }

        // 命中测试要用「重排后的矩形」：展开卡的实际位置和它在世界里的原槽位不是一回事，
        // 拿原槽位判定会点不中（或者点到邻居身上）
        val hitTargets = remember(drawn, reflows) {
            drawn.map { instance -> instance to (reflows[instance.instanceId] ?: instance.rect) }
        }
        val hit = rememberUpdatedState(hitTargets)
        // pointerInput(Unit) 只挂一次，闭包里会是「第一次组合时」的回调；
        // 页面重组合出来的新回调（比如切了模式）必须从这层壳里取，否则点了还是老行为
        val tapCard = rememberUpdatedState(onTapCard)
        val tapBlank = rememberUpdatedState(onTapBlank)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 世界坐标是 dp（卡片 offset 用 dp、视口用 maxWidth/maxHeight），
                    // 而触摸事件和 graphicsLayer 的 translation 是 px。density 只在这里换算一次，
                    // 千万不要把两套单位混着加减 —— 混着用会让位移和绘制范围差一个 density 倍。
                    val density = this.density
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture
                        camera.stop()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        var travelled = Offset.Zero
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            val delta = change.position - last
                            last = change.position
                            if (!change.pressed) break
                            if (!dragging) {
                                travelled += delta
                                if (travelled.getDistance() > viewConfiguration.touchSlop) dragging = true
                            }
                            if (dragging) {
                                tracker.addPosition(change.uptimeMillis, change.position)
                                camera.move(delta.x / density, delta.y / density)
                                change.consume()
                            }
                        }
                        if (dragging) {
                            val v = tracker.calculateVelocity()
                            if (kotlin.math.hypot(v.x, v.y) > 90f * density) {
                                camera.fling(scope, v.x / density, v.y / density)
                            }
                        } else {
                            val target = hitInstance(
                                hit.value,
                                down.position.x, down.position.y,
                                density, camera.x, camera.y
                            )
                            if (target != null) tapCard.value(target) else tapBlank.value()
                        }
                    }
                }
        ) {
            // 两团柔光：上游 .lattice-root 的背景就是 accent / secondary 两处径向渐变
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(PColors.Accent.copy(alpha = 0.13f), Color.Transparent),
                            center = Offset(size.width * 0.14f, size.height * 0.12f),
                            radius = size.width * 0.62f
                        )
                    )
                }
            )
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0xFF6A48FF).copy(alpha = 0.16f), Color.Transparent),
                            center = Offset(size.width * 0.88f, size.height * 0.84f),
                            radius = size.width * 0.68f
                        )
                    )
                }
            )

            // 边缘压暗（上游 .lattice-root.has-vignette::before）：
            // 中间完全透明、78% 之后渐到 52% 黑。墙是「看封面」的表面，暗角必须很浅，
            // 只负责把视线收到中间，别和封面本身抢。
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            0.78f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.52f),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = kotlin.math.max(size.width, size.height) * 0.72f
                        )
                    )
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = camera.x.dp.toPx()
                        translationY = camera.y.dp.toPx()
                    }
            ) {
                drawn.forEach { instance ->
                    // 实例池只增不删，墙上这份列表可能比它新：越界的实例直接不画，别让下标炸掉整面墙
                    val song = songs.getOrNull(instance.queueIndex) ?: return@forEach
                    key(instance.instanceId) {
                        val rect = reflows[instance.instanceId] ?: instance.rect
                        val isActive = active?.instanceId == instance.instanceId
                        // 展开着的时候切歌（点下一首 / 自动切 / 通知栏切）：窗口留在原地，
                        // 但里面的封面、标题、序号、歌词全部换成新歌。
                        // 之前这里一直用 instance.queueIndex 取歌，instance 是「点开那一刻」的那张卡，
                        // 不会跟着播放变 —— 表现就是「窗口没反应、歌词还是上一首的」。
                        val shownSong = if (isActive) {
                            songs.firstOrNull { it.id == currentSongId } ?: song
                        } else song
                        val shownIndex = if (isActive) {
                            songs.indexOfFirst { it.id == shownSong.id }
                                .takeIf { it >= 0 } ?: instance.queueIndex
                        } else instance.queueIndex
                        WallPoster(
                            instance = instance,
                            index = shownIndex,
                            rect = rect,
                            song = shownSong,
                            art = art,
                            lyricsOf = lyricsOf,
                            playback = playback,
                            isCurrent = shownSong.id == currentSongId,
                            isActive = isActive,
                            reduceMotion = reduceMotion,
                            onOpenPlayer = onOpenPlayer,
                            onTogglePlay = onTogglePlay,
                            onNext = onNext,
                            onPrev = onPrev
                        )
                    }
                }
            }
        }
    }
}

/**
 * 已经算出来的卡片池。
 *
 * 墙在平移时只会「进新卡 / 淘汰远卡」，中间那些一直看得见的卡必须原地留着 ——
 * 每次重算都整批替换的话，Compose 会把它们全部拆掉重组，封面重新走一遍异步加载，
 * 表现就是「划着划着突然没图了」。
 *
 * 用 LinkedHashMap 保持插入顺序：顺序不变，Compose 的 key 匹配就不会挪动槽位。
 */
internal class WallInstanceStore {
    private val live = LinkedHashMap<String, WallInstance>()

    fun seed(initial: List<WallInstance>): List<WallInstance> {
        live.clear()
        for (instance in initial) live[instance.instanceId] = instance
        return snapshot()
    }

    /** 并入新算的卡片，丢掉越过保留区的。返回内容是否真的变了（没变就别通知 Compose） */
    fun merge(fresh: List<WallInstance>, keep: WallBounds): Boolean {
        var changed = false
        for (instance in fresh) {
            if (live.put(instance.instanceId, instance) == null) changed = true
        }
        if (live.size > fresh.size) {
            val it = live.values.iterator()
            while (it.hasNext()) {
                if (!keep.intersects(it.next().rect)) {
                    it.remove()
                    changed = true
                }
            }
        }
        return changed
    }

    fun snapshot(): List<WallInstance> = live.values.toList()
}

/**
 * 相机：世界原点在屏幕上的位移。用普通的 float state 而不是 Animatable ——
 * 拖动发生在 `AwaitPointerEventScope`（受限挂起作用域）里，只能改 state，不能调 Animatable 的挂起方法。
 * 平移/惯性都跑在外面的协程里，改的还是同一个 state，所以每帧只更新一次 layer 变换。
 */
private class WallCamera {
    var x by mutableFloatStateOf(0f)
        private set
    var y by mutableFloatStateOf(0f)
        private set
    private var job: Job? = null

    fun stop() {
        job?.cancel()
        job = null
    }

    fun set(nx: Float, ny: Float) {
        stop()
        x = nx
        y = ny
    }

    fun move(dx: Float, dy: Float) {
        x += dx
        y += dy
    }

    fun panTo(scope: CoroutineScope, tx: Float, ty: Float, spec: AnimationSpec<Float>) {
        stop()
        val sx = x
        val sy = y
        job = scope.launch {
            animate(0f, 1f, animationSpec = spec) { p, _ ->
                x = sx + (tx - sx) * p
                y = sy + (ty - sy) * p
            }
        }
    }

    /**
     * 松手惯性：按真实帧间隔积分，每秒把速度衰减到 2%（手感接近系统的 splineBasedDecay）。
     * 不用 Compose 的 decay 是因为它要 FloatDecayAnimationSpec / AnimationState，
     * 而这里的位移是两个独立轴，自己积分反而更直接。
     */
    fun fling(scope: CoroutineScope, vx: Float, vy: Float) {
        stop()
        job = scope.launch {
            var vxNow = vx
            var vyNow = vy
            var last = 0L
            while (kotlin.math.hypot(vxNow, vyNow) > 40f) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 1f / 60f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    last = now
                    x += vxNow * dt
                    y += vyNow * dt
                    val k = Math.pow(0.02, dt.toDouble()).toFloat()
                    vxNow *= k
                    vyNow *= k
                }
            }
        }
    }
}

/**
 * 一张封面卡。位置/尺寸用 spring 跟随（只有展开收起的重排会让它动），
 * 浏览时的平移交给外层 world 的 layer，卡片本身不重组。
 */
@Composable
private fun WallPoster(
    instance: WallInstance,
    /** 徽标上显示的序号；展开卡切歌后要跟着新歌走，所以不能直接用 instance.queueIndex */
    index: Int,
    rect: WallRect,
    song: SongEntity,
    art: ArtRepository,
    lyricsOf: suspend (SongEntity) -> List<LyricsRepository.Stanza>,
    playback: State<PlaybackUi>,
    isCurrent: Boolean,
    isActive: Boolean,
    reduceMotion: Boolean,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val spec: AnimationSpec<Float> =
        if (reduceMotion) snap() else spring(stiffness = 320f, dampingRatio = 0.86f)
    val x by animateFloatAsState(rect.x, spec, label = "x")
    val y by animateFloatAsState(rect.y, spec, label = "y")
    val w by animateFloatAsState(rect.width, spec, label = "w")
    val h by animateFloatAsState(rect.height, spec, label = "h")

    // 同步从内存缓存起手：这张卡如果是刚进组合的，第一帧就有图，不会露一个空白框
    var cover by remember(song.id) { mutableStateOf(ArtImageCache.get(song.id)) }
    LaunchedEffect(song.id, art.revision) {
        val file = runCatching { art.artFor(song) }.getOrNull() ?: return@LaunchedEffect
        // 解码放到 IO：一次补算可能同时有几十张新卡，在主线程解会把帧卡住
        val loaded = withContext(Dispatchers.IO) { ArtImageCache.load(song.id, file) }
        if (loaded != null) cover = loaded
    }
    val shape = RoundedCornerShape(0.dp)
    // 上游 .lattice-poster-copy：标题字号跟着卡片宽度走（不是固定小字），
    // 行距压到 0.96、字距收紧，才有「专辑墙」那种大字排版的味道
    val titleSp = (w * 0.150f).coerceIn(12.5f, 25f)
    val titleLine = titleSp * 0.99f
    val artistSp = (w * 0.062f).coerceIn(9.5f, 13f)
    val pad = (w * 0.055f).coerceIn(6f, 17f)

    Box(
        modifier = Modifier
            .offset(x.dp, y.dp)
            .size(w.dp, h.dp)
            .zIndex(if (isActive) 20f else 0f)
            .background(posterBrush(song))
            .border(
                width = if (isCurrent) 2.dp else 0.dp,
                color = if (isCurrent) PColors.Accent else Color.Transparent,
                shape = shape
            )
    ) {
        if (cover != null) {
            Image(
                bitmap = cover!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isActive) {
            ExpandedPoster(
                song = song,
                lyricsOf = lyricsOf,
                playback = playback,
                onOpenPlayer = onOpenPlayer,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onPrev = onPrev,
                widthDp = w
            )
        } else {
            // 底部压暗：上游 .lattice-poster-shade 的三段渐变（34% 处 5%、56% 处 24%、底部 92%）
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.05f),
                        0.34f to Color.Black.copy(alpha = 0.05f),
                        0.56f to Color.Black.copy(alpha = 0.24f),
                        1f to Color.Black.copy(alpha = 0.92f),
                        startY = 0f, endY = Float.POSITIVE_INFINITY
                    )
                )
            )
            // 左上角一道极淡的高光：卡片不再是「一张扁平的图」，有一点点被灯照到的层次
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.10f),
                        0.42f to Color.Transparent
                    )
                )
            )

            // 序号徽标：上游 11px / 800 字重 / 0.12em 字距，深色底不用模糊（模糊每张卡一层，最贵）
            Text(
                text = if (isCurrent) "正在播放 · ${index + 1}"
                else (index + 1).toString().padStart(2, '0'),
                color = if (isCurrent) PColors.Accent else Color.White.copy(alpha = 0.86f),
                fontSize = (w * 0.055f).coerceIn(8f, 11f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = if (isCurrent) 0.78f else 0.62f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )

            if (h >= 60f) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = pad.dp, vertical = (pad * 0.85f).dp)
                ) {
                    Text(
                        song.title,
                        color = Color.White,
                        fontSize = titleSp.sp,
                        // 紧行距 + 负字距：上游 clamp(22px,2vw,36px)/0.96/-0.045em
                        lineHeight = titleLine.sp,
                        letterSpacing = (-titleSp * 0.035f).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (h >= 120f) 3 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (h >= 88f) {
                        Text(
                            song.artist,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = artistSp.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 展开态：封面铺满 + 双向压暗 + 顶部曲目信息 + 中间歌词 + 底部走带 */
@Composable
private fun ExpandedPoster(
    song: SongEntity,
    lyricsOf: suspend (SongEntity) -> List<LyricsRepository.Stanza>,
    playback: State<PlaybackUi>,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    widthDp: Float
) {
    // 上游 .is-expanded .lattice-poster-shade：底部 + 左侧两段渐变（底部给走带留可读底）
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.28f),
                0.30f to Color.Black.copy(alpha = 0.10f),
                0.62f to Color.Black.copy(alpha = 0.46f),
                1f to Color.Black.copy(alpha = 0.90f)
            )
        )
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.45f),
                0.6f to Color.Black.copy(alpha = 0.12f),
                1f to Color.Transparent
            )
        )
    )

    val pad = (widthDp * 0.07f).coerceIn(12f, 24f)

    Column(Modifier.fillMaxSize().padding(horizontal = pad.dp, vertical = pad.dp)) {
        Text(
            song.title,
            color = Color.White,
            fontSize = if (widthDp < 300f) 19.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            lineHeight = 26.sp,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            song.artist,
            color = Color.White.copy(alpha = 0.66f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        WallLyrics(
            lyricsOf = lyricsOf,
            playback = playback,
            song = song,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        WallTransport(
            playback = playback,
            onOpenPlayer = onOpenPlayer,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onPrev = onPrev
        )
    }
}

/** 一行歌词占的高度（dp）。固定值才能让「整块滑一行」严丝合缝 */
private const val LYRIC_STEP = 58f

/**
 * 展开卡中间的歌词。
 *
 * 照上游 Lattice 的「轨道」模型重做：
 * - 固定 5 个槽位（当前 ±2），换段时整块先往下偏一行蛋再弹回原位，
 *   读起来就是歌词「往上推一行」，而不是文字直接跳变；
 * - 每一行按离当前段的远近给 alpha / 缩放 / 模糊（远处虚、近处实），切换时三者一起补间；
 * - 当前行做逐字填充（卡拉 OK 式），填充进度按本段到下一段的时间轴算。
 */
@Composable
private fun WallLyrics(
    lyricsOf: suspend (SongEntity) -> List<LyricsRepository.Stanza>,
    playback: State<PlaybackUi>,
    song: SongEntity,
    modifier: Modifier = Modifier
) {
    val now = playback.value
    val stanzas by produceState(emptyList<LyricsRepository.Stanza>(), song.id) {
        value = runCatching { lyricsOf(song) }.getOrDefault(emptyList())
    }

    if (stanzas.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "暂无歌词",
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    WallLyricsBody(stanzas, now.positionMs, modifier)
}

/**
 * 歌词的纯渲染部分（不含加载）：给测试和截图用，也让「取歌词」和「画歌词」彻底分开。
 * stanzas 必须已经按时间排好；第一条的 atMs < 0 表示这是一份没有时间轴的纯文本歌词。
 */
@Composable
internal fun WallLyricsBody(
    stanzas: List<LyricsRepository.Stanza>,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    if (stanzas.isEmpty()) return
    val timed = stanzas.first().atMs >= 0
    val index = if (!timed) -1 else stanzaIndex(stanzas, positionMs)

    // 整块歌词的滑动：换段时从「上一段的位置」弹回原位
    val slide = remember { Animatable(0f) }
    var lastIndex by remember(stanzas) { mutableStateOf(index) }
    LaunchedEffect(index, stanzas) {
        val prev = lastIndex
        lastIndex = index
        if (prev == index || prev < 0 || index < 0) return@LaunchedEffect
        slide.snapTo((index - prev).coerceIn(-2, 2) * LYRIC_STEP)
        slide.animateTo(
            0f,
            tween(430, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
        )
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, slide.value.dp.roundToPx()) },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 槽位固定 5 个：越界的位置也占住高度，这样进出时整块不会「抖一下」
            for (d in -2..2) {
                key(d) {
                    val i = if (timed) index + d else d
                    val stanza = stanzas.getOrNull(i)
                    val active = timed && d == 0
                    LyricSlot(
                        text = stanza?.lines?.firstOrNull().orEmpty(),
                        sub = stanza?.lines?.getOrNull(1),
                        distance = if (timed) d else 3,
                        active = active,
                        fill = if (active) lyricFill(stanzas, i, positionMs) else 0f
                    )
                }
            }
        }
    }
}

/**
 * 单个歌词槽位：远处虚、近处实，换字时做交叉淡入淡出。
 * 缩放和模糊都走 graphicsLayer，不参与布局 —— 否则一行放大就会把上下行顶开，
 * 整块会跟着一跳一跳。
 */
@Composable
private fun LyricSlot(
    text: String,
    sub: String?,
    distance: Int,
    active: Boolean,
    fill: Float
) {
    val toneAlpha = when {
        active -> 1f
        kotlin.math.abs(distance) == 1 -> 0.52f
        kotlin.math.abs(distance) == 2 -> 0.26f
        else -> 0.15f
    }
    val toneScale = when {
        active -> 1f
        kotlin.math.abs(distance) == 1 -> 0.88f
        else -> 0.76f
    }
    val toneBlur = when {
        active -> 0f
        kotlin.math.abs(distance) == 1 -> 1.1f
        kotlin.math.abs(distance) == 2 -> 2.4f
        else -> 3.2f
    }
    val alpha by animateFloatAsState(
        toneAlpha, tween(320, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)), label = "lyA"
    )
    val scale by animateFloatAsState(
        toneScale, spring(dampingRatio = 1f, stiffness = 190f), label = "lyS"
    )
    val blurR by animateFloatAsState(toneBlur, tween(340), label = "lyB")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LYRIC_STEP.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .blur(blurR.dp)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        if (text.isEmpty()) return@Box
        // 换行时旧字往上飘出、新字从下方顶上来 —— 原版是 Pixi 的轨道推进，
        // 单纯淡入淡出看起来像「文字原地闪了一下」，这是「不像原版」的主要观感差。
        AnimatedContent(
            targetState = text to sub,
            transitionSpec = {
                (fadeIn(tween(300, delayMillis = 70)) +
                    slideInVertically(
                        tween(360, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
                    ) { it * 3 / 5 })
                    .togetherWith(
                        fadeOut(tween(200)) + slideOutVertically(tween(320)) { -it * 3 / 5 }
                    )
            },
            label = "lyX"
        ) { pair ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (active) {
                    KaraokeText(pair.first, fill)
                } else {
                    Text(
                        pair.first,
                        color = Color.White,
                        fontSize = if (distance == 1) 18.sp else 15.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        lineHeight = 24.sp,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                    )
                }
                pair.second?.let {
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

/** 当前行：底稿半透明，逐字填充的那层用白色盖上去（卡拉 OK 效果），并带一点辉光 */
@Composable
private fun KaraokeText(text: String, progress: Float) {
    val p = progress.coerceIn(0f, 1f)
    val shadow = androidx.compose.ui.graphics.Shadow(
        color = PColors.Accent.copy(alpha = 0.45f), blurRadius = 16f
    )
    val base = androidx.compose.ui.text.TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    // 逐字填充要按「这一行字自己的宽度」算，不能拿整块宽度算 ——
    // 一行字只占卡片中间一小段，用整块宽度算的话半句就「点亮完了」，看着像没填充。
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val textW = remember(text, maxPx) {
            runCatching {
                measurer.measure(
                    androidx.compose.ui.text.AnnotatedString(text),
                    base,
                    maxLines = 2,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxPx)
                ).size.width.toFloat()
            }.getOrDefault(0f)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text,
                color = Color.White.copy(alpha = 0.42f),
                style = base,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
            Text(
                text,
                color = Color.White,
                style = base.copy(shadow = shadow),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .drawWithContent {
                        if (p <= 0f) return@drawWithContent
                        if (p >= 1f) {
                            drawContent()
                            return@drawWithContent
                        }
                        // 文字是居中的：先算出文字真正的左边缘，再从左往右裁到「唱到」的位置
                        val w = if (textW > 0f) textW else size.width
                        val left = ((size.width - w) / 2f).coerceAtLeast(0f)
                        clipRect(left, 0f, left + w * p, size.height) { this@drawWithContent.drawContent() }
                    }
            )
        }
    }
}

/** 当前段已经唱了多久（0~1）：以本段与下一段的时间戳为区间 */
internal fun lyricFill(stanzas: List<LyricsRepository.Stanza>, index: Int, positionMs: Long): Float {
    if (index !in stanzas.indices) return 0f
    val start = stanzas[index].atMs
    if (start < 0) return 0f
    val end = stanzas.getOrNull(index + 1)?.atMs ?: (start + 4_000L)
    val span = (end - start).coerceAtLeast(400L)
    return ((positionMs - start).toFloat() / span).coerceIn(0f, 1f)
}

/** 展开卡底部：细进度条 + 上一首/播放/下一首 + 打开播放页 */
@Composable
private fun WallTransport(
    playback: State<PlaybackUi>,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val now = playback.value
    val duration = now.durationMs.coerceAtLeast(1L)
    val progress = (now.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(PColors.Accent)
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${formatClock(now.positionMs)} / ${formatClock(now.durationMs)}",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 10.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                WallIconButton(onPrev, Icons.Rounded.SkipPrevious, "上一首", 32.dp, 19.dp, Color.White)
                WallIconButton(
                    onTogglePlay,
                    if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (now.isPlaying) "暂停" else "播放",
                    38.dp, 25.dp, Color.White
                )
                WallIconButton(onNext, Icons.Rounded.SkipNext, "下一首", 32.dp, 19.dp, Color.White)
                WallIconButton(onOpenPlayer, Icons.Rounded.Fullscreen, "打开播放页", 34.dp, 19.dp, PColors.Accent)
            }
        }
    }
}

/** 展开卡底部的小按钮：IconButton 自带 48dp 最小可点区域，会把卡底撑破，这里自己画 */
@Composable
private fun WallIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    tint: Color
) {
    Box(
        modifier = Modifier.size(size).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** 当前应当高亮的段落下标（二分；纯文本歌词返回 -1） */
internal fun stanzaIndex(stanzas: List<LyricsRepository.Stanza>, positionMs: Long): Int {
    if (stanzas.isEmpty() || stanzas.first().atMs < 0L) return -1
    var lo = 0
    var hi = stanzas.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (stanzas[mid].atMs <= positionMs) {
            ans = mid
            lo = mid + 1
        } else hi = mid - 1
    }
    return ans
}

/**
 * 无封面时的封面底色。
 *
 * 上游用的是「字符码求和 % 360」——在 uuid 上够用，在我们这种自增数字 id 上是灾难：
 * 相邻 id 只差 1，色相也只差 1 度，整面墙会挤在同一种橄榄绿里（就是「颜色太单调、分布不对」）。
 * 这里换成 FNV-1a，并把标题、艺人一起搅进哈希：相邻两首歌的哈希值天差地别，色相铺满整圈。
 */
private fun posterBrush(song: SongEntity): Brush {
    var h = -0x7ee3623b            // 2166136261，FNV-1a 偏移基数
    for (ch in song.title) { h = (h xor ch.code) * 0x01000193 }
    h = (h xor 0x1f) * 0x01000193
    for (ch in song.artist) { h = (h xor ch.code) * 0x01000193 }
    h = (h xor 0x1f) * 0x01000193
    for (ch in song.id.toString()) { h = (h xor ch.code) * 0x01000193 }
    val u = h.toLong() and 0xFFFFFFFFL
    val hue = (u % 360L).toFloat()
    // 亮度和饱和度也由哈希的另外几位决定：同色相的两张卡也不会是一模一样的渐变
    val sat = 0.54f + ((u ushr 9) % 26L).toFloat() / 100f
    val lift = ((u ushr 17) % 22L).toFloat() / 100f
    return Brush.linearGradient(
        colors = listOf(
            hsl(hue, sat, 0.50f + lift * 0.45f),
            hsl((hue + 26f) % 360f, sat * 0.94f, 0.30f + lift * 0.30f),
            hsl((hue + 48f) % 360f, sat * 0.80f, 0.12f + lift * 0.14f)
        ),
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs(((h / 60f) % 2f) - 1f))
    val m = l - c / 2f
    return when {
        h < 60f -> Color(c + m, x + m, m)
        h < 120f -> Color(x + m, c + m, m)
        h < 180f -> Color(m, c + m, x + m)
        h < 240f -> Color(m, x + m, c + m)
        h < 300f -> Color(x + m, m, c + m)
        else -> Color(c + m, m, x + m)
    }
}
