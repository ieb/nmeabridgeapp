package uk.co.tfd.nmeabridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import uk.co.tfd.nmeabridge.bluetooth.BluetoothDeviceSelector
import uk.co.tfd.nmeabridge.service.SourceType
import uk.co.tfd.nmeabridge.ui.theme.NmeaBridgeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ServerViewModel by viewModels()
    private val bleTestViewModel: BleTestViewModel by viewModels()

    private enum class Screen { NAV, SETTINGS, BLE_TEST }
    private var currentScreen by mutableStateOf(Screen.NAV)
    private var pendingBleAction: (() -> Unit)? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.refreshPairedDevices(this)
            val action = pendingBleAction
            pendingBleAction = null
            if (action != null) {
                action()
            } else {
                actuallyStartServer()
            }
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        proceedToStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.loadSettings(this)
        val bluetoothAvailable = BluetoothDeviceSelector.isBluetoothAvailable(this)

        // Auto-start if we have a saved BLE GPS source
        if (viewModel.sourceType.value == SourceType.BLE_GPS &&
            viewModel.bleAddress.value.isNotBlank() &&
            hasBluetoothPermissions()
        ) {
            viewModel.startServer(this)
        }

        setContent {
            NmeaBridgeTheme {
                Surface {
                    when (currentScreen) {
                        Screen.NAV -> NavigationScreen(
                            viewModel = viewModel,
                            onSettings = { currentScreen = Screen.SETTINGS }
                        )
                        Screen.SETTINGS -> ServerScreen(
                            viewModel = viewModel,
                            bluetoothAvailable = bluetoothAvailable,
                            onStart = { requestPermissionsAndStart() },
                            onStop = { viewModel.stopServer(this) },
                            onBleTest = { currentScreen = Screen.BLE_TEST },
                            onBleScan = {
                                requestBlePermissionsThen { viewModel.scanForBleNmeaDevices(this) }
                            },
                            onBack = { currentScreen = Screen.NAV }
                        )
                        Screen.BLE_TEST -> BleTestScreen(
                            viewModel = bleTestViewModel,
                            onScan = {
                                requestBlePermissionsThen { bleTestViewModel.startScan(this) }
                            },
                            onConnect = { address ->
                                requestBlePermissionsThen { bleTestViewModel.connectToDevice(this, address) }
                            },
                            onDisconnect = { bleTestViewModel.disconnect() },
                            onClear = { bleTestViewModel.clearLog() },
                            onBack = { currentScreen = Screen.SETTINGS }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            viewModel.bindService(this)
        } catch (_: Exception) {}

        if (hasBluetoothPermissions()) {
            viewModel.refreshPairedDevices(this)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            viewModel.unbindService(this)
        } catch (_: Exception) {}
    }

    private fun requestBlePermissionsThen(action: () -> Unit) {
        if (hasBluetoothPermissions()) {
            action()
        } else {
            pendingBleAction = action
            bluetoothPermissionLauncher.launch(getBluetoothPermissions())
        }
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToStart()
    }

    private fun proceedToStart() {
        val source = viewModel.sourceType.value
        if (source == SourceType.BLUETOOTH || source == SourceType.BLE_GPS) {
            if (!hasBluetoothPermissions()) {
                pendingBleAction = null
                bluetoothPermissionLauncher.launch(getBluetoothPermissions())
                return
            }
            if (source == SourceType.BLUETOOTH) {
                viewModel.refreshPairedDevices(this)
            }
        }
        actuallyStartServer()
    }

    private fun actuallyStartServer() {
        if (viewModel.sourceType.value == SourceType.BLUETOOTH &&
            viewModel.selectedDevice.value == null
        ) {
            Toast.makeText(this, "Please select a Bluetooth device first", Toast.LENGTH_SHORT).show()
            return
        }
        if (viewModel.sourceType.value == SourceType.BLE_GPS &&
            viewModel.bleAddress.value.isBlank()
        ) {
            Toast.makeText(this, "Please select a BLE device", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.startServer(this)
        currentScreen = Screen.NAV
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
}
