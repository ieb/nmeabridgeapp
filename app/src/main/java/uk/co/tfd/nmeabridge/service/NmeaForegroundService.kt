package uk.co.tfd.nmeabridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import uk.co.tfd.nmeabridge.NmeaBridgeApp
import uk.co.tfd.nmeabridge.bluetooth.BleNmeaSource
import uk.co.tfd.nmeabridge.bluetooth.BluetoothDeviceSelector
import uk.co.tfd.nmeabridge.bluetooth.BluetoothGpsSource
import uk.co.tfd.nmeabridge.nmea.NmeaSource
import uk.co.tfd.nmeabridge.server.NmeaTcpServer
import uk.co.tfd.nmeabridge.simulator.SimulatorNmeaSource
import uk.co.tfd.nmeabridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class NmeaForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _debug = MutableStateFlow(DebugState())
    val debug: StateFlow<DebugState> = _debug.asStateFlow()

    // Raw wire frames for history rings. Always-on service-level fan-out
    // so that when the current nmeaSource is a BleNmeaSource its raw flows
    // are forwarded here; when it's a simulator source these stay empty.
    // Published alongside decoded ServiceState so the VM can maintain
    // wire-format history rings without a separate binding path.
    private val _rawNavFrames = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val rawNavFrames: SharedFlow<ByteArray> = _rawNavFrames.asSharedFlow()

    private val _rawEngineFrames = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val rawEngineFrames: SharedFlow<ByteArray> = _rawEngineFrames.asSharedFlow()

    private val _rawBatteryFrames = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val rawBatteryFrames: SharedFlow<ByteArray> = _rawBatteryFrames.asSharedFlow()

    private val nmeaFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var tcpServer: NmeaTcpServer? = null
    private var nmeaSource: NmeaSource? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        val service: NmeaForegroundService get() = this@NmeaForegroundService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guard against duplicate starts
        if (_state.value.isRunning) {
            return START_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 10110) ?: 10110
        val sourceTypeName = intent?.getStringExtra(EXTRA_SOURCE_TYPE) ?: SourceType.SIMULATOR.name
        val sourceType = SourceType.valueOf(sourceTypeName)
        val btAddress = intent?.getStringExtra(EXTRA_BT_ADDRESS)
        val blePin = intent?.getStringExtra(EXTRA_BLE_PIN) ?: "0000"

        startForeground(NmeaBridgeApp.NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        // Set up TCP server
        val server = NmeaTcpServer(port, nmeaFlow, serviceScope) { clientCount ->
            _state.update { it.copy(connectedClients = clientCount) }
        }
        tcpServer = server

        // Set up NMEA source
        val source: NmeaSource = when (sourceType) {
            SourceType.SIMULATOR -> SimulatorNmeaSource()
            SourceType.BLUETOOTH -> {
                val devices = BluetoothDeviceSelector.getPairedDevices(this)
                val device = devices.find { it.address == btAddress }
                if (device != null) {
                    BluetoothGpsSource(device.device) { connected, status ->
                        _state.update {
                            it.copy(
                                bluetoothConnected = connected,
                                bluetoothStatus = status
                            )
                        }
                    }
                } else {
                    _state.update { it.copy(errorMessage = "Bluetooth device not found") }
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            SourceType.BLE_GPS -> {
                if (btAddress != null) {
                    BleNmeaSource(this, btAddress, blePin) { connected, status ->
                        _state.update {
                            it.copy(
                                bluetoothConnected = connected,
                                bluetoothStatus = status,
                                errorMessage = when {
                                    connected -> null
                                    status != null && "failed" in status -> status
                                    else -> it.errorMessage
                                }
                            )
                        }
                    }
                } else {
                    _state.update { it.copy(errorMessage = "BLE device address not set") }
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        nmeaSource = source

        // Collect navigation and battery state from BLE source
        if (source is BleNmeaSource) {
            serviceScope.launch {
                // Forward nulls too: the BLE source emits null when it hasn't
                // seen a nav frame for the staleness window, and the UI relies
                // on navigationState going null to fall back to "---".
                source.navigationState.collect { nav ->
                    _state.update { it.copy(navigationState = nav) }
                }
            }
            serviceScope.launch {
                source.batteryState.collect { battery ->
                    _state.update { it.copy(batteryState = battery) }
                }
            }
            serviceScope.launch {
                source.engineState.collect { engine ->
                    _state.update { it.copy(engineState = engine) }
                }
            }
            // Forward raw wire frames from the BLE source to service-level
            // SharedFlows so the VM (or any other service consumer) can
            // maintain wire-format history rings without binding to the
            // source directly.
            serviceScope.launch {
                source.rawNavFrames.collect { _rawNavFrames.tryEmit(it) }
            }
            serviceScope.launch {
                source.rawEngineFrames.collect { _rawEngineFrames.tryEmit(it) }
            }
            serviceScope.launch {
                source.rawBatteryFrames.collect { _rawBatteryFrames.tryEmit(it) }
            }
        }

        // Track sentences flowing through. Lives in a dedicated DebugState so
        // that non-debug screens don't recompose per sentence.
        serviceScope.launch {
            var count = 0L
            val recent = ArrayDeque<String>(12)
            nmeaFlow.collect { sentence ->
                count++
                if (recent.size >= 12) recent.removeFirst()
                recent.addLast(sentence)
                _debug.value = DebugState(
                    lastSentence = sentence,
                    recentSentences = recent.toList(),
                    sentenceCount = count
                )
            }
        }

        // Launch TCP server
        serviceScope.launch {
            try {
                server.start()
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "TCP server error: ${e.message}") }
                stopSelf()
            }
        }

        // Launch NMEA source
        serviceScope.launch {
            try {
                source.start(nmeaFlow)
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "NMEA source error: ${e.message}") }
            }
        }

        _state.update {
            it.copy(
                isRunning = true,
                port = port,
                sourceType = sourceType,
                serverAddress = getDeviceIpAddress(),
                bluetoothDeviceName = when (sourceType) {
                    SourceType.BLUETOOTH -> BluetoothDeviceSelector.getPairedDevices(this)
                        .find { d -> d.address == btAddress }?.name ?: btAddress ?: ""
                    SourceType.BLE_GPS -> btAddress ?: ""
                    else -> ""
                },
                errorMessage = null
            )
        }

        return START_STICKY
    }

    /**
     * Toggle the battery (0xAA03) notification subscription at the firmware.
     * No-op when the current source is not a BLE BoatWatch source.
     */
    fun setBatteryMonitoring(enabled: Boolean) {
        (nmeaSource as? BleNmeaSource)?.setBatterySubscribed(enabled)
    }

    /**
     * Toggle the engine (0xFF02) notification subscription at the firmware.
     * No-op when the current source is not a BLE source.
     */
    fun setEngineMonitoring(enabled: Boolean) {
        (nmeaSource as? BleNmeaSource)?.setEngineSubscribed(enabled)
    }

    /**
     * Enable or disable the firmware's WiFi radio via the BoatWatch command
     * characteristic (0xAA02). No-op when the current source is not a BLE
     * source.
     */
    fun setWifiEnabled(enabled: Boolean) {
        (nmeaSource as? BleNmeaSource)?.sendWifiCommand(enabled)
    }

    override fun onDestroy() {
        nmeaSource?.stop()
        tcpServer?.stop()
        serviceScope.cancel()
        releaseWakeLock()
        _state.update { ServiceState() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NmeaBridgeApp.CHANNEL_ID)
            .setContentTitle("NMEA Bridge")
            .setContentText("TCP server is running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NmeaBridge::ServerWakeLock"
        ).apply {
            acquire(/* timeout = */ 24 * 60 * 60 * 1000L) // 24 hours max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun getDeviceIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .map { it.hostAddress ?: "unknown" }
                .firstOrNull() ?: "localhost"
        } catch (_: Exception) {
            "localhost"
        }
    }

    companion object {
        const val EXTRA_PORT = "port"
        const val EXTRA_SOURCE_TYPE = "source_type"
        const val EXTRA_BT_ADDRESS = "bt_address"
        const val EXTRA_BLE_PIN = "ble_pin"
    }
}
