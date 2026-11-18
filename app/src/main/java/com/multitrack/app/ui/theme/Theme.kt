package com.multitrack.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * CyrilMusic 配色（对标 Adobe Audition 的灰阶面板 + 青蓝波形）。
 * 支持黑色/白色两套模式：切换 [Palette.lightMode] 即可全局换肤。
 */
object Palette {
    var lightMode by mutableStateOf(false)

    val Bg get() = if (lightMode) Color(0xFFF1F1F1) else Color(0xFF1B1B1B)
    val Panel get() = if (lightMode) Color(0xFFFAFAFA) else Color(0xFF2A2A2A)
    val PanelAlt get() = if (lightMode) Color(0xFFEDEDED) else Color(0xFF303030)
    val Lane get() = if (lightMode) Color(0xFFF7F7F7) else Color(0xFF1F1F1F)
    val WaveBg get() = if (lightMode) Color(0xFFEDEDED) else Color(0xFF232323)
    val Border get() = if (lightMode) Color(0xFFC8C8C8) else Color(0xFF3A3A3A)
    val Grid get() = if (lightMode) Color(0xFFD8D8D8) else Color(0xFF3E3E3E)
    val Text get() = if (lightMode) Color(0xFF1F1F1F) else Color(0xFFD6D6D6)
    val TextDim get() = if (lightMode) Color(0xFF5C5C5C) else Color(0xFF9A9A9A)
    val TextWeak get() = if (lightMode) Color(0xFF8A8A8A) else Color(0xFF777777)
    val Brand get() = if (lightMode) Color(0xFF0A84C1) else Color(0xFF2E9BD6)
    val Playhead get() = if (lightMode) Color(0xFF2A2A2A) else Color(0xFFEDEDED)
    val Solo get() = Color(0xFFE8C547)
    val Mute get() = Color(0xFFD96B6B)
    val BtnBg get() = if (lightMode) Color(0xFFE2E2E2) else Color(0xFF3A3A3A)
    val Wave get() = if (lightMode) Color(0xFF1B7FBF) else Color(0xFF2EA9E1)

    // 结构性底色（菜单条 / 概览条 / 混合轨 / 时间码 / 下拉窗口）
    val MenuBg get() = if (lightMode) Color(0xFFE4E4E4) else Color(0xFF232323)
    val NavBg get() = if (lightMode) Color(0xFFE9E9E9) else Color(0xFF101317)
    val MixBg get() = if (lightMode) Color(0xFFEFEFEF) else Color(0xFF232628)
    val TimecodeBg get() = if (lightMode) Color(0xFFF7F7F7) else Color(0xFF1A1A1A)
    val DropdownBg get() = if (lightMode) Color(0xFFFFFFFF) else Color(0xFF2B2B2B)
    val DropdownBorder get() = if (lightMode) Color(0xFFBDBDBD) else Color(0xFF4A4A4A)
    val DropdownTitle get() = if (lightMode) Color(0xFF0A6EA0) else Color(0xFF7FA6C8)
    /** 菜单里被选中的那一行（AU 的左栏高亮） */
    val RowActive get() = if (lightMode) Color(0xFFD6E9F5) else Color(0xFF37414B)
}

private val Dark = darkColorScheme(
    primary = Color(0xFF2E9BD6),
    onPrimary = Color.White,
    background = Color(0xFF1B1B1B),
    surface = Color(0xFF2A2A2A),
    onBackground = Color(0xFFD6D6D6),
    onSurface = Color(0xFFD6D6D6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0A84C1),
    onPrimary = Color.White,
    background = Color(0xFFF1F1F1),
    surface = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1F1F1F),
    onSurface = Color(0xFF1F1F1F),
)

@Composable
fun CyrilMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (Palette.lightMode) Light else Dark, content = content)
}
