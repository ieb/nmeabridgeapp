package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_ENGINE_WINDOW_MS = 5L * 60 * 1000        // 5 min
private const val MIN_ENGINE_WINDOW_MS = 30L * 1000                // 30 s
private const val MAX_ENGINE_WINDOW_MS = 6L * 3600 * 1000          // 6 h

private val RpmColor      = Color(0xFF4FC3F7)
private val CoolantColor  = Color(0xFFE57373)
private val ExhaustColor  = Color(0xFFFF7043)
private val AltTempColor  = Color(0xFFFFD54F)

@Composable
fun EngineGraphsScreen(
    viewModel: ServerViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.serviceState.collectAsState()
    val history by viewModel.engineHistory.collectAsState()

    // Shared zoom / pan / crosshair state so all four charts are synchronised.
    var endMs by remember { mutableStateOf<Long?>(null) }          // null = pinned live
    var windowMs by remember { mutableLongStateOf(DEFAULT_ENGINE_WINDOW_MS) }
    var crosshairMs by remember { mutableStateOf<Long?>(null) }
    var plotLeftPx by remember { mutableFloatStateOf(0f) }
    var plotWidthPx by remember { mutableFloatStateOf(1f) }

    val latest = if (history.size > 0) history.newestMs else System.currentTimeMillis()
    val tEnd = endMs ?: latest
    val tStart = tEnd - windowMs

    fun pxToMs(xPx: Float): Long {
        val w = plotWidthPx.coerceAtLeast(1f)
        val frac = ((xPx - plotLeftPx) / w).coerceIn(0f, 1f)
        return tStart + (frac * (tEnd - tStart)).toLong()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SubScreenTopBar(title = "ENGINE GRAPHS", onBack = onBack)

        // Only bail out when we have neither a live engine stream nor any
        // historical data to plot. Once history has samples we can render
        // the chart with a gap up to "now", even if the live stream has
        // since gone silent (e.g. NMEA 2000 bus switched off).
        if (history.size == 0 && state.engineState == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        !state.isRunning -> "Server stopped"
                        !state.bluetoothConnected -> "Waiting for BLE connection…"
                        else -> "Subscribing to engine…"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        // All four charts share a single gesture handler. Pinch + drag pans and
        // zooms the shared window; tap pins the crosshair; double-tap clears.
        // Mouse/trackpad hover moves the crosshair live on Chromebook.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(history.version) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            windowMs = (windowMs / zoom)
                                .toLong()
                                .coerceIn(MIN_ENGINE_WINDOW_MS, MAX_ENGINE_WINDOW_MS)
                        }
                        if (pan.x != 0f) {
                            val w = plotWidthPx.coerceAtLeast(1f)
                            val msPerPx = windowMs.toDouble() / w
                            val dtMs = (-pan.x * msPerPx).toLong()
                            val freshLatest = if (history.size > 0) history.newestMs else System.currentTimeMillis()
                            val current = endMs ?: freshLatest
                            val next = current + dtMs
                            endMs = if (next >= freshLatest) null else next
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            crosshairMs = pxToMs(offset.x)
                        },
                        onDoubleTap = {
                            endMs = null
                            windowMs = DEFAULT_ENGINE_WINDOW_MS
                            crosshairMs = null
                        }
                    )
                }
                .pointerInput(Unit) {
                    // Mouse-hover tracking for Chromebook trackpads. PointerEvent.Move
                    // fires on non-pressed pointer motion; Exit fires when the cursor
                    // leaves the Column. Touch drags are already consumed by the
                    // transform handler above.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Move -> {
                                    val anyPressed = event.changes.any { it.pressed }
                                    if (!anyPressed) {
                                        event.changes.firstOrNull()?.let {
                                            crosshairMs = pxToMs(it.position.x)
                                        }
                                    }
                                }
                                PointerEventType.Exit -> {
                                    crosshairMs = null
                                }
                            }
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EngineMetricChart(
                label = "RPM",
                history = history,
                extract = { s, i -> EngineProtocol.rpmAt(s, i)?.toDouble() },
                tStart = tStart,
                tEnd = tEnd,
                crosshairMs = crosshairMs,
                color = RpmColor,
                fallbackLo = 0.0,
                fallbackHi = 4000.0,
                minSpan = 500.0,
                valueFormat = "%.0f",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onPlotLayout = { l, w -> plotLeftPx = l; plotWidthPx = w }
            )
            EngineMetricChart(
                label = "COOLANT °C",
                history = history,
                extract = EngineProtocol::coolantCAt,
                tStart = tStart,
                tEnd = tEnd,
                crosshairMs = crosshairMs,
                color = CoolantColor,
                fallbackLo = 20.0,
                fallbackHi = 100.0,
                minSpan = 10.0,
                valueFormat = "%.0f",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            EngineMetricChart(
                label = "EXHAUST °C",
                history = history,
                extract = EngineProtocol::exhaustCAt,
                tStart = tStart,
                tEnd = tEnd,
                crosshairMs = crosshairMs,
                color = ExhaustColor,
                fallbackLo = 0.0,
                fallbackHi = 400.0,
                minSpan = 50.0,
                valueFormat = "%.0f",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            EngineMetricChart(
                label = "ALT TEMP °C",
                history = history,
                extract = EngineProtocol::alternatorCAt,
                tStart = tStart,
                tEnd = tEnd,
                crosshairMs = crosshairMs,
                color = AltTempColor,
                fallbackLo = 20.0,
                fallbackHi = 90.0,
                minSpan = 10.0,
                valueFormat = "%.0f",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        // Status footer: window width, live / paused indicator, crosshair timestamp.
        EngineGraphsStatusBar(
            windowMs = windowMs,
            isLive = endMs == null,
            crosshairMs = crosshairMs
        )
    }
}

@Composable
private fun EngineGraphsStatusBar(
    windowMs: Long,
    isLive: Boolean,
    crosshairMs: Long?
) {
    val windowLabel = formatWindow(windowMs)
    val rightLabel = when {
        crosshairMs != null -> "⎔ " + crosshairTime(crosshairMs)
        isLive -> "● live"
        else -> "paused"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = windowLabel,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = rightLabel,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
    }
}

private val CROSSHAIR_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
private fun crosshairTime(ms: Long): String = CROSSHAIR_FMT.format(Date(ms))
