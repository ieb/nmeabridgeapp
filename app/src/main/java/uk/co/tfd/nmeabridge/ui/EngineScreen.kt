package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeabridge.nmea.EngineState

@Composable
fun EngineScreen(
    viewModel: ServerViewModel,
    onBack: () -> Unit
) {
    DisposableEffect(Unit) {
        viewModel.setEngineMonitoring(true)
        onDispose { viewModel.setEngineMonitoring(false) }
    }

    val state by viewModel.serviceState.collectAsState()
    val engine = state.engineState

    // BoxWithConstraints forces a bounded-height layout context so the
    // weights on the dials below actually resolve (weights silently collapse
    // to 0 inside a parent with unbounded height).
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val padding = 8.dp
        val spacing = 6.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            EngineTopBar(onBack = onBack)

            if (!state.bluetoothConnected || engine == null) {
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

            // Active alarms are rendered INSIDE the tachometer dial (below the
            // hour meter) so they're always visible, regardless of how the
            // window is sized / clipped. Title + tiles take their intrinsic
            // height; the two dial rows split the remainder via weight().
            TachometerDial(
                rpm = engine.rpm,
                engineHoursSec = engine.engineHoursSec,
                alarms = engine.alarms,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                CoolantDial(
                    tempC = engine.coolantC,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                OilPressureDial(
                    bar = engine.oilBar,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            SecondaryValues(engine)
        }
    }
}

@Composable
private fun EngineTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onBack) { Text("< Back") }
        Text(
            text = "ENGINE",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun SecondaryValues(e: EngineState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EngineTile("ALT V", formatVolts(e.alternatorV), Modifier.weight(1f))
            EngineTile("ALT T", formatTemp(e.alternatorC), Modifier.weight(1f))
            EngineTile("BATT V", formatVolts(e.engineBattV), Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EngineTile("EXHAUST", formatTemp(e.exhaustC), Modifier.weight(1f))
            EngineTile("ENG ROOM", formatTemp(e.engineRoomC), Modifier.weight(1f))
            EngineTile("FUEL", formatPct(e.fuelPct), Modifier.weight(1f))
        }
    }
}

@Composable
private fun EngineTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

private fun formatVolts(v: Double?): String =
    if (v == null) "—" else "%.2f V".format(v)

private fun formatTemp(c: Double?): String =
    if (c == null) "—" else "%.0f °C".format(c)

private fun formatPct(p: Double?): String =
    if (p == null) "—" else "%.0f %%".format(p)
