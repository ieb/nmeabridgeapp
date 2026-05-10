package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeabridge.history.RingSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val AxisColor = Color(0xFF9E9E9E)
private val GridColor = Color(0x33888888)
private val CrosshairColor = Color(0xCCFFFFFF)

/**
 * One trace plotted on a [MultiSeriesChart]. The chart is stream-
 * agnostic: each series carries its own [RingSnapshot] (the chart's
 * x-axis is wall-clock time, so per-stream snapshots align naturally
 * via timestamps without further bookkeeping) and its own value reader.
 */
data class PlottedSeries(
    val label: String,
    val color: Color,
    val unit: UnitGroup,
    val format: String,
    val snapshot: RingSnapshot,
    /** Read value at slot `i` of the carried [snapshot]; null = no data. */
    val read: (Int) -> Double?,
)

/**
 * Renders one chart panel that may carry up to two unit groups (left
 * Y axis and right Y axis). All series in [leftSeries] must share one
 * [UnitGroup]; same for [rightSeries]. The two groups must differ
 * (caller's invariant — the chart doesn't enforce it).
 *
 * State that needs synchronising across multiple charts on the same
 * screen — `tStart` / `tEnd` / `crosshairMs` — is hoisted by the parent.
 * The chart reports its plot bounds via [onPlotLayout] so the parent's
 * gesture handler can convert pointer X to a timestamp.
 */
