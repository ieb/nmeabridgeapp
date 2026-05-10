package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.tfd.nmeabridge.history.HistoryWindowSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_WINDOW_MS = 5L * 60 * 1000          // 5 min
private const val MIN_WINDOW_MS = 30L * 1000                  // 30 s
private const val MAX_WINDOW_MS = 30L * 24 * 3600 * 1000      // 30 days
private const val LIVE_REFRESH_MS = 2_000L                    // re-pull while live
private const val CHART_HEIGHT_DP = 180

@Composable
fun HistoryScreen(viewModel: ServerViewModel) {
    val context = LocalContext.current
    val charts by viewModel.historyCharts.collectAsState()
    val windowMs by viewModel.historyWindowMs.collectAsState()
    val endMs by viewModel.historyEndMs.collectAsState()
    val polar by viewModel.activePolar.collectAsState()
    val state by viewModel.serviceState.collectAsState()

    // Resolve the window. tEnd-now if live, otherwise the persisted endMs.
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endMs) {
        if (endMs == null) {
            while (true) {
                nowTick = System.currentTimeMillis()
                delay(LIVE_REFRESH_MS)
            }
        }
    }
    val tEnd = endMs ?: nowTick
    val tStart = tEnd - windowMs

    // Crosshair (per composition) and shared plot bounds for cursor sync.
    var crosshairMs by remember { mutableStateOf<Long?>(null) }
    var plotLeftPx by remember { mutableFloatStateOf(0f) }
    var plotWidthPx by remember { mutableFloatStateOf(1f) }

    // Pull a fresh snapshot whenever the window or chart layout changes.
    val dataSource = viewModel.historyDataSource()
    val snapshot: HistoryWindowSnapshot = produceState(
        initialValue = HistoryWindowSnapshot.EMPTY,
        key1 = tStart, key2 = tEnd, key3 = dataSource,
    ) {
        value = dataSource?.loadWindow(tStart, tEnd) ?: HistoryWindowSnapshot.EMPTY
    }.value

    // Active picker dialog state.
    var pickerForChart by remember { mutableStateOf<Int?>(null) }

    // The pointerInput modifiers below are keyed on Unit (so the gesture
    // handlers aren't recreated mid-gesture), but their lambdas need to
    // see the *current* time window and chart bounds to translate
    // pointer events correctly. Local `val tStart` / `val tEnd` are
    // captured by value at first composition and would go stale after
    // any pan or zoom — exactly the symptom that caused the cursor to
    // de-sync. rememberUpdatedState keeps the references live.
    val currentTStart by rememberUpdatedState(tStart)
    val currentTEnd by rememberUpdatedState(tEnd)
    val currentWindowMs by rememberUpdatedState(windowMs)
    val currentEndMs by rememberUpdatedState(endMs)

    fun pxToMs(xPx: Float): Long {
        val w = plotWidthPx.coerceAtLeast(1f)
        val frac = ((xPx - plotLeftPx) / w).coerceIn(0f, 1f)
        val ts = currentTStart
        val te = currentTEnd
        return ts + (frac * (te - ts)).toLong()
    }

    TopLevelScreen(
        viewModel = viewModel,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HistoryToolbar(
                windowMs = windowMs,
                isLive = endMs == null,
                crosshairMs = crosshairMs,
                onAddChart = { viewModel.addHistoryChart(context) },
                onResetView = {
                    viewModel.setHistoryEndMs(context, null)
                    viewModel.setHistoryWindowMs(context, DEFAULT_WINDOW_MS)
                    crosshairMs = null
                },
            )

            // Show server-not-running notice but keep the layout visible —
            // disk history doesn't depend on a live BLE connection.
            if (!state.isRunning) {
                Text(
                    text = "Server stopped — showing recorded data only",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            // Charts column. The shared gesture handler is on this Column
            // so a pinch / drag / hover anywhere on the stack pans/zooms
            // every chart in step.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // Always read the *current* window via the
                            // live State delegates; capturing the locals
                            // would freeze them at first-composition.
                            if (zoom != 1f) {
                                val newWindow = (currentWindowMs / zoom)
                                    .toLong()
                                    .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
                                viewModel.setHistoryWindowMs(context, newWindow)
                            }
                            if (pan.x != 0f) {
                                val w = plotWidthPx.coerceAtLeast(1f)
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
                            onTap = { offset -> crosshairMs = pxToMs(offset.x) },
                            onDoubleTap = {
                                viewModel.setHistoryEndMs(context, null)
                                viewModel.setHistoryWindowMs(context, DEFAULT_WINDOW_MS)
                                crosshairMs = null
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // Hover (Chromebook trackpad) tracking for the crosshair.
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                when (ev.type) {
                                    PointerEventType.Move -> {
                                        val anyPressed = ev.changes.any { it.pressed }
                                        if (!anyPressed) {
                                            ev.changes.firstOrNull()?.let {
                                                crosshairMs = pxToMs(it.position.x)
                                            }
                                        }
                                    }
                                    PointerEventType.Exit -> { crosshairMs = null }
                                }
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(charts.size) { idx ->
                        val cfg = charts[idx]
                        HistoryChartPanel(
                            cfg = cfg,
                            snapshot = snapshot,
                            polar = polar,
                            tStart = tStart,
                            tEnd = tEnd,
                            crosshairMs = crosshairMs,
                            onAddSeriesClick = { pickerForChart = idx },
                            onRemoveSeries = { fieldId ->
                                viewModel.removeHistorySeries(context, idx, fieldId)
                            },
                            onRemoveChart = { viewModel.removeHistoryChart(context, idx) },
                            onPlotLayout = { l, w -> plotLeftPx = l; plotWidthPx = w },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CHART_HEIGHT_DP.dp),
                        )
                    }
                }
            }
        }
    }

    if (pickerForChart != null) {
        val chartIdx = pickerForChart!!
        val chart = charts.getOrNull(chartIdx)
        if (chart == null) {
            pickerForChart = null
        } else {
            HistoryFieldPickerDialog(
                chart = chart,
                onDismiss = { pickerForChart = null },
                onPick = { field ->
                    val color = nextColorFor(chart, charts).toArgb()
                    val ok = viewModel.addHistorySeries(context, chartIdx, field.id, color)
                    if (ok) pickerForChart = null
                },
            )
        }
    }
}

