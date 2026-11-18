package com.multitrack.app

import com.multitrack.app.ui.EdgeScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边缘自动滚动的边界行为。
 * 这里的用例就是 3.12~3.21 反复出问题的几个点：
 * 右侧够不到阈值、左侧误触发、按住不动不滚、反向拖动不停。
 */
class EdgeScrollTest {

    private val edge = 102f  // 34dp @ 3x

    @Test
    fun `视口中部不滚动`() {
        // 竖屏常见视口：1440px 宽、172dp(516px) 轨道头 → 924px 视口
        assertEquals(0f, EdgeScroll.horizontal(462f, 924f, edge, 0f), 0.001f)
        assertEquals(0f, EdgeScroll.horizontal(462f, 924f, edge, 1f), 0.001f)
    }

    @Test
    fun `右侧边缘一定会滚`() {
        // 旧实现的阈值是 viewportW + HEADER_W - edge，屏幕只有 1440px 宽永远够不到
        val step = EdgeScroll.horizontal(900f, 924f, edge, 1f)
        assertTrue("右边缘应向右滚，实际 $step", step > 0f)
        assertEquals(EdgeScroll.MAX_STEP_H, EdgeScroll.horizontal(924f, 924f, edge, 1f), 0.001f)
    }

    @Test
    fun `左侧边缘在没有轨道头偏移时正确触发`() {
        val step = EdgeScroll.horizontal(10f, 924f, edge, -1f)
        assertTrue("左边缘应向左滚，实际 $step", step < 0f)
    }

    @Test
    fun `按住不动时贴边仍然持续滚动`() {
        // dir = 0 表示这一帧没有位移，不能因此停滚
        assertTrue(EdgeScroll.horizontal(920f, 924f, edge, 0f) > 0f)
        assertTrue(EdgeScroll.horizontal(5f, 924f, edge, 0f) < 0f)
    }

    @Test
    fun `反向拖动立即停止`() {
        // 贴在右边缘但朝左拖 → 不滚
        assertEquals(0f, EdgeScroll.horizontal(920f, 924f, edge, -1f), 0.001f)
        // 贴在左边缘但朝右拖 → 不滚
        assertEquals(0f, EdgeScroll.horizontal(5f, 924f, edge, 1f), 0.001f)
    }

    @Test
    fun `横屏宽视口同样生效且步长有上限`() {
        // 横屏 3200px 宽，轨道头 516px → 2684px 视口
        assertTrue(EdgeScroll.horizontal(2600f, 2684f, edge, 1f) > 0f)
        assertEquals(EdgeScroll.MAX_STEP_H, EdgeScroll.horizontal(2900f, 2684f, edge, 1f), 0.001f)
        assertEquals(0f, EdgeScroll.horizontal(1342f, 2684f, edge, 1f), 0.001f)
    }

    @Test
    fun `极窄视口不会整条都算边缘`() {
        // 折叠屏分栏 / 小窗：视口只有 100px，感应带被压到一半以内
        assertEquals(0f, EdgeScroll.horizontal(50f, 100f, edge, 1f), 0.001f)
        assertTrue(EdgeScroll.horizontal(99f, 100f, edge, 1f) > 0f)
    }

    @Test
    fun `非法尺寸不产生位移`() {
        assertEquals(0f, EdgeScroll.horizontal(500f, 0f, edge, 1f), 0.001f)
        assertEquals(0f, EdgeScroll.horizontal(Float.NaN, 924f, edge, 1f), 0.001f)
        assertEquals(0f, EdgeScroll.vertical(500f, 0f, edge, 1f), 0.001f)
    }

    @Test
    fun `纵向滚动只在贴边时触发`() {
        assertEquals(0f, EdgeScroll.vertical(400f, 800f, 84f, 0f), 0.001f)
        assertTrue(EdgeScroll.vertical(790f, 800f, 84f, 1f) > 0f)
        assertTrue(EdgeScroll.vertical(10f, 800f, 84f, -1f) < 0f)
    }
}