@Composable
fun MultiSeriesChart(
    leftSeries: List<PlottedSeries>,
    rightSeries: List<PlottedSeries>,
    tStart: Long,
    tEnd: Long,
    crosshairMs: Long?,
    modifier: Modifier = Modifier,
    onPlotLayout: (leftPx: Float, widthPx: Float) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current

    val (leftMin, leftMax) = axisRange(leftSeries, tStart, tEnd)
    val (rightMin, rightMax) = axisRange(rightSeries, tStart, tEnd)

    val leftUnit = leftSeries.firstOrNull()?.unit
    val rightUnit = rightSeries.firstOrNull()?.unit

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        // Top legend: series labels and current values.
        SeriesLegend(
            leftSeries = leftSeries,
            rightSeries = rightSeries,
            crosshairMs = crosshairMs,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Canvas(
            // Fill the remaining height in the Column (the legend ate
            // some of the parent allowance). NO horizontal padding:
            // the chart's local x=0 must match the parent's x=0 so the
            // (plotLeftPx, plotWidthPx) reported via onPlotLayout
            // translates pointer-event x correctly. Adding any
            // horizontal pad here would silently de-sync the cursor
            // by exactly that pad.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 4.dp),
        ) {
            val leftPad = with(density) { (if (leftSeries.isNotEmpty()) 44.dp else 12.dp).toPx() }
            val rightPad = with(density) { (if (rightSeries.isNotEmpty()) 44.dp else 12.dp).toPx() }
            val topPad = with(density) { 6.dp.toPx() }
            // Bottom pad reserves room for the time-of-day x-axis labels.
            val bottomPad = with(density) { 16.dp.toPx() }
            val plotLeft = leftPad
            val plotRight = size.width - rightPad
            val plotTop = topPad
            val plotBottom = size.height - bottomPad
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            onPlotLayout(plotLeft, plotW)

            // Plot frame + horizontal grid.
            drawLine(AxisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 1f)
            drawLine(AxisColor, Offset(plotRight, plotTop), Offset(plotRight, plotBottom), 1f)
            drawLine(AxisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 1f)
            val gridSteps = 4
            for (k in 0..gridSteps) {
                val y = plotTop + plotH * k / gridSteps
                drawLine(GridColor, Offset(plotLeft, y), Offset(plotRight, y), 1f)
            }

            // Y-axis tick labels (left + right).
            val labelPaint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(0xFF, 0xB0, 0xB0, 0xB0)
                textSize = with(density) { 10.sp.toPx() }
                isAntiAlias = true
            }
            if (leftSeries.isNotEmpty()) {
                val fmt = pickAxisFormat(leftMin, leftMax)
                for (k in 0..gridSteps) {
                    val y = plotTop + plotH * k / gridSteps
                    val v = leftMax - (leftMax - leftMin) * k / gridSteps
                    drawContext.canvas.nativeCanvas.drawText(
                        fmt.format(v), 2f, y + 4f, labelPaint
                    )
                }
                if (leftUnit != null) {
                    drawContext.canvas.nativeCanvas.drawText(
                        leftUnit.label, 2f, plotTop - 2f, labelPaint
                    )
                }
            }
            if (rightSeries.isNotEmpty()) {
                val fmt = pickAxisFormat(rightMin, rightMax)
                val rightPaint = android.graphics.Paint(labelPaint).apply {
                    textAlign = android.graphics.Paint.Align.LEFT
                }
                for (k in 0..gridSteps) {
                    val y = plotTop + plotH * k / gridSteps
                    val v = rightMax - (rightMax - rightMin) * k / gridSteps
                    drawContext.canvas.nativeCanvas.drawText(
                        fmt.format(v), plotRight + 2f, y + 4f, rightPaint
                    )
                }
                if (rightUnit != null) {
                    drawContext.canvas.nativeCanvas.drawText(
                        rightUnit.label, plotRight + 2f, plotTop - 2f, rightPaint
                    )
                }
            }

            val tSpan = (tEnd - tStart).coerceAtLeast(1L)
            fun xOf(ms: Long): Float =
                plotLeft + plotW * ((ms - tStart).toDouble() / tSpan).toFloat()

            // X-axis ticks + time-of-day labels along the bottom.
            run {
                val intervalMs = pickTickIntervalMs(tEnd - tStart)
                val fmt = pickTickFormat(intervalMs)
                val xPaint = android.graphics.Paint(labelPaint).apply {
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val tickHalfHeight = with(density) { 3.dp.toPx() }
                val labelBaselineOffset = with(density) { 11.dp.toPx() }
                var t = firstTickAtOrAfter(tStart, intervalMs)
                while (t <= tEnd) {
                    val x = xOf(t)
                    if (x in plotLeft..plotRight) {
                        // Faint vertical gridline through the plot area, plus a
                        // short tick that crosses the bottom axis.
                        drawLine(
                            GridColor,
                            Offset(x, plotTop), Offset(x, plotBottom),
                            strokeWidth = 1f,
                        )
                        drawLine(
                            AxisColor,
                            Offset(x, plotBottom - tickHalfHeight),
                            Offset(x, plotBottom + tickHalfHeight),
                            strokeWidth = 1f,
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            fmt.format(java.util.Date(t)),
                            x, plotBottom + labelBaselineOffset, xPaint,
                        )
                    }
                    t += intervalMs
                }
            }

            // Series polylines.
            drawSeries(
                series = leftSeries,
                xOf = ::xOf,
                yMin = leftMin, yMax = leftMax,
                plotTop = plotTop, plotBottom = plotBottom, plotLeft = plotLeft, plotRight = plotRight,
                tStart = tStart, tEnd = tEnd,
            )
            drawSeries(
                series = rightSeries,
                xOf = ::xOf,
                yMin = rightMin, yMax = rightMax,
                plotTop = plotTop, plotBottom = plotBottom, plotLeft = plotLeft, plotRight = plotRight,
                tStart = tStart, tEnd = tEnd,
            )

            // Crosshair line + marker dots on every series.
            if (crosshairMs != null && crosshairMs in tStart..tEnd) {
                val cx = xOf(crosshairMs).coerceIn(plotLeft, plotRight)
                drawLine(
                    CrosshairColor,
                    Offset(cx, plotTop),
                    Offset(cx, plotBottom),
                    strokeWidth = 1f,
                )
                drawMarkers(leftSeries, crosshairMs, leftMin, leftMax, cx, plotTop, plotBottom)
                drawMarkers(rightSeries, crosshairMs, rightMin, rightMax, cx, plotTop, plotBottom)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    series: List<PlottedSeries>,
    xOf: (Long) -> Float,
    yMin: Double,
    yMax: Double,
    plotTop: Float,
    plotBottom: Float,
    plotLeft: Float,
    plotRight: Float,
    tStart: Long,
    tEnd: Long,
) {
    if (series.isEmpty()) return
    val ySpan = (yMax - yMin).coerceAtLeast(1e-9)
    val plotH = plotBottom - plotTop
    fun yOf(v: Double): Float =
        plotTop + plotH * ((yMax - v) / ySpan).toFloat()

    for (s in series) {
        val snap = s.snapshot
        if (snap.size == 0) continue
        val lo = snap.lowerBound(tStart)
        val hiExclusive = snap.upperBound(tEnd)
        val visStart = max(0, lo - 1)
        val visEnd = min(snap.size - 1, hiExclusive)
        if (visEnd < visStart) continue

        val path = Path()
        var penDown = false
        for (k in visStart..visEnd) {
            val v = s.read(k)
            if (v == null) {
                penDown = false
                continue
            }
            val t = snap.timestampAt(k)
            val x = xOf(t).coerceIn(plotLeft, plotRight)
            val y = yOf(v).coerceIn(plotTop, plotBottom)
            if (!penDown) {
                path.moveTo(x, y); penDown = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path, color = s.color, style = Stroke(width = 2f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarkers(
    series: List<PlottedSeries>,
    crosshairMs: Long,
    yMin: Double,
    yMax: Double,
    cx: Float,
    plotTop: Float,
    plotBottom: Float,
) {
    if (series.isEmpty()) return
    val ySpan = (yMax - yMin).coerceAtLeast(1e-9)
    val plotH = plotBottom - plotTop
    for (s in series) {
        val idx = nearestIndex(s.snapshot, crosshairMs)
        if (idx < 0) continue
        val v = s.read(idx) ?: continue
        val y = (plotTop + plotH * ((yMax - v) / ySpan).toFloat()).coerceIn(plotTop, plotBottom)
        drawCircle(s.color, radius = 3.5f, center = Offset(cx, y))
    }
}

@Composable
private fun SeriesLegend(
    leftSeries: List<PlottedSeries>,
    rightSeries: List<PlottedSeries>,
    crosshairMs: Long?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in leftSeries + rightSeries) {
            val v = displayedValueFor(s, crosshairMs)
            val text = if (v != null) "${s.label} ${s.format.format(v)}" else "${s.label} —"
            Text(
                text = text,
                color = s.color,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun displayedValueFor(s: PlottedSeries, crosshairMs: Long?): Double? {
    val snap = s.snapshot
    if (snap.size == 0) return null
    val idx = if (crosshairMs != null) nearestIndex(snap, crosshairMs) else snap.size - 1
    return if (idx >= 0) s.read(idx) else null
}

/**
 * Compute the y-axis range for a group of series, walking each
 * series's visible slice and pulling out finite samples. Returns
 * (0, 1) when the group is empty so callers can short-circuit drawing.
 */
private fun axisRange(series: List<PlottedSeries>, tStart: Long, tEnd: Long): Pair<Double, Double> {
    if (series.isEmpty()) return 0.0 to 1.0
    val finite = ArrayList<Double>()
    for (s in series) {
        val snap = s.snapshot
        if (snap.size == 0) continue
        val lo = snap.lowerBound(tStart)
        val hiExclusive = snap.upperBound(tEnd)
        val visStart = max(0, lo - 1)
        val visEnd = min(snap.size - 1, hiExclusive)
        for (k in visStart..visEnd) {
            val v = s.read(k) ?: continue
            finite += v
        }
    }
    return niceRange(
        values = finite,
        fallbackLo = 0.0,
        fallbackHi = 1.0,
        minSpan = 0.1,
        includeZero = false,
    )
}

/**
 * Pick a `String.format` pattern wide enough to display `lo..hi` without
 * losing precision. Matches what the live charts use.
 */
private fun pickAxisFormat(lo: Double, hi: Double): String {
    val span = abs(hi - lo)
    return when {
        span >= 100.0 -> "%.0f"
        span >= 10.0 -> "%.1f"
        span >= 1.0 -> "%.2f"
        else -> "%.3f"
    }
}

/** Default colour wheel for newly-added series. */
internal val SeriesPalette: List<Color> = listOf(
    Color(0xFF4FC3F7),  // light blue
    Color(0xFFE57373),  // red
    Color(0xFFFFB74D),  // orange
    Color(0xFF81C784),  // green
    Color(0xFFBA68C8),  // purple
    Color(0xFFFFD54F),  // yellow
    Color(0xFF4DD0E1),  // cyan
    Color(0xFFFF7043),  // deep orange
)
