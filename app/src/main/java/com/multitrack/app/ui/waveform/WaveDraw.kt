package com.multitrack.app.ui.waveform

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.multitrack.app.ui.theme.Palette
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.multitrack.app.audio.PeakExtractor
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 波形与标尺绘制：从鸿蒙版 CanvasRenderingContext2D 平移到 Compose DrawScope。
 * 文字用 nativeCanvas + android.graphics.Paint 绘制，兼容所有 Compose 版本。
 */

/** 文本画笔（每次调用创建，开销可忽略） */
private fun textPaint(color: Color, size: Float): Paint = Paint().apply {
    isAntiAlias = true
    this.color = color.toArgb()
    textSize = size
}

/** 绘制一个片段的波形（含背景、边框、淡变三角、片段名） */
fun DrawScope.drawWaveform(
    peaks: FloatArray,
    totalFrames: Int,
    cWidth: Float,
    cHeight: Float,
    color: Color,
    fadeInPx: Float,
    fadeOutPx: Float,
    selected: Boolean,
    name: String,
    density: Float = 1f,
    peaksRight: FloatArray? = null,
    stacked: Boolean = false
) {
    // 背景
    drawRect(color = Palette.WaveBg, size = androidx.compose.ui.geometry.Size(cWidth, cHeight))
    // 选中边框
    drawRect(
        color = if (selected) Color(0xFFFF5252) else Palette.Border,
        size = androidx.compose.ui.geometry.Size(cWidth, cHeight),
        style = Stroke(width = if (selected) 3f else 1.5f)
    )

    if (totalFrames > 0 && cWidth > 0 && peaks.isNotEmpty()) {
        val pxCount = max(1, floor(cWidth).toInt())
        val agg = PeakExtractor.aggregatePeaks(peaks, totalFrames, 0, totalFrames, pxCount)
        val waveColor = color

        if (stacked && peaksRight != null && peaksRight.isNotEmpty()) {
            // 双轨（左右声道）堆叠显示：上半 L，下半 R，中间一条分轨线
            val aggR = PeakExtractor.aggregatePeaks(peaksRight, totalFrames, 0, totalFrames, pxCount)
            val laneH = cHeight / 2
            drawLine(
                Color(0x33FFFFFF),
                Offset(0f, laneH), Offset(cWidth, laneH), 1f
            )
            for (px in 0 until pxCount) {
                val x = px + 0.5f
                val midL = laneH / 2
                val midR = laneH + laneH / 2
                val half = laneH / 2 - 1f
                drawLine(waveColor, Offset(x, midL - agg[px * 2 + 1] * half),
                    Offset(x, (midL - agg[px * 2] * half).coerceAtLeast(midL - agg[px * 2 + 1] * half)), 1f)
                drawLine(waveColor.copy(alpha = 0.75f), Offset(x, midR - aggR[px * 2 + 1] * half),
                    Offset(x, (midR - aggR[px * 2] * half).coerceAtLeast(midR - aggR[px * 2 + 1] * half)), 1f)
            }
        } else {
            val yMid = cHeight / 2
            val half = cHeight / 2 - 1
            for (px in 0 until pxCount) {
                val mn = agg[px * 2]
                val mx = agg[px * 2 + 1]
                val x = px + 0.5f
                val yTop = yMid - mx * half
                val yBot = (yMid - mn * half).coerceAtLeast(yTop)
                drawLine(waveColor, Offset(x, yTop), Offset(x, yBot), 1f)
            }
        }
    }

    // 淡入淡出三角
    val fadePaint = Color(0x59FFFFFF)
    if (fadeInPx > 0) {
        val p = Path().apply {
            moveTo(0f, cHeight / 2)
            lineTo(fadeInPx.coerceAtMost(cWidth), 0f)
            lineTo(fadeInPx.coerceAtMost(cWidth), cHeight)
            close()
        }
        drawPath(p, fadePaint)
    }
    if (fadeOutPx > 0) {
        val p = Path().apply {
            moveTo(cWidth, cHeight / 2)
            lineTo(cWidth - fadeOutPx.coerceAtMost(cWidth), 0f)
            lineTo(cWidth - fadeOutPx.coerceAtMost(cWidth), cHeight)
            close()
        }
        drawPath(p, fadePaint)
    }

    // 片段名
    drawContext.canvas.nativeCanvas.drawText(
        name, 6f * density, 14f * density,
        textPaint(Color(0xFFE6E8EE), 11f * density)
    )
}

/** 计算合适的刻度间隔（秒） */
private fun niceStep(pps: Float): Float {
    val raw = 80f / pps
    val steps = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f)
    for (s in steps) if (s >= raw) return s
    return 600f
}

private fun fmt(sec: Float): String {
    val m = floor(sec / 60).toInt()
    val s = floor(sec % 60).toInt()
    return "$m:${s.toString().padStart(2, '0')}"
}

/** 绘制时间标尺（秒刻度 + 底线 + 数字） */
fun DrawScope.drawRuler(
    cWidth: Float,
    cHeight: Float,
    pxPerSecond: Float,
    totalSeconds: Float,
    density: Float = 1f
) {
    drawRect(color = Palette.Lane, size = androidx.compose.ui.geometry.Size(cWidth, cHeight))
    if (pxPerSecond <= 0f || pxPerSecond.isNaN() || pxPerSecond.isInfinite()) return
    val step = niceStep(pxPerSecond)
    val linePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = Color(0xFF3A3F4B).toArgb()
        strokeWidth = 1f
    }
    val labelPaint = textPaint(Color(0xFF9AA0AD), 10f * density)
    val nc = drawContext.canvas.nativeCanvas

    // 底线
    nc.drawLine(0f, cHeight - 1, cWidth, cHeight - 1, linePaint)

    var s = 0f
    while (true) {
        val x = (s * pxPerSecond).roundToInt() + 0.5f
        if (x > cWidth) break
        nc.drawLine(x, cHeight - 10, x, cHeight - 1, linePaint)
        nc.drawText(fmt(s), x + 2, 12f * density, labelPaint)
        s += step
    }
}

/** 按可视窗口绘制标尺：从 startSec 开始铺满整块画布（不再受工程长度/内容宽度影响） */
fun DrawScope.drawRulerWindow(
    cWidth: Float,
    cHeight: Float,
    pxPerSecond: Float,
    startSec: Float,
    density: Float = 1f
) {
    drawRect(color = Palette.Lane, size = androidx.compose.ui.geometry.Size(cWidth, cHeight))
    if (pxPerSecond <= 0f || pxPerSecond.isNaN() || pxPerSecond.isInfinite()) return
    val step = niceStep(pxPerSecond)
    val linePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = Color(0xFF3A3F4B).toArgb()
        strokeWidth = 1f
    }
    val labelPaint = textPaint(Color(0xFF9AA0AD), 10f * density)
    val nc = drawContext.canvas.nativeCanvas

    nc.drawLine(0f, cHeight - 1, cWidth, cHeight - 1, linePaint)

    var s = (floor(startSec / step) * step)
    if (s < 0f) s = 0f
    while (true) {
        val x = ((s - startSec) * pxPerSecond).roundToInt() + 0.5f
        if (x > cWidth) break
        if (x >= -40f) {
            nc.drawLine(x, cHeight - 10, x, cHeight - 1, linePaint)
            nc.drawText(fmt(s), x + 2, 12f * density, labelPaint)
        }
        s += step
    }
}
