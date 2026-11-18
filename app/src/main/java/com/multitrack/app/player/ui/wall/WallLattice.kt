package com.multitrack.app.player.ui.wall

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 无限拼贴墙的几何内核 —— 上游 `vendor-src/folia/src/components/app/lattice/{layout,blockTemplates}.ts`
 * 的直译（oirge/Mineradio 二创里的 Folia）。
 *
 * 和上一版最大的区别：世界是**二维无限**的。以前把「一块 = 屏宽」竖向排成一列，只能上下滚；
 * 这里世界没有边界，块坐标可正可负，视口在任意方向平移都能拼出新内容 —— 所以上下左右都能一直翻。
 *
 * 世界是「单元块 12×8 = 96 个格子」的重复，一块里精确塞下 12 张封面（模板 4 套 × 4 种镜像）。
 * 展开一张卡时它吃掉 6×6 的齿位，块内其余 11 张按 [BLOCK_REFLOWS] 重新咬合，块的外框不变，
 * 所以不会把邻居挤开、也不会塌出空洞。
 */

internal const val BLOCK_COLS = 12
internal const val BLOCK_ROWS = 8
internal const val SLOTS_PER_BLOCK = 12

/** 展开卡固定吃 6×6：唯一一个「每个槽位都恰好有解」的齿位（与上游一致） */
internal const val EXPANSION_COLS = 6
internal const val EXPANSION_ROWS = 6

internal data class BlockSlot(val x: Int, val y: Int, val cols: Int, val rows: Int)

internal data class WallMetrics(val cellSize: Float, val gap: Float) {
    val pitch: Float get() = cellSize + gap
}

internal data class WallBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(rect: WallRect): Boolean =
        rect.x < right && rect.x + rect.width > left && rect.y < bottom && rect.y + rect.height > top
}

internal data class WallRect(val x: Float, val y: Float, val width: Float, val height: Float)

/** 一块网格（重复单元）里含多少块 */
internal data class LatticeGeometry(
    val blocksPerRow: Int,
    val blockRows: Int,
    val cellSlots: Int,
    val cellWidth: Float,
    val cellHeight: Float,
    val blockWidth: Float,
    val blockHeight: Float
)

/** 世界里的一张卡片实例：同一首歌会在墙上出现很多次 */
internal data class WallInstance(
    val instanceId: String,
    val queueIndex: Int,
    val cellSlot: Int,
    val repeatX: Int,
    val repeatY: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val rect: WallRect get() = WallRect(x, y, width, height)
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f

    fun contains(px: Float, py: Float) = px >= x && px <= x + width && py >= y && py <= y + height
}

/** 4 套 12 张的模板，每套都精确覆盖 12×8（与上游 blockTemplates.ts 逐字一致） */
private val TEMPLATES: List<List<BlockSlot>> = listOf(
    listOf(
        BlockSlot(0, 0, 3, 2), BlockSlot(3, 0, 6, 4), BlockSlot(9, 0, 3, 2),
        BlockSlot(0, 2, 3, 2), BlockSlot(9, 2, 3, 4), BlockSlot(0, 4, 4, 4),
        BlockSlot(4, 4, 2, 2), BlockSlot(6, 4, 3, 2), BlockSlot(4, 6, 2, 2),
        BlockSlot(6, 6, 2, 2), BlockSlot(8, 6, 2, 2), BlockSlot(10, 6, 2, 2)
    ),
    listOf(
        BlockSlot(0, 0, 2, 3), BlockSlot(2, 0, 2, 3), BlockSlot(4, 0, 2, 3),
        BlockSlot(6, 0, 6, 4), BlockSlot(0, 3, 6, 2), BlockSlot(6, 4, 2, 4),
        BlockSlot(8, 4, 2, 4), BlockSlot(10, 4, 2, 2), BlockSlot(0, 5, 2, 3),
        BlockSlot(2, 5, 2, 3), BlockSlot(4, 5, 2, 3), BlockSlot(10, 6, 2, 2)
    ),
    listOf(
        BlockSlot(0, 0, 6, 4), BlockSlot(6, 0, 6, 3), BlockSlot(6, 3, 4, 3),
        BlockSlot(10, 3, 2, 2), BlockSlot(0, 4, 4, 2), BlockSlot(4, 4, 2, 2),
        BlockSlot(10, 5, 2, 3), BlockSlot(0, 6, 2, 2), BlockSlot(2, 6, 2, 2),
        BlockSlot(4, 6, 2, 2), BlockSlot(6, 6, 2, 2), BlockSlot(8, 6, 2, 2)
    ),
    listOf(
        BlockSlot(0, 0, 2, 2), BlockSlot(2, 0, 2, 2), BlockSlot(4, 0, 4, 3),
        BlockSlot(8, 0, 2, 4), BlockSlot(10, 0, 2, 4), BlockSlot(0, 2, 2, 3),
        BlockSlot(2, 2, 2, 2), BlockSlot(4, 3, 4, 3), BlockSlot(2, 4, 2, 2),
        BlockSlot(8, 4, 4, 4), BlockSlot(0, 5, 2, 3), BlockSlot(2, 6, 6, 2)
    )
)

