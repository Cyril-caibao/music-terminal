package com.multitrack.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multitrack.app.ui.theme.Palette
import kotlin.math.max
import kotlin.math.pow

/**
 * 底部主输出电平条（对标 AU 底部那块「电平」表）。
 *
 * 取的是 [com.multitrack.app.audio.AudioEngine] 在渲染线程里算好的总线上真实峰值 ——
 * 包括多轨叠加过载之后的电平，不是"估算"。所以推爆的时候它真的会顶到红区。
 *
 * - **左右分列**：L/R 各一条、各自读数。单条表看不出一边过载一边正常，也看不出声像塌了；
 * - **过载灯**：本块只要有采样撞到 0dBFS 就点亮，并且**锁存**——瞬态只有几毫秒，
 *   不锁存的灯等于没做。点一下灯（或下次按播放）复位；
 * - 横向范围 -60…+6 dBFS，0dB 处画一条亮线（AU 里 0 是"该收手了"的位置）；
 * - 峰值保持 1.5 秒再以 1.2dB/帧回落，眼睛来得及看清瞬态；
 * - 条子本身用渐变色：绿 → 黄 → 橙 → 红，由 dB 决定而不是由像素决定。
 */
@Composable
fun LevelMeterBar(state: EditorState, overrideDb: Float? = null) {
    var levelL by remember { mutableFloatStateOf(-60f) }
    var levelR by remember { mutableFloatStateOf(-60f) }
    var peakL by remember { mutableFloatStateOf(-60f) }
    var peakR by remember { mutableFloatStateOf(-60f) }
    var peakAtL by remember { mutableLongStateOf(0L) }
    var peakAtR by remember { mutableLongStateOf(0L) }
    var clipL by remember { mutableStateOf(false) }
    var clipR by remember { mutableStateOf(false) }

    LaunchedEffect(state.playing, overrideDb) {
        if (overrideDb != null) {
            levelL = overrideDb
            levelR = overrideDb
            peakL = overrideDb
            peakR = overrideDb
            return@LaunchedEffect
        }
        var lastFrameNs = 0L
        while (true) {
            // 跟显示帧走：一帧取一次，和声音落在同一个时间刻度上
            val frameNs = androidx.compose.runtime.withFrameNanos { it }
            val dtSec = if (lastFrameNs == 0L) 0f else (frameNs - lastFrameNs) / 1e9f
            lastFrameNs = frameNs
            val on = state.playing
            val dbL = if (on) state.engine.levelDbL else -60f
            val dbR = if (on) state.engine.levelDbR else -60f
            // 弹道（起振瞬时、22dB/秒回落）在引擎里做，界面直接照抄。
            // 以前引擎给瞬时值、界面再平滑 0.9/0.3 —— 两级平滑叠起来就是"表总是慢半拍"。
            levelL = dbL
            levelR = dbR
            val now = SystemClock.uptimeMillis()
            // 峰值保持线的下落必须按**时间**算：改成帧驱动后，"每帧 0.8dB" 在 120Hz 屏上
            // 会变成 96dB/秒，一眼就掉没了。
            val fall = 18f * dtSec
            if (dbL >= peakL) {
                peakL = dbL; peakAtL = now
            } else if (now - peakAtL > 1500L) {
                peakL = max(dbL, peakL - fall)
            }
            if (dbR >= peakR) {
                peakR = dbR; peakAtR = now
            } else if (now - peakAtR > 1500L) {
                peakR = max(dbR, peakR - fall)
            }
            // 灯只看引擎的锁存位，而且**各看各的**：只有左声道爆了却两条灯一起亮，
            // 等于把"哪一边炸的"这个唯一有用的信息抹掉了
            clipL = state.engine.clipL
            clipR = state.engine.clipR
        }
    }

    val density = LocalDensity.current
    // 刻度数字直接画在 Canvas 上：和条子共用同一套坐标，才不会像用 Row 排时那样错开几个像素
    val tickPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    tickPaint.textSize = with(density) { 8.5.sp.toPx() }
    tickPaint.color = Palette.TextDim.toArgb()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Palette.TimecodeBg)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "电平",
            color = Palette.TextWeak,
            fontSize = 10.5.sp,
            modifier = Modifier.width(30.dp)
        )
        StereoLevelBar(
            dbL = levelL,
            dbR = levelR,
            peakDbL = peakL,
            peakDbR = peakR,
            tickPaint = tickPaint,
            modifier = Modifier.weight(1f).height(32.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.width(64.dp)) {
            ChannelReadout(
                tag = "L", db = levelL, hot = clipL || levelL > 0.05f,
                clipped = clipL, onReset = { state.engine.clearClip() }
            )
            ChannelReadout(
                tag = "R", db = levelR, hot = clipR || levelR > 0.05f,
                clipped = clipR, onReset = { state.engine.clearClip() }
            )
        }
        Text(
            "dB",
            color = Palette.TextDim, fontSize = 9.sp, modifier = Modifier.width(16.dp)
        )
    }
}

