package uk.co.tfd.nmeabridge.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BleFoundDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

data class BleTestState(
    val logLines: List<String> = emptyList(),
    val scanning: Boolean = false,
    val foundDevices: List<BleFoundDevice> = emptyList(),
    val phase: String = "idle"
)

class BleTestViewModel : ViewModel() {

    companion object {
        private const val SCAN_TIMEOUT_MS = 10_000L
        val BOATWATCH_SERVICE_UUID: UUID = UUID.fromString("0000AA00-0000-1000-8000-00805f9b34fb")
    }

    private val _state = MutableStateFlow(BleTestState())
    val state: StateFlow<BleTestState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val seenAddresses = ConcurrentHashMap<String, Boolean>()
    @Volatile private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return
            val name = try { device.name } catch (_: Exception) { null } ?: "Unknown"
            val rssi = result.rssi

            // Deduplicate without touching _state
            if (seenAddresses.putIfAbsent(address, true) != null) return

            // Post to main thread to avoid any threading issues
            handler.post {
                appendLog("Found: $name ($address) RSSI=$rssi")
                val current = _state.value
                _state.value = current.copy(
                    foundDevices = current.foundDevices + BleFoundDevice(name, address, rssi)
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "app registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                else -> "unknown ($errorCode)"
            }
            handler.post {
                appendLog("SCAN FAILED: $reason")
                _state.value = _state.value.copy(scanning = false, phase = "error")
                isScanning = false
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        appendLog("CONNECTED (status=$status)")
                        appendLog("Requesting MTU 64...")
                        _state.value = _state.value.copy(phase = "connected")
                        g.requestMtu(64)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        appendLog("DISCONNECTED (status=$status)")
                        _state.value = _state.value.copy(phase = "disconnected")
                        g.close()
                        gatt = null
                    }
                    else -> {
                        appendLog("Connection state: $newState (status=$status)")
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                appendLog("MTU changed to $mtu (status=$status)")
                appendLog("Discovering services...")
                _state.value = _state.value.copy(phase = "discovering")
                g.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appendLog("SERVICE DISCOVERY FAILED: status=$status")
                    _state.value = _state.value.copy(phase = "error")
                    return@post
                }

                val services = g.services
                appendLog("Discovered ${services.size} service(s):")

                var foundBoatWatch = false
                for (service in services) {
                    val shortUuid = formatUuid(service.uuid.toString())
                    val isBoatWatch = service.uuid == BOATWATCH_SERVICE_UUID
                    if (isBoatWatch) foundBoatWatch = true

                    val marker = if (isBoatWatch) " << BOATWATCH" else ""
                    appendLog("  Service: $shortUuid$marker")
                    logCharacteristics(service)
                }

                if (foundBoatWatch) {
                    appendLog("SUCCESS: BoatWatch service found!")
                } else {
                    appendLog("BoatWatch service (AA00) not found on this device")
                }

                appendLog("--- Test complete ---")
                _state.value = _state.value.copy(phase = "done")
            }
        }
    }

    private fun logCharacteristics(service: BluetoothGattService) {
        for (char in service.characteristics) {
            val uuid = formatUuid(char.uuid.toString())
            val props = formatProperties(char.properties)
            appendLog("    Char: $uuid [$props]")
        }
    }

    private fun formatProperties(props: Int): String {
        val parts = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts.add("R")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts.add("W")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts.add("WNR")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts.add("N")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts.add("I")
        return parts.joinToString(",")
    }

    private fun formatUuid(uuid: String): String {
        if (uuid.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return uuid.substring(4, 8).uppercase()
        }
        return uuid.uppercase()
    }

    // Must be called on main thread
    private fun appendLog(message: String) {
        val ts = timeFormat.format(Date())
        val current = _state.value
        _state.value = current.copy(logLines = current.logLines + "[$ts] $message")
    }

    @SuppressLint("MissingPermission")
    fun startScan(context: Context) {
        if (isScanning) {
            appendLog("Scan already in progress")
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        // Pre-flight checks
        if (adapter == null) {
            appendLog("ERROR: Bluetooth adapter not available")
            _state.value = _state.value.copy(phase = "error")
            return
        }
        if (!adapter.isEnabled) {
            appendLog("ERROR: Bluetooth is disabled")
            _state.value = _state.value.copy(phase = "error")
            return
        }

        // On API < 31, BLE scan requires location services to be enabled
        if (Build.VERSION.SDK_INT < 31) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            if (!locationEnabled) {
                appendLog("WARNING: Location services are disabled")
                appendLog("BLE scanning on API ${Build.VERSION.SDK_INT} requires location to be ON")
                appendLog("Enable location in Settings and retry")
                _state.value = _state.value.copy(phase = "error")
                return
            }
            appendLog("Location services: enabled (required for BLE scan on API ${Build.VERSION.SDK_INT})")
        }

        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            appendLog("ERROR: BLE scanner not available (is Bluetooth on?)")
            _state.value = _state.value.copy(phase = "error")
            return
        }

        seenAddresses.clear()
        _state.value = _state.value.copy(scanning = true, foundDevices = emptyList(), phase = "scanning")
        appendLog("Starting BLE scan (${SCAN_TIMEOUT_MS / 1000}s)...")
        appendLog("Android API: ${Build.VERSION.SDK_INT}, BT adapter: ${adapter.name ?: "unknown"}")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            leScanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            appendLog("ERROR starting scan: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = _state.value.copy(scanning = false, phase = "error")
            isScanning = false
            return
        }

        handler.postDelayed({
            stopScan(leScanner)
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(scanner: android.bluetooth.le.BluetoothLeScanner? = null) {
        if (!isScanning) return
        isScanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            appendLog("Warning stopping scan: ${e.message}")
        }
        val count = _state.value.foundDevices.size
        appendLog("Scan stopped: $count device(s) found")
        _state.value = _state.value.copy(scanning = false, phase = if (count > 0) "scanned" else "idle")
    }

    @SuppressLint("MissingPermission")
    fun stopScanFromUi() {
        val btManager: BluetoothManager? = null // we don't have context here
        // Just set flags - the timeout will clean up
        if (isScanning) {
            isScanning = false
            val count = _state.value.foundDevices.size
            appendLog("Scan cancelled")
            _state.value = _state.value.copy(scanning = false, phase = if (count > 0) "scanned" else "idle")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(context: Context, address: String) {
        disconnect()

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null) {
            appendLog("Bluetooth adapter not available")
            return
        }

        appendLog("Connecting to $address...")
        _state.value = _state.value.copy(phase = "connecting")

        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                appendLog("ERROR: connectGatt returned null")
                _state.value = _state.value.copy(phase = "error")
            }
        } catch (e: Exception) {
            appendLog("connectGatt failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = _state.value.copy(phase = "error")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let {
            appendLog("Disconnecting...")
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                appendLog("Disconnect error: ${e.message}")
            }
        }
        gatt = null
    }

    fun clearLog() {
        _state.value = BleTestState()
        seenAddresses.clear()
    }

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        isScanning = false
        try { disconnect() } catch (_: Exception) {}
    }
}