/** 镜像保持槽位顺序，所以一张卡在 4 种朝向下始终是同一个下标 */
private val REFLECTIONS: List<(BlockSlot) -> BlockSlot> = listOf(
    { it },
    { it.copy(x = BLOCK_COLS - it.x - it.cols) },
    { it.copy(y = BLOCK_ROWS - it.y - it.rows) },
    { it.copy(x = BLOCK_COLS - it.x - it.cols, y = BLOCK_ROWS - it.y - it.rows) }
)

private val ORIENTED_TEMPLATES: List<List<BlockSlot>> =
    TEMPLATES.flatMap { t -> REFLECTIONS.map { r -> t.map(r) } }

private val ORIENTED_REFLOWS: List<List<List<BlockSlot>>> =
    BLOCK_REFLOWS.flatMap { perSlot -> REFLECTIONS.map { r -> perSlot.map { slots -> slots.map(r) } } }

/** 坐标哈希：选镜像，让整面墙不会看出周期性的网格 */
private fun mixBlockCoords(blockColumn: Int, blockRow: Int): Int {
    var v = (blockColumn * -0x61c88647) xor (blockRow * -0x7a143589)
    v = v xor (v ushr 15)
    v *= 0x2c1b3c6d
    v = v xor (v ushr 12)
    return v
}

/**
 * 模板沿对角线轮换（跨列 +1、跨行 +2），相邻块永远不会撞模板；
 * 镜像交给哈希，所以看不出格子。
 */
private fun blockOrientation(blockColumn: Int, blockRow: Int): Int {
    val count = TEMPLATES.size
    val template = ((blockColumn + blockRow * 2) % count + count) % count
    return template * REFLECTIONS.size + (mixBlockCoords(blockColumn, blockRow) and (REFLECTIONS.size - 1))
}

internal fun blockTemplate(blockColumn: Int, blockRow: Int): List<BlockSlot> =
    ORIENTED_TEMPLATES[blockOrientation(blockColumn, blockRow)]

/** 这一块里 `expandedSlot` 展开后，12 张卡各自的落位 */
internal fun blockReflow(blockColumn: Int, blockRow: Int, expandedSlot: Int): List<BlockSlot>? {
    if (expandedSlot < 0 || expandedSlot >= SLOTS_PER_BLOCK) return null
    return ORIENTED_REFLOWS[blockOrientation(blockColumn, blockRow)][expandedSlot]
}

// 重复单元的长宽比。小于 1 会让队列纵向比横向更早重复，看着像条纹；轻微横向偏置能让重复更均匀。
private const val FIELD_ASPECT = 2.2f

/** 一次最多画多少张（与上游同为 400）：正常视口只有百来张，这个数只是兜住极端情况的一帧开销 */
internal const val MAX_RENDERED_INSTANCES = 400