/** 一个声道的读数：名字 + dB + 过载灯 */
@Composable
internal fun ChannelReadout(
    tag: String,
    db: Float,
    hot: Boolean,
    clipped: Boolean,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.height(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tag, color = Palette.TextWeak, fontSize = 8.5.sp, modifier = Modifier.width(9.dp))
        Text(
            formatDb(db),
            color = when {
                hot -> Palette.Mute
                db > -6f -> Color(0xFFE0A03C)
                else -> Palette.Text
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        ClipLamp(
            lit = clipped,
            onClick = onReset,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/**
 * 过载警告灯。
 *
 * 亮的时候外面套一圈半透明光晕 —— 纯红方块扔在深色条上不够"亮"，光晕才是信号。
 * 点一下复位；没过载时它是个暗着的空壳，不抢注意力。
 */
@Composable
internal fun ClipLamp(lit: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (lit) Color(0xFFFF3B30)
    else if (Palette.lightMode) Color(0xFFCFCFCF) else Color(0xFF2A2A2A)
    Canvas(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "复位过载指示", onClick = onClick)
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        if (lit) {
            drawCircle(color = Color(0x66FF3B30), radius = size.minDimension / 2f)
        }
        drawCircle(color = color, radius = size.minDimension / 2f * 0.62f)
        if (lit) {
            // 高光点：让灯看起来是"发出来的"，而不是涂上去的
            drawCircle(
                color = Color(0x99FFFFFF),
                radius = size.minDimension / 2f * 0.2f,
                center = Offset(c.x - size.width * 0.12f, c.y - size.height * 0.12f)
            )
        }
    }
}

@Composable
internal fun StereoLevelBar(
    dbL: Float,
    dbR: Float,
    peakDbL: Float,
    peakDbR: Float,
    tickPaint: android.graphics.Paint,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val barH = 8.dp.toPx()
        val gap = 2.5.dp.toPx()
        fun top(ch: Int) = if (ch == 0) 0f else barH + gap
        drawBar(dbL, peakDbL, top(0), barH, tickPaint, labelTicks = false)
        drawBar(dbR, peakDbR, top(1), barH, tickPaint, labelTicks = true)
    }
}

private const val MIN_DB = -60f
private const val MAX_DB = 6f

private fun fracDb(v: Float): Float = ((v - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)

private val BAR_GRADIENT = Brush.horizontalGradient(
    0f to Color(0xFF2FA35C),
    0.72f to Color(0xFF5BC46B),
    0.86f to Color(0xFFD9C24A),
    0.93f to Color(0xFFE08A3C),
    1f to Color(0xFFD9493C)
)

/**
 * 画一条电平：[db] 是当前值、[peakDb] 是保持峰值，[top] 是这条在画布里的纵向起点。
 * 刻度线只画在条子范围内（[labelTicks] 决定这条底下要不要画数字），
 * 两条共用一套 x 坐标，L/R 才能一眼比出高低。
 */
private fun DrawScope.drawBar(
    db: Float,
    peakDb: Float,
    top: Float,
    barH: Float,
    tickPaint: android.graphics.Paint,
    labelTicks: Boolean
) {
    val w = size.width
    val r = barH / 2

    // 槽
    drawRoundRect(
        color = if (Palette.lightMode) Color(0xFFDCDCDC) else Color(0xFF151515),
        topLeft = Offset(0f, top),
        size = Size(w, barH),
        cornerRadius = CornerRadius(r, r)
    )
    // 刻度（每 6dB 一格，0dB 更亮）
    var tick = MIN_DB
    while (tick <= MAX_DB) {
        val x = w * fracDb(tick)
        val zero = tick >= -0.01f && tick <= 0.01f
        drawLine(
            color = if (zero) {
                if (Palette.lightMode) Color(0xFF1F1F1F) else Color(0xFFFFFFFF)
            } else if (Palette.lightMode) Color(0x33000000) else Color(0x33FFFFFF),
            start = Offset(x, top),
            end = Offset(x, top + barH),
            strokeWidth = if (zero) 1.8f else 1f
        )
        tick += 6f
    }
    // 电平本体：整条渐变按 dB 布色，clip 到当前水平
    clipRect(left = 0f, top = top, right = w * fracDb(db), bottom = top + barH) {
        drawRoundRect(
            brush = BAR_GRADIENT,
            topLeft = Offset(0f, top),
            size = Size(w, barH),
            cornerRadius = CornerRadius(r, r)
        )
    }
    // 峰值保持线
    if (peakDb > -59.5f) {
        val px = (w * fracDb(peakDb)).coerceIn(1f, w - 1f)
        drawLine(
            color = if (peakDb > 0f) Color(0xFFFF5A4A) else Palette.Text,
            start = Offset(px, top),
            end = Offset(px, top + barH),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )
    }
    if (labelTicks) {
        val labels = listOf(-48, -36, -24, -12, 0)
        for (t in labels) {
            val label = "$t"
            val x = w * fracDb(t.toFloat())
            val tw = tickPaint.measureText(label)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                (x - tw / 2f).coerceIn(0f, w - tw),
                top + barH + 9.dp.toPx(),
                tickPaint
            )
        }
    }
}

/** 供别处复用的 dB 转线性（测试也会用到，省得两边各写一份） */
internal fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

/** 线性幅度转 dBFS，-60 以下统一当 -60（表底） */
internal fun linearToDb(v: Float): Float =
    if (v <= 0.0001f) -60f else (20.0 * kotlin.math.log10(v.toDouble())).toFloat()

/**
 * 电平条上的读数。小于表底显示 -∞；
 * 同时把 -0.0 / 0.04 这种"贴着零"的值归到 0.0，免得读数在 -0.0 和 0.0 之间跳。
 */
internal fun formatDb(db: Float): String {
    if (db <= -59.5f) return "-∞"
    val v = if (kotlin.math.abs(db) < 0.05f) 0f else db
    return "%.1f".format(v)
}
