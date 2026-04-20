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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
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
    onBack: () -> Unit,
    onGraphs: () -> Unit
) {
    DisposableEffect(Unit) {
        viewModel.setEngineMonitoring(true)
        onDispose { viewModel.setEngineMonitoring(false) }
    }

    val state by viewModel.serviceState.collectAsState()
    // Render dials and tiles even before the first engine packet arrives: an
    // empty EngineState has all-null fields, which the dials display as "— —"
    // with a dimmed needle. The tach-face pills (Connecting / Engine off /
    // real alarms) communicate the current status.
    val engine = state.engineState ?: EngineState()

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

            // Active alarms are rendered INSIDE the tachometer dial (below the
            // hour meter) so they're always visible, regardless of how the
            // window is sized / clipped. Title + tiles take their intrinsic
            // height; the two dial rows split the remainder via weight().
            TachometerDial(
                rpm = engine.rpm,
                engineHoursSec = engine.engineHoursSec,
                statusPills = enginePills(
                    connected = state.bluetoothConnected,
                    engineState = state.engineState
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
            )

            // Dials row: coolant + oil-pressure take full half-widths so the
            // circles are as large as possible. The graphs button is overlaid at
            // the bottom-centre — right in the wedge of empty space between the
            // two circles near their base — so it reads as sitting "below the
            // gap" between the dials, aligned with the bottom of the dial row
            // and just above the ALT T tile in the row below.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
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
                FilledIconButton(
                    onClick = onGraphs,
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.BottomCenter),
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) {
                    // Core Icons set lacks a chart glyph; use an emoji to avoid
                    // pulling in material-icons-extended for a single image.
                    Text(text = "📈", fontSize = 18.sp)
                }
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

private const val IDLE_RPM_THRESHOLD = 400

/**
 * Decide which pills to show on the tach face:
 *   - "Connecting" — BLE not connected, or no engine frame has been received
 *     yet.
 *   - "Engine off" — the ECU isn't broadcasting engine data (rpm reported as
 *     not-available). No alarm pills are emitted here: without rpm there's no
 *     running engine to attach alarms to.
 *   - "Idle" (rpm below IDLE_RPM_THRESHOLD) and "Running" (rpm at or above
 *     threshold) both show any real alarms from status1/status2. Idle adds
 *     its own pill; running does not. If the status words report
 *     not-available (engine.alarms == null), no alarm pills are shown —
 *     we can't synthesise a state the firmware didn't report.
 */
private fun enginePills(connected: Boolean, engineState: EngineState?): List<StatusPill> {
    if (!connected || engineState == null) return listOf(StatusPill.Connecting)
    val rpm = engineState.rpm ?: return listOf(StatusPill.Off)
    val alarms = engineState.alarms?.map { StatusPill.fromAlarm(it) } ?: emptyList()
    return if (rpm < IDLE_RPM_THRESHOLD) listOf(StatusPill.Idle) + alarms else alarms
}

private fun formatVolts(v: Double?): String =
    if (v == null) "—" else "%.2f V".format(v)

private fun formatTemp(c: Double?): String =
    if (c == null) "—" else "%.0f °C".format(c)

private fun formatPct(p: Double?): String =
    if (p == null) "—" else "%.0f %%".format(p)