internal fun latticeGeometry(totalEntries: Int, metrics: WallMetrics): LatticeGeometry {
    val pitch = metrics.pitch
    val blockWidth = BLOCK_COLS * pitch
    val blockHeight = BLOCK_ROWS * pitch
    val blocks = max(1, ceil(max(0, totalEntries).toFloat() / SLOTS_PER_BLOCK).toInt())
    val blocksPerRow = if (blocks <= 1) 1 else max(
        1,
        (sqrt(FIELD_ASPECT * blocks * BLOCK_ROWS / BLOCK_COLS.toFloat())).roundToInt()
    )
    val blockRows = ceil(blocks.toFloat() / blocksPerRow).toInt()
    return LatticeGeometry(
        blocksPerRow = blocksPerRow,
        blockRows = blockRows,
        cellSlots = blocksPerRow * blockRows * SLOTS_PER_BLOCK,
        cellWidth = blocksPerRow * blockWidth,
        cellHeight = blockRows * blockHeight,
        blockWidth = blockWidth,
        blockHeight = blockHeight
    )
}

/**
 * 造出「绝对块坐标 (column,row) 的第 slotIndex 个槽」上的卡片。
 * 模板取块在**重复单元内**的位置（这才是周期性），单元下标只贡献平移。
 */
private fun buildInstance(
    geometry: LatticeGeometry,
    totalEntries: Int,
    column: Int,
    row: Int,
    slotIndex: Int,
    metrics: WallMetrics
): WallInstance {
    val repeatX = floor(column.toFloat() / geometry.blocksPerRow).toInt()
    val repeatY = floor(row.toFloat() / geometry.blockRows).toInt()
    val localColumn = column - repeatX * geometry.blocksPerRow
    val localRow = row - repeatY * geometry.blockRows
    val cellSlot = (localRow * geometry.blocksPerRow + localColumn) * SLOTS_PER_BLOCK + slotIndex
    val slot = blockTemplate(localColumn, localRow)[slotIndex]
    val pitch = metrics.pitch
    return WallInstance(
        instanceId = "$cellSlot:$repeatX:$repeatY",
        // 单元装的是整块，容量往往大于曲目数；取模让余下的位置也有歌，而不是留一条每单元都重现的空洞
        queueIndex = if (totalEntries > 0) cellSlot % totalEntries else 0,
        cellSlot = cellSlot,
        repeatX = repeatX,
        repeatY = repeatY,
        x = repeatX * geometry.cellWidth + localColumn * geometry.blockWidth + slot.x * pitch,
        y = repeatY * geometry.cellHeight + localRow * geometry.blockHeight + slot.y * pitch,
        width = slot.cols * pitch - metrics.gap,
        height = slot.rows * pitch - metrics.gap
    )
}

/**
 * 视口里（含 overscan）能看到的卡片。开销只跟屏幕上的数量有关，跟曲库长度无关。
 *
 * 横竖分开给余量：屏幕是瘦长的，用同一个值要么横向白留一大片、要么纵向不够。
 */
internal fun layoutLattice(
    geometry: LatticeGeometry,
    totalEntries: Int,
    bounds: WallBounds,
    overscanX: Float,
    overscanY: Float,
    metrics: WallMetrics
): List<WallInstance> {
    if (totalEntries <= 0 || geometry.blockWidth <= 0f || geometry.blockHeight <= 0f) return emptyList()
    val view = WallBounds(
        bounds.left - overscanX,
        bounds.top - overscanY,
        bounds.right + overscanX,
        bounds.bottom + overscanY
    )
    val fromColumn = floor(view.left / geometry.blockWidth).toInt()
    val toColumn = floor(view.right / geometry.blockWidth).toInt()
    val fromRow = floor(view.top / geometry.blockHeight).toInt()
    val toRow = floor(view.bottom / geometry.blockHeight).toInt()
    val out = ArrayList<WallInstance>(64)
    var row = fromRow
    while (row <= toRow) {
        var column = fromColumn
        while (column <= toColumn) {
            var slotIndex = 0
            while (slotIndex < SLOTS_PER_BLOCK) {
                val instance = buildInstance(geometry, totalEntries, column, row, slotIndex, metrics)
                if (view.intersects(instance.rect)) {
                    out.add(instance)
                    if (out.size >= MAX_RENDERED_INSTANCES) return out
                }
                slotIndex++
            }
            column++
        }
        row++
    }
    return out
}

