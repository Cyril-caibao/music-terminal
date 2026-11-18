package com.multitrack.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.audio.EqBand
import com.multitrack.app.audio.EqType
import com.multitrack.app.audio.ProQ
import com.multitrack.app.audio.SpectrumAnalyzer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pro-Q 风格参数均衡器（视觉对标 FabFilter Pro-Q 4）。
 *
 * 面板结构照 Q4：左侧 logo＋型号、中间预设名与 ‹ ›、右侧功能键；图内左侧 dB 刻度、
 * 右侧双通道电平条；底部为 MIDI Learn / Zero Latency / Analyzer 状态条。
 * 图内：1/3 八度网格、灰色频谱、每段彩色曲线 + 竖直渐变填充、彩色节点＋白圈、
 * 选中读数浮窗。横屏自动压缩页头页脚，把高度都留给曲线区。
 */

private val BG_TOP = Color(0xFF1B2029)
private val BG_MID = Color(0xFF12151A)
private val BG_BOT = Color(0xFF0B0D11)
private val GRID_MINOR = Color(0xFF2A323C)
private val GRID_MAJOR = Color(0xFF434D5A)
private val AXIS = Color(0xFF9AA5B4)
private val AXIS_DIM = Color(0xFF636D7B)
private val ACCENT = Color(0xFF62C6FF)
private val TEXT_MAIN = Color(0xFFE7EBF1)
private val STATUS_BG = Color(0xFF0A0C0F)

private const val MIN_F = 20f
private const val MAX_F = 20000f
private const val DB_RANGE = 10f

private fun freqToFrac(f: Float): Float {
    val a = log10(MIN_F.toDouble())
    val b = log10(MAX_F.toDouble())
    return ((log10(f.coerceIn(MIN_F, MAX_F).toDouble()) - a) / (b - a)).toFloat()
}

private fun fracToFreq(fr: Float): Float =
    10.0.pow(log10(MIN_F.toDouble()) + fr * (log10(MAX_F.toDouble()) - log10(MIN_F))).toFloat()

private fun fmtFreq(f: Float): String = when {
    f >= 1000f -> String.format("%.2f kHz", f / 1000f)
    else -> String.format("%d Hz", f.roundToInt())
}

private fun fmtDb(v: Float): String = String.format("%+.1f dB", v)
private fun fmtQ(v: Float): String = String.format("%.2f", v)

@Composable
fun EqEditorScreen(
    state: EditorState,
    onClose: () -> Unit,
    demoSpectrum: FloatArray? = null,
    demoLevelDb: Float? = null,
    windowed: Boolean = false
) {
    val bands = ProQ.bands
    val selected = bands.firstOrNull { it.id == ProQ.selectedId }

    var realSpectrum by remember { mutableStateOf(FloatArray(120)) }
    var realLevelL by remember { mutableStateOf(-60f) }
    var realLevelR by remember { mutableStateOf(-60f) }
    LaunchedEffect(ProQ.analyzerOn) {
        while (true) {
            realSpectrum = if (ProQ.analyzerOn) SpectrumAnalyzer.spectrum(120) else FloatArray(120)
            // 左右分开取：以前两条竖条喂的是同一个值，画出来永远一样长，"双通道"是假的
            realLevelL = state.engine.levelDbL
            realLevelR = state.engine.levelDbR
            delay(30)
        }
    }
    val spectrum = demoSpectrum ?: realSpectrum
    val levelL = demoLevelDb ?: realLevelL
    val levelR = demoLevelDb ?: realLevelR

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BG_TOP, BG_MID, BG_BOT)))
            .then(if (windowed) Modifier else Modifier.statusBarsPadding())
            .navigationBarsPadding()
    ) {
        val landscape = maxWidth > maxHeight
        Column(modifier = Modifier.fillMaxSize()) {
            EqTopBar(landscape, state, onClose)
            EqCanvas(
                spectrum = spectrum,
                levelDbL = levelL,
                levelDbR = levelR,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            BandStrip(bands)
            EqControls(selected, landscape)
            EqStatusBar(landscape)
        }
    }
}

