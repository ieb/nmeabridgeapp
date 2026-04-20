package uk.co.tfd.nmeabridge.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.co.tfd.nmeabridge.nmea.EngineAlarm
import uk.co.tfd.nmeabridge.nmea.EngineAlarmSeverity
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val BezelOuter = Color(0xFF1A1B1D)
private val BezelInner = Color(0xFF3D3E42)
private val FaceColor = Color(0xFF0F1012)
private val TickWhite = Color(0xFFE4E4E4)
private val TickDim = Color(0xFF8A8A8A)
private val LabelWhite = Color(0xFFF2F2F2)
private val RedZone = Color(0xFFC62828)
private val NeedleRed = Color(0xFFDC2B1D)
private val NeedleDim = Color(0xFF555555)
private val HubSilver = Color(0xFFB0B0B0)
private val HubDark = Color(0xFF1A1A1A)
private val DigitalGreen = Color(0xFFB3FF66)
private val DigitalWindow = Color(0xFF0A0A0A)
private val PillAlarm = Color(0xFFE53935)
private val PillWarning = Color(0xFFFFA000)
private val PillInfo = Color(0xFF455A64)

/**
 * Rendered as a clickable pill inside the tachometer. Used for real engine
 * alarms (red/amber) and for synthetic status indicators (grey-blue) like
 * "Connecting" or "Engine Off".
 */
data class StatusPill(
    val shortCode: String,
    val label: String,
    val color: Color
) {
    companion object {
        fun fromAlarm(alarm: EngineAlarm): StatusPill {
            val color = when (alarm.severity) {
                EngineAlarmSeverity.ALARM -> PillAlarm
                EngineAlarmSeverity.WARNING -> PillWarning
            }
            return StatusPill(alarm.shortCode, alarm.label, color)
        }

        val Connecting = StatusPill("CONN", "Connecting", PillInfo)
        val Idle = StatusPill("IDLE", "Idle", PillInfo)
        val Off = StatusPill("OFF", "Engine off", PillInfo)
    }
}

// --------- Public dial composables ---------

