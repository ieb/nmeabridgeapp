package uk.co.tfd.nmeabridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeabridge.bluetooth.BluetoothDeviceInfo
import uk.co.tfd.nmeabridge.service.DebugState
import uk.co.tfd.nmeabridge.service.ServiceState
import uk.co.tfd.nmeabridge.service.SourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    viewModel: ServerViewModel,
    bluetoothAvailable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBleTest: () -> Unit = {},
    onBleScan: () -> Unit = {},
) {
    val serviceState by viewModel.serviceState.collectAsState()
    val debugState by viewModel.debugState.collectAsState()
    val port by viewModel.port.collectAsState()
    val sourceType by viewModel.sourceType.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()

    TopLevelScreen(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header — navigation lives on the shared AppBottomBar below.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.width(12.dp))
            StatusDot(isRunning = serviceState.isRunning)
        }

        // Source selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = sourceType == SourceType.BLE_GPS,
                onClick = { viewModel.setSourceType(SourceType.BLE_GPS) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                enabled = !serviceState.isRunning && bluetoothAvailable
            ) { Text("BLE Nav") }
            SegmentedButton(
                selected = sourceType == SourceType.BLUETOOTH,
                onClick = { viewModel.setSourceType(SourceType.BLUETOOTH) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                enabled = !serviceState.isRunning && bluetoothAvailable
            ) { Text("BT Classic") }
            SegmentedButton(
                selected = sourceType == SourceType.SIMULATOR,
                onClick = { viewModel.setSourceType(SourceType.SIMULATOR) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                enabled = !serviceState.isRunning
            ) { Text("Simulator") }
        }

        // Bluetooth Classic device selector
        if (sourceType == SourceType.BLUETOOTH) {
            DeviceDropdown(
                devices = pairedDevices,
                selected = selectedDevice,
                onSelected = { viewModel.setSelectedDevice(it) },
                enabled = !serviceState.isRunning
            )
        }

        // BLE Nav device scan + selection
        if (sourceType == SourceType.BLE_GPS) {
            val bleAddress by viewModel.bleAddress.collectAsState()
            val bleScannedDevices by viewModel.bleScannedDevices.collectAsState()
            val bleScanning by viewModel.bleScanning.collectAsState()
            val blePin by viewModel.blePin.collectAsState()

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBleScan,
                    enabled = !serviceState.isRunning && !bleScanning
                ) {
                    Text(if (bleScanning) "Scanning..." else "Scan for BLE Nav")
                }
                if (bleAddress.isNotEmpty()) {
                    Text(
                        text = bleAddress,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (bleScannedDevices.isNotEmpty()) {
                bleScannedDevices.forEach { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !serviceState.isRunning) {
                                viewModel.selectBleDevice(device)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (device.address == bleAddress)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(device.name, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                device.address,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = blePin,
                onValueChange = { text ->
                    if (text.length <= 4) viewModel.setBlePin(text)
                },
                label = { Text("BLE PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.width(120.dp),
                enabled = !serviceState.isRunning,
                singleLine = true
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

        // Firmware WiFi toggle — only meaningful when the BLE BoatWatch
        // service is connected and authenticated, since the 0xAA02 command
        // characteristic is gated on auth. Show it only then, so users don't
        // flip a disabled switch that does nothing.
        if (serviceState.isRunning &&
            sourceType == SourceType.BLE_GPS &&
            serviceState.bluetoothConnected) {
            val wifiEnabled by viewModel.wifiEnabled.collectAsState()
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Firmware WiFi", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Enable or disable the WiFi radio on the NMEA bridge firmware.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = wifiEnabled,
                        onCheckedChange = { viewModel.setWifiEnabled(it) }
                    )
                }
            }
        }

        // Status card
        if (serviceState.isRunning) {
            StatusCard(serviceState, debugState)
        }

        // NMEA output
        if (serviceState.isRunning && debugState.lastSentence.isNotEmpty()) {
            NmeaOutputCard(debugState)
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

        // BLE Test button
        OutlinedButton(
            onClick = onBleTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("BLE Diagnostic Test")
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
private fun StatusCard(state: ServiceState, debug: DebugState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            StatusRow("Source", when (state.sourceType) {
                SourceType.SIMULATOR -> "Simulator"
                SourceType.BLUETOOTH -> "BT Classic: ${state.bluetoothDeviceName}"
                SourceType.BLE_GPS -> "BLE Nav: ${state.bluetoothDeviceName}"
            })
            if (state.sourceType == SourceType.BLUETOOTH || state.sourceType == SourceType.BLE_GPS) {
                StatusRow("BT Status", when {
                    state.bluetoothConnected -> "Connected"
                    state.bluetoothStatus != null -> state.bluetoothStatus
                    else -> "Disconnected"
                })
            }
            StatusRow("Server", "${state.serverAddress}:${state.port}")
            StatusRow("Clients", state.connectedClients.toString())
            StatusRow("Sentences", debug.sentenceCount.toString())
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
private fun NmeaOutputCard(state: DebugState) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Auto-scroll to bottom when new sentences arrive
    androidx.compose.runtime.LaunchedEffect(state.recentSentences.size) {
        if (state.recentSentences.isNotEmpty()) {
            listState.animateScrollToItem(state.recentSentences.size - 1)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier
            .padding(12.dp)
            .height(200.dp)
        ) {
            Text(
                "NMEA Sentences (${state.sentenceCount})",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(
                    count = state.recentSentences.size,
                    key = { it }
                ) { index ->
                    Text(
                        text = state.recentSentences[index],
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 1
                    )
                }
            }
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
