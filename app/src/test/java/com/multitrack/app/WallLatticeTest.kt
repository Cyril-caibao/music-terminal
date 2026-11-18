package com.multitrack.app

import com.multitrack.app.player.ui.wall.BLOCK_COLS
import com.multitrack.app.player.ui.wall.BLOCK_ROWS
import com.multitrack.app.player.ui.wall.EXPANSION_COLS
import com.multitrack.app.player.ui.wall.EXPANSION_ROWS
import com.multitrack.app.player.ui.wall.SLOTS_PER_BLOCK
import com.multitrack.app.player.ui.wall.WallBounds
import com.multitrack.app.player.ui.wall.WallInstance
import com.multitrack.app.player.ui.wall.WallInstanceStore
import com.multitrack.app.player.ui.wall.WallMetrics
import com.multitrack.app.player.ui.wall.baseTemplates
import com.multitrack.app.player.ui.wall.blockReflow
import com.multitrack.app.player.ui.wall.cameraToCenter
import com.multitrack.app.player.ui.wall.coversExactly
import com.multitrack.app.player.ui.wall.expandedSize
import com.multitrack.app.player.ui.wall.followInstance
import com.multitrack.app.player.ui.wall.hitInstance
import com.multitrack.app.player.ui.wall.latticeGeometry
import com.multitrack.app.player.ui.wall.layoutExpandedBlock
import com.multitrack.app.player.ui.wall.layoutLattice
import com.multitrack.app.player.ui.wall.locateNearestInstance
import com.multitrack.app.player.ui.wall.orientedReflows
import com.multitrack.app.player.ui.wall.overscanFor
import com.multitrack.app.player.ui.wall.keepFor
import com.multitrack.app.player.ui.wall.refineStepFor
import com.multitrack.app.player.ui.wall.worldBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 拼贴墙几何内核的硬约束：模板/让位表必须精确覆盖 12×8，世界必须真的无限。
 * 这些数字错一个，界面上就是一条缝或者一块空白 —— 光看截图看不出来，只能算。
 */
class WallLatticeTest {

    private val metrics = WallMetrics(cellSize = 52f, gap = 4.7f)

    @Test
    fun templatesCoverBlockExactly() {
        baseTemplates().forEachIndexed { i, slots ->
            assertEquals("模板 $i 槽位数", SLOTS_PER_BLOCK, slots.size)
            assertTrue("模板 $i 必须精确覆盖 ${BLOCK_COLS}x${BLOCK_ROWS}", coversExactly(slots, BLOCK_COLS, BLOCK_ROWS))
        }
    }

    /** 4 变体 × 12 槽 × 4 镜像 = 192 张让位表，每张都得是精确覆盖 */
    @Test
    fun everyReflowCoversBlockExactly() {
        val table = orientedReflows()
        // 4 变体 × 4 镜像 = 16 个朝向，每个朝向 12 张（下标 = 被展开的槽位）
        assertEquals(16, table.size)
        table.forEachIndexed { orientation, perSlot ->
            assertEquals(12, perSlot.size)
            perSlot.forEachIndexed { slot, layout ->
                assertEquals("朝向 $orientation 展开 $slot 的槽位数", SLOTS_PER_BLOCK, layout.size)
                assertTrue(
                    "朝向 $orientation 展开 $slot 必须精确覆盖",
                    coversExactly(layout, BLOCK_COLS, BLOCK_ROWS)
                )
                val expanded = layout[slot]
                assertEquals("被展开的那张必须是 6x6", EXPANSION_COLS, expanded.cols)
                assertEquals(EXPANSION_ROWS, expanded.rows)
            }
        }
    }

    @Test
    fun geometrySplitsBlocksIntoRepeatingCell() {
        val g = latticeGeometry(36, metrics)
        // 36 首需要 3 块，按 2.2 的横向偏置折成 2 列 → 2 行（装得下 4 块）
        assertTrue(g.blocksPerRow * g.blockRows >= 3)
        assertEquals(BLOCK_COLS * metrics.pitch * g.blocksPerRow, g.cellWidth, 0.01f)
        assertEquals(BLOCK_ROWS * metrics.pitch * g.blockRows, g.cellHeight, 0.01f)
        assertEquals(g.blocksPerRow * g.blockRows * SLOTS_PER_BLOCK, g.cellSlots)
    }