@Composable
fun TachometerDial(
    rpm: Int?,
    engineHoursSec: Long?,
    statusPills: List<StatusPill> = emptyList(),
    modifier: Modifier = Modifier
) {
    val maxRpm = 4000f
    val redStartRpm = 3600f
    val start = 135f
    val sweep = 270f
    val target = (rpm ?: 0).toFloat().coerceIn(0f, maxRpm)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "tach"
    )
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val minDim = if (maxWidth < maxHeight) maxWidth else maxHeight
        Canvas(Modifier.fillMaxSize()) {
            drawFace()
            drawRedZoneArc(
                startAngle = start + sweep * (redStartRpm / maxRpm),
                sweepAngle = sweep * (1f - redStartRpm / maxRpm)
            )
            drawMinorTicks(start, sweep, count = 40, color = TickDim, density)
            // Medium ticks at 500, 1500, 2500, 3500 rpm — halfway between majors.
            drawMediumTicks(
                start, sweep,
                fractions = listOf(0.125f, 0.375f, 0.625f, 0.875f),
                color = TickWhite,
                density = density
            )
            drawMajorTicks(start, sweep, count = 4, color = TickWhite, density)
            drawRadialLabels(
                start, sweep,
                labels = listOf("0", "10", "20", "30", "40"),
                textSizeSp = 18f,
                radiusFrac = 0.70f,
                density = density
            )
            drawCaption("rpm × 100", offsetYFrac = 0.22f, textSizeSp = 16.5f, density = density)
            drawHourMeterWindow(engineHoursSec, density)
            val frac = animated / maxRpm
            drawNeedle(
                angleDeg = start + sweep * frac,
                lengthFrac = 0.84f,
                baseWidthFrac = 0.030f,
                available = rpm != null
            )
            drawHub()
            drawCenterReadout(
                text = rpm?.let { "$it" } ?: "— —",
                subtitle = null,
                available = rpm != null,
                density = density,
                textSizeSp = 22f,
                offsetYFrac = -0.30f
            )
        }
        // Clickable status pills overlay: positioned just below the hour-meter
        // window, inside the dial face. Tapping a pill shows the full label
        // description in a tooltip.
        if (statusPills.isNotEmpty()) {
            StatusPillOverlay(
                pills = statusPills,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = minDim * 0.32f)
                    .fillMaxWidth(0.82f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StatusPillOverlay(
    pills: List<StatusPill>,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        pills.forEach { pill -> StatusPillView(pill) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusPillView(pill: StatusPill) {
    val tooltipState = rememberTooltipState(isPersistent = false)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(pill.label)
            }
        },
        state = tooltipState
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(pill.color)
                .clickable { scope.launch { tooltipState.show() } }
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = pill.shortCode,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun CoolantDial(
    tempC: Double?,
    modifier: Modifier = Modifier
) {
    // Range is symmetric around the normal operating temp so that 90 °C
    // (= (min+max)/2) sits at the 12 o'clock (straight-up) position.
    val minT = 60f
    val maxT = 120f
    val redStart = 104f
    val start = 180f
    val sweep = 180f
    val target = ((tempC ?: minT.toDouble()).toFloat()).coerceIn(minT, maxT)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f),
        label = "coolant"
    )
    val density = LocalDensity.current

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawFace()
            val redFrac = (redStart - minT) / (maxT - minT)
            drawRedZoneArc(
                startAngle = start + sweep * redFrac,
                sweepAngle = sweep * (1f - redFrac)
            )
            drawMinorTicks(start, sweep, count = 12, color = TickDim, density)
            drawMajorTicks(start, sweep, count = 4, color = TickWhite, density)
            drawRadialLabels(
                start, sweep,
                labels = listOf("60", "75", "90", "105", "120"),
                textSizeSp = 14f,
                radiusFrac = 0.66f,
                density = density
            )
            drawCaption("TEMP °C", offsetYFrac = -0.35f, textSizeSp = 10f, density = density)
            val frac = ((animated - minT) / (maxT - minT)).coerceIn(0f, 1f)
            drawNeedle(
                angleDeg = start + sweep * frac,
                lengthFrac = 0.78f,
                baseWidthFrac = 0.035f,
                available = tempC != null
            )
            drawHub(small = true)
            drawCenterReadout(
                text = tempC?.let { "%.0f°".format(it) } ?: "— —",
                subtitle = null,
                available = tempC != null,
                density = density,
                textSizeSp = 18f,
                offsetYFrac = 0.30f
            )
        }
    }
}

@Composable
fun OilPressureDial(
    bar: Double?,
    modifier: Modifier = Modifier
) {
    val minP = 0f
    val maxP = 5f
    val redEnd = 1.0f
    val start = 180f
    val sweep = 180f
    val target = ((bar ?: 0.0).toFloat()).coerceIn(minP, maxP)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f),
        label = "oil"
    )
    val density = LocalDensity.current

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawFace()
            val redFrac = (redEnd - minP) / (maxP - minP)
            drawRedZoneArc(
                startAngle = start,
                sweepAngle = sweep * redFrac
            )
            drawMinorTicks(start, sweep, count = 20, color = TickDim, density)
            drawMajorTicks(start, sweep, count = 5, color = TickWhite, density)
            drawRadialLabels(
                start, sweep,
                labels = listOf("0", "1", "2", "3", "4", "5"),
                textSizeSp = 16f,
                radiusFrac = 0.68f,
                density = density
            )
            drawCaption("OIL bar", offsetYFrac = -0.35f, textSizeSp = 10f, density = density)
            val frac = ((animated - minP) / (maxP - minP)).coerceIn(0f, 1f)
            drawNeedle(
                angleDeg = start + sweep * frac,
                lengthFrac = 0.78f,
                baseWidthFrac = 0.035f,
                available = bar != null
            )
            drawHub(small = true)
            drawCenterReadout(
                text = bar?.let { "%.1f".format(it) } ?: "— —",
                subtitle = null,
                available = bar != null,
                density = density,
                textSizeSp = 18f,
                offsetYFrac = 0.30f
            )
        }
    }
}

