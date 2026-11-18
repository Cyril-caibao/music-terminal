package com.multitrack.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import com.multitrack.app.audio.AiDenoise
import com.multitrack.app.audio.dfn3.Dfn3
import com.multitrack.app.audio.ProQ
import com.multitrack.app.ui.theme.Palette

/**
 * 顶部菜单条：CyrilMusic + 文件/编辑/多轨/视图/效果/窗口/帮助 + 右侧「设置」齿轮。
 * 命中区：整行 40dp 高，每项至少 44dp 宽，避免点不中；右侧齿轮 48dp。
 */
@Composable
fun MenuBarView(
    activeMenu: String?,
    onMenu: (String) -> Unit,
    onSettings: () -> Unit,
    settingsActive: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Palette.MenuBg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "CyrilMusic",
            color = Palette.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp, end = 6.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
        ) {
            // 与 AU 一致：文件 / 编辑 / 选择 / 视图 / 多轨 / 效果 / 窗口 / 帮助（窄屏可横滑）
            for (m in listOf("文件", "编辑", "选择", "视图", "多轨", "效果", "窗口", "帮助")) {
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .width(42.dp)
                        .background(
                            if (activeMenu == m) Palette.Brand.copy(alpha = 0.28f) else Color.Transparent
                        )
                        .clickable { onMenu(m) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        m,
                        color = if (activeMenu == m) Palette.Brand else Palette.Text,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .height(40.dp)
                .width(52.dp)
                .background(if (settingsActive) Palette.Brand.copy(alpha = 0.28f) else Color.Transparent)
                .clickable { onSettings() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = if (settingsActive) Palette.Brand else Palette.Text, modifier = Modifier.size(17.dp))
        }
    }
}

/** 工具按钮：线性矢量图标 + 文字，命中区 40dp */
@Composable
fun TBtn(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    active: Boolean = false,
    accent: Color? = null,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (active) Palette.Brand else Palette.Text
    Row(
        modifier = Modifier
            .height(if (compact) 32.dp else 40.dp)
            .widthIn(min = if (compact) 46.dp else 60.dp)
            .background(
                when {
                    accent != null -> accent.copy(alpha = 0.22f)
                    active -> Palette.Brand.copy(alpha = 0.26f)
                    else -> Palette.BtnBg
                },
                RoundedCornerShape(3.dp)
            )
            .border(
                1.dp,
                if (active || accent != null) Palette.Brand else Palette.Border,
                RoundedCornerShape(3.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(if (compact) 14.dp else 16.dp)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            color = tint,
            fontSize = if (compact) 10.sp else 11.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 顶部工具行（仿 AU）：左上角 导入/导出（带图标），其后是编辑类工具。
 * 播放/停止等走底部走带条，不再占用顶部空间。
 */
@Composable
fun TopToolbarView(
    state: EditorState,
    compact: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(Palette.Panel)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TBtn("导入", Icons.Filled.FileDownload, accent = Palette.Brand, compact = compact) { onImport() }
        TBtn("导出", Icons.Filled.FileUpload, compact = compact) { onExport() }
        Box(Modifier.width(1.dp).height(if (compact) 24.dp else 30.dp).background(Palette.Border))
        TBtn("缩小", Icons.Filled.ZoomOut, compact = compact) { state.zoomOut() }
        TBtn("放大", Icons.Filled.ZoomIn, compact = compact) { state.zoomIn() }
        TBtn("分割", Icons.Filled.ContentCut, compact = compact) { state.splitSelected() }
        TBtn("删除", Icons.Filled.Delete, compact = compact) { state.deleteSelected() }
        TBtn("加轨", Icons.Filled.LibraryAdd, compact = compact) { state.addTrack() }
        TBtn("EQ", Icons.Filled.Equalizer, accent = Palette.Solo, compact = compact) { state.openEq() }
        Spacer(Modifier.width(4.dp))
        Text(
            if (state.importing) "导入中 ${(state.importProgress * 100).roundToInt()}%"
            else "选中：${state.focusedTrackName()}",
            color = Palette.TextWeak, fontSize = 10.5.sp
        )
    }
}

/** 底部走带条（仿 AU 的播放控制区）：播放/停止/循环/预览/录音 + 时间码 */
@Composable
fun TransportBarView(state: EditorState, compact: Boolean, onRecord: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(Palette.Panel)
            .border(width = 1.dp, color = Palette.Border)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TBtn(if (state.playing) "暂停" else "播放", if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, active = state.playing, accent = Palette.Brand, compact = compact) {
            state.togglePlay()
        }
        TBtn("停止", Icons.Filled.Stop, compact = compact) { state.stopPlay() }
        TBtn("循环", Icons.Filled.Repeat, active = state.loop, accent = Palette.Solo, compact = compact) {
            state.setLoopEnabled(!state.loop)
        }
        TBtn(if (state.previewing) "停预览" else "预览", Icons.Filled.Visibility, active = state.previewing, compact = compact) {
            state.previewSelected()
        }
        TBtn("录音", Icons.Filled.FiberManualRecord, active = state.recording, accent = Palette.Mute, compact = compact) { onRecord() }
        Row(
            modifier = Modifier
                .background(Palette.TimecodeBg, RoundedCornerShape(2.dp))
                .border(1.dp, Palette.Border, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                state.fmtTime(state.playheadFrame),
                color = Palette.Text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                " / ${state.fmtTime(state.totalFrames())}",
                color = Palette.TextWeak, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** 迷你参数滑条：拖动/点按都改值，用于延迟、反馈、混合、音高微调 */
@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    display: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextWeak, fontSize = 12.5.sp, modifier = Modifier.width(74.dp))
        val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .width(180.dp)
                .height(40.dp)
                .pointerInput(min, max) {
                    detectTapGestures { off ->
                        onChange(min + (off.x / size.width).coerceIn(0f, 1f) * (max - min))
                    }
                }
                .pointerInput(min, max) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        onChange(min + f * (max - min))
                    }
                }
        ) {
            val cy = size.height / 2
            drawLine(Palette.Border, androidx.compose.ui.geometry.Offset(0f, cy),
                androidx.compose.ui.geometry.Offset(size.width, cy), 6f, cap = StrokeCap.Round)
            drawLine(Palette.Brand, androidx.compose.ui.geometry.Offset(0f, cy),
                androidx.compose.ui.geometry.Offset(size.width * frac, cy), 10f, cap = StrokeCap.Round)
            drawCircle(Palette.Brand, 12f,
                androidx.compose.ui.geometry.Offset(size.width * frac, cy))
            drawCircle(Color.White, 5f,
                androidx.compose.ui.geometry.Offset(size.width * frac, cy))
        }
        Spacer(Modifier.width(8.dp))
        Text(display(value), color = Palette.Text, fontSize = 12.5.sp, modifier = Modifier.width(70.dp))
    }
}


