package uk.co.tfd.nmeabridge.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import uk.co.tfd.nmeabridge.bluetooth.BleNmeaSource
import uk.co.tfd.nmeabridge.bluetooth.BluetoothDeviceInfo
import uk.co.tfd.nmeabridge.bluetooth.BluetoothDeviceSelector
import uk.co.tfd.nmeabridge.history.RingSnapshot
import uk.co.tfd.nmeabridge.nmea.PolarRepository
import uk.co.tfd.nmeabridge.nmea.PolarTable
import uk.co.tfd.nmeabridge.service.DebugState
import uk.co.tfd.nmeabridge.service.NmeaForegroundService
import uk.co.tfd.nmeabridge.service.ServiceState
import uk.co.tfd.nmeabridge.service.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BleScannedDevice(
    val name: String,
    val address: String
)

/**
 * The currently-displayed top-level screen. Stored on the ViewModel so it
 * survives Activity recreation (ChromeOS/ARC++ tears down and rebuilds
 * the Activity on window focus changes without killing the process, which
 * would otherwise reset the user to NAV every time they Alt-Tab back).
 */
enum class Screen { NAV, SETTINGS, BLE_TEST, BATTERY, ENGINE, ENGINE_GRAPHS, POLAR }

class ServerViewModel : ViewModel() {

    companion object {
        private const val PREFS_NAME = "nmea_bridge_settings"
        private const val KEY_SOURCE_TYPE = "source_type"
        private const val KEY_PORT = "port"
        private const val KEY_BLE_ADDRESS = "ble_address"
        private const val KEY_BT_ADDRESS = "bt_address"
        private const val KEY_BLE_PIN = "ble_pin"
        private val NAV_SERVICE_PARCEL = ParcelUuid(BleNmeaSource.NMEA_SERVICE_UUID)
    }

    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _debugState = MutableStateFlow(DebugState())
    val debugState: StateFlow<DebugState> = _debugState.asStateFlow()

    private val _currentScreen = MutableStateFlow(Screen.NAV)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    fun setCurrentScreen(s: Screen) { _currentScreen.value = s }

    // History ring snapshots are mirrors of the StateFlows owned by the
    // service. The rings themselves now live on the service so their
    // contents survive Activity recreation. Each onServiceConnected
    // starts a fresh collector; StateFlow's inherent replay-1 means the
    // new VM immediately sees the latest snapshot, including history
    // accumulated while no Activity was bound.
    private val _navHistory = MutableStateFlow(RingSnapshot.EMPTY)
    val navHistory: StateFlow<RingSnapshot> = _navHistory.asStateFlow()

    private val _engineHistory = MutableStateFlow(RingSnapshot.EMPTY)
    val engineHistory: StateFlow<RingSnapshot> = _engineHistory.asStateFlow()

    private val _batteryHistory = MutableStateFlow(RingSnapshot.EMPTY)
    val batteryHistory: StateFlow<RingSnapshot> = _batteryHistory.asStateFlow()

    private val _port = MutableStateFlow(10110)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _sourceType = MutableStateFlow(SourceType.SIMULATOR)
    val sourceType: StateFlow<SourceType> = _sourceType.asStateFlow()

    private val _selectedDevice = MutableStateFlow<BluetoothDeviceInfo?>(null)
    val selectedDevice: StateFlow<BluetoothDeviceInfo?> = _selectedDevice.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDeviceInfo>> = _pairedDevices.asStateFlow()

    private val _bleAddress = MutableStateFlow("")
    val bleAddress: StateFlow<String> = _bleAddress.asStateFlow()

    private val _blePin = MutableStateFlow("0000")
    val blePin: StateFlow<String> = _blePin.asStateFlow()

    private val _bleScannedDevices = MutableStateFlow<List<BleScannedDevice>>(emptyList())
    val bleScannedDevices: StateFlow<List<BleScannedDevice>> = _bleScannedDevices.asStateFlow()

    private val _bleScanning = MutableStateFlow(false)
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()

    // Local UI-only state. The firmware exposes no WiFi-state read path on
    // BLE, so the switch reflects the last intent issued by this client, not
    // the actual firmware state.
    private val _wifiEnabled = MutableStateFlow(false)
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()

    private var polarRepo: PolarRepository? = null
    private val _activePolar = MutableStateFlow<PolarTable?>(null)
    val activePolar: StateFlow<PolarTable?> = _activePolar.asStateFlow()
    private val _polarNames = MutableStateFlow<List<String>>(emptyList())
    val polarNames: StateFlow<List<String>> = _polarNames.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val seenBleAddresses = mutableSetOf<String>()
    private var savedBtAddress: String? = null

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            if (address in seenBleAddresses) return

            val deviceName = try { result.device.name } catch (_: Exception) { null }
            val advertisesNavService = result.scanRecord?.serviceUuids?.contains(NAV_SERVICE_PARCEL) == true

            // Only show devices that advertise the Nav Data service or have a name
            if (!advertisesNavService && deviceName == null) return

            seenBleAddresses.add(address)
            val name = deviceName ?: "BLE Nav"
            handler.post {
                _bleScannedDevices.value = _bleScannedDevices.value + BleScannedDevice(name, address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { _bleScanning.value = false }
        }
    }