// --------- Drawing helpers ---------

private fun DrawScope.drawFace() {
    val c = center
    val r = minDim() / 2f
    drawCircle(BezelOuter, radius = r, center = c)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(BezelInner, BezelOuter),
            center = c,
            radius = r
        ),
        radius = r * 0.97f,
        center = c
    )
    drawCircle(FaceColor, radius = r * 0.91f, center = c)
}

private fun DrawScope.drawRedZoneArc(startAngle: Float, sweepAngle: Float) {
    val r = minDim() / 2f
    val arcR = r * 0.86f
    val strokeW = r * 0.06f
    drawArc(
        color = RedZone,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(center.x - arcR, center.y - arcR),
        size = Size(arcR * 2f, arcR * 2f),
        style = Stroke(width = strokeW, cap = StrokeCap.Butt)
    )
}

private fun DrawScope.drawMinorTicks(
    startAngle: Float, sweep: Float, count: Int,
    color: Color, density: Density
) {
    val r = minDim() / 2f
    val innerR = r * 0.80f
    val outerR = r * 0.86f
    val strokeW = with(density) { 1.dp.toPx() }
    for (i in 0..count) {
        val a = startAngle + sweep * i / count
        drawRadialLine(a, innerR, outerR, color, strokeW)
    }
}

private fun DrawScope.drawMajorTicks(
    startAngle: Float, sweep: Float, count: Int,
    color: Color, density: Density
) {
    val r = minDim() / 2f
    val innerR = r * 0.77f
    val outerR = r * 0.88f
    val strokeW = with(density) { 2.2.dp.toPx() }
    for (i in 0..count) {
        val a = startAngle + sweep * i / count
        drawRadialLine(a, innerR, outerR, color, strokeW)
    }
}

/**
 * Draw emphasis ticks at arbitrary sweep fractions. Slightly longer and bolder
 * than minor ticks but shorter than majors — used for the 500/1500/2500/3500 rpm
 * midpoints on the tachometer.
 */
private fun DrawScope.drawMediumTicks(
    startAngle: Float, sweep: Float, fractions: List<Float>,
    color: Color, density: Density
) {
    val r = minDim() / 2f
    val innerR = r * 0.78f
    val outerR = r * 0.87f
    val strokeW = with(density) { 1.8.dp.toPx() }
    for (frac in fractions) {
        val a = startAngle + sweep * frac
        drawRadialLine(a, innerR, outerR, color, strokeW)
    }
}