internal data class FxParamSpec(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val unit: String = "",
    val decimals: Int = 0
)

internal data class FxSpec(
    val title: String,
    val desc: String,
    val params: List<FxParamSpec> = emptyList()
)

internal fun specOf(kind: String): FxSpec = when (kind) {
    "graphicEq" -> FxSpec("图形均衡器 · 10 段", "31.5Hz–16kHz，每段 ±10dB；点「应用」才写入音频")
    "limiter" -> FxSpec(
        "强制限幅 · Hard Limiter",
        "AU 式峰值限幅：提前看峰值，输出不超过上限，峰值后按 Release 恢复",
        listOf(
            FxParamSpec("ceiling", "最大幅度", -20f, 0f, -3f, "dB", 1),
            FxParamSpec("inputBoost", "输入提升", 0f, 18f, 0f, "dB", 1),
            FxParamSpec("lookahead", "预览时间", 1f, 50f, 5f, "ms"),
            FxParamSpec("limitRelease", "释放时间", 10f, 500f, 80f, "ms")
        )
    )
    "compressor" -> FxSpec(
        "单频段压缩器 · Compressor",
        "AU 式单频段动态压缩",
        listOf(
            FxParamSpec("compThreshold", "阈值", -60f, 0f, -18f, "dB", 1),
            FxParamSpec("compRatio", "比率", 1f, 20f, 4f, ":1", 1),
            FxParamSpec("compAttack", "启动时间", 0.1f, 100f, 5f, "ms", 1),
            FxParamSpec("compRelease", "释放时间", 10f, 1000f, 120f, "ms"),
            FxParamSpec("compOut", "输出增益", -12f, 12f, 0f, "dB", 1)
        )
    )
    "delay" -> FxSpec("延迟与回声 · Echo", "AU 式单抽头回声", listOf(
        FxParamSpec("delayMs", "延迟时间", 20f, 1000f, 140f, "ms"),
        FxParamSpec("delayFb", "反馈", 0f, 0.8f, 0.3f, "", 2),
        FxParamSpec("delayMix", "混合", 0f, 1f, 0.35f, "", 2)
    ))
    "chorus" -> FxSpec("和声", "短延迟调制，制造合唱感", listOf(
        FxParamSpec("chRate", "速率", 0.2f, 3f, 0.8f, "Hz", 1),
        FxParamSpec("chDepth", "深度", 0.5f, 8f, 3f, "ms", 1),
        FxParamSpec("chMix", "混合", 0f, 1f, 0.35f, "", 2)
    ))
    "tremolo" -> FxSpec("颤音", "周期性音量起伏", listOf(
        FxParamSpec("trRate", "速率", 0.5f, 12f, 4f, "Hz", 1),
        FxParamSpec("trDepth", "深度", 0f, 1f, 0.4f, "", 2)
    ))
    "deesser" -> FxSpec("消除齿音", "压制 s/sh 频段", listOf(
        FxParamSpec("deesserThr", "阈值", -40f, -6f, -26f, "dB")
    ))
    "denoise" -> FxSpec(
        "降噪（处理） · Noise Reduction",
        "先捕捉一段纯噪声做样本，再按强度做谱减；没有样本时自动从最低能量帧估计",
        listOf(
            FxParamSpec("dnStrength", "降噪强度", 0f, 100f, 60f, "%"),
            FxParamSpec("dnBand", "频带宽度", 0f, 400f, 120f, "Hz"),
            FxParamSpec("dnFloor", "残余底", -60f, 0f, -24f, "dB")
        )
    )
    "aiDenoise" -> FxSpec(
        "AI 降噪 · 人声 / 伴奏",
        "人声档跑的是 DeepFilterNet3 的官方预训练权重（assets/dfn3 里的 enc/erb_dec/df_dec 三个 ONNX 图，" +
            "48kHz·FFT960·32 带 ERB·5 阶深滤波），伴奏档走 noisereduce 那套分带掩码。人声档比较吃 CPU，一分钟素材大概要跑一会儿。",
        listOf(
            FxParamSpec("aiStrength", "处理强度", 0f, 100f, 65f, "%"),
            FxParamSpec("aiFloor", "残余底", -48f, 0f, -20f, "dB"),
            FxParamSpec("aiDetail", "细节保留", 0f, 100f, 55f, "%")
        )
    )
    "dehum" -> FxSpec(
        "消除嗡嗡声 · Dehummer",
        "自动识别 50/60Hz 工频，挖掉基频与各次谐波（只挖极窄的一条，不动贝斯）",
        listOf(FxParamSpec("dehumOrder", "谐波次数", 2f, 12f, 8f, " 次"))
    )
    "declick" -> FxSpec(
        "自动咔嗒声移除 · Click Removal",
        "找出波形里的孤立尖刺，用两端插值补平；灵敏度越高越激进",
        listOf(FxParamSpec("declickSens", "灵敏度", 0.1f, 1f, 0.5f, "", 2))
    )
    "deess" -> FxSpec(
        "降低嘶声 · De-esser",
        "只对 3kHz 以上做谱减，压掉「嘶——」的底噪，不闷掉人声",
        listOf(FxParamSpec("deessAmount", "衰减量", 0f, 24f, 12f, "dB"))
    )
    "dereverb" -> FxSpec(
        "减少混响 · De-reverb",
        "识别能量正在衰减的尾音并按比例压掉，保留直达声",
        listOf(FxParamSpec("rvReduce", "减少量", 0f, 1f, 0.5f, "", 2))
    )
    "reverb" -> FxSpec("室内混响", "空间感", listOf(
        FxParamSpec("rvSize", "房间", 0f, 1f, 0.5f, "", 2),
        FxParamSpec("rvWet", "湿度", 0f, 1f, 0.25f, "", 2)
    ))
    "widen" -> FxSpec("立体声扩展器", "加宽立体声像", listOf(
        FxParamSpec("widenAmount", "宽度", 1f, 2.5f, 1.4f, "×", 1)
    ))
    "distort" -> FxSpec("扭曲", "失真/饱和", listOf(
        FxParamSpec("dstDrive", "驱动", 1f, 8f, 2.2f, "", 1),
        FxParamSpec("dstMix", "混合", 0f, 1f, 0.8f, "", 2)
    ))
    "vocal" -> FxSpec("人声增强", "提亮人声频段，无额外参数")
    "pitchApply" -> FxSpec("音高换档器", "非破坏性移调（会改变片段长度）", listOf(
        FxParamSpec("pitchSemi", "半音", -12f, 12f, 2f, "半音")
    ))
    "stretch" -> FxSpec("伸缩（变速变调）", "改变速度/长度", listOf(
        FxParamSpec("stretchRatio", "倍速", 0.5f, 2f, 0.8f, "×", 1)
    ))
    "silenceDetect" -> FxSpec("静音检测", "只分析并报告，不改动音频")
    "removeSilence" -> FxSpec("删除静音", "删掉检测到的静音段（请先确认）")
    else -> FxSpec(kind, "")
}

