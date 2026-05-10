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
import uk.co.tfd.nmeabridge.history.FrameRing
import uk.co.tfd.nmeabridge.history.HistoryDataSource
import uk.co.tfd.nmeabridge.history.RingSnapshot
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import uk.co.tfd.nmeabridge.nmea.NmeaSource
import uk.co.tfd.nmeabridge.server.NmeaTcpServer
import uk.co.tfd.nmeabridge.simulator.SimulatorNmeaSource
import uk.co.tfd.nmeabridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

// History ring capacities: 12 h at 1 Hz = 43 200 frames. The BMS source
// publishes at 0.2 Hz in practice, so the BMS ring holds ~60 h of data
// at that rate — still well under 1 MB for a 48 B fixed slot.
private const val HISTORY_CAPACITY = 43_200

// Cap how often we publish a fresh RingSnapshot to subscribers. Append
// into the ring happens on every BLE notification; only the StateFlow
// emission (and any resulting Compose recomposition) is throttled.
private const val HISTORY_PUBLISH_INTERVAL_MS = 1000L

// Target cadence for the history rings. Every HISTORY_TICK_MS a background
// ticker tops up each ring with a sentinel frame (all-0xFF data) if no
// real frame has arrived within the grace window. Keeps the time axis
// contiguous so chart lines show gaps when the stream dies instead of
// holding the last value until the next real sample arrives.
private const val HISTORY_TICK_MS = 1000L

// If no real frame arrived within this window, the ticker inserts a
// sentinel. Nav/engine publish at ~1 Hz, but BLE notification jitter,
// GC, and connection-interval drift routinely delay a frame by more
// than 500 ms. 1.5 s left no headroom and produced visible holes in
// the engine chart while data was still flowing; 3 s tolerates normal
// jitter and still flags a dead stream within ~2 samples.
private const val HISTORY_GAP_THRESHOLD_MS = 3_000L

// BMS cadence is much slower in practice (~5 s from the BoatWatch firmware
// and the Python simulator). Threshold has to exceed the real cadence
// plus jitter, otherwise the ticker inserts a sentinel between every pair
// of real frames and the chart line breaks visibly even while data is
// flowing.
private const val BATTERY_GAP_THRESHOLD_MS = 15_000L

class NmeaForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _debug = MutableStateFlow(DebugState())
    val debug: StateFlow<DebugState> = _debug.asStateFlow()

    // Wire-format history rings live on the service (rather than the
    // ViewModel) so they share lifetime with the BLE pipeline. Activity
    // recreation no longer wipes the in-memory chart history: the new VM
    // mirrors these StateFlows and gets the latest snapshot on subscribe.
    private val navRing = FrameRing(
        frameSize = BinaryProtocol.FRAME_SIZE,
        capacity = HISTORY_CAPACITY,
    )
    private val engineRing = FrameRing(
        frameSize = EngineProtocol.FRAME_SIZE,
        capacity = HISTORY_CAPACITY,
    )
    private val batteryRing = FrameRing(
        frameSize = BmsProtocol.HISTORY_SLOT_SIZE,
        capacity = HISTORY_CAPACITY,
    )

    private val _navHistory = MutableStateFlow(RingSnapshot.EMPTY)
    val navHistory: StateFlow<RingSnapshot> = _navHistory.asStateFlow()

    private val _engineHistory = MutableStateFlow(RingSnapshot.EMPTY)
    val engineHistory: StateFlow<RingSnapshot> = _engineHistory.asStateFlow()

    private val _batteryHistory = MutableStateFlow(RingSnapshot.EMPTY)
    val batteryHistory: StateFlow<RingSnapshot> = _batteryHistory.asStateFlow()

    private var lastNavPublishMs = 0L
    private var lastEnginePublishMs = 0L
    private var lastBatteryPublishMs = 0L

    // Timestamp of the most recent *real* append per stream. The gap-filler
    // ticker compares against these to decide whether silence has passed
    // and a sentinel should be inserted. @Volatile: written from the BLE
    // collectors and the ticker, both on Dispatchers.Default.
    @Volatile private var lastNavAppendMs = 0L
    @Volatile private var lastEngineAppendMs = 0L
    @Volatile private var lastBatteryAppendMs = 0L

    private val nmeaFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var tcpServer: NmeaTcpServer? = null
    private var nmeaSource: NmeaSource? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Append-only disk persistence. Created once per service instance;
    // writes happen from the BLE raw-frame collectors below alongside the
    // in-memory ring append. Closed in onDestroy.
    private lateinit var historyLogger: uk.co.tfd.nmeabridge.history.persist.HistoryLogger

    /**
     * Read-only view onto the same files the historyLogger writes. Lives
     * on the service so its window cache survives Activity recreation,
     * the same reason the history rings moved here.
     */
    val historyDataSource: HistoryDataSource by lazy {
        HistoryDataSource(historyLogger.directory)
    }

    override fun onCreate() {
        super.onCreate()
        historyLogger = uk.co.tfd.nmeabridge.history.persist.HistoryLogger(this)
        // Gap-filler ticker. Fires every HISTORY_TICK_MS and inserts a
        // sentinel into any stream that's been silent longer than its
        // gap threshold. Only runs once there's been at least one real
        // sample on a stream, so silent streams (e.g. engine on a non-
        // BoatWatch source) don't accumulate filler.
        serviceScope.launch {
            while (isActive) {
                delay(HISTORY_TICK_MS)
                val now = System.currentTimeMillis()
                if (lastNavAppendMs > 0 && now - lastNavAppendMs >= HISTORY_GAP_THRESHOLD_MS) {
                    appendNavSentinel(now)
                }
                if (lastEngineAppendMs > 0 && now - lastEngineAppendMs >= HISTORY_GAP_THRESHOLD_MS) {
                    appendEngineSentinel(now)
                }
                if (lastBatteryAppendMs > 0 && now - lastBatteryAppendMs >= BATTERY_GAP_THRESHOLD_MS) {
                    appendBatterySentinel(now)
                }
            }
        }
    }

    /**
     * Append a raw nav wire frame (29 B, magic 0xCC) to the history ring,
     * persist to disk, and republish a snapshot at most every
     * HISTORY_PUBLISH_INTERVAL_MS.
     */
    private fun appendNav(frame: ByteArray) {
        if (frame.size != BinaryProtocol.FRAME_SIZE) return
        val now = System.currentTimeMillis()
        navRing.append(now, frame)
        historyLogger.nav.append(frame)
        lastNavAppendMs = now
        if (now - lastNavPublishMs >= HISTORY_PUBLISH_INTERVAL_MS) {
            lastNavPublishMs = now
            _navHistory.value = navRing.snapshot()
        }
    }

    private fun appendEngine(frame: ByteArray) {
        if (frame.size != EngineProtocol.FRAME_SIZE) return
        val now = System.currentTimeMillis()
        engineRing.append(now, frame)
        historyLogger.engine.append(frame)
        lastEngineAppendMs = now
        if (now - lastEnginePublishMs >= HISTORY_PUBLISH_INTERVAL_MS) {
            lastEnginePublishMs = now
            _engineHistory.value = engineRing.snapshot()
        }
    }

    private fun appendBattery(wireFrame: ByteArray) {
        // BMS frames are variable-length on the wire; canonicalise to
        // the fixed 48 B history slot before logging. Drop malformed
        // frames silently (encodeHistorySlot returns null).
        val slot = BmsProtocol.encodeHistorySlot(wireFrame) ?: return
        val now = System.currentTimeMillis()
        batteryRing.append(now, slot)
        historyLogger.bms.append(slot)
        lastBatteryAppendMs = now
        if (now - lastBatteryPublishMs >= HISTORY_PUBLISH_INTERVAL_MS) {
            lastBatteryPublishMs = now
            _batteryHistory.value = batteryRing.snapshot()
        }
    }

    /**
     * Append a sentinel frame + republish snapshot. Used by the gap-filler
     * to keep the time axis contiguous when the source stream has stopped.
     * Doesn't update lastXxxAppendMs — the stream is still silent.
     */
    private fun appendNavSentinel(now: Long) {
        navRing.append(now, BinaryProtocol.SENTINEL_FRAME)
        lastNavPublishMs = now
        _navHistory.value = navRing.snapshot()
    }

    private fun appendEngineSentinel(now: Long) {
        engineRing.append(now, EngineProtocol.SENTINEL_FRAME)
        lastEnginePublishMs = now
        _engineHistory.value = engineRing.snapshot()
    }

    private fun appendBatterySentinel(now: Long) {
        batteryRing.append(now, BmsProtocol.SENTINEL_SLOT)
        lastBatteryPublishMs = now
        _batteryHistory.value = batteryRing.snapshot()
    }

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
            // Feed raw wire frames into the in-memory history rings
            // (which also persist to disk via historyLogger). Rings live
            // on the service so they survive Activity recreation —
            // viewmodel rebinds re-read the latest snapshot from the
            // history StateFlows.
            serviceScope.launch {
                source.rawNavFrames.collect { appendNav(it) }
            }
            serviceScope.launch {
                source.rawEngineFrames.collect { appendEngine(it) }
            }
            serviceScope.launch {
                source.rawBatteryFrames.collect { appendBattery(it) }
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
        // Close history files before cancelling the scope: the close path
        // runs fd.sync() one final time so the last record is durable
        // even if the process is killed microseconds later.
        if (::historyLogger.isInitialized) historyLogger.close()
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