@Composable
private fun EqTopBar(landscape: Boolean, state: EditorState, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (landscape) 40.dp else 44.dp)
            .background(Color(0xFF14181E))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(ACCENT, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) { Text("CY", color = Color(0xFF0B0D10), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(7.dp))
        Column {
            Text("Cyril EQ", color = TEXT_MAIN, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Pro-Q 风格 · 参数均衡器", color = AXIS_DIM, fontSize = 8.sp)
        }

        if (landscape) {
            Spacer(Modifier.width(10.dp))
            // 预设名（照 Q4 的中间预设条）
            Row(
                modifier = Modifier
                    .background(Color(0xFF1D2229), RoundedCornerShape(3.dp))
                    .border(1.dp, Color(0xFF2C333D), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = AXIS, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text("Default Setting", color = Color(0xFFCBD3DE), fontSize = 9.5.sp)
                Spacer(Modifier.width(8.dp))
                Text("›", color = AXIS, fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
            SmallChip("A/B") { }
            Spacer(Modifier.width(6.dp))
            SmallChip("Copy") { }
        }

        Spacer(Modifier.weight(1f))
        SmallChip(if (state.playing) "暂停" else "试听", active = state.playing) { state.eqAudition() }
        Spacer(Modifier.width(5.dp))
        SmallChip("分析", active = ProQ.analyzerOn) { ProQ.analyzerOn = !ProQ.analyzerOn }
        Spacer(Modifier.width(5.dp))
        SmallChip(if (ProQ.bypass) "已旁通" else "旁通", active = !ProQ.bypass) {
            ProQ.bypass = !ProQ.bypass
        }
        Spacer(Modifier.width(5.dp))
        SmallChip("完成") { onClose() }
    }
}

@Composable
private fun BandStrip(bands: List<EqBand>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(Color(0xFF14181E))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        for (b in bands) {
            BandChip(b, b.id == ProQ.selectedId) { ProQ.selectedId = b.id }
        }
        SmallChip("+ 频段") { ProQ.newBand(3000f) }
    }
}

@Composable
private fun EqControls(selected: EqBand?, landscape: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171B22))
            .padding(horizontal = 9.dp, vertical = if (landscape) 3.dp else 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (selected == null) "未选中频段" else "频段 ${selected.id}",
                color = TEXT_MAIN, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(7.dp))
            if (selected != null) {
                Text(fmtFreq(selected.freq), color = Color(selected.color), fontSize = 10.5.sp)
                Spacer(Modifier.width(7.dp))
                Text(fmtDb(selected.gainDb), color = Color(selected.color), fontSize = 10.5.sp)
                Spacer(Modifier.width(7.dp))
                Text("Q ${fmtQ(selected.q)}", color = Color(selected.color), fontSize = 10.5.sp)
            }
            if (landscape) {
                Spacer(Modifier.width(10.dp))
                for (t in EqType.values()) {
                    TypeChip(t.short, active = selected?.type == t) {
                        if (selected != null) {
                            ProQ.update(selected.id) {
                                it.copy(type = t, gainDb = if (t.usesGain) it.gainDb else 0f)
                            }
                        }
                    }
                    Spacer(Modifier.width(3.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            if (selected != null) {
                SmallChip(if (selected.enabled) "开" else "关", active = selected.enabled) {
                    ProQ.update(selected.id) { it.copy(enabled = !it.enabled) }
                }
                Spacer(Modifier.width(4.dp))
                SmallChip("删除", danger = true) { ProQ.removeBand(selected.id) }
                Spacer(Modifier.width(4.dp))
            }
            SmallChip("复位") { ProQ.resetAll() }
        }

        if (!landscape) {
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (t in EqType.values()) {
                    TypeChip(t.short, active = selected?.type == t) {
                        if (selected != null) {
                            ProQ.update(selected.id) {
                                it.copy(type = t, gainDb = if (t.usesGain) it.gainDb else 0f)
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }

        Spacer(Modifier.height(if (landscape) 3.dp else 5.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("输出增益", color = AXIS_DIM, fontSize = 10.sp)
            Spacer(Modifier.width(5.dp))
            SmallChip("-") { ProQ.outputGainDb = (ProQ.outputGainDb - 0.5f).coerceIn(-24f, 24f) }
            Text(
                fmtDb(ProQ.outputGainDb), color = TEXT_MAIN, fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 5.dp)
            )
            SmallChip("+") { ProQ.outputGainDb = (ProQ.outputGainDb + 0.5f).coerceIn(-24f, 24f) }
            Spacer(Modifier.width(12.dp))
            Text("拖节点改频率/增益 · 拖右侧小点改 Q", color = Color(0xFF6B7483), fontSize = 10.sp)
        }
    }
}

/** Q4 底部状态条：MIDI Learn / 相位模式 / 分析器 / 缩放 / 输出电平 */
@Composable
private fun EqStatusBar(landscape: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (landscape) 18.dp else 20.dp)
            .background(STATUS_BG)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusText("MIDI 学习")
        StatusText("Zero Latency")
        StatusText(if (ProQ.analyzerOn) "Analyzer: Pre+Post+SC" else "Analyzer: Off")
        if (landscape) {
            StatusText("Character: Clean")
            StatusText("① 100%")
        }
        Spacer(Modifier.weight(1f))
        StatusText("${ProQ.bands.size} Bands · ${fmtDb(ProQ.outputGainDb)}")
    }
}

@Composable
private fun StatusText(t: String) {
    Text(t, color = Color(0xFF8A94A3), fontSize = 8.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(end = 12.dp))
}

private data class Plot(val left: Float, val top: Float, val w: Float, val h: Float) {
    fun x(freq: Float): Float = left + freqToFrac(freq) * w
    fun y(db: Float): Float = top + (0.5f - db / (2 * DB_RANGE)) * h
    fun freqAt(x: Float): Float = fracToFreq(((x - left) / w).coerceIn(0f, 1f))
    fun dbAt(y: Float): Float = ((0.5f - (y - top) / h) * 2 * DB_RANGE)
}

/** 左侧 dB 刻度、右侧双通道电平条 */
private fun plotOf(w: Float, h: Float) = Plot(w * 0.07f, h * 0.035f, w * 0.87f, h * 0.915f)

@Composable
private fun EqCanvas(spectrum: FloatArray, levelDbL: Float, levelDbR: Float, modifier: Modifier) {
    val bands = ProQ.bands
    val selectedId = ProQ.selectedId
    val density = LocalDensity.current.density
    val hitR = 30f * density
    val qOffset = 34f * density

    var dragId by remember { mutableStateOf(0) }
    var dragQ by remember { mutableStateOf(false) }

    fun nearestTo(px: Float, py: Float, plot: Plot): EqBand? {
        var best: EqBand? = null
        var bestD = Float.MAX_VALUE
        for (b in ProQ.bands) {
            val dx = plot.x(b.freq) - px
            val hDb = when (b.type) {
                EqType.LOW_SHELF, EqType.HIGH_SHELF -> b.gainDb / 2f
                EqType.HIGH_PASS, EqType.LOW_PASS, EqType.NOTCH -> 0f
                else -> b.gainDb
            }
            val dy = plot.y(hDb) - py
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = b }
        }
        return if (bestD <= hitR * hitR) best else null
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val plot = plotOf(size.width.toFloat(), size.height.toFloat())
                    val hit = nearestTo(off.x, off.y, plot)
                    if (hit != null) {
                        ProQ.selectedId = hit.id
                    } else {
                        ProQ.newBand(plot.freqAt(off.x), plot.dbAt(off.y).coerceIn(-12f, 12f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        val plot = plotOf(size.width.toFloat(), size.height.toFloat())
                        val hit = nearestTo(off.x, off.y, plot)
                        dragId = hit?.id ?: 0
                        dragQ = false
                        if (hit != null) {
                            ProQ.selectedId = hit.id
                            val qx = plot.x(hit.freq) + qOffset
                            if (abs(off.x - qx) < 26f * density && hit.type.usesGain) dragQ = true
                        }
                    },
                    onDragEnd = { dragId = 0; dragQ = false },
                    onDragCancel = { dragId = 0; dragQ = false },
                    onDrag = { change, amount ->
                        change.consume()
                        val id = dragId
                        if (id == 0) return@detectDragGestures
                        val band = ProQ.bands.firstOrNull { it.id == id } ?: return@detectDragGestures
                        val plot = plotOf(size.width.toFloat(), size.height.toFloat())
                        if (dragQ) {
                            val nq = (band.q * (1f + amount.y / plot.h * 2.5f)).coerceIn(0.1f, 20f)
                            ProQ.update(id) { it.copy(q = nq) }
                        } else {
                            val fr = (freqToFrac(band.freq) + amount.x / plot.w).coerceIn(0f, 1f)
                            val scale = if (band.type == EqType.LOW_SHELF || band.type == EqType.HIGH_SHELF) 2f else 1f
                            val ng = (band.gainDb - amount.y / plot.h * 2 * DB_RANGE * scale).coerceIn(-DB_RANGE, DB_RANGE)
                            ProQ.update(id) {
                                if (band.type.usesGain) it.copy(freq = fracToFreq(fr), gainDb = ng)
                                else it.copy(freq = fracToFreq(fr))
                            }
                        }
                    }
                )
            }
    ) {
        val plot = plotOf(size.width, size.height)
        val mono = Typeface.MONOSPACE

        // 面板底部轻微光晕
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x1A455468), Color(0x00000000)),
                center = Offset(plot.left + plot.w * 0.5f, plot.top + plot.h * 0.42f),
                radius = plot.w * 0.85f
            ),
            topLeft = Offset(plot.left, plot.top),
            size = Size(plot.w, plot.h)
        )

        // 网格：1/3 八度
        var oct = 0
        var f = MIN_F
        while (f <= MAX_F + 1f) {
            val x = plot.x(f)
            val strong = oct % 3 == 0
            drawLine(
                if (strong) GRID_MAJOR else GRID_MINOR,
                Offset(x, plot.top), Offset(x, plot.top + plot.h),
                if (strong) 1.1f else 1f
            )
            oct++
            f = (MIN_F * 2.0.pow(oct / 3.0)).toFloat()
        }
        for (d in 10 downTo -10 step 2) {
            val y = plot.y(d.toFloat())
            val major = d % 4 == 0
            drawLine(
                if (d == 0) Color(0xFF4E5866) else if (major) GRID_MAJOR else GRID_MINOR,
                Offset(plot.left, y), Offset(plot.left + plot.w, y),
                if (d == 0) 1.7f else if (major) 1.1f else 1f
            )
        }

        // 频谱（Q4 里是浅灰剪影）
        if (ProQ.analyzerOn && spectrum.any { it > 0f }) {
            val path = Path()
            for (i in spectrum.indices) {
                val x = plot.left + plot.w * (i.toFloat() / (spectrum.size - 1))
                val y = plot.top + plot.h * (1f - spectrum[i] * 0.94f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val filled = Path().apply {
                addPath(path)
                lineTo(plot.left + plot.w, plot.top + plot.h)
                lineTo(plot.left, plot.top + plot.h)
                close()
            }
            drawPath(
                filled,
                brush = Brush.verticalGradient(
                    listOf(Color(0x447C8A9C), Color(0x0A7C8A9C)),
                    startY = plot.top, endY = plot.top + plot.h
                ),
                style = Fill
            )
            drawPath(path, Color(0x8F9CA9B8), style = Stroke(width = 1.1f))
        }

        // 频段
        for (b in bands) {
            val color = Color(b.color)
            val isSel = b.id == selectedId
            if (b.enabled) {
                val n = plot.w.toInt().coerceIn(80, 400)
                val path = Path()
                for (i in 0 until n) {
                    val fr = i.toFloat() / (n - 1)
                    val x = plot.left + plot.w * fr
                    val y = plot.y(ProQ.bandMagnitudeDb(b, fracToFreq(fr).toDouble()).toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val filled = Path().apply {
                    addPath(path)
                    lineTo(plot.left + plot.w, plot.y(0f))
                    lineTo(plot.left, plot.y(0f))
                    close()
                }
                // 竖直渐变填充：贴近曲线处更浓（Q4 观感）
                drawPath(filled, color.copy(alpha = if (isSel) 0.30f else 0.17f), style = Fill)
                drawPath(path, color.copy(alpha = if (isSel) 0.45f else 0.22f), style = Stroke(width = 7f))
                drawPath(path, if (isSel) color else color.copy(alpha = 0.9f),
                    style = Stroke(width = if (isSel) 2.4f else 1.9f))
            }

            val nx = plot.x(b.freq)
            val handleDb = when (b.type) {
                EqType.LOW_SHELF, EqType.HIGH_SHELF -> b.gainDb / 2f
                EqType.HIGH_PASS, EqType.LOW_PASS, EqType.NOTCH -> 0f
                else -> b.gainDb
            }
            val ny = plot.y(handleDb)
            val r = if (isSel) 8.5f * density else 6f * density
            if (b.enabled) {
                drawCircle(color.copy(alpha = 0.30f), radius = r + 5f * density, center = Offset(nx, ny))
            }
            drawCircle(if (b.enabled) color else color.copy(alpha = 0.30f), radius = r, center = Offset(nx, ny))
            drawCircle(
                if (isSel) Color.White else Color(0xFF0E1116),
                radius = r, center = Offset(nx, ny),
                style = Stroke(width = if (isSel) 2.4f * density else 1.2f * density)
            )

            if (isSel && b.type.usesGain) {
                drawCircle(Color(0xFF0F1216), radius = 4.6f * density, center = Offset(nx + qOffset, ny))
                drawCircle(Color(0xFFCAD3DF), radius = 4.6f * density, center = Offset(nx + qOffset, ny),
                    style = Stroke(width = 1.3f * density))
            }

            if (!isSel && b.enabled && b.type.usesGain) {
                val txt = fmtDb(b.gainDb)
                val lp = Paint().apply {
                    isAntiAlias = true
                    textSize = 8f * density
                    typeface = mono
                }
                lp.color = b.color.toInt()
                val tw = lp.measureText(txt)
                val lx = (nx + r + 4f * density)
                val ly = ny - 7f * density
                drawRoundRect(
                    color.copy(alpha = 0.16f),
                    topLeft = Offset(lx - 3f * density, ly - 1f * density),
                    size = Size(tw + 6f * density, 11.5f * density),
                    cornerRadius = CornerRadius(2.5f * density)
                )
                drawContext.canvas.nativeCanvas.drawText(txt, lx, ly + 8f * density, lp)
            }
        }

        // 右上角量程徽标
        val badge = Paint().apply {
            isAntiAlias = true
            color = 0xFFC9A227.toInt()
            textSize = 9f * density
            typeface = mono
            textAlign = Paint.Align.RIGHT
        }
        drawContext.canvas.nativeCanvas.drawText(
            "+${DB_RANGE.toInt()} dB", plot.left + plot.w - 4f * density, plot.top + 12f * density, badge
        )

        // 左 dB 刻度
        val axisPaint = Paint().apply {
            isAntiAlias = true
            textSize = 8.5f * density
            typeface = mono
        }
        for (d in 10 downTo -10 step 4) {
            val label = when {
                d > 0 -> "+$d"
                d == 0 -> "0"
                else -> "$d"
            }
            val lp = Paint(axisPaint).apply {
                textAlign = Paint.Align.RIGHT
                color = if (d == 0) 0xFFB6C0CD.toInt() else AXIS_DIM.toArgb()
            }
            drawContext.canvas.nativeCanvas.drawText(label, plot.left - 6f * density, plot.y(d.toFloat()) + 3f * density, lp)
            drawLine(
                Color(0xFF48515E),
                Offset(plot.left - 3.5f * density, plot.y(d.toFloat())),
                Offset(plot.left, plot.y(d.toFloat())),
                1f
            )
        }

        // 底部频率刻度
        val majorFreqs = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
        for (fr in majorFreqs) {
            val label = if (fr >= 1000f) "${(fr / 1000f).toInt()}k" else "${fr.toInt()}"
            val tp = Paint(axisPaint).apply {
                textAlign = Paint.Align.CENTER
                color = AXIS_DIM.toArgb()
            }
            val lx = plot.x(fr).coerceIn(plot.left + 9f * density, plot.left + plot.w - 9f * density)
            drawContext.canvas.nativeCanvas.drawText(label, lx, plot.top + plot.h + 11.5f * density, tp)
            drawLine(
                Color(0xFF48515E),
                Offset(plot.x(fr), plot.top + plot.h),
                Offset(plot.x(fr), plot.top + plot.h + 3.5f * density),
                1f
            )
        }

        // 右双通道电平条
        val mLeft = plot.left + plot.w + 7f * density
        val mWidth = 4.5f * density
        val gap = 2.5f * density
        for (ch in 0 until 2) {
            val lv = (((if (ch == 0) levelDbL else levelDbR) + 60f) / 60f).coerceIn(0f, 1f)
            val x = mLeft + ch * (mWidth + gap)
            drawRect(Color(0xFF0C0F13), topLeft = Offset(x, plot.top), size = Size(mWidth, plot.h))
            val mh = plot.h * lv
            if (mh > 1f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF57C46A), Color(0xFFD6C247), Color(0xFFE0574A)),
                        startY = plot.top + plot.h, endY = plot.top
                    ),
                    topLeft = Offset(x, plot.top + plot.h - mh),
                    size = Size(mWidth, mh)
                )
            }
            drawRect(
                Color(0x2E323A46), topLeft = Offset(x, plot.top), size = Size(mWidth, plot.h),
                style = Stroke(width = 1f)
            )
        }

        // 选中读数浮窗（带彩色左边条）
        val sel = bands.firstOrNull { it.id == selectedId }
        if (sel != null) {
            val text = "${fmtFreq(sel.freq)}   ${fmtDb(sel.gainDb)}   Q ${fmtQ(sel.q)}"
            val tp = Paint().apply {
                isAntiAlias = true
                color = 0xFFEDF1F7.toInt()
                textSize = 9.5f * density
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val tw = tp.measureText(text)
            val bx = (plot.x(sel.freq) - tw / 2f)
                .coerceIn(plot.left + 4f * density, plot.left + plot.w - tw - 4f * density)
            val selDb = when (sel.type) {
                EqType.LOW_SHELF, EqType.HIGH_SHELF -> sel.gainDb / 2f
                EqType.HIGH_PASS, EqType.LOW_PASS, EqType.NOTCH -> 0f
                else -> sel.gainDb
            }
            val by = (plot.y(selDb) - 24f * density)
                .coerceIn(plot.top + 2f * density, plot.top + plot.h - 20f * density)
            val padH = 7f * density
            drawRoundRect(
                Color(0xF20A0C10),
                topLeft = Offset(bx - padH, by - 14f * density),
                size = Size(tw + padH * 2, 20f * density),
                cornerRadius = CornerRadius(4f * density)
            )
            drawRoundRect(
                Color(sel.color),
                topLeft = Offset(bx - padH, by - 14f * density),
                size = Size(2.5f * density, 20f * density),
                cornerRadius = CornerRadius(4f * density)
            )
            drawContext.canvas.nativeCanvas.drawText(text, bx, by + 1f * density, tp)
        }
    }
}

