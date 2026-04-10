package com.example.nmeabridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.example.nmeabridge.bluetooth.BluetoothDeviceSelector
import com.example.nmeabridge.service.SourceType

class MainActivity : ComponentActivity() {

    private val viewModel: ServerViewModel by viewModels()

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.refreshPairedDevices(this)
            actuallyStartServer()
        } else {
            Toast.makeText(this, "Bluetooth permissions required for GPS mode", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Proceed regardless — notification is nice-to-have, service still works
        proceedToStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothAvailable = BluetoothDeviceSelector.isBluetoothAvailable(this)

        setContent {
            MaterialTheme {
                Surface {
                    ServerScreen(
                        viewModel = viewModel,
                        bluetoothAvailable = bluetoothAvailable,
                        onStart = { requestPermissionsAndStart() },
                        onStop = { viewModel.stopServer(this) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to service if it's already running
        try {
            viewModel.bindService(this)
        } catch (_: Exception) {}

        // Refresh BT devices if we have permission
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

    private fun requestPermissionsAndStart() {
        // Step 1: Notification permission (API 33+)
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
        // Step 2: If Bluetooth source, request BT permissions
        if (viewModel.sourceType.value == SourceType.BLUETOOTH) {
            if (!hasBluetoothPermissions()) {
                bluetoothPermissionLauncher.launch(getBluetoothPermissions())
                return
            }
            viewModel.refreshPairedDevices(this)
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
        viewModel.startServer(this)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
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