    private var service: NmeaForegroundService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as NmeaForegroundService.LocalBinder).service
            bound = true
            viewModelScope.launch {
                service!!.state.collect { _serviceState.value = it }
            }
            viewModelScope.launch {
                service!!.debug.collect { _debugState.value = it }
            }
            // Mirror the service-owned history StateFlows. StateFlow has
            // an implicit replay of 1, so each fresh collector immediately
            // receives the current snapshot — including history that was
            // accumulated while no Activity was bound.
            viewModelScope.launch {
                service!!.navHistory.collect { _navHistory.value = it }
            }
            viewModelScope.launch {
                service!!.engineHistory.collect { _engineHistory.value = it }
            }
            viewModelScope.launch {
                service!!.batteryHistory.collect { _batteryHistory.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    fun loadSettings(context: Context) {
        if (polarRepo == null) {
            val repo = PolarRepository(context.applicationContext)
            polarRepo = repo
            _polarNames.value = repo.list()
            viewModelScope.launch { repo.active.collect { _activePolar.value = it } }
            viewModelScope.launch { repo.names.collect { _polarNames.value = it } }
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _port.value = prefs.getInt(KEY_PORT, 10110)
        _bleAddress.value = prefs.getString(KEY_BLE_ADDRESS, "") ?: ""
        _blePin.value = prefs.getString(KEY_BLE_PIN, "0000") ?: "0000"
        savedBtAddress = prefs.getString(KEY_BT_ADDRESS, null)
        val sourceStr = prefs.getString(KEY_SOURCE_TYPE, null)
        if (sourceStr != null) {
            try {
                _sourceType.value = SourceType.valueOf(sourceStr)
            } catch (_: Exception) {}
        }
    }

    private fun saveSettings(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE_TYPE, _sourceType.value.name)
            .putInt(KEY_PORT, _port.value)
            .putString(KEY_BLE_ADDRESS, _bleAddress.value)
            .putString(KEY_BLE_PIN, _blePin.value)
            .putString(KEY_BT_ADDRESS, _selectedDevice.value?.address ?: savedBtAddress)
            .apply()
    }

    fun bindService(context: Context) {
        val intent = Intent(context, NmeaForegroundService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (bound) {
            context.unbindService(connection)
            bound = false
            service = null
        }
    }

    fun refreshPairedDevices(context: Context) {
        _pairedDevices.value = BluetoothDeviceSelector.getPairedDevices(context)
        // Restore previously selected BT Classic device
        if (_selectedDevice.value == null && savedBtAddress != null) {
            _selectedDevice.value = _pairedDevices.value.find { it.address == savedBtAddress }
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForBleNmeaDevices(context: Context) {
        if (_bleScanning.value) return

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = btManager?.adapter?.bluetoothLeScanner ?: return

        seenBleAddresses.clear()
        _bleScannedDevices.value = emptyList()
        _bleScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, bleScanCallback)

        handler.postDelayed({
            try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
            _bleScanning.value = false
        }, 8_000L)
    }

    fun selectBleDevice(device: BleScannedDevice) {
        _bleAddress.value = device.address
    }

    fun setPort(port: Int) {
        _port.value = port
    }

    fun setSourceType(type: SourceType) {
        _sourceType.value = type
    }

    fun setSelectedDevice(device: BluetoothDeviceInfo?) {
        _selectedDevice.value = device
    }

    fun setBleAddress(address: String) {
        _bleAddress.value = address
    }

    fun setBlePin(pin: String) {
        _blePin.value = pin
    }

    fun startServer(context: Context) {
        saveSettings(context)
        val intent = Intent(context, NmeaForegroundService::class.java).apply {
            putExtra(NmeaForegroundService.EXTRA_PORT, _port.value)
            putExtra(NmeaForegroundService.EXTRA_SOURCE_TYPE, _sourceType.value.name)
            when (_sourceType.value) {
                SourceType.BLUETOOTH -> putExtra(NmeaForegroundService.EXTRA_BT_ADDRESS, _selectedDevice.value?.address)
                SourceType.BLE_GPS -> {
                    putExtra(NmeaForegroundService.EXTRA_BT_ADDRESS, _bleAddress.value)
                    putExtra(NmeaForegroundService.EXTRA_BLE_PIN, _blePin.value)
                }
                else -> {}
            }
        }
        ContextCompat.startForegroundService(context, intent)
        bindService(context)
    }

    fun setBatteryMonitoring(enabled: Boolean) {
        service?.setBatteryMonitoring(enabled)
    }

    fun setEngineMonitoring(enabled: Boolean) {
        service?.setEngineMonitoring(enabled)
    }

    fun setWifiEnabled(enabled: Boolean) {
        _wifiEnabled.value = enabled
        service?.setWifiEnabled(enabled)
    }

    fun setActivePolar(name: String) {
        polarRepo?.setActive(name)
    }

    fun importPolar(uri: Uri, proposedName: String): Result<String> =
        polarRepo?.import(uri, proposedName)
            ?: Result.failure(IllegalStateException("polar repo not initialised"))

    fun deletePolar(name: String) {
        polarRepo?.delete(name)
    }

    fun savePolar(table: PolarTable): Result<Unit> =
        polarRepo?.save(table) ?: Result.failure(IllegalStateException("polar repo not initialised"))

    fun savePolarAs(newName: String, table: PolarTable): Result<String> =
        polarRepo?.saveAs(newName, table) ?: Result.failure(IllegalStateException("polar repo not initialised"))

    fun exportPolar(uri: Uri, table: PolarTable): Result<Unit> =
        polarRepo?.export(uri, table) ?: Result.failure(IllegalStateException("polar repo not initialised"))

    fun stopServer(context: Context) {
        unbindService(context)
        context.stopService(Intent(context, NmeaForegroundService::class.java))
        _serviceState.value = ServiceState()
    }
}
