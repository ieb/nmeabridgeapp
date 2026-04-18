package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeabridge.nmea.BatteryState
import kotlin.math.abs

private val ChargeColor = Color(0xFF4CAF50)     // green
private val DischargeColor = Color(0xFFFFA000)  // amber
private val MinTagColor = Color(0xFFFFA000)     // amber
private val MaxTagColor = Color(0xFF4CAF50)     // green
private val AlarmColor = Color(0xFFE53935)      // red

@Composable
fun BatteryScreen(
    viewModel: ServerViewModel,
    onBack: () -> Unit
) {
    DisposableEffect(Unit) {
        viewModel.setBatteryMonitoring(true)
        onDispose { viewModel.setBatteryMonitoring(false) }
    }

    val state by viewModel.serviceState.collectAsState()
    val history by viewModel.batteryHistory.collectAsState()
    val battery = state.batteryState
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val chartHeight = screenHeightDp * 0.33f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("< Back") }
            Text(
                text = "BATTERY",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(48.dp)) // balance row
        }

        // Top third: V/I time-series chart (uses in-memory history from the VM)
        BatteryChart(
            history = history,
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        )

        if (!state.bluetoothConnected || battery == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (!state.isRunning) "Server stopped"
                           else if (!state.bluetoothConnected) "Waiting for BLE connection…"
                           else "Subscribing to battery…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        BatteryContent(battery)
    }
}

@Composable
private fun BatteryContent(b: BatteryState) {
    // SOC fuel gauge bar + percent
    SocBar(b.soc)

    // Aggregate row
    Text(
        text = buildString {
            append("%.1f / %.1f Ah".format(b.remainingAh, b.fullAh))
            append("   •   ")
            append("${b.cycles} cyc")
            val ttg = timeToGo(b)
            if (ttg != null) {
                append("   •   t → $ttg")
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace
    )

    // Δ mV tile
    if (b.cellVoltagesV.isNotEmpty()) {
        val minV = b.cellVoltagesV.min()
        val maxV = b.cellVoltagesV.max()
        val deltaMv = ((maxV - minV) * 1000).toInt()
        DeltaTile(deltaMv)
    }

    // Per-cell bars
    if (b.cellVoltagesV.isNotEmpty()) {
        Text(
            text = "CELLS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CellBars(b.cellVoltagesV)
    }

    // Temps
    if (b.tempsC.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "TEMPS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            b.tempsC.forEachIndexed { i, t ->
                Text(
                    text = "T${i + 1} %.1f °C".format(t),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    // FET pills
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "FET",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FetPill("Charge", b.chargeFet)
        FetPill("Discharge", b.dischargeFet)
    }

    // Alarms (only if non-empty)
    if (b.alarms.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "ALARMS",
                style = MaterialTheme.typography.labelSmall,
                color = AlarmColor
            )
            b.alarms.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AlarmColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = a.label,
                        color = AlarmColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SocBar(soc: Int) {
    val pct = soc.coerceIn(0, 100)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "SOC",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$pct %",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct / 100f)
                    .height(14.dp)
                    .background(socColour(pct))
            )
        }
    }
}

@Composable
private fun DeltaTile(deltaMv: Int) {
    val colour = when {
        deltaMv <= 20 -> ChargeColor
        deltaMv <= 50 -> DischargeColor
        else -> AlarmColor
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Δ CELL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$deltaMv mV",
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = colour
            )
        }
    }
}

@Composable
private fun CellBars(cells: List<Double>) {
    if (cells.isEmpty()) return
    val minV = cells.min()
    val maxV = cells.max()
    val minIdx = cells.indexOf(minV)
    val maxIdx = cells.indexOf(maxV)
    // Scale across [min − 5 mV, max + 5 mV] so an even pack still shows bars
    val padding = 0.005
    val lo = minV - padding
    val hi = maxV + padding
    val span = (hi - lo).coerceAtLeast(1e-6)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.forEachIndexed { i, v ->
            val isMin = i == minIdx && maxIdx != minIdx
            val isMax = i == maxIdx && maxIdx != minIdx
            CellRow(
                index = i + 1,
                voltage = v,
                fraction = ((v - lo) / span).toFloat().coerceIn(0f, 1f),
                isMin = isMin,
                isMax = isMax
            )
        }
    }
}

@Composable
private fun CellRow(
    index: Int,
    voltage: Double,
    fraction: Float,
    isMin: Boolean,
    isMax: Boolean
) {
    val outline = when {
        isMax -> MaxTagColor
        isMin -> MinTagColor
        else -> null
    }
    val barColour = cellColour(voltage)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "C$index",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = "%.3f V".format(voltage),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(80.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .let {
                    if (outline != null) it.border(2.dp, outline, RoundedCornerShape(3.dp)) else it
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(18.dp)
                    .background(barColour)
            )
        }
        val tag = when {
            isMax -> "max"
            isMin -> "min"
            else -> ""
        }
        Text(
            text = tag,
            color = when {
                isMax -> MaxTagColor
                isMin -> MinTagColor
                else -> Color.Transparent
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(32.dp)
        )
    }
}

@Composable
private fun FetPill(label: String, on: Boolean) {
    val fg = if (on) ChargeColor else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, fg, RoundedCornerShape(999.dp))
            .background(
                if (on) ChargeColor.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label ${if (on) "ON" else "OFF"}",
            color = fg,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// --- helpers ---

private fun socColour(pct: Int): Color = when {
    pct >= 50 -> ChargeColor
    pct >= 20 -> DischargeColor
    else -> AlarmColor
}

private fun cellColour(v: Double): Color = when {
    v >= 3.55 -> AlarmColor        // overcharged LFP
    v >= 3.20 -> ChargeColor       // healthy LFP
    v >= 2.90 -> DischargeColor    // getting low
    else -> AlarmColor             // danger low
}

private fun timeToGo(b: BatteryState): String? {
    val amps = abs(b.currentA)
    if (amps < 0.1) return null
    val ah = if (b.currentA < 0) b.remainingAh
             else (b.fullAh - b.remainingAh).coerceAtLeast(0.0)
    if (ah <= 0.0) return null
    val hours = ah / amps
    val h = hours.toInt()
    val m = ((hours - h) * 60).toInt()
    return "%dh %02dm".format(h, m)
}