/** 单测/纯几何用：两个方向给同样的余量 */
internal fun layoutLattice(
    geometry: LatticeGeometry,
    totalEntries: Int,
    bounds: WallBounds,
    overscan: Float,
    metrics: WallMetrics
): List<WallInstance> = layoutLattice(geometry, totalEntries, bounds, overscan, overscan, metrics)

/**
 * 离某个世界坐标点最近的那份 `queueIndex`。
 * 一首歌在一个单元里可能占好几个槽，每个槽都按最近的重复次数试探一遍。
 */
internal fun locateNearestInstance(
    geometry: LatticeGeometry,
    totalEntries: Int,
    queueIndex: Int,
    pointX: Float,
    pointY: Float,
    metrics: WallMetrics
): WallInstance? {
    if (totalEntries <= 0 || queueIndex < 0 || queueIndex >= totalEntries) return null
    var best: WallInstance? = null
    var bestDistance = Float.MAX_VALUE
    var cellSlot = queueIndex
    while (cellSlot < geometry.cellSlots) {
        val blockInCell = cellSlot / SLOTS_PER_BLOCK
        val localColumn = blockInCell % geometry.blocksPerRow
        val localRow = blockInCell / geometry.blocksPerRow
        val slotIndex = cellSlot % SLOTS_PER_BLOCK
        val base = buildInstance(geometry, totalEntries, localColumn, localRow, slotIndex, metrics)
        val repeatX = ((pointX - base.x - base.width / 2f) / geometry.cellWidth).roundToInt()
        val repeatY = ((pointY - base.y - base.height / 2f) / geometry.cellHeight).roundToInt()
        val candidate = buildInstance(
            geometry, totalEntries,
            repeatX * geometry.blocksPerRow + localColumn,
            repeatY * geometry.blockRows + localRow,
            slotIndex, metrics
        )
        val dx = candidate.centerX - pointX
        val dy = candidate.centerY - pointY
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = candidate
        }
        cellSlot += totalEntries
    }
    return best
}

/**
 * 展开卡所在那一块里，12 张卡的新落位。块的外框不变，所以块外的一切都不动。
 * key = instanceId
 */
internal fun layoutExpandedBlock(
    geometry: LatticeGeometry,
    totalEntries: Int,
    active: WallInstance,
    metrics: WallMetrics
): Map<String, WallRect> {
    if (totalEntries <= 0) return emptyMap()
    val blockInCell = active.cellSlot / SLOTS_PER_BLOCK
    val localColumn = blockInCell % geometry.blocksPerRow
    val localRow = blockInCell / geometry.blocksPerRow
    val expandedSlot = active.cellSlot % SLOTS_PER_BLOCK
    val reflow = blockReflow(localColumn, localRow, expandedSlot) ?: return emptyMap()

    val pitch = metrics.pitch
    val originX = active.repeatX * geometry.cellWidth + localColumn * geometry.blockWidth
    val originY = active.repeatY * geometry.cellHeight + localRow * geometry.blockHeight

    val out = HashMap<String, WallRect>(SLOTS_PER_BLOCK * 2)
    var slotIndex = 0
    while (slotIndex < SLOTS_PER_BLOCK) {
        val slot = reflow[slotIndex]
        val cellSlot = blockInCell * SLOTS_PER_BLOCK + slotIndex
        out["$cellSlot:${active.repeatX}:${active.repeatY}"] = WallRect(
            x = originX + slot.x * pitch,
            y = originY + slot.y * pitch,
            width = slot.cols * pitch - metrics.gap,
            height = slot.rows * pitch - metrics.gap
        )
        slotIndex++
    }
    return out
}

/**
 * 切歌之后，展开窗口应该落在哪张卡上。
 *
 * 原版 Folia 的「正在播放」是一张会自己长大的卡：换了歌，长大的地方也跟着换，
 * 而不是让原来那张卡留在原地、只把里面的封面换掉 —— 后者看上去就像「窗口根本没反应」。
 * 这里取「新歌离当前窗口最近的那一份」，窗口因此只会挪到隔壁，不会突然飞到屏幕另一头。
 */
internal fun followInstance(
    geometry: LatticeGeometry,
    totalEntries: Int,
    queueIndex: Int,
    from: WallRect,
    metrics: WallMetrics
): WallInstance? = locateNearestInstance(
    geometry,
    totalEntries,
    queueIndex,
    from.x + from.width / 2f,
    from.y + from.height / 2f,
    metrics
)

