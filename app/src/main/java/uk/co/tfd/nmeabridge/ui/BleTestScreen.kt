package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BleTestScreen(
    viewModel: BleTestViewModel,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val logListState = rememberLazyListState()

    // Auto-scroll log to bottom
    LaunchedEffect(state.logLines.size) {
        if (state.logLines.isNotEmpty()) {
            logListState.animateScrollToItem(state.logLines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "BLE Diagnostic",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // Phase indicator
        Text(
            text = "Phase: ${state.phase}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onScan,
                enabled = !state.scanning && state.phase != "connecting" && state.phase != "discovering"
            ) {
                Text(if (state.scanning) "Scanning..." else "Scan")
            }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = state.phase in listOf("connected", "discovering", "done")
            ) {
                Text("Disconnect")
            }
            OutlinedButton(onClick = onClear) {
                Text("Clear")
            }
        }

        // Found devices
        if (state.foundDevices.isNotEmpty() && state.phase in listOf("scanning", "scanned")) {
            Text("Tap a device to connect:", style = MaterialTheme.typography.labelLarge)
            Column(
                modifier = Modifier.height(150.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.foundDevices.forEach { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConnect(device.address) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = device.name,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = "${device.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Log output
        Text("Log:", style = MaterialTheme.typography.labelLarge)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (state.logLines.isEmpty()) {
                Text(
                    text = "Tap Scan to begin...",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(state.logLines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}
