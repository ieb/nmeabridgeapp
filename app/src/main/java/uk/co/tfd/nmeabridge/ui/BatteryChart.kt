package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private val CurrentColor = Color(0xFFFFA000)   // amber — left axis
private val VoltageColor = Color(0xFF42A5F5)   // blue — right axis
private val AxisColor = Color(0xFF9E9E9E)
private val GridColor = Color(0x33888888)

private const val MIN_WINDOW_MS = 30L * 1000            // 30 s
private const val MAX_WINDOW_MS = 12L * 3600 * 1000     // 12 h
private const val DEFAULT_WINDOW_MS = 5L * 60 * 1000    // 5 min

@Composable
fun BatteryChart(
    history: List<BatterySample>,
    modifier: Modifier = Modifier
) {
    // null endMs = follow live (pin to latest sample)
    var endMs by remember { mutableStateOf<Long?>(null) }
    var windowMs by remember { mutableLongStateOf(DEFAULT_WINDOW_MS) }
    var canvasWidthPx by remember { mutableStateOf(1f) }

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
                .pointerInput(history.size) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Zoom
                        if (zoom != 1f) {
                            windowMs = (windowMs / zoom)
                                .toLong()
                                .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
                        }
                        // Pan (horizontal only). Convert pixel drag → time delta.
                        if (pan.x != 0f) {
                            val plotW = (canvasWidthPx - leftPadPx - rightPadPx).coerceAtLeast(1f)
                            val msPerPx = windowMs.toDouble() / plotW
                            val dtMs = (-pan.x * msPerPx).toLong()
                            val latest = history.lastOrNull()?.tMs ?: System.currentTimeMillis()
                            val current = endMs ?: latest
                            val next = current + dtMs
                            // Clamp: don't pan beyond latest (snap to live when reached)
                            endMs = if (next >= latest) null else next
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        endMs = null
                        windowMs = DEFAULT_WINDOW_MS
                    })
                }
        ) {
            canvasWidthPx = size.width

            val plotLeft = leftPadPx
            val plotRight = size.width - rightPadPx
            val plotTop = topPadPx
            val plotBottom = size.height - bottomPadPx
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            // Resolve time window
            val latest = history.lastOrNull()?.tMs ?: System.currentTimeMillis()
            val tEnd = endMs ?: latest
            val tStart = tEnd - windowMs

            // Slice visible data (plus one point before / after so lines reach the edges)
            val visible = if (history.isEmpty()) emptyList()
            else {
                val lo = history.indexOfFirst { it.tMs >= tStart }
                val hi = history.indexOfLast { it.tMs <= tEnd }
                val startIdx = if (lo == -1) history.size else max(0, lo - 1)
                val endIdx = if (hi == -1) -1 else min(history.size - 1, hi + 1)
                if (endIdx < startIdx) emptyList() else history.subList(startIdx, endIdx + 1)
            }

            // Compute axis ranges from visible points, with sensible fallbacks / padding
            val (iMin, iMax) = niceRange(
                visible.map { it.i.toDouble() },
                fallbackLo = -10.0, fallbackHi = 10.0,
                minSpan = 2.0, includeZero = true
            )
            val (vMin, vMax) = niceRange(
                visible.map { it.v.toDouble() },
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
            val liveLabel = if (endMs == null) "  ● live" else ""
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

            if (visible.size < 2) {
                // Not enough data to draw a line
                drawContext.canvas.nativeCanvas.drawText(
                    if (history.isEmpty()) "waiting for data…" else "extend window to see history",
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
            visible.forEachIndexed { idx, s ->
                val x = xOf(s.tMs)
                val yI = yOfI(s.i.toDouble())
                val yV = yOfV(s.v.toDouble())
                if (idx == 0) {
                    currentPath.moveTo(x, yI)
                    voltagePath.moveTo(x, yV)
                } else {
                    currentPath.lineTo(x, yI)
                    voltagePath.lineTo(x, yV)
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
        }

        // Overlay: current and voltage legend with latest values
        val latestSample = history.lastOrNull()
        if (latestSample != null) {
            Text(
                text = "I %.1f A".format(latestSample.i),
                color = CurrentColor,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 48.dp, top = 2.dp)
            )
            Text(
                text = "V %.2f".format(latestSample.v),
                color = VoltageColor,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 48.dp, top = 2.dp)
            )
        }
    }

    // Touch the state so recomposition keeps following live when new data arrives
    LaunchedEffect(history.size) { /* no-op, triggers recompose */ }
}
