package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import uk.co.tfd.nmeabridge.history.HistoryDataSource
import uk.co.tfd.nmeabridge.history.playback.PlaybackEngine

// Shared bounds for every chart stack in the app. Pinch / pan / double-tap
// reset all clamp to these — picking a single set means the History,
// Battery, and Engine Graphs screens stay in lockstep when you switch
// between them.
internal const val CHART_MIN_WINDOW_MS = 30L * 1000                  // 30 s
internal const val CHART_MAX_WINDOW_MS = 30L * 24 * 3600 * 1000      // 30 days
internal const val CHART_DEFAULT_WINDOW_MS = 5L * 60 * 1000          // 5 min
internal const val CHART_LIVE_REFRESH_MS = 2_000L                    // re-pull while live

/**
 * The same pan / zoom / tap / hover handler the History screen used to
 * own inline. Lifted out so the Battery and Engine Graphs screens get
 * identical behaviour with zero duplication.
 *
 * Pan and zoom write back to `viewModel.setHistoryWindowMs` /
 * `setHistoryEndMs` (so every screen sees them). Tap during playback
 * seeks the playhead; tap when stopped pins the shared crosshair. Mouse
 * hover (Chromebook trackpad) tracks the crosshair live and clears it
 * on Exit. Double-tap resets window + crosshair to the defaults.
 *
 * Caller must supply the plot's left edge and width (in px) — collected
 * via the chart's `onPlotLayout` callback. The handler uses those to
 * translate pointer X coordinates into timestamps.
 */
@Composable
fun chartStackGestureModifier(
    viewModel: ServerViewModel,
    tStart: Long,
    tEnd: Long,
    plotLeftPx: Float,
    plotWidthPx: Float,
): Modifier {
    val context = LocalContext.current
    val windowMs by viewModel.historyWindowMs.collectAsState()
    val endMs by viewModel.historyEndMs.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val playbackActive = playbackState != PlaybackEngine.State.STOPPED

    // pointerInput is keyed on Unit so a pan/zoom in progress isn't
    // interrupted by recomposition. Lambdas need to see live state —
    // rememberUpdatedState gives them a stable handle that follows the
    // latest value without re-keying the handler.
    val currentWindowMs by rememberUpdatedState(windowMs)
    val currentEndMs by rememberUpdatedState(endMs)
    val currentTStart by rememberUpdatedState(tStart)
    val currentTEnd by rememberUpdatedState(tEnd)
    val currentPlotLeftPx by rememberUpdatedState(plotLeftPx)
    val currentPlotWidthPx by rememberUpdatedState(plotWidthPx)
    val playbackActiveState by rememberUpdatedState(playbackActive)

    fun pxToMs(xPx: Float): Long {
        val w = currentPlotWidthPx.coerceAtLeast(1f)
        val frac = ((xPx - currentPlotLeftPx) / w).coerceIn(0f, 1f)
        return currentTStart + (frac * (currentTEnd - currentTStart)).toLong()
    }

    return Modifier
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                if (zoom != 1f) {
                    val newWindow = (currentWindowMs / zoom)
                        .toLong()
                        .coerceIn(CHART_MIN_WINDOW_MS, CHART_MAX_WINDOW_MS)
                    viewModel.setHistoryWindowMs(context, newWindow)
                }
                if (pan.x != 0f) {
                    val w = currentPlotWidthPx.coerceAtLeast(1f)
                    val msPerPx = currentWindowMs.toDouble() / w
                    val dt = (-pan.x * msPerPx).toLong()
                    val freshLatest = System.currentTimeMillis()
                    val curEnd = currentEndMs ?: freshLatest
                    val next = curEnd + dt
                    viewModel.setHistoryEndMs(
                        context,
                        if (next >= freshLatest) null else next,
                    )
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { offset ->
                    val ms = pxToMs(offset.x)
                    if (playbackActiveState) {
                        // Tap during playback steers the playhead; the
                        // shared crosshair follows the playhead so we
                        // don't also write it directly.
                        viewModel.seekPlayback(ms)
                    } else {
                        viewModel.setCrosshairMs(ms)
                    }
                },
                onDoubleTap = {
                    viewModel.setHistoryEndMs(context, null)
                    viewModel.setHistoryWindowMs(context, CHART_DEFAULT_WINDOW_MS)
                    if (!playbackActiveState) viewModel.setCrosshairMs(null)
                },
            )
        }
        .pointerInput(Unit) {
            // Chromebook trackpad hover. During playback the cursor is
            // the playhead, so hover shouldn't drag it sideways.
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (playbackActiveState) continue
                    when (ev.type) {
                        PointerEventType.Move -> {
                            val anyPressed = ev.changes.any { it.pressed }
                            if (!anyPressed) {
                                ev.changes.firstOrNull()?.let {
                                    viewModel.setCrosshairMs(pxToMs(it.position.x))
                                }
                            }
                        }
                        PointerEventType.Exit -> {
                            viewModel.setCrosshairMs(null)
                        }
                        else -> {}
                    }
                }
            }
        }
}

/**
 * Resolve the [HistoryDataSource] currently exposed by the bound
 * foreground service. Returns null while the service is unbound (Activity
 * recreation, first composition, after teardown).
 */
@Composable
fun ServerViewModel.historyDataSource(): HistoryDataSource? {
    val service by this.boundService.collectAsState()
    return service?.historyDataSource
}