    /** 视口里的卡片不能互相压住：一个块内 12 张必须严丝合缝，块与块之间也不能重叠 */
    @Test
    fun visibleInstancesNeverOverlap() {
        val g = latticeGeometry(24, metrics)
        val bounds = worldBounds(0f, 0f, 393f, 851f)
        val list = layoutLattice(g, 24, bounds, 120f, metrics)
        assertTrue("视口里应该有卡片", list.size > 10)
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                val overlapX = a.x < b.x + b.width - 0.01f && a.x + a.width > b.x + 0.01f
                val overlapY = a.y < b.y + b.height - 0.01f && a.y + a.height > b.y + 0.01f
                assertTrue("实例 ${a.instanceId} 与 ${b.instanceId} 重叠", !(overlapX && overlapY))
            }
        }
    }

    /** 世界无边界：块坐标为负也照样有内容，队列索引按单元取模 */
    @Test
    fun worldExtendsToNegativeCoordinates() {
        val g = latticeGeometry(20, metrics)
        // 相机推到 (4000,3000) → 视口落在世界坐标 (-4000,-3000) 一带
        val far = worldBounds(4000f, 3000f, 400f, 800f)
        val list = layoutLattice(g, 20, far, 100f, metrics)
        assertTrue("负坐标区域也该有卡片", list.isNotEmpty())
        assertTrue(list.all { it.queueIndex in 0 until 20 })
        // 世界真的往负方向延伸：卡片的中心确实落在视口里，而不是被夹回原点
        assertTrue(list.any { it.centerX in -4000f..-3600f && it.centerY in -3000f..-2200f })
    }

    @Test
    fun expansionOnlyMovesCardsInsideItsOwnBlock() {
        val g = latticeGeometry(40, metrics)
        val bounds = worldBounds(0f, 0f, 393f, 851f)
        val visible = layoutLattice(g, 40, bounds, 0f, metrics)
        val active = visible.first()
        val reflowed = layoutExpandedBlock(g, 40, active, metrics)
        assertEquals(SLOTS_PER_BLOCK, reflowed.size)

        val expanded = reflowed[active.instanceId]
        assertNotNull(expanded)
        assertEquals(EXPANSION_COLS * metrics.pitch - metrics.gap, expanded!!.width, 0.01f)

        // 展开卡变大，但只在它自己那一块里重排：块外的实例不许出现在让位表里
        assertTrue(expanded.width > active.width)
        val blockIndex = active.cellSlot / SLOTS_PER_BLOCK
        reflowed.keys.forEach { id ->
            val cellSlot = id.substringBefore(':').toInt()
            assertEquals(blockIndex, cellSlot / SLOTS_PER_BLOCK)
        }
        // 让位表铺满的仍是块的外框（12 格 × 8 格），所以块外不会空出来也不会被挤动
        val blockLeft = reflowed.values.minOf { it.x }
        val blockTop = reflowed.values.minOf { it.y }
        val blockRight = reflowed.values.maxOf { it.x + it.width }
        val blockBottom = reflowed.values.maxOf { it.y + it.height }
        assertEquals(BLOCK_COLS * metrics.pitch - metrics.gap, blockRight - blockLeft, 0.02f)
        assertEquals(BLOCK_ROWS * metrics.pitch - metrics.gap, blockBottom - blockTop, 0.02f)
    }

    @Test
    fun nearestInstanceIsActuallyNearest() {
        val g = latticeGeometry(30, metrics)
        val pointX = 120f
        val pointY = -80f
        val near = locateNearestInstance(g, 30, 7, pointX, pointY, metrics)
        assertNotNull(near)
        val dx = near!!.centerX - pointX
        val dy = near.centerY - pointY
        val distance = dx * dx + dy * dy
        // 再远一格必有更远的同类实例
        assertTrue(distance < g.cellWidth * g.cellWidth)
    }

    /**
     * 连续甩动时，屏幕上任何一帧都不许出现大片空白。
     *
     * 上一版的补算是「整批替换 + 只留 0.3 屏余量」，一甩就滑出已算范围，屏幕直接空掉。
     * 这里按真机的帧位移（150dp/帧，比正常甩动快一倍）往下走，每一步都量视口被卡片盖住的面积。
     * 卡片之间有 8% 的缝，所以满覆盖大约 0.9。
     */
    /**
     * 往任意方向甩，视口任何一帧都不许露白。
     *
     * 之前只测了左上一条对角线，正好放过「某些角度有空缺」这个 bug。它其实是两处叠加：
     * 一是余量按「屏」取（竖屏纵向够、横向不够），二是相机内部把 dp 和 px 混着加。
     * 这里八个方向都走一遍，每帧 150dp（比正常甩动快一倍）。
     */
    @Test
    fun draggingNeverExposesBlankViewportInAnyDirection() {
        val dirs = listOf(
            1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f,
            1f to 1f, 1f to -1f, -1f to 1f, -1f to -1f
        )
        for ((ux, uy) in dirs) {
            val worst = panAndMeasure(ux, uy, frames = 240).first
            assertTrue(
                "方向 ($ux,$uy) 最差一帧只盖住 ${(worst * 100).toInt()}%",
                worst > 0.85f
            )
        }
    }

    /**
     * 一直滑，池子里不能越攒越多，也不能出现重复的实例。
     * （去重靠 instanceId，重复了会出现两张图叠在一格里）
     */
    @Test
    fun instancePoolStaysBounded() {
        for ((ux, uy) in listOf(1f to 0f, -1f to 1f, 0f to -1f)) {
            val peak = panAndMeasure(ux, uy, 600).second
            assertTrue("方向 ($ux,$uy) 池子涨到 \${peak} 张，远处的卡没被淘汰", peak <= 400)
        }
    }

    /**
     * 手指落在哪张卡上，就是哪张卡。
     *
     * 这是「点的和实际播放的不是同一个歌曲」的根：触摸点是像素，相机和卡片是世界 dp，
     * 之前直接拿 px 减 dp，差了一个 density 倍，点左边会命中右边。这里用真实密度
     * （2.75，主流 1080p 手机）逐张点中心，点谁就得是谁。
     */
    @Test
    fun tapHitsThePosterUnderTheFinger() {
        val density = 2.75f
        val total = 313
        val g = latticeGeometry(total, metrics)
        val vw = 393f
        val vh = 851f
        val over = overscanFor(metrics)
        // 相机故意推到非原点：单位混用时偏差会随距离放大，一眼能看出来
        val camX = 1372f
        val camY = 615f
        val view = worldBounds(camX, camY, vw, vh)
        val list = layoutLattice(g, total, view, over, over, metrics)
        val targets = list.map { it to it.rect }

        val inside = list.filter {
            it.x >= view.left && it.y >= view.top &&
                it.x + it.width <= view.right && it.y + it.height <= view.bottom
        }
        assertTrue("视口内至少要有几张完整的卡", inside.size >= 6)

        for (instance in inside.take(12)) {
            val px = (instance.centerX + camX) * density
            val py = (instance.centerY + camY) * density
            val hit = hitInstance(targets, px, py, density, camX, camY)
            assertEquals(
                "点 \${instance.instanceId} 的中心，命中的却是 \${hit?.instanceId}",
                instance.instanceId,
                hit?.instanceId
            )
            // 反证：忘了把 px 换算成 dp 的话（density 当 1），同一根手指会落到别的卡上
            val unConverted = hitInstance(targets, px, py, 1f, camX, camY)
            assertNotEquals(
                "这条测试抓不到单位混用，说明样本没选好",
                instance.instanceId, unConverted?.instanceId
            )
        }
    }

    /** 沿一条直线拖动，返回 (最差覆盖率, 池子峰值) */
    private fun panAndMeasure(ux: Float, uy: Float, frames: Int): Pair<Float, Int> {
        val total = 313
        val g = latticeGeometry(total, metrics)
        val vw = 393f
        val vh = 851f
        val over = overscanFor(metrics)
        val keep = keepFor(metrics)
        val step = refineStepFor(metrics)

        val store = WallInstanceStore()
        store.seed(layoutLattice(g, total, worldBounds(0f, 0f, vw, vh), over, over, metrics))

        var camX = 0f
        var camY = 0f
        var lastX = Float.NaN
        var lastY = Float.NaN
        var worst = 1f
        var peak = 0

        repeat(frames) {
            camX += ux * 150f
            camY += uy * 150f
            if (lastX.isNaN() || abs(camX - lastX) >= step || abs(camY - lastY) >= step) {
                lastX = camX
                lastY = camY
                val view = worldBounds(camX, camY, vw, vh)
                val fresh = layoutLattice(g, total, view, over, over, metrics)
                val keepBounds = WallBounds(
                    view.left - keep, view.top - keep, view.right + keep, view.bottom + keep
                )
                store.merge(fresh, keepBounds)
            }
            val pool = store.snapshot()
            assertEquals("实例不能重复", pool.size, pool.map { it.instanceId }.toSet().size)
            if (pool.size > peak) peak = pool.size
            val c = coverage(pool, worldBounds(camX, camY, vw, vh))
            if (c < worst) worst = c
        }
        return worst to peak
    }

    /** 卡片之间不重叠，所以直接累加与视口的交集面积就是覆盖率 */
    private fun coverage(instances: List<WallInstance>, view: WallBounds): Float {
        var area = 0f
        for (inst in instances) {
            val left = maxOf(inst.x, view.left)
            val top = maxOf(inst.y, view.top)
            val right = minOf(inst.x + inst.width, view.right)
            val bottom = minOf(inst.y + inst.height, view.bottom)
            if (right > left && bottom > top) area += (right - left) * (bottom - top)
        }
        val total = (view.right - view.left) * (view.bottom - view.top)
        return if (total <= 0f) 0f else area / total
    }

    @Test
    fun cameraCentersRectOnViewport() {
        val (x, y) = cameraToCenter(
            com.multitrack.app.player.ui.wall.WallRect(100f, 200f, 300f, 300f), 393f, 851f
        )
        assertEquals(393f / 2f - 250f, x, 0.01f)
        assertEquals(851f / 2f - 350f, y, 0.01f)
        assertEquals(EXPANSION_COLS * metrics.pitch - metrics.gap, expandedSize(metrics), 0.01f)
        assertTrue(abs(x) < 400f)
    }

    /**
     * 切歌时窗口必须「搬到新歌身上」，而不是留在原地换一张封面。
     * 同时兜住两件事：新位置得是最近的副本（不能飞到单元另一头），再算一次要稳定。
     */
    @Test
    fun followMovesWindowToNearestCopyOfTheNewSong() {
        val total = 30
        val geo = latticeGeometry(total, metrics)
        val start = locateNearestInstance(geo, total, 5, 400f, 700f, metrics)!!
        val expanded = layoutExpandedBlock(geo, total, start, metrics)[start.instanceId]!!

        val next = followInstance(geo, total, 6, expanded, metrics)!!
        assertEquals(6, next.queueIndex)
        assertNotEquals(start.instanceId, next.instanceId)

        val cx = expanded.x + expanded.width / 2f
        val cy = expanded.y + expanded.height / 2f
        assertTrue("窗口跳得太远", abs(next.centerX - cx) < geo.blockWidth * 1.5f)
        assertTrue("窗口跳得太远", abs(next.centerY - cy) < geo.blockHeight * 1.5f)

        // 已经在新歌身上再算一次：必须还是这一张（否则会和相机互相拉扯、画面抖）
        val expandedNext = layoutExpandedBlock(geo, total, next, metrics)[next.instanceId]!!
        assertEquals(next.instanceId, followInstance(geo, total, 6, expandedNext, metrics)!!.instanceId)
    }
}
