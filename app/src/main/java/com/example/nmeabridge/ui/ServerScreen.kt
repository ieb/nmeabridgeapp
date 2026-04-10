package com.example.nmeabridge.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.nmeabridge.bluetooth.BluetoothDeviceInfo
import com.example.nmeabridge.service.ServiceState
import com.example.nmeabridge.service.SourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    viewModel: ServerViewModel,
    bluetoothAvailable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val serviceState by viewModel.serviceState.collectAsState()
    val port by viewModel.port.collectAsState()
    val sourceType by viewModel.sourceType.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "NMEA Bridge",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.width(12.dp))
            StatusDot(isRunning = serviceState.isRunning)
        }

        // Source selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = sourceType == SourceType.SIMULATOR,
                onClick = { viewModel.setSourceType(SourceType.SIMULATOR) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !serviceState.isRunning
            ) { Text("Simulator") }
            SegmentedButton(
                selected = sourceType == SourceType.BLUETOOTH,
                onClick = { viewModel.setSourceType(SourceType.BLUETOOTH) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !serviceState.isRunning && bluetoothAvailable
            ) { Text("Bluetooth GPS") }
        }

        // Bluetooth device selector (only when BT source selected)
        if (sourceType == SourceType.BLUETOOTH) {
            DeviceDropdown(
                devices = pairedDevices,
                selected = selectedDevice,
                onSelected = { viewModel.setSelectedDevice(it) },
                enabled = !serviceState.isRunning
            )
        }

        // Port input
        OutlinedTextField(
            value = port.toString(),
            onValueChange = { text ->
                text.toIntOrNull()?.let { viewModel.setPort(it) }
            },
            label = { Text("TCP Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            enabled = !serviceState.isRunning,
            singleLine = true
        )

        // Start/Stop button
        Button(
            onClick = { if (serviceState.isRunning) onStop() else onStart() },
            modifier = Modifier.fillMaxWidth(),
            colors = if (serviceState.isRunning) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            enabled = !serviceState.isRunning || serviceState.isRunning  // always enabled
        ) {
            Text(if (serviceState.isRunning) "Stop Server" else "Start Server")
        }

        // Error message
        serviceState.errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Status card
        if (serviceState.isRunning) {
            StatusCard(serviceState)
        }

        // NMEA output
        if (serviceState.isRunning && serviceState.lastSentence.isNotEmpty()) {
            NmeaOutputCard(serviceState)
        }

        // Connection instructions
        if (serviceState.isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Connect from other apps:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Host: ${serviceState.serverAddress}  (or localhost)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Port: ${serviceState.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(isRunning: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(if (isRunning) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
    )
}

@Composable
private fun StatusCard(state: ServiceState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            StatusRow("Source", when (state.sourceType) {
                SourceType.SIMULATOR -> "Simulator"
                SourceType.BLUETOOTH -> "Bluetooth: ${state.bluetoothDeviceName}"
            })
            if (state.sourceType == SourceType.BLUETOOTH) {
                StatusRow("BT Status", when {
                    state.bluetoothConnected -> "Connected"
                    state.bluetoothStatus != null -> state.bluetoothStatus
                    else -> "Disconnected"
                })
            }
            StatusRow("Server", "${state.serverAddress}:${state.port}")
            StatusRow("Clients", state.connectedClients.toString())
            StatusRow("Sentences", state.sentenceCount.toString())
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun NmeaOutputCard(state: ServiceState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Last NMEA Sentence", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.lastSentence,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDropdown(
    devices: List<BluetoothDeviceInfo>,
    selected: BluetoothDeviceInfo?,
    onSelected: (BluetoothDeviceInfo) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.name} (${it.address})" } ?: "Select device...",
            onValueChange = {},
            readOnly = true,
            label = { Text("Bluetooth Device") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            enabled = enabled
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No paired devices found") },
                    onClick = { expanded = false },
                    enabled = false
                )
            } else {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text("${device.name} (${device.address})") },
                        onClick = {
                            onSelected(device)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
