package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeabridge.history.RingSnapshot
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import kotlin.math.max
import kotlin.math.min

private val CurrentColor = Color(0xFFFFA000)   // amber — left axis
private val VoltageColor = Color(0xFF42A5F5)   // blue — right axis
private val AxisColor = Color(0xFF9E9E9E)
private val GridColor = Color(0x33888888)
private val CrosshairColor = Color(0xFFB0BEC5)

/**
 * V/A time-series chart over the shared chart window. Pan/zoom/tap are
 * driven by the parent (chartStackGestureModifier) so this composable
 * is render-only — no private window state. Crosshair is provided too;
 * when set and inside the window, a vertical line is drawn and the
 * overlay legend shows V/A at the cursor instead of latest.
 */
@Composable
fun BatteryChart(
    history: RingSnapshot,
    tStart: Long,
    tEnd: Long,
    windowMs: Long,
    isLive: Boolean,
    crosshairMs: Long?,
    modifier: Modifier = Modifier,
    onPlotLayout: (leftPx: Float, widthPx: Float) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val leftPadPx = with(density) { 44.dp.toPx() }
    val rightPadPx = with(density) { 44.dp.toPx() }
    val topPadPx = with(density) { 10.dp.toPx() }
    val bottomPadPx = with(density) { 18.dp.toPx() }

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
            val plotLeft = leftPadPx
            val plotRight = size.width - rightPadPx
            val plotTop = topPadPx
            val plotBottom = size.height - bottomPadPx
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            onPlotLayout(plotLeft, plotW)

            // Slice visible data (plus one point before / after so lines
            // reach the edges). Snapshot bounds are inclusive [startIdx, endIdx].
            val startIdx: Int
            val endIdx: Int
            if (history.size == 0) {
                startIdx = 0; endIdx = -1
            } else {
                val lo = history.lowerBound(tStart)
                val hiExclusive = history.upperBound(tEnd)
                startIdx = max(0, lo - 1)                      // one point before
                endIdx = min(history.size - 1, hiExclusive)   // one point after (upperBound already +1)
            }
            val visibleCount = if (endIdx < startIdx) 0 else endIdx - startIdx + 1

            // Compute axis ranges by walking the slice once, extracting v and
            // i via per-field accessors. Gap-filler sentinels surface as
            // nulls here — NaN lets us keep the arrays fixed-size while
            // still skipping nulls in niceRange and the draw loop.
            val iValues = DoubleArray(visibleCount) { k ->
                BmsProtocol.currentAAt(history, startIdx + k) ?: Double.NaN
            }
            val vValues = DoubleArray(visibleCount) { k ->
                BmsProtocol.packVAt(history, startIdx + k) ?: Double.NaN
            }
            val iFinite = ArrayList<Double>(visibleCount)
            val vFinite = ArrayList<Double>(visibleCount)
            for (k in 0 until visibleCount) {
                if (!iValues[k].isNaN()) iFinite += iValues[k]
                if (!vValues[k].isNaN()) vFinite += vValues[k]
            }
            val (iMin, iMax) = niceRange(
                iFinite,
                fallbackLo = -10.0, fallbackHi = 10.0,
                minSpan = 2.0, includeZero = true
            )
            val (vMin, vMax) = niceRange(
                vFinite,
                fallbackLo = 12.0, fallbackHi = 14.4,
                minSpan = 0.2, includeZero = false
            )

            // --- Axes and grid ---
            drawLine(AxisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 1f)
            drawLine(AxisColor, Offset(plotRight, plotTop), Offset(plotRight, plotBottom), 1f)
            drawLine(AxisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 1f)

            val gridSteps = 4
            for (k in 0..gridSteps) {
                val y = plotTop + plotH * k / gridSteps
                drawLine(GridColor, Offset(plotLeft, y), Offset(plotRight, y), 1f)
            }

            // --- Axis labels ---
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(0xFF, 0xB0, 0xB0, 0xB0)
                textSize = with(density) { 10.sp.toPx() }
                isAntiAlias = true
            }
            val rightLabelPaint = android.graphics.Paint(labelPaint)
            for (k in 0..gridSteps) {
                val y = plotTop + plotH * k / gridSteps
                val iVal = iMax - (iMax - iMin) * k / gridSteps
                val vVal = vMax - (vMax - vMin) * k / gridSteps
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(iVal),
                    2f, y + 4f, labelPaint
                )
                rightLabelPaint.textAlign = android.graphics.Paint.Align.LEFT
                drawContext.canvas.nativeCanvas.drawText(
                    "%.2f".format(vVal),
                    plotRight + 2f, y + 4f, rightLabelPaint
                )
            }
            // Axis headers
            drawContext.canvas.nativeCanvas.drawText(
                "A", 2f, plotTop - 2f, labelPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                "V", plotRight + 2f, plotTop - 2f, labelPaint
            )

            // Time span label at bottom
            val windowLabel = formatWindow(windowMs)
            val liveLabel = if (isLive) "  ● live" else ""
            drawContext.canvas.nativeCanvas.drawText(
                "$windowLabel$liveLabel",
                plotLeft, plotBottom + bottomPadPx - 4f, labelPaint
            )

            // Zero line for current (if in range)
            if (iMin < 0 && iMax > 0) {
                val zeroY = plotTop + plotH * ((iMax - 0.0) / (iMax - iMin)).toFloat()
                drawLine(
                    CurrentColor.copy(alpha = 0.35f),
                    Offset(plotLeft, zeroY), Offset(plotRight, zeroY), 1f
                )
            }

            if (visibleCount < 2) {
                // Not enough data to draw a line
                drawContext.canvas.nativeCanvas.drawText(
                    if (history.size == 0) "waiting for data…" else "extend window to see history",
                    plotLeft + 6f,
                    plotTop + 14f,
                    labelPaint
                )
                return@Canvas
            }

            // --- Line drawing helper ---
            fun xOf(tMs: Long): Float {
                val frac = (tMs - tStart).toDouble() / windowMs.toDouble()
                return plotLeft + (plotW * frac).toFloat()
            }
            fun yOfI(v: Double): Float {
                val frac = (iMax - v) / (iMax - iMin)
                return plotTop + (plotH * frac).toFloat()
            }
            fun yOfV(v: Double): Float {
                val frac = (vMax - v) / (vMax - vMin)
                return plotTop + (plotH * frac).toFloat()
            }

            val currentPath = Path()
            val voltagePath = Path()
            var iPenDown = false
            var vPenDown = false
            for (k in 0 until visibleCount) {
                val i = startIdx + k
                val x = xOf(history.timestampAt(i))
                val iv = iValues[k]
                val vv = vValues[k]
                if (iv.isNaN()) {
                    iPenDown = false
                } else {
                    val y = yOfI(iv)
                    if (!iPenDown) { currentPath.moveTo(x, y); iPenDown = true }
                    else currentPath.lineTo(x, y)
                }
                if (vv.isNaN()) {
                    vPenDown = false
                } else {
                    val y = yOfV(vv)
                    if (!vPenDown) { voltagePath.moveTo(x, y); vPenDown = true }
                    else voltagePath.lineTo(x, y)
                }
            }

            drawPath(
                path = currentPath,
                color = CurrentColor,
                style = Stroke(width = 2f)
            )
            drawPath(
                path = voltagePath,
                color = VoltageColor,
                style = Stroke(width = 2f)
            )

            // Crosshair line + marker dots on each trace at the cursor
            // time. Only drawn when the cursor is within the visible
            // window — matches EngineMetricChart's behaviour.
            if (crosshairMs != null && crosshairMs in tStart..tEnd) {
                val cx = xOf(crosshairMs).coerceIn(plotLeft, plotRight)
                drawLine(
                    CrosshairColor,
                    Offset(cx, plotTop),
                    Offset(cx, plotBottom),
                    1f,
                )
                val idx = nearestIndex(history, crosshairMs)
                if (idx >= 0) {
                    BmsProtocol.currentAAt(history, idx)?.let { iv ->
                        drawCircle(CurrentColor, radius = 3.5f, center = Offset(cx, yOfI(iv)))
                    }
                    BmsProtocol.packVAt(history, idx)?.let { vv ->
                        drawCircle(VoltageColor, radius = 3.5f, center = Offset(cx, yOfV(vv)))
                    }
                }
            }
        }

        // Overlay: current and voltage legend with values at the cursor
        // when set, otherwise the latest sample. Walks backwards to find
        // the most recent non-null sample — the very latest frame may
        // be a gap-filler sentinel if the stream died.
        if (history.size > 0) {
            val cursorIdx = if (crosshairMs != null && crosshairMs in tStart..tEnd) {
                nearestIndex(history, crosshairMs)
            } else -1

            val (latestI, latestV) = if (cursorIdx >= 0) {
                BmsProtocol.currentAAt(history, cursorIdx) to BmsProtocol.packVAt(history, cursorIdx)
            } else {
                var li: Double? = null
                var lv: Double? = null
                var k = history.size - 1
                while (k >= 0 && (li == null || lv == null)) {
                    if (li == null) li = BmsProtocol.currentAAt(history, k)
                    if (lv == null) lv = BmsProtocol.packVAt(history, k)
                    k--
                }
                li to lv
            }
            Text(
                text = latestI?.let { "I %.1f A".format(it) } ?: "I —",
                color = CurrentColor,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 48.dp, top = 2.dp)
            )
            Text(
                text = latestV?.let { "V %.2f".format(it) } ?: "V —",
                color = VoltageColor,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 48.dp, top = 2.dp)
            )
        }
    }
}