/**
 * 图形 EQ 的竖推子。
 *
 * 这一版按「手指能按住」重做：滑轨 2px → 6px、手指 12px → 22px、整根 176dp 高，
 * 读数 13sp、频点 12sp。手机竖屏上原来那根 2px 的线和 6px 的点，
 * 不但看不清，拖起来也经常以为自己没按到。
 */
@Composable
private fun BandFader(
    index: Int,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier.width(46.dp)
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (value > 0.05f) "+${value.roundToInt()}" else "${value.roundToInt()}",
            color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .pointerInput(index) {
                    detectTapGestures { off ->
                        val v = 10f - (off.y / size.height) * 20f
                        onChange(v.coerceIn(-10f, 10f))
                    }
                }
                .pointerInput(index) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val v = 10f - (change.position.y / size.height) * 20f
                        onChange(v.coerceIn(-10f, 10f))
                    }
                }
        ) {
            val cx = size.width / 2
            val top = 8f
            val bottom = size.height - 8f
            val mid = (top + bottom) / 2f
            // 槽：粗一点，才看得出「这是一条能拖的轨」
            drawLine(Palette.Border, androidx.compose.ui.geometry.Offset(cx, top),
                androidx.compose.ui.geometry.Offset(cx, bottom), 8f, cap = StrokeCap.Round)
            // 0dB 基准线与两侧刻度
            drawLine(Palette.TextDim.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset(cx - 13f, mid),
                androidx.compose.ui.geometry.Offset(cx + 13f, mid), 1.5f)
            drawCircle(Palette.TextDim.copy(alpha = 0.55f), 1.8f,
                androidx.compose.ui.geometry.Offset(cx - 15f, top))
            drawCircle(Palette.TextDim.copy(alpha = 0.55f), 1.8f,
                androidx.compose.ui.geometry.Offset(cx - 15f, bottom))
            val y0 = mid - (value / 10f) * (mid - 10f)
            drawLine(Palette.Brand, androidx.compose.ui.geometry.Offset(cx, mid),
                androidx.compose.ui.geometry.Offset(cx, y0), 12f, cap = StrokeCap.Round)
            drawCircle(Palette.Brand, 13f, androidx.compose.ui.geometry.Offset(cx, y0))
            drawCircle(Color.White, 5.5f, androidx.compose.ui.geometry.Offset(cx, y0))
        }
        Text(
            when (index) {
                0 -> "31"; 1 -> "63"; 2 -> "125"; 3 -> "250"; 4 -> "500"
                5 -> "1k"; 6 -> "2k"; 7 -> "4k"; 8 -> "8k"; else -> "16k"
            },
            color = Palette.TextWeak, fontSize = 12.sp
        )
    }
}

