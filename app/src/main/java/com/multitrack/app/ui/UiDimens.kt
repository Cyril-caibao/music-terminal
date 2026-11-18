package com.multitrack.app.ui

import androidx.compose.ui.unit.dp

/** 编辑器布局常量（仿 AU：左侧轨道头 / 顶部标尺 / 波形泳道） */
object UiDimens {
    val HEADER_W = 172.dp      // 左侧轨道头宽度
    val NAV_H = 40.dp        // 顶部导航条（全曲概览）
    val RULER_H = 30.dp        // 顶部时间标尺高度
    val LANE_H = 96.dp         // 单条音轨高度
    val TOOLBAR_H = 96.dp      // 顶部工具栏高度（两行：走带 + 编辑）
}
