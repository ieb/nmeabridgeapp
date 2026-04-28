package uk.co.tfd.nmeabridge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.co.tfd.nmeabridge.nmea.PolarRepository
import uk.co.tfd.nmeabridge.nmea.PolarTable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolarScreen(
    viewModel: ServerViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by viewModel.activePolar.collectAsState()
    val names by viewModel.polarNames.collectAsState()
    val state by viewModel.serviceState.collectAsState()

    // Working copy — seeded from the active polar, mutated by drags.
    var working by remember { mutableStateOf<PolarTable?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var selectedTwsIdx by remember { mutableStateOf(-1) }
    // Default to live mode: the screen is most useful for observing current
    // boat performance against the polar; editing is an occasional task.
    var liveMode by remember { mutableStateOf(true) }
    LaunchedEffect(active?.name) {
        working = active
        dirty = false
        selectedTwsIdx = -1
    }

    var menuOpen by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importName by remember { mutableStateOf("") }
    var saveAsOpen by remember { mutableStateOf(false) }
    var saveAsName by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            importName = uri.lastPathSegment?.substringAfterLast('/')
                ?.substringBeforeLast('.') ?: "imported"
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val t = working ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            val res = viewModel.exportPolar(uri, t)
            statusMsg = if (res.isSuccess) "Exported" else "Export failed: ${res.exceptionOrNull()?.message}"
        }
    }

    val polar = working

    TopLevelScreen(
        viewModel = viewModel,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top row: name dropdown / live / import / export / delete. Back
        // navigation lives on the shared AppBottomBar below, or scroll to
        // reveal it if the polar chart has pushed it off-screen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(polar?.name ?: "—")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (n in names) {
                        DropdownMenuItem(
                            text = { Text(n) },
                            onClick = {
                                viewModel.setActivePolar(n)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("Live", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = liveMode,
                onCheckedChange = {
                    liveMode = it
                    if (it) selectedTwsIdx = -1
                }
            )
            Spacer(Modifier.weight(1f))
            if (!liveMode) {
                TooltipIconButton(
                    tooltip = if (dirty) "Save changes" else "Save (rename)",
                    enabled = polar != null,
                    onClick = {
                        val t = working ?: return@TooltipIconButton
                        saveAsName = t.name
                        saveAsOpen = true
                    }
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = "Save",
                        tint = if (dirty) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurface
                    )
                }
                TooltipIconButton(
                    tooltip = "Revert unsaved changes",
                    enabled = dirty,
                    onClick = {
                        working = active
                        dirty = false
                        selectedTwsIdx = -1
                    }
                ) { Icon(Icons.Filled.Undo, contentDescription = "Revert") }
                TooltipIconButton(
                    tooltip = "Import polar CSV",
                    onClick = { importLauncher.launch(arrayOf("text/*", "text/csv", "*/*")) }
                ) { Icon(Icons.Filled.FileUpload, contentDescription = "Import") }
                TooltipIconButton(
                    tooltip = "Export polar CSV",
                    enabled = polar != null,
                    onClick = {
                        val n = polar?.name ?: "polar"
                        exportLauncher.launch("$n.csv")
                    }
                ) { Icon(Icons.Filled.FileDownload, contentDescription = "Export") }
                val canDelete = polar != null && polar.name != PolarRepository.BUILTIN_NAME
                TooltipIconButton(
                    tooltip = if (canDelete) "Delete this polar" else "Built-in polar cannot be deleted",
                    enabled = canDelete,
                    onClick = { polar?.name?.let { viewModel.deletePolar(it) } }
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        }

        if (!liveMode && selectedTwsIdx >= 0 && polar != null) {
            Text(
                "Editing TWS ${trimKn(polar.twsAxis[selectedTwsIdx])} kn — drag dots to adjust",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        statusMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        if (polar != null) {
            val nav = state.navigationState
            val derived = remember(nav, polar) {
                if (nav != null) uk.co.tfd.nmeabridge.nmea.Performance.derive(nav, polar) else null
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                PolarChart(
                    polar = polar,
                    selectedTwsIdx = if (liveMode) -1 else selectedTwsIdx,
                    liveMode = liveMode,
                    onPointChanged = { twaIdx, twsIdx, newSpeed ->
                        val t = working ?: return@PolarChart
                        working = t.withSpeed(twaIdx, twsIdx, newSpeed)
                        dirty = true
                    },
                    liveTwaDeg = derived?.twaDeg,
                    liveStwKn = nav?.stw,
                    liveTwsKn = derived?.twsKn,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (!liveMode) {
                Legend(polar, selectedTwsIdx) { idx ->
                    selectedTwsIdx = if (selectedTwsIdx == idx) -1 else idx
                }
            }

            if (liveMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReadoutTile("TWS", formatKn(derived?.twsKn), Modifier.weight(1f))
                    ReadoutTile("STW", formatKn(nav?.stw), Modifier.weight(1f))
                    ReadoutTile("AWS", formatKn(nav?.aws), Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReadoutTile("TWA", formatAngle(derived?.twaDeg), Modifier.weight(1f))
                    ReadoutTile("POLAR %", formatPct(derived?.polarSpeedRatio), Modifier.weight(1f))
                    ReadoutTile("AWA", formatAngle(nav?.awa), Modifier.weight(1f))
                }
            }
        }
    }
    }

    if (pendingImportUri != null) {
        NameDialog(
            title = "Name this polar",
            initial = importName,
            onDismiss = { pendingImportUri = null },
            onConfirm = { name ->
                val uri = pendingImportUri
                pendingImportUri = null
                if (uri != null) {
                    scope.launch {
                        val res = viewModel.importPolar(uri, name)
                        statusMsg = if (res.isSuccess) {
                            viewModel.setActivePolar(res.getOrThrow())
                            "Imported"
                        } else {
                            "Import failed: ${res.exceptionOrNull()?.message}"
                        }
                    }
                }
            }
        )
    }

    if (saveAsOpen) {
        NameDialog(
            title = "Save polar",
            initial = saveAsName,
            onDismiss = { saveAsOpen = false },
            onConfirm = { name ->
                val t = working ?: run { saveAsOpen = false; return@NameDialog }
                val trimmed = name.trim()
                if (trimmed == PolarRepository.BUILTIN_NAME) {
                    statusMsg = "Name \"${PolarRepository.BUILTIN_NAME}\" is reserved — pick another"
                    return@NameDialog
                }
                saveAsOpen = false
                val res = if (trimmed == t.name) viewModel.savePolar(t).map { t.name }
                          else viewModel.savePolarAs(trimmed, t)
                statusMsg = if (res.isSuccess) {
                    dirty = false
                    "Saved as ${res.getOrThrow()}"
                } else {
                    "Save failed: ${res.exceptionOrNull()?.message}"
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, enabled = enabled) { content() }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    LaunchedEffect(initial) { name = initial }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val tsPalette = listOf(
    Color(0xFF1f77b4), Color(0xFFff7f0e), Color(0xFF2ca02c), Color(0xFFd62728),
    Color(0xFF9467bd), Color(0xFF8c564b), Color(0xFFe377c2), Color(0xFF17becf),
    Color(0xFFbcbd22), Color(0xFF7f7f7f), Color(0xFF393b79), Color(0xFFe7ba52),
    Color(0xFF637939), Color(0xFFad494a), Color(0xFF5254a3), Color(0xFF8ca252)
)

/** Indexes (into polar.twsAxis) that are actually drawn (skip 0-kn column). */
private fun plottedTwsIdxs(polar: PolarTable): IntArray {
    val out = ArrayList<Int>()
    for (i in polar.twsAxis.indices) if (polar.twsAxis[i] > 0.01) out.add(i)
    return out.toIntArray()
}

@Composable
private fun PolarChart(
    polar: PolarTable,
    selectedTwsIdx: Int,
    liveMode: Boolean,
    onPointChanged: (twaIdx: Int, twsIdx: Int, newSpeedKn: Double) -> Unit,
    liveTwaDeg: Double?,
    liveStwKn: Double?,
    liveTwsKn: Double?,
    modifier: Modifier = Modifier
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface

    val plotIdxs = plottedTwsIdxs(polar)

    // Scale: max speed across all plotted TWS curves (so axes don't jump when editing)
    var maxSpeed = 0.0
    for (i in plotIdxs) {
        for (r in polar.twaAxis.indices) if (polar.speeds[r][i] > maxSpeed) maxSpeed = polar.speeds[r][i]
    }
    val ringStepKn = 2.0
    val maxRing = (kotlin.math.ceil(maxSpeed / ringStepKn) * ringStepKn).coerceAtLeast(ringStepKn)

    // Layout state captured from the Canvas, used by the drag-gesture handler.
    val layoutCx = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val layoutCy = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val layoutPxPerKn = remember { androidx.compose.runtime.mutableFloatStateOf(1f) }

    // Snapshots that the long-lived pointerInput lambda always reads through.
    // Keying pointerInput on `polar` would restart the gesture on every drag
    // update (since drags mutate `polar`), which made dragging impossible.
    val polarState = rememberUpdatedState(polar)
    val selectedIdxState = rememberUpdatedState(selectedTwsIdx)
    val onPointChangedState = rememberUpdatedState(onPointChanged)

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            var dragTwaIdx = -1
            var dragSide = 0  // +1 stbd (right), -1 port (left)
            detectDragGestures(
                onDragStart = { pos ->
                    val p = polarState.value
                    val selIdx = selectedIdxState.value
                    if (selIdx < 0) return@detectDragGestures
                    val idxs = plottedTwsIdxs(p)
                    if (selIdx !in idxs) return@detectDragGestures
                    val cx = layoutCx.floatValue
                    val cy = layoutCy.floatValue
                    val ppk = layoutPxPerKn.floatValue
                    var best = -1
                    var bestDist = 64f  // widened hit target
                    var side = 0
                    for (r in p.twaAxis.indices) {
                        val twaDeg = p.twaAxis[r]
                        val speed = p.speeds[r][selIdx]
                        val a = (twaDeg - 90) * PI / 180.0
                        val rp = (speed * ppk).toFloat()
                        val sx = cx + cos(a).toFloat() * rp
                        val sy = cy + sin(a).toFloat() * rp
                        val px = cx - cos(a).toFloat() * rp
                        val py = cy + sin(a).toFloat() * rp
                        val ds = hypot(pos.x - sx, pos.y - sy)
                        val dp = hypot(pos.x - px, pos.y - py)
                        if (ds < bestDist) { bestDist = ds; best = r; side = 1 }
                        if (dp < bestDist) { bestDist = dp; best = r; side = -1 }
                    }
                    dragTwaIdx = best
                    dragSide = side
                },
                onDrag = { change, _ ->
                    val r = dragTwaIdx
                    if (r < 0) return@detectDragGestures
                    change.consume()
                    val p = polarState.value
                    val selIdx = selectedIdxState.value
                    if (selIdx < 0 || r !in p.twaAxis.indices) return@detectDragGestures
                    val cx = layoutCx.floatValue
                    val cy = layoutCy.floatValue
                    val ppk = layoutPxPerKn.floatValue
                    val twaDeg = p.twaAxis[r]
                    val a = (twaDeg - 90) * PI / 180.0
                    val dirX = dragSide * cos(a).toFloat()
                    val dirY = sin(a).toFloat()
                    val vx = change.position.x - cx
                    val vy = change.position.y - cy
                    val proj = (vx * dirX + vy * dirY).coerceAtLeast(0f)
                    val newSpeed = (proj / ppk).toDouble()
                    onPointChangedState.value(r, selIdx, newSpeed)
                },
                onDragEnd = { dragTwaIdx = -1; dragSide = 0 },
                onDragCancel = { dragTwaIdx = -1; dragSide = 0 }
            )
        }
    ) {
        layoutCx.floatValue = size.width / 2f
        layoutCy.floatValue = size.height / 2f
        val cx = layoutCx.floatValue
        val cy = layoutCy.floatValue
        val radius = (max(size.width, size.height) / 2f * 0.92f)
        layoutPxPerKn.floatValue = (radius / maxRing).toFloat()
        val pxPerKn = layoutPxPerKn.floatValue

        // Speed rings
        var r = ringStepKn
        while (r <= maxRing + 1e-6) {
            drawCircle(
                color = axisColor.copy(alpha = 0.25f),
                radius = (r * pxPerKn).toFloat(),
                center = Offset(cx, cy),
                style = Stroke(width = 1f)
            )
            r += ringStepKn
        }

        // Spokes every 30°
        for (deg in 0..180 step 30) {
            val a = (deg - 90) * PI / 180.0
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            drawLine(
                color = axisColor.copy(alpha = 0.3f),
                start = Offset(cx, cy),
                end = Offset(cx + dx * radius, cy + dy * radius),
                strokeWidth = 1f
            )
            if (deg != 0 && deg != 180) {
                drawLine(
                    color = axisColor.copy(alpha = 0.3f),
                    start = Offset(cx, cy),
                    end = Offset(cx - dx * radius, cy + dy * radius),
                    strokeWidth = 1f
                )
            }
        }

        // Draw curves. In live mode, draw only a single curve interpolated
        // at the current live TWS. Otherwise draw the full family.
        if (liveMode) {
            val tws = liveTwsKn
            if (tws != null && tws > 0) {
                val color = Color(0xFF1f77b4)
                val stbd = Path()
                val port = Path()
                var started = false
                var a = 0
                while (a <= 180) {
                    val speed = polar.polarSpeed(tws, a.toDouble())
                    val rp = (speed * pxPerKn).toFloat()
                    val ang = (a - 90) * PI / 180.0
                    val sx = cx + cos(ang).toFloat() * rp
                    val sy = cy + sin(ang).toFloat() * rp
                    val px = cx - cos(ang).toFloat() * rp
                    val py = cy + sin(ang).toFloat() * rp
                    if (!started) {
                        stbd.moveTo(sx, sy); port.moveTo(px, py); started = true
                    } else {
                        stbd.lineTo(sx, sy); port.lineTo(px, py)
                    }
                    a += 1
                }
                drawPath(stbd, color = color, style = Stroke(width = 3.5f))
                drawPath(port, color = color, style = Stroke(width = 3.5f))
            }
        } else {
            for ((paletteIdx, colIdx) in plotIdxs.withIndex()) {
                val dim = selectedTwsIdx >= 0 && colIdx != selectedTwsIdx
                val color = tsPalette[paletteIdx % tsPalette.size]
                    .copy(alpha = if (dim) 0.25f else 1f)
                val stroke = if (colIdx == selectedTwsIdx) 3.5f else 2f
                val stbd = Path()
                val port = Path()
                var started = false
                var a = 0
                while (a <= 180) {
                    val speed = polar.polarSpeed(polar.twsAxis[colIdx], a.toDouble())
                    val rp = (speed * pxPerKn).toFloat()
                    val ang = (a - 90) * PI / 180.0
                    val sx = cx + cos(ang).toFloat() * rp
                    val sy = cy + sin(ang).toFloat() * rp
                    val px = cx - cos(ang).toFloat() * rp
                    val py = cy + sin(ang).toFloat() * rp
                    if (!started) {
                        stbd.moveTo(sx, sy); port.moveTo(px, py); started = true
                    } else {
                        stbd.lineTo(sx, sy); port.lineTo(px, py)
                    }
                    a += 1
                }
                drawPath(stbd, color = color, style = Stroke(width = stroke))
                drawPath(port, color = color, style = Stroke(width = stroke))
            }
        }

        // Draw handles on the selected curve
        if (selectedTwsIdx >= 0 && selectedTwsIdx in plotIdxs) {
            val handleColor = Color(0xFFFFC107)
            for (row in polar.twaAxis.indices) {
                val speed = polar.speeds[row][selectedTwsIdx]
                val ang = (polar.twaAxis[row] - 90) * PI / 180.0
                val rp = (speed * pxPerKn).toFloat()
                val sx = cx + cos(ang).toFloat() * rp
                val sy = cy + sin(ang).toFloat() * rp
                val px = cx - cos(ang).toFloat() * rp
                val py = cy + sin(ang).toFloat() * rp
                drawCircle(handleColor, 8f, Offset(sx, sy))
                drawCircle(Color.Black, 8f, Offset(sx, sy), style = Stroke(width = 1.5f))
                drawCircle(handleColor, 8f, Offset(px, py))
                drawCircle(Color.Black, 8f, Offset(px, py), style = Stroke(width = 1.5f))
            }
        }

        // Live marker — only shown in live mode.
        if (liveMode && liveTwaDeg != null && liveStwKn != null && liveStwKn > 0) {
            val a = (abs(liveTwaDeg) - 90) * PI / 180.0
            val rp = (liveStwKn * pxPerKn).toFloat()
            val signX = if (liveTwaDeg >= 0) 1f else -1f
            val mx = cx + signX * cos(a).toFloat() * rp
            val my = cy + sin(a).toFloat() * rp
            drawCircle(Color(0xFFFFEB3B), 7f, Offset(mx, my))
            drawCircle(Color.Black, 7f, Offset(mx, my), style = Stroke(width = 1.5f))
        }

        // Ring labels (kn) below centre
        val textPaint = android.graphics.Paint().apply {
            color = textColor.toArgb()
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        var rr = ringStepKn
        while (rr <= maxRing + 1e-6) {
            drawContext.canvas.nativeCanvas.drawText(
                "${rr.toInt()}",
                cx + 2f,
                cy + (rr * pxPerKn).toFloat() - 2f,
                textPaint
            )
            rr += ringStepKn
        }
    }
}

private fun Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )

private fun trimKn(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun ReadoutTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

private fun formatKn(v: Double?): String =
    if (v == null) "---" else "%.1f kn".format(v)

private fun formatAngle(deg: Double?): String {
    if (deg == null) return "---"
    val side = if (deg >= 0) "S" else "P"
    return "$side%d°".format(abs(deg).roundToInt())
}

private fun formatPct(ratio: Double?): String =
    if (ratio == null) "---" else "%.0f %%".format(ratio * 100)

@Composable
private fun Legend(
    polar: PolarTable,
    selectedTwsIdx: Int,
    onSelect: (Int) -> Unit
) {
    val idxs = plottedTwsIdxs(polar)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "TWS kn:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for ((paletteIdx, colIdx) in idxs.withIndex()) {
            val selected = colIdx == selectedTwsIdx
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { onSelect(colIdx) }
                    .padding(horizontal = 2.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else Color.Transparent
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(tsPalette[paletteIdx % tsPalette.size])
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = trimKn(polar.twsAxis[colIdx]),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