/**
 * 「效果」菜单窗口 —— 按 AU 的两栏结构复刻：
 * 左边是类别（降噪/恢复、振幅与压限…），右边是该类别的具体命令，带快捷键与「…」，
 * 暂不支持的命令灰显而不是藏起来（AU 里也是这样：看得到、点不动）。
 *
 * 手机屏放不下 AU 那种鼠标跟随的浮层，所以改成两栏常驻：左栏点一下切右栏，
 * 位置固定，手指不用追着菜单跑。
 */
@Composable
fun EffectMenuWindow(state: EditorState, onClose: () -> Unit) {
    val groups = auGroups(state)
    var groupIndex by remember { mutableIntStateOf(groups.indexOfFirst { it.first == "降噪/恢复" }.coerceAtLeast(0)) }
    val current = groups.getOrNull(groupIndex)?.second ?: emptyList()

    Row(
        modifier = Modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .heightIn(max = 560.dp)
            .background(Palette.DropdownBg, RoundedCornerShape(4.dp))
            .border(1.dp, Palette.DropdownBorder, RoundedCornerShape(4.dp))
    ) {
        // ---- 左：类别 ----
        Column(
            modifier = Modifier
                .width(120.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            Text(
                "效果",
                color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
            for ((i, g) in groups.withIndex()) {
                val active = i == groupIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        .background(if (active) Palette.RowActive else Color.Transparent)
                        .clickable { groupIndex = i },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        g.first,
                        color = if (active) Palette.Text else Palette.TextWeak,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).padding(start = 10.dp, top = 7.dp, bottom = 7.dp)
                    )
                    Text("›", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Palette.DropdownBorder))
        // ---- 右：该类别下的命令 ----
        Column(
            modifier = Modifier
                .width(222.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            Text(
                groups.getOrNull(groupIndex)?.first ?: "",
                color = Palette.DropdownTitle, fontSize = 10.5.sp,
                modifier = Modifier.padding(start = 10.dp, bottom = 3.dp)
            )
            for (item in current) {
                if (item.label == "—") {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                            .height(1.dp).background(Palette.DropdownBorder)
                    )
                    continue
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        .clickable(enabled = item.enabled) {
                            onClose()
                            item.action?.invoke()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.label,
                        color = if (item.enabled) Palette.Text else Palette.TextDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp)
                    )
                    if (item.shortcut.isNotEmpty()) {
                        Text(
                            item.shortcut,
                            color = Palette.TextWeak,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** AU 菜单条目：label 里带上 AU 的快捷字母，shortcut 显示在右侧 */
private data class AuItem(
    val label: String,
    val shortcut: String = "",
    val enabled: Boolean = true,
    val action: (() -> Unit)? = null
)

/**
 * 类别 → 命令。名字与快捷键尽量贴近 AU（中文版），
 * 本机做不到的（声音移除、相位校正、VST、批处理）标灰，点击无效。
 */
private fun auGroups(state: EditorState): List<Pair<String, List<AuItem>>> = listOf(
    "均衡" to listOf(
        AuItem("参数均衡器 Pro-Q(P)…") { state.openEq() },
        AuItem("图形均衡器（10 段）(G)…") { state.openFx("graphicEq") }
    ),
    "振幅与压限" to listOf(
        AuItem("强制限幅(L)…") { state.openFx("limiter") },
        AuItem("单频段压缩器(C)…") { state.openFx("compressor") },
        AuItem("归一化(N)") { state.applyFxAsync("normalize") }
    ),
    "延迟与回声" to listOf(
        AuItem("延迟与回声(D)…") { state.openFx("delay") }
    ),
    "诊断" to listOf(
        AuItem("静音检测(S)") { state.applyFxAsync("silenceDetect") },
        AuItem("删除静音(M)") { state.applyFxAsync("removeSilence") }
    ),
    "滤波与均衡" to listOf(
        AuItem("图形均衡器（10 段）(G)…") { state.openFx("graphicEq") },
        AuItem("低通/高通滤波", enabled = false)
    ),
    "调制" to listOf(
        AuItem("和声(C)…") { state.openFx("chorus") },
        AuItem("颤音(T)…") { state.openFx("tremolo") }
    ),
    "降噪/恢复" to listOf(
        AuItem("捕捉噪声样本(B)", "Shift+P") {
            state.showToast(state.captureNoiseFromSelection())
        },
        AuItem("降噪（处理）(N)…", "Ctrl+Shift+P") { state.openFx("denoise") },
        AuItem("AI 降噪（人声 / 伴奏）(I)…", "Ctrl+Shift+I") { state.openFx("aiDenoise") },
        AuItem("—"),
        AuItem("了解声音模型(L)", enabled = false),
        AuItem("声音移除（处理）(S)…", enabled = false),
        AuItem("咔嗒声/爆音消除器(E)…") { state.openFx("declick") },
        AuItem("降低嘶声（处理）(H)…", "Ctrl+Shift+H") { state.openFx("deess") },
        AuItem("—"),
        AuItem("降噪(O)…") { state.openFx("denoise") },
        AuItem("自适应降噪(A)…") { state.openFx("denoise") },
        AuItem("自动咔嗒声移除(C)…") { state.openFx("declick") },
        AuItem("自动相位校正(P)…", enabled = false),
        AuItem("消除嗡嗡声(D)…") { state.openFx("dehum") },
        AuItem("减少混响(R)…") { state.openFx("dereverb") }
    ),
    "混响" to listOf(
        AuItem("室内混响(R)…") { state.openFx("reverb") }
    ),
    "特殊效果" to listOf(
        AuItem("扭曲(D)…") { state.openFx("distort") },
        AuItem("人声增强(V)") { state.applyFxAsync("vocal") }
    ),
    "立体声声像" to listOf(
        AuItem("立体声扩展器(W)…") { state.openFx("widen") }
    ),
    "时间与变调" to listOf(
        AuItem("音高换档器(P)…") { state.openFx("pitchApply") },
        AuItem("伸缩（变速变调）(S)…") { state.openFx("stretch") }
    ),
    "批处理" to listOf(
        AuItem("全部片段应用当前效果", enabled = false),
        AuItem("宏(M)…", enabled = false)
    ),
    "VST" to listOf(
        AuItem("VST3 效果(V)…", enabled = false)
    )
)

/**
 * 通用浮动窗口：标题栏拖动移动、右下角 ◢ 拖动缩放、点窗口外空白不关闭（只吃掉点击，防止误触）。
 * 尺寸 clamp 到屏幕和最小尺寸，避免缩到极小后布局测量异常导致闪退。
 */
@Composable
fun ResizableWindow(
    title: String,
    initialWidth: Dp,
    initialHeight: Dp,
    onClose: () -> Unit,
    minWidth: Dp = 280.dp,
    minHeight: Dp = 200.dp,
    onSizeChange: ((Dp, Dp) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().zIndex(40f)) {
        val maxW = maxWidth
        val maxH = maxHeight
        val safeMinW = minOf(minWidth, maxW)
        val safeMinH = minOf(minHeight, maxH)
        var wDp by remember { mutableStateOf(initialWidth.coerceIn(safeMinW, maxW)) }
        var hDp by remember { mutableStateOf(initialHeight.coerceIn(safeMinH, maxH)) }
        var ox by remember { mutableStateOf(0f) }
        var oy by remember { mutableStateOf(0f) }
        val density = LocalDensity.current.density
        LaunchedEffect(maxW, maxH) {
            if (wDp > maxW) wDp = maxW
            if (hDp > maxH) hDp = maxH
            if (wDp < safeMinW) wDp = safeMinW
            if (hDp < safeMinH) hDp = safeMinH
        }

        // 遮罩：只消费点击，不关闭窗口（缩放时手指滑出窗口也不会误关）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .pointerInput(Unit) { detectTapGestures { } }
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { androidx.compose.ui.unit.IntOffset(ox.toInt(), oy.toInt()) }
                .width(wDp)
                .height(hDp)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.DropdownBg)
                .border(1.dp, Palette.DropdownBorder, RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(Palette.Panel)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            ox += drag.x
                            oy += drag.y
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.width(32.dp).height(30.dp).clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = Palette.TextWeak, fontSize = 12.sp) }
            }
            content()
            // 右下角缩放条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(Palette.Panel)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(30.dp)
                        .height(10.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val nw = (wDp.value + drag.x / density).coerceIn(safeMinW.value, maxW.value)
                                val nh = (hDp.value + drag.y / density).coerceIn(safeMinH.value, maxH.value)
                                wDp = nw.dp
                                hDp = nh.dp
                                onSizeChange?.invoke(wDp, hDp)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("◢", color = Palette.TextWeak, fontSize = 10.sp)
                }
            }
        }
    }
}

/** AU 风格效果参数窗口：先调参数，点「应用」才改音频；窗口可拖可缩放 */
@Composable
fun EffectDialogWindow(state: EditorState, onClose: () -> Unit) {
    val kind = state.fxDialog ?: return
    val spec = specOf(kind)
    val initialH = when {
        kind == "graphicEq" -> 400.dp
        // 降噪多一块「噪声样本」面板
        kind == "denoise" -> (280 + spec.params.size * 50).dp
        // AI 降噪多一块档位面板
        kind == "aiDenoise" -> (300 + spec.params.size * 50).dp
        // 去嗡声多一行工频选择
        kind == "dehum" -> (200 + spec.params.size * 50).dp
        else -> (160 + spec.params.size * 50).dp
    }
    ResizableWindow(
        title = spec.title,
        initialWidth = if (kind == "graphicEq") 420.dp else 380.dp,
        initialHeight = initialH,
        minWidth = 300.dp,
        minHeight = 260.dp,
        onClose = onClose
    ) {
        Text(
            spec.desc,
            color = Palette.TextWeak, fontSize = 11.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp)
        )
        if (kind == "denoise") NoiseSamplePanel(state)
        if (kind == "aiDenoise") AiModelPanel(state)
        if (kind == "dehum") HumBasePanel(state)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (kind == "graphicEq") {
                // 10 段平分宽度：窗口最窄（300dp）时一段 27dp 也还按得住，
                // 常规 420dp 下正好铺满，不用横着滑
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (i in 0 until 10) {
                        BandFader(
                            i,
                            state.fxParams["g$i"] ?: 0f,
                            { v -> state.fxParams["g$i"] = v },
                            Modifier.weight(1f)
                        )
                    }
                }
            }
            for (p in spec.params) {
                val value = state.fxParams[p.key] ?: p.def
                ParamSlider(
                    p.label, value, p.min, p.max,
                    { v ->
                        if (p.decimals == 0) "${v.roundToInt()}${p.unit}"
                        else "%.${p.decimals}f%s".format(v, p.unit)
                    }
                ) { v -> state.fxParams[p.key] = v }
                if (kind == "denoise" && p.key == "dnStrength") {
                    // 把「强度」翻译成人话，免得只看到一根没有单位的滑条
                    Text(
                        "约 %.0f dB 压制 · 噪声被压到 %.0f%%".format(
                            6f + value / 100f * 34f,
                            100.0 * Math.pow(10.0, -(6f + value / 100f * 34f) / 20.0)
                        ),
                        color = Palette.TextDim, fontSize = 10.5.sp,
                        modifier = Modifier.padding(start = 74.dp, bottom = 2.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TBtn("重置", compact = true) { state.resetFxParams() }
            Spacer(Modifier.weight(1f))
            TBtn("取消", compact = true) { onClose() }
            Spacer(Modifier.width(8.dp))
            TBtn("应用", accent = Palette.Brand, compact = true) {
                state.commitFxDialog()
            }
        }
    }
}

/**
 * AI 降噪的档位面板：人声 / 伴奏两枚胶囊 + 这个档位护哪一段 + 噪声底从哪来。
 *
 * 把「用了什么方法」写进窗口而不是藏进 README：用户调滑条的时候就知道自己在听什么。
 */
@Composable
private fun AiModelPanel(state: EditorState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Palette.Panel, RoundedCornerShape(4.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("档位", color = Palette.TextWeak, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            for (m in AiDenoise.Model.entries) {
                TBtn(m.label, active = state.aiModel == m, compact = true) { state.chooseAiModel(m) }
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            state.aiModel.subtitle,
            color = Palette.Brand, fontSize = 10.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(AiDenoise.describe(state.aiModel), color = Palette.TextDim, fontSize = 10.5.sp)
        Spacer(Modifier.height(3.dp))
        if (state.aiModel == AiDenoise.Model.VOCAL) {
            // 人声档走真权重，强度/残余底/细节这三根滑条对它不生效（模型自己出掩码），
            // 这一点必须写出来，否则用户会以为拖了没反应
            Text(
                if (Dfn3.ready) "权重：DeepFilterNet3（已加载，参数由模型决定）"
                else "权重：DeepFilterNet3（首次使用会加载 7.6MB 模型）",
                color = Palette.TextDim, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            return@Column
        }
        Text(
            state.noiseProfile?.let { "噪声底：复用样本「${it.name}」" }
                ?: "噪声底：自动取最安静的 15% 帧估计",
            color = Palette.TextDim, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 降噪窗口顶部的噪声样本区：捕捉 / 导入 / 换样本，一眼看到当前用的是哪张谱。
 *
 * 这是 AU 那套流程的核心 —— 先「捕捉噪声样本」，再「降噪（处理）」。
 * 没有样本也能跑（自适应估），但把"当前用的是哪个"写清楚，用户才知道该不该去捕捉。
 */
@Composable
private fun NoiseSamplePanel(state: EditorState) {
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { state.importNoiseProfile(it) } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Palette.Panel, RoundedCornerShape(4.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("噪声样本", color = Palette.TextWeak, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(
                state.noiseProfile?.name ?: "自适应（未捕捉）",
                color = if (state.noiseProfile == null) Palette.TextDim else Palette.Brand,
                fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            state.noiseSourceLabel.ifEmpty { "先在时间轴上选一段只有噪声的地方，再点「捕捉」" },
            color = Palette.TextWeak, fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TBtn("捕捉选区", compact = true) {
                state.showToast(state.captureNoiseFromSelection())
            }
            TBtn("导入…", compact = true) {
                importLauncher.launch(arrayOf("audio/*", "application/octet-stream"))
            }
            TBtn("自适应", compact = true, active = state.noiseProfile == null) {
                state.useAdaptiveNoise()
            }
        }
        if (state.noiseProfiles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (p in state.noiseProfiles) {
                    TBtn(p.name, compact = true, active = p.name == state.noiseProfile?.name) {
                        state.selectNoiseProfile(p.name)
                    }
                }
            }
        }
    }
}

/** 去嗡声窗口里的工频选择：默认自动判断 50/60，也可以强制 */
@Composable
private fun HumBasePanel(state: EditorState) {
    val base = state.fxParams["dehumBase"] ?: 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("工频", color = Palette.TextWeak, fontSize = 11.5.sp, modifier = Modifier.width(52.dp))
        TBtn("自动", compact = true, active = base == 0f) { state.fxParams["dehumBase"] = 0f }
        TBtn("50Hz", compact = true, active = base == 50f) { state.fxParams["dehumBase"] = 50f }
        TBtn("60Hz", compact = true, active = base == 60f) { state.fxParams["dehumBase"] = 60f }
    }
}

/** Pro-Q 小窗：可拖标题栏移动、右下角缩放；尺寸写回会话 */
@Composable
fun EqWindow(state: EditorState, onClose: () -> Unit) {
    ResizableWindow(
        title = "Pro-Q 均衡器 · 拖标题栏移动 · 右下角缩放",
        initialWidth = if (state.eqWinW > 0f) state.eqWinW.dp else 340.dp,
        initialHeight = if (state.eqWinH > 0f) state.eqWinH.dp else 520.dp,
        minWidth = 300.dp,
        minHeight = 380.dp,
        onClose = onClose,
        onSizeChange = { w, h ->
            state.eqWinW = w.value
            state.eqWinH = h.value
        }
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            EqEditorScreen(state = state, onClose = onClose, windowed = true)
        }
    }
}

/** 「设置」下拉窗口：帮助 + 黑白两种外观 */
@Composable
fun SettingsWindow(state: EditorState, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .width(250.dp)
            .background(Palette.DropdownBg, RoundedCornerShape(4.dp))
            .border(1.dp, Palette.DropdownBorder, RoundedCornerShape(4.dp))
            .padding(vertical = 6.dp)
    ) {
        Text("设置", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        SettingsRow("帮助") {
            state.showToast("单击轨道头选导入目标 · 长按轨道头上下拖动排序 · 拖动概览条两缘缩放 · CyrilMusic 2.6")
            onClose()
        }
        Text("外观", color = Palette.TextWeak, fontSize = 10.5.sp,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
        Row(modifier = Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TBtn("黑色模式", active = !Palette.lightMode, compact = true) {
                Palette.lightMode = false
            }
            TBtn("白色模式", active = Palette.lightMode, compact = true) {
                Palette.lightMode = true
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = Palette.Text, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** 导出格式小窗口 */
@Composable
fun ExportWindow(state: EditorState, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .width(220.dp)
            .background(Palette.DropdownBg, RoundedCornerShape(4.dp))
            .border(1.dp, Palette.DropdownBorder, RoundedCornerShape(4.dp))
            .padding(vertical = 6.dp)
    ) {
        Text("导出混音", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        SettingsRow("WAV（16bit PCM）") { onPick("wav") }
        SettingsRow("M4A（AAC 256k）") { onPick("m4a") }
        SettingsRow("FLAC（无损，设备支持时）") { onPick("flac") }
    }
}
