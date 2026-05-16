package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uk.co.tfd.nmeabridge.history.HistoryWindowSnapshot
import uk.co.tfd.nmeabridge.history.playback.PlaybackEngine
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val windowMs by viewModel.historyWindowMs.collectAsState()
    val endMs by viewModel.historyEndMs.collectAsState()
    val crosshairMs by viewModel.crosshairMs.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val playbackPositionMs by viewModel.playbackPositionMs.collectAsState()
    val playbackActive = playbackState != PlaybackEngine.State.STOPPED
    val effectiveCrosshair: Long? = if (playbackActive) playbackPositionMs else crosshairMs

    // Live-tail refresh tick — see HistoryScreen / BatteryScreen.
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endMs) {
        if (endMs == null) {
            while (true) {
                nowTick = System.currentTimeMillis()
                delay(CHART_LIVE_REFRESH_MS)
            }
        }
    }
    val tEnd = endMs ?: nowTick
    val tStart = tEnd - windowMs

    val dataSource = viewModel.historyDataSource()
    val snapshot: HistoryWindowSnapshot = produceState(
        initialValue = HistoryWindowSnapshot.EMPTY,
        key1 = tStart, key2 = tEnd, key3 = dataSource,
    ) {
        value = dataSource?.loadWindow(tStart, tEnd) ?: HistoryWindowSnapshot.EMPTY
    }.value
    val history = snapshot.engine

    var plotLeftPx by remember { mutableFloatStateOf(0f) }
    var plotWidthPx by remember { mutableFloatStateOf(1f) }

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

        // All four charts share the same gesture handler used by the
        // History and Battery screens. Pinch / pan / tap / hover all
        // route through the ViewModel so the window and cursor track
        // every other chart screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    chartStackGestureModifier(
                        viewModel = viewModel,
                        tStart = tStart,
                        tEnd = tEnd,
                        plotLeftPx = plotLeftPx,
                        plotWidthPx = plotWidthPx,
                    )
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EngineMetricChart(
                label = "RPM",
                history = history,
                extract = { s, i -> EngineProtocol.rpmAt(s, i)?.toDouble() },
                tStart = tStart,
                tEnd = tEnd,
                crosshairMs = effectiveCrosshair,
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
                crosshairMs = effectiveCrosshair,
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
                crosshairMs = effectiveCrosshair,
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
                crosshairMs = effectiveCrosshair,
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
            crosshairMs = effectiveCrosshair
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