private fun DrawScope.drawRadialLine(
    angleDeg: Float, innerR: Float, outerR: Float,
    color: Color, strokeW: Float
) {
    val rad = Math.toRadians(angleDeg.toDouble())
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    drawLine(
        color,
        start = Offset(center.x + innerR * cosA, center.y + innerR * sinA),
        end = Offset(center.x + outerR * cosA, center.y + outerR * sinA),
        strokeWidth = strokeW,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawRadialLabels(
    startAngle: Float, sweep: Float,
    labels: List<String>,
    textSizeSp: Float,
    radiusFrac: Float,
    density: Density
) {
    if (labels.isEmpty()) return
    val r = minDim() / 2f
    val labelR = r * radiusFrac
    val textPx = with(density) { textSizeSp.sp.toPx() }
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(
            (LabelWhite.red * 255).toInt(),
            (LabelWhite.green * 255).toInt(),
            (LabelWhite.blue * 255).toInt()
        )
        textSize = textPx
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val step = if (labels.size > 1) sweep / (labels.size - 1) else 0f
    for ((i, label) in labels.withIndex()) {
        if (label.isEmpty()) continue
        val a = startAngle + step * i
        val rad = Math.toRadians(a.toDouble())
        val x = center.x + labelR * cos(rad).toFloat()
        val y = center.y + labelR * sin(rad).toFloat() + textPx * 0.33f
        drawContext.canvas.nativeCanvas.drawText(label, x, y, paint)
    }
}

private fun DrawScope.drawCaption(
    text: String, offsetYFrac: Float, textSizeSp: Float, density: Density
) {
    val r = minDim() / 2f
    val textPx = with(density) { textSizeSp.sp.toPx() }
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(180, 180, 180)
        textSize = textPx
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        letterSpacing = 0.15f
    }
    drawContext.canvas.nativeCanvas.drawText(
        text, center.x, center.y + r * offsetYFrac, paint
    )
}

private fun DrawScope.drawNeedle(
    angleDeg: Float,
    lengthFrac: Float,
    baseWidthFrac: Float,
    available: Boolean
) {
    val r = minDim() / 2f
    val len = r * lengthFrac
    val base = r * baseWidthFrac
    val tail = r * 0.10f
    val color = if (available) NeedleRed else NeedleDim
    rotate(angleDeg, center) {
        val path = Path().apply {
            moveTo(center.x - tail, center.y - base * 0.6f)
            lineTo(center.x - tail, center.y + base * 0.6f)
            lineTo(center.x + len * 0.92f, center.y + base * 0.15f)
            lineTo(center.x + len, center.y)
            lineTo(center.x + len * 0.92f, center.y - base * 0.15f)
            close()
        }
        drawPath(path, color = color)
    }
}

private fun DrawScope.drawHub(small: Boolean = false) {
    val r = minDim() / 2f
    val hubR = r * if (small) 0.07f else 0.09f
    drawCircle(HubDark, radius = hubR * 1.3f, center = center)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(HubSilver, HubDark),
            center = center.copy(x = center.x - hubR * 0.3f, y = center.y - hubR * 0.3f),
            radius = hubR
        ),
        radius = hubR,
        center = center
    )
}

private fun DrawScope.drawCenterReadout(
    text: String,
    subtitle: String?,
    available: Boolean,
    density: Density,
    textSizeSp: Float,
    offsetYFrac: Float
) {
    val r = minDim() / 2f
    val textPx = with(density) { textSizeSp.sp.toPx() }
    val paint = android.graphics.Paint().apply {
        color = if (available) android.graphics.Color.WHITE else android.graphics.Color.rgb(120, 120, 120)
        textSize = textPx
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    drawContext.canvas.nativeCanvas.drawText(
        text,
        center.x,
        center.y + r * offsetYFrac + textPx * 0.33f,
        paint
    )
    if (subtitle != null) {
        val subPaint = android.graphics.Paint(paint).apply {
            textSize = textPx * 0.55f
            color = android.graphics.Color.rgb(180, 180, 180)
            typeface = android.graphics.Typeface.DEFAULT
        }
        drawContext.canvas.nativeCanvas.drawText(
            subtitle,
            center.x,
            center.y + r * offsetYFrac + textPx * 1.2f,
            subPaint
        )
    }
}

private fun DrawScope.drawHourMeterWindow(engineHoursSec: Long?, density: Density) {
    val r = minDim() / 2f
    val w = r * 0.55f
    val h = r * 0.18f
    val topLeft = Offset(center.x - w / 2f, center.y + r * 0.37f)
    val cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f, w * 0.06f)
    drawRoundRect(
        color = DigitalWindow,
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = cornerRadius
    )
    drawRoundRect(
        color = HubDark,
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = cornerRadius,
        style = Stroke(width = with(density) { 1.dp.toPx() })
    )
    val label = if (engineHoursSec != null) {
        val hours = engineHoursSec / 3600
        val minutes = (engineHoursSec % 3600) / 60
        "%dh%02dm".format(hours, minutes)
    } else {
        "—h——m"
    }
    val textPx = with(density) { 14.sp.toPx() }
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(
            (DigitalGreen.red * 255).toInt(),
            (DigitalGreen.green * 255).toInt(),
            (DigitalGreen.blue * 255).toInt()
        )
        textSize = textPx
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    drawContext.canvas.nativeCanvas.drawText(
        label,
        center.x,
        topLeft.y + h * 0.70f,
        paint
    )
}

private fun DrawScope.minDim(): Float = min(size.width, size.height)