@Composable
private fun SmallChip(
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        danger -> Color(0xFF3A2022)
        active -> ACCENT.copy(alpha = 0.20f)
        else -> Color(0xFF232930)
    }
    Text(
        label,
        color = when {
            danger -> Color(0xFFFF9A9A)
            active -> ACCENT
            else -> Color(0xFFC2C9D4)
        },
        fontSize = 9.5.sp,
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .border(1.dp, if (active) ACCENT.copy(alpha = 0.6f) else Color(0xFF303742), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.5.dp, vertical = 3.5.dp)
    )
}

@Composable
private fun BandChip(band: EqBand, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(if (selected) Color(0xFF282F3A) else Color(0xFF1E242C), RoundedCornerShape(4.dp))
            .border(1.dp, if (selected) Color(band.color) else Color(0xFF303742), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.5.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.5.dp)
                .background(Color(band.color), RoundedCornerShape(50))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "${band.id}-${band.type.short}",
            color = if (band.enabled) TEXT_MAIN else Color(0xFF6B7280),
            fontSize = 9.sp
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (band.type.usesGain) fmtFreq(band.freq) + " " + fmtDb(band.gainDb) else fmtFreq(band.freq),
            color = Color(0xFF98A2B0), fontSize = 8.sp
        )
    }
}

@Composable
private fun TypeChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.White else Color(0xFFB4BDCA),
        fontSize = 9.sp,
        modifier = Modifier
            .background(if (active) ACCENT.copy(alpha = 0.30f) else Color(0xFF232930), RoundedCornerShape(4.dp))
            .border(1.dp, if (active) ACCENT.copy(alpha = 0.7f) else Color(0xFF303742), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.5.dp, vertical = 2.5.dp)
    )
}