@Composable
private fun HistoryToolbar(
    windowMs: Long,
    isLive: Boolean,
    crosshairMs: Long?,
    onAddChart: () -> Unit,
    onResetView: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "HISTORY",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = formatWindow(windowMs),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                crosshairMs != null -> "⎔ " + crosshairTimeFmt.format(Date(crosshairMs))
                isLive -> "● live"
                else -> "paused"
            },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp).then(Modifier))
        TextButton(onClick = onResetView) {
            Text("Reset", fontSize = 11.sp)
        }
        TextButton(onClick = onAddChart) {
            Icon(Icons.Filled.Add, contentDescription = "Add chart", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Chart", fontSize = 11.sp)
        }
    }
}

@Composable
private fun HistoryChartPanel(
    cfg: HistoryChartConfig,
    snapshot: HistoryWindowSnapshot,
    polar: uk.co.tfd.nmeabridge.nmea.PolarTable?,
    tStart: Long,
    tEnd: Long,
    crosshairMs: Long?,
    onAddSeriesClick: () -> Unit,
    onRemoveSeries: (String) -> Unit,
    onRemoveChart: () -> Unit,
    onPlotLayout: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Per-chart action bar (above the chart).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Series chips (tap × to remove)
            for (ref in cfg.left + cfg.right) {
                val field = HistoryFieldCatalog.byId(ref.fieldId)
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(ref.colorArgb)),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = field?.label ?: ref.fieldId,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = { onRemoveSeries(ref.fieldId) },
                            modifier = Modifier.size(16.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove series",
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
            // Push the +/× to the right.
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddSeriesClick, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add series",
                    modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onRemoveChart, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove chart",
                    modifier = Modifier.size(14.dp))
            }
        }

        // The chart itself.
        val left = cfg.left.mapNotNull { ref ->
            val f = HistoryFieldCatalog.byId(ref.fieldId) ?: return@mapNotNull null
            buildSeries(ref, f, snapshot, polar)
        }
        val right = cfg.right.mapNotNull { ref ->
            val f = HistoryFieldCatalog.byId(ref.fieldId) ?: return@mapNotNull null
            buildSeries(ref, f, snapshot, polar)
        }
        if (left.isEmpty() && right.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Tap + to add a series",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        } else {
            MultiSeriesChart(
                leftSeries = left,
                rightSeries = right,
                tStart = tStart,
                tEnd = tEnd,
                crosshairMs = crosshairMs,
                onPlotLayout = onPlotLayout,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

private fun buildSeries(
    ref: HistorySeriesRef,
    field: HistoryField,
    snapshot: HistoryWindowSnapshot,
    polar: uk.co.tfd.nmeabridge.nmea.PolarTable?,
): PlottedSeries {
    val ringSnap = HistoryFieldCatalog.ringFor(snapshot, field.stream)
    return PlottedSeries(
        label = field.label,
        color = Color(ref.colorArgb),
        unit = field.unit,
        format = field.format,
        snapshot = ringSnap,
        read = { i -> field.read(snapshot, i, polar) },
    )
}

/**
 * Pick the next colour for a new series in this chart, preferring
 * palette entries not yet used in any chart on the screen.
 */
private fun nextColorFor(
    chart: HistoryChartConfig,
    allCharts: List<HistoryChartConfig>,
): Color {
    val used = (allCharts.flatMap { it.left + it.right }).map { it.colorArgb }.toSet()
    return SeriesPalette.firstOrNull { it.toArgb() !in used }
        ?: SeriesPalette[(chart.left.size + chart.right.size) % SeriesPalette.size]
}

@Composable
private fun HistoryFieldPickerDialog(
    chart: HistoryChartConfig,
    onDismiss: () -> Unit,
    onPick: (HistoryField) -> Unit,
) {
    val leftUnit = chart.left.firstOrNull()?.let { HistoryFieldCatalog.byId(it.fieldId)?.unit }
    val rightUnit = chart.right.firstOrNull()?.let { HistoryFieldCatalog.byId(it.fieldId)?.unit }

    fun isCompatible(field: HistoryField): Boolean = when {
        leftUnit == null -> true
        leftUnit == field.unit -> true
        rightUnit == null -> true
        rightUnit == field.unit -> true
        else -> false
    }

    val grouped = HistoryFieldCatalog.ALL.groupBy { it.id.substringBefore('.') }
    val order = listOf("nav", "derived", "engine", "bms")

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Add series") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                for (group in order) {
                    val fields = grouped[group] ?: continue
                    item { Text(
                        text = displayCategoryName(group),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) }
                    items(fields) { field ->
                        val compatible = isCompatible(field)
                        val alreadyAdded = (chart.left + chart.right).any { it.fieldId == field.id }
                        val enabled = compatible && !alreadyAdded
                        TextButton(
                            onClick = { if (enabled) onPick(field) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = field.label,
                                    color = if (enabled)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = when {
                                        alreadyAdded -> "in chart"
                                        !compatible -> "axis full"
                                        else -> field.unit.label
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun displayCategoryName(prefix: String): String = when (prefix) {
    "nav" -> "Navigation"
    "derived" -> "Derived"
    "engine" -> "Engine"
    "bms" -> "Battery"
    else -> prefix
}

// Local TZ so the toolbar's crosshair time matches the x-axis tick
// labels (which are also local). Date included so that for windows that
// span a day boundary, the user can disambiguate same-time-yesterday vs
// today at a glance.
private val crosshairTimeFmt: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

// ------------------------------------------------------------------
// Glue: expose the service-side HistoryDataSource through the VM. The
// VM keeps a nullable handle that follows whether the service is bound.
// ------------------------------------------------------------------

@Composable
private fun ServerViewModel.historyDataSource(): uk.co.tfd.nmeabridge.history.HistoryDataSource? {
    val service by this.boundService.collectAsState()
    return service?.historyDataSource
}
