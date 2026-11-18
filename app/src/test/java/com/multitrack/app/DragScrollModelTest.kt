package com.multitrack.app

import com.multitrack.app.ui.EdgeScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动位移模型 + 边缘滚动的联合回归测试。
 *
 * 口径（和 UI 层完全一致）：
 *   dragDx = rootDelta + scrollComp      // 手指真实位移 + 自动滚过的量
 *   lx     = clipStart + dragDx + grab - scroll
 *
 * 不变式：手指不动时 lx 必须恒等于手指在视口里的位置 —— 这是「贴边一直滚、片段不脱手」的基础。
 * 早期版本用「节点内局部坐标」累加再叠加滚动补偿，而局部坐标本身就被滚动带偏，
 * 于是滚动量被算了两次，贴边后片段会抢在手指前面。
 */
class DragScrollModelTest {

    private val vpW = 924f              // 竖屏 480dp @3x：整屏 1440px 减去 172dp 轨道头
    private val edge = 34f * 3f         // 感应带 34dp
    private val clipStart = 100f        // 片段左沿（内容坐标）
    private val grab = 50f              // 抓取点距片段左沿
    private val maxScroll = 5000f

    private class Result(val scroll: Float, val comp: Float, val framesScrolled: Int, val lxs: List<Float>)

    /**
     * @param fingerX 手指固定在视口里的 X
     * @param frames 跑多少帧
     * @param startScroll 拖动开始时时间轴已经滚到的位置
     */
    private fun run(fingerX: Float, frames: Int, startScroll: Float = 0f): Result {
        var scroll = startScroll
        var comp = 0f
        var moved = 0
        val lxs = ArrayList<Float>()
        // 按下瞬间：片段在视口里的位置是 clipStart - startScroll，手指落在 fingerX 处 → 抓取偏移
        val grabEff = fingerX - (clipStart - startScroll)
        repeat(frames) {
            // 手指没动，位移全部来自自动滚过的量
            val dragDx = comp
            val lx = clipStart + dragDx + grabEff - scroll
            lxs.add(lx)
            val step = EdgeScroll.horizontal(lx, vpW, edge, 0f)
            if (step != 0f) {
                val used = step.coerceIn(-scroll, maxScroll - scroll)
                if (used != 0f) {
                    scroll += used
                    comp += used
                    moved++
                }
            }
        }
        return Result(scroll, comp, moved, lxs)
    }

    @Test
    fun `贴住右边缘会持续滚动`() {
        val r = run(vpW, 60)                       // 手指落在视口右沿
        assertTrue("右边缘必须滚", r.scroll > 0f)
        assertEquals(60, r.framesScrolled)
    }

    @Test
    fun `贴住左边缘会持续滚动`() {
        val r = run(0f, 60, startScroll = 600f)    // 手指落在视口左沿
        assertTrue("左边缘必须滚", r.scroll < 600f)
        assertTrue("不会滚成负数", r.scroll >= 0f)
    }

    @Test
    fun `视口中间不滚`() {
        val r = run(vpW / 2f, 60)
        assertEquals(0f, r.scroll, 0f)
        assertEquals(0, r.framesScrolled)
    }

    @Test
    fun `手指不动时 lx 恒等于手指位置`() {
        val r = run(vpW, 60)
        for (lx in r.lxs) assertEquals(vpW, lx, 0.6f)
    }

    @Test
    fun `滚到内容上限后补偿不再虚增`() {
        val start = maxScroll - 200f
        val r = run(vpW, 60, startScroll = start)
        assertEquals(maxScroll, r.scroll, 0.01f)
        // comp 只等于「实际滚过的量」，不会被请求量灌水
        assertEquals(r.scroll - start, r.comp, 0.01f)
        // 撞上限后 lx 依旧等于手指位置，落点不会漂
        assertEquals(vpW, r.lxs.last(), 0.6f)
    }

    @Test
    fun `横屏宽视口同样按贴边触发`() {
        val w = 2684f                              // 横屏视口
        assertTrue(EdgeScroll.horizontal(w, w, edge, 0f) > 0f)          // 右沿
        assertTrue(EdgeScroll.horizontal(10f, w, edge, 0f) < 0f)        // 左沿
        assertEquals(0f, EdgeScroll.horizontal(w / 2f, w, edge, 0f), 0f) // 中间
    }

    @Test
    fun `分屏极窄视口不会整条都算边缘`() {
        val w = 100f
        assertEquals(0f, EdgeScroll.horizontal(w / 2f, w, edge, 0f), 0f)
        assertTrue(EdgeScroll.horizontal(w - 1f, w, edge, 0f) > 0f)
        assertTrue(EdgeScroll.horizontal(1f, w, edge, 0f) < 0f)
    }

    @Test
    fun `反向拖动立即停止滚动`() {
        // 手指在右沿但最近方向是向左（dirX = -1）：右边缘不该再滚
        assertEquals(0f, EdgeScroll.horizontal(vpW, vpW, edge, -1f), 0f)
        // 手指在左沿但方向向右：左边缘不滚
        assertEquals(0f, EdgeScroll.horizontal(0f, vpW, edge, 1f), 0f)
    }
}
