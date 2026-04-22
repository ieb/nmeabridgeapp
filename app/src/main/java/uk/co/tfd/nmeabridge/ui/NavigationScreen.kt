package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeabridge.nmea.DerivedNav
import uk.co.tfd.nmeabridge.nmea.NavigationState
import uk.co.tfd.nmeabridge.nmea.Performance
import uk.co.tfd.nmeabridge.service.ServiceState
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun NavigationScreen(
    viewModel: ServerViewModel,
    onSettings: () -> Unit,
    onBattery: () -> Unit,
    onEngine: () -> Unit,
    onPolar: () -> Unit
) {
    val state by viewModel.serviceState.collectAsState()
    val polar by viewModel.activePolar.collectAsState()
    val nav = state.navigationState
    val perf = remember(nav, polar) {
        val p = polar
        if (nav != null && p != null) Performance.derive(nav, p) else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Position — full width
        NavCard(
            label = "POSITION",
            value = formatPosition(nav),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 22
        )

        // COG / SOG
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavCard(
                label = "COG",
                value = formatAngle360(nav?.cog),
                modifier = Modifier.weight(1f)
            )
            NavCard(
                label = "SOG",
                value = formatSpeedKn(nav?.sog),
                modifier = Modifier.weight(1f)
            )
        }

        // HDG / STW
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavCard(
                label = "HDG",
                value = formatAngle360(nav?.heading),
                modifier = Modifier.weight(1f)
            )
            NavCard(
                label = "STW",
                value = formatSpeedKn(nav?.stw),
                modifier = Modifier.weight(1f)
            )
        }

        // DEPTH / LOG
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavCard(
                label = "DEPTH",
                value = formatDepth(nav?.depth),
                modifier = Modifier.weight(1f)
            )
            NavCard(
                label = "LOG",
                value = formatLog(nav?.logNm),
                modifier = Modifier.weight(1f)
            )
        }

        // AWA / AWS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavCard(
                label = "AWA",
                value = formatAnglePM180(nav?.awa),
                modifier = Modifier.weight(1f)
            )
            NavCard(
                label = "AWS",
                value = formatSpeedKn(nav?.aws),
                modifier = Modifier.weight(1f)
            )
        }

        // --- Derived performance (calculated on-phone) ---
        PerformanceDivider()

        // TWA / TWS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavCard(
                label = "TWA",
                value = formatAnglePM180(perf?.twaDeg),
                modifier = Modifier.weight(1f)
            )
            NavCard(
                label = "TWS",
                value = formatSpeedKn(perf?.twsKn),
                modifier = Modifier.weight(1f)
            )
        }

        // POLAR / POLAR %
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavCard(
                label = "POLAR",
                value = formatSpeedKn(perf?.polarSpeedKn),
                modifier = Modifier.weight(1f)
            )
            NavCard(
                label = "POLAR %",
                value = formatPercent(perf?.polarSpeedRatio),
                modifier = Modifier.weight(1f)
            )
        }

        // VMG / VMG %
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavCard(
                label = "VMG",
                value = formatSignedSpeedKn(perf?.vmgKn),
                modifier = Modifier.weight(1f)
            )
            NavCard(
                label = "VMG %",
                value = formatPercent(perf?.polarVmgRatio),
                modifier = Modifier.weight(1f)
            )
        }

        // T-TWA / T-STW
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavCard(
                label = "T-TWA",
                value = formatAnglePM180(perf?.targetTwaDeg),
                modifier = Modifier.weight(1f)
            )
            NavCard(
                label = "T-STW",
                value = formatSpeedKn(perf?.targetStwKn),
                modifier = Modifier.weight(1f)
            )
        }

        // Bottom status bar
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Connection indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.isRunning && (state.bluetoothConnected || state.sourceType == uk.co.tfd.nmeabridge.service.SourceType.SIMULATOR))
                                Color(0xFF4CAF50)
                            else if (state.isRunning)
                                Color(0xFFFFA000)
                            else
                                Color(0xFF757575)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatVariation(nav?.variation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                if (state.isRunning) {
                    Text(
                        text = "TCP: ${state.connectedClients}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row {
                TextButton(onClick = onPolar) {
                    Text("Polar")
                }
                TextButton(onClick = onEngine) {
                    Text("Engine")
                }
                TextButton(onClick = onBattery) {
                    Text("Battery")
                }
                TextButton(onClick = onSettings) {
                    Text("Settings")
                }
            }
        }
    }
}

@Composable
private fun NavCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 32
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PerformanceDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "PERFORMANCE (calculated)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

// --- Formatting functions ---

private fun formatPosition(nav: NavigationState?): String {
    val lat = nav?.latitude
    val lon = nav?.longitude
    if (lat == null || lon == null) return "---"

    return "${formatDDM(lat, "N", "S")}  ${formatDDM(lon, "E", "W")}"
}

private fun formatDDM(degrees: Double, pos: String, neg: String): String {
    val absDeg = abs(degrees)
    val d = absDeg.toInt()
    val m = (absDeg - d) * 60.0
    val dir = if (degrees >= 0) pos else neg
    val pad = if (pos == "E" || pos == "W") 3 else 2
    return "%0${pad}d\u00B0%06.3f'$dir".format(d, m)
}

private fun formatAngle360(deg: Double?): String {
    if (deg == null) return "---"
    val d = ((deg.roundToInt() % 360) + 360) % 360
    return "%03d\u00B0".format(d)
}

private fun formatAnglePM180(deg: Double?): String {
    if (deg == null) return "---"
    val side = if (deg >= 0) "S" else "P"
    return "$side%d\u00B0".format(abs(deg).roundToInt())
}

private fun formatSpeedKn(kn: Double?): String {
    if (kn == null) return "---"
    return "%.1f kn".format(kn)
}

private fun formatDepth(m: Double?): String {
    if (m == null) return "---"
    return "%.1f m".format(m)
}

private fun formatLog(nm: Double?): String {
    if (nm == null) return "---"
    return "%.1f Nm".format(nm)
}

private fun formatVariation(deg: Double?): String {
    if (deg == null) return "VAR ---"
    val dir = if (deg >= 0) "E" else "W"
    return "VAR %d\u00B0$dir".format(abs(deg).roundToInt())
}

private fun formatSignedSpeedKn(kn: Double?): String {
    if (kn == null) return "---"
    val sign = if (kn >= 0) "+" else "\u2212"
    return "$sign%.1f kn".format(abs(kn))
}

private fun formatPercent(ratio: Double?): String {
    if (ratio == null) return "---"
    return "%.0f %%".format(ratio * 100)
}
