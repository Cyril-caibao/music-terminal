package com.multitrack.app.ui

/**
 * 时间轴拖动的「边缘自动滚动」计算。
 *
 * 坐标系约定：一律使用**滚动视口内的局部像素**（视口左沿 = 0，右沿 = viewportW），
 * 不含左侧轨道头（HEADER_W）。早期版本用整屏宽度做判定又拿内容坐标去比，
 * 结果右侧永远够不到阈值、左侧提前一个轨道头宽度误触发。
 *
 * 方向 dirX/dirY：+1 = 朝右/下，-1 = 朝左/上，0 = 按住不动。
 * dir = 0 时仍然允许滚动 —— 手指贴住边缘静止也要持续滚（这是「常驻协程」的本意）。
 */
object EdgeScroll {

    /** 水平单帧最大位移（px） */
    const val MAX_STEP_H = 22f

    /** 垂直单帧最大位移（px） */
    const val MAX_STEP_V = 18f

    private const val MIN_STEP = 2f
    private const val GAIN = 0.5f

    /**
     * @param localX 手指在视口内的 X
     * @param viewportW 视口宽度（px）
     * @param edge 边缘感应带宽度（px）
     * @param dirX 最近一帧的水平方向
     * @return 本帧应滚动量的像素，正值向右、负值向左、0 表示不滚
     */
    fun horizontal(localX: Float, viewportW: Float, edge: Float, dirX: Float): Float {
        if (!viewportW.isFinite() || !localX.isFinite() || viewportW <= 0f || edge <= 0f) return 0f
        // 视口很窄时感应带不能超过一半，否则整条时间轴都算「边缘」
        val band = edge.coerceAtMost(viewportW / 2f)
        val rightEdge = viewportW - band
        return when {
            localX > rightEdge && dirX >= 0f ->
                ((localX - rightEdge) * GAIN + MIN_STEP).coerceIn(MIN_STEP, MAX_STEP_H)
            localX < band && dirX <= 0f ->
                -(((band - localX) * GAIN + MIN_STEP).coerceIn(MIN_STEP, MAX_STEP_H))
            else -> 0f
        }
    }

    /** 竖直方向同理，用于跨轨拖动时滚轨道。 */
    fun vertical(localY: Float, viewportH: Float, edge: Float, dirY: Float): Float {
        if (!viewportH.isFinite() || !localY.isFinite() || viewportH <= 0f || edge <= 0f) return 0f
        val band = edge.coerceAtMost(viewportH / 2f)
        val bottomEdge = viewportH - band
        return when {
            localY > bottomEdge && dirY >= 0f ->
                ((localY - bottomEdge) * GAIN + MIN_STEP).coerceIn(MIN_STEP, MAX_STEP_V)
            localY < band && dirY <= 0f ->
                -(((band - localY) * GAIN + MIN_STEP).coerceIn(MIN_STEP, MAX_STEP_V))
            else -> 0f
        }
    }
}
