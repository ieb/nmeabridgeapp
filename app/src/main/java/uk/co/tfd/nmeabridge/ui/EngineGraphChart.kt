package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import kotlin.math.max
import kotlin.math.min

private val AxisColor = Color(0xFF9E9E9E)
private val GridColor = Color(0x33888888)
private val CrosshairColor = Color(0xCCFFFFFF)

/**
 * Single-series time-series chart for the engine graphs screen. View-only: all
 * window / crosshair state is provided by the parent so the four charts in
 * [EngineGraphsScreen] remain synchronised.
 *
 * Draws its own plot and axis labels (left Y axis only); null samples (sentinels
 * from the engine-off BLE frames) produce a gap in the line rather than a drop
 * to zero.
 *
 * @param onPlotLayout called once with the plot's left-edge X (px) and width (px)
 *   so the parent can translate pointer events into timestamps. Safe for every
 *   chart to report; the parent just overwrites with the latest value.
 */
@Composable
fun EngineMetricChart(
    label: String,
    history: RingSnapshot,
    extract: (RingSnapshot, Int) -> Double?,
    tStart: Long,
    tEnd: Long,
    crosshairMs: Long?,
    color: Color,
    fallbackLo: Double,
    fallbackHi: Double,
    minSpan: Double,
    valueFormat: String,
    modifier: Modifier = Modifier,
    onPlotLayout: (leftPx: Float, widthPx: Float) -> Unit = { _, _ -> }
) {
    val density = LocalDensity.current

    // Slice visible samples (plus one on each side for line continuity).
    val visibleStart: Int
    val visibleEnd: Int   // inclusive
    if (history.size == 0) {
        visibleStart = 0; visibleEnd = -1
    } else {
        val lo = history.lowerBound(tStart)
        val hiExclusive = history.upperBound(tEnd)
        visibleStart = max(0, lo - 1)
        visibleEnd = min(history.size - 1, hiExclusive)
    }
    val visibleCount = if (visibleEnd < visibleStart) 0 else visibleEnd - visibleStart + 1

    // Display value: crosshair value when pinned, otherwise latest.
    val displayedValue: Double? = when {
        crosshairMs != null && history.size > 0 -> {
            val idx = nearestIndex(history, crosshairMs)
            if (idx >= 0) extract(history, idx) else null
        }
        history.size > 0 -> extract(history, history.size - 1)
        else -> null
    }

    // Precompute Y values over the visible slice once (skip nulls for range calc).
    val sliceValues = DoubleArray(visibleCount) { k ->
        extract(history, visibleStart + k) ?: Double.NaN
    }
    val finiteValues = ArrayList<Double>(visibleCount)
    for (v in sliceValues) if (!v.isNaN()) finiteValues += v

    val (yMin, yMax) = niceRange(
        values = finiteValues,
        fallbackLo = fallbackLo,
        fallbackHi = fallbackHi,
        minSpan = minSpan,
        includeZero = false
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            val leftPadPx = with(density) { 44.dp.toPx() }
            val rightPadPx = with(density) { 12.dp.toPx() }
            val topPadPx = with(density) { 18.dp.toPx() }
            val bottomPadPx = with(density) { 6.dp.toPx() }
            val plotLeft = leftPadPx
            val plotRight = size.width - rightPadPx
            val plotTop = topPadPx
            val plotBottom = size.height - bottomPadPx
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            onPlotLayout(plotLeft, plotW)

            // Axes + gridlines
            drawLine(AxisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 1f)
            drawLine(AxisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 1f)
            val gridSteps = 4
            for (k in 0..gridSteps) {
                val y = plotTop + plotH * k / gridSteps
                drawLine(GridColor, Offset(plotLeft, y), Offset(plotRight, y), 1f)
            }

            // Y-axis labels (left)
            val labelPaint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(0xFF, 0xB0, 0xB0, 0xB0)
                textSize = with(density) { 10.sp.toPx() }
                isAntiAlias = true
            }
            for (k in 0..gridSteps) {
                val y = plotTop + plotH * k / gridSteps
                val yVal = yMax - (yMax - yMin) * k / gridSteps
                drawContext.canvas.nativeCanvas.drawText(
                    valueFormat.format(yVal),
                    2f, y + 4f, labelPaint
                )
            }

            // Top-of-plot label bar
            drawContext.canvas.nativeCanvas.drawText(
                label, plotLeft, plotTop - 4f, labelPaint
            )
            val valueLabel = displayedValue?.let { valueFormat.format(it) } ?: "—"
            val valuePaint = android.graphics.Paint(labelPaint).apply {
                this.color = toAndroidColor(color)
                textAlign = android.graphics.Paint.Align.RIGHT
                textSize = with(density) { 12.sp.toPx() }
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            drawContext.canvas.nativeCanvas.drawText(
                valueLabel, plotRight, plotTop - 4f, valuePaint
            )

            // Series path — break on null samples to show gaps.
            if (visibleCount > 0) {
                val span = (tEnd - tStart).coerceAtLeast(1L)
                val ySpan = (yMax - yMin).coerceAtLeast(1e-9)

                fun xOf(ms: Long): Float =
                    plotLeft + plotW * ((ms - tStart).toDouble() / span).toFloat()

                fun yOf(v: Double): Float =
                    plotTop + plotH * ((yMax - v) / ySpan).toFloat()

                val path = Path()
                var penDown = false
                for (k in 0 until visibleCount) {
                    val v = sliceValues[k]
                    if (v.isNaN()) {
                        penDown = false
                        continue
                    }
                    val t = history.timestampAt(visibleStart + k)
                    val x = xOf(t).coerceIn(plotLeft, plotRight)
                    val y = yOf(v).coerceIn(plotTop, plotBottom)
                    if (!penDown) {
                        path.moveTo(x, y)
                        penDown = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(path, color = color, style = Stroke(width = 2f))

                // Crosshair line + marker dot on the trace at the crosshair time.
                if (crosshairMs != null && crosshairMs in tStart..tEnd) {
                    val cx = xOf(crosshairMs).coerceIn(plotLeft, plotRight)
                    drawLine(
                        CrosshairColor,
                        Offset(cx, plotTop),
                        Offset(cx, plotBottom),
                        strokeWidth = 1f
                    )
                    val idx = nearestIndex(history, crosshairMs)
                    val markerV = if (idx >= 0) extract(history, idx) else null
                    if (markerV != null) {
                        val my = yOf(markerV).coerceIn(plotTop, plotBottom)
                        drawCircle(color, radius = 3.5f, center = Offset(cx, my))
                    }
                }
            }
        }
    }
}

// Binary-search for the logical index whose timestamp is closest to tMs.
// Returns -1 when the snapshot is empty.
private fun nearestIndex(s: RingSnapshot, tMs: Long): Int {
    if (s.size == 0) return -1
    val lo = s.lowerBound(tMs).coerceIn(0, s.size - 1)
    if (lo == 0) return 0
    val tLo = s.timestampAt(lo)
    val tPrev = s.timestampAt(lo - 1)
    return if (kotlin.math.abs(tLo - tMs) <= kotlin.math.abs(tPrev - tMs)) lo else lo - 1
}

private fun toAndroidColor(c: Color): Int {
    return android.graphics.Color.argb(
        (c.alpha * 255).toInt(),
        (c.red * 255).toInt(),
        (c.green * 255).toInt(),
        (c.blue * 255).toInt()
    )
}

/**
 * A label-only alternative layout — unused but handy for previews or variants.
 * Kept compact here to avoid pulling in more Compose APIs.
 */
@Composable
@Suppress("unused")
private fun LabelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