/**
 * 视口外的渲染余量，单位是世界单位（dp）。
 *
 * 上游 `PosterWall.tsx` 是 OVERSCAN = 500、cellSize = 128 —— 也就是四边各多算约 3.9 格。
 * 按「格」而不是按「屏」取，是因为竖屏又瘦又长：按屏算的话纵向余量会大到离谱、横向却不够，
 * 斜着划的时候正好从角上漏出去（这就是之前「某些角度有空缺」的直接原因）。
 */
internal fun overscanFor(metrics: WallMetrics): Float = metrics.cellSize * 3.9f

/** 已画好的卡保留到多远：比渲染边界再外扩一半，回头划时不用重新解码封面 */
internal fun keepFor(metrics: WallMetrics): Float = overscanFor(metrics) * 1.6f

/** 相机走过这么远才补算一遍（约一格二，远小于余量，任何甩动都追不上） */
internal fun refineStepFor(metrics: WallMetrics): Float = metrics.cellSize * 1.2f

/** 展开卡正好占 6×6 齿位，这是它在世界里的边长 */
internal fun expandedSize(metrics: WallMetrics): Float = EXPANSION_COLS * metrics.pitch - metrics.gap

/** 相机（世界原点在屏幕上的位置）→ 视口覆盖的世界矩形 */
internal fun worldBounds(cameraX: Float, cameraY: Float, viewportW: Float, viewportH: Float): WallBounds =
    WallBounds(-cameraX, -cameraY, viewportW - cameraX, viewportH - cameraY)

/** 把某张卡居中所需的相机位置 */
internal fun cameraToCenter(rect: WallRect, viewportW: Float, viewportH: Float): Pair<Float, Float> =
    (viewportW / 2f - (rect.x + rect.width / 2f)) to (viewportH / 2f - (rect.y + rect.height / 2f))

/**
 * 屏幕上的触摸点落在哪张卡上。
 *
 * 三个坐标系必须一次换清楚：手指位置是 **px**，相机和卡片是世界 **dp**。
 * 之前直接把 px 减 dp 的相机，差了 density 倍，点左边会命中右边那几张 ——
 * 表现就是「点的和实际播放的不是同一个歌曲」。抽成纯函数是为了能算、能测，
 * 不再靠界面上一眼看上去「差不多」。
 *
 * `targets` 里存的是**重排后**的矩形（展开时卡片会换位置），顺序即绘制顺序，后面的在上层。
 */
internal fun hitInstance(
    targets: List<Pair<WallInstance, WallRect>>,
    touchX: Float,
    touchY: Float,
    density: Float,
    cameraX: Float,
    cameraY: Float
): WallInstance? {
    if (density <= 0f) return null
    val wx = touchX / density - cameraX
    val wy = touchY / density - cameraY
    var hit: WallInstance? = null
    for ((instance, rect) in targets) {
        if (wx >= rect.x && wx <= rect.x + rect.width && wy >= rect.y && wy <= rect.y + rect.height) {
            hit = instance
        }
    }
    return hit
}

/** 精确覆盖自检用：槽位集合是否无重叠地铺满 cols×rows */
internal fun coversExactly(slots: List<BlockSlot>, cols: Int, rows: Int): Boolean {
    val seen = IntArray(cols * rows)
    for (slot in slots) {
        if (slot.cols <= 0 || slot.rows <= 0) return false
        if (slot.x < 0 || slot.y < 0 || slot.x + slot.cols > cols || slot.y + slot.rows > rows) return false
        for (dy in 0 until slot.rows) for (dx in 0 until slot.cols) {
            val idx = (slot.y + dy) * cols + slot.x + dx
            if (seen[idx] != 0) return false
            seen[idx] = 1
        }
    }
    return seen.all { it == 1 }
}

/** 单测用：暴露 4 套基础模板与 48 张让位表 */
internal fun baseTemplates(): List<List<BlockSlot>> = TEMPLATES
internal fun orientedReflows(): List<List<List<BlockSlot>>> = ORIENTED_REFLOWS
