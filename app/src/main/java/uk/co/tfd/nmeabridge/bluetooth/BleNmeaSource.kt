package uk.co.tfd.nmeabridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import uk.co.tfd.nmeabridge.nmea.BatteryState
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import uk.co.tfd.nmeabridge.nmea.EngineState
import uk.co.tfd.nmeabridge.nmea.NavigationState
import uk.co.tfd.nmeabridge.nmea.NmeaSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class BleNmeaSource(
    private val context: Context,
    private val deviceAddress: String,
    private val pin: String = "0000",
    private val onConnectionStateChanged: (Boolean, String?) -> Unit = { _, _ -> }
) : NmeaSource {

    companion object {
        val NMEA_SERVICE_UUID: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805f9b34fb")
        val NMEA_NOTIFY_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805f9b34fb")
        val ENGINE_STATE_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // BoatWatch auth service
        val BW_SERVICE_UUID: UUID = UUID.fromString("0000AA00-0000-1000-8000-00805f9b34fb")
        val BW_AUTOPILOT_UUID: UUID = UUID.fromString("0000AA01-0000-1000-8000-00805f9b34fb")
        val BW_COMMAND_UUID: UUID = UUID.fromString("0000AA02-0000-1000-8000-00805f9b34fb")
        val BW_BATTERY_UUID: UUID = UUID.fromString("0000AA03-0000-1000-8000-00805f9b34fb")

        private const val MAGIC_AUTOPILOT: Byte = 0xAA.toByte()
        private const val MAGIC_AUTH_RESP: Byte = 0xAF.toByte()
        private const val CMD_AUTH: Byte = 0xF0.toByte()
        private const val CMD_ENABLE_WIFI: Byte = 0x40.toByte()
        private const val CMD_DISABLE_WIFI: Byte = 0x41.toByte()

        private const val RECONNECT_DELAY_MS = 3000L

        // Nav frames are published at 1 Hz. If none arrive for this long,
        // treat the stream as stale: drop last-known NavigationState so
        // downstream consumers render "not available" instead of frozen
        // numbers, and flip the UI connection indicator to the
        // "disconnected" colour so the stale condition is visible.
        // Do NOT tear down GATT on nav silence — the NMEA 2000 bus can be
        // off while BMS/engine data continue to flow over BLE, and a
        // disconnect would lose their history. Reconnect is driven only by
        // real link-layer STATE_DISCONNECTED events.
        private const val NAV_STALENESS_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    @Volatile private var running = false
    private var sink: MutableSharedFlow<String>? = null

    private val _navigationState = MutableStateFlow<NavigationState?>(null)
    val navigationState: StateFlow<NavigationState?> = _navigationState.asStateFlow()

    private val _batteryState = MutableStateFlow<BatteryState?>(null)
    val batteryState: StateFlow<BatteryState?> = _batteryState.asStateFlow()

    private val _engineState = MutableStateFlow<EngineState?>(null)
    val engineState: StateFlow<EngineState?> = _engineState.asStateFlow()

    private var commandChar: BluetoothGattCharacteristic? = null
    private var batteryChar: BluetoothGattCharacteristic? = null
    private var engineChar: BluetoothGattCharacteristic? = null
    // Default to subscribed: we accumulate battery and engine history for
    // as long as the source is running, so the UI can show it at any time.
    // The screens previously toggled these on-demand via DisposableEffect,
    // which meant history was lost every time the screen was left.
    @Volatile private var batteryWanted: Boolean = true
    @Volatile private var batterySubscribed: Boolean = false
    @Volatile private var engineWanted: Boolean = true
    @Volatile private var engineSubscribed: Boolean = false
    @Volatile private var authenticated: Boolean = false

    // Android GATT only allows one outstanding write at a time. We queue ops and only
    // dispatch the next once the current one's completion callback fires. The flag
    // guards against re-dispatching while a write is in flight.
    @Volatile private var gattOpInFlight: Boolean = false
    private val pendingGattOps = ArrayDeque<() -> Unit>()

    // Buffer for accumulating bytes (in case a frame spans notifications)
    private val frameBuffer = ByteArray(64)
    private var framePos = 0

    private val navStaleRunnable = Runnable {
        // Nav stream has been silent for NAV_STALENESS_MS. Clear nav state
        // only — GATT stays up and battery / engine notifications can
        // still arrive independently (BMS is on its own power rail). Nav
        // staleness is communicated to the UI by _navigationState going
        // null; the GATT-connected flag must stay true so screens that
        // depend on non-nav data (BatteryScreen) keep rendering live.
        _navigationState.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!running) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onConnectionStateChanged(false, "Connected, discovering services...")
                    g.requestMtu(256)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onConnectionStateChanged(false, "Disconnected")
                    g.close()
                    gatt = null
                    authenticated = false
                    batterySubscribed = false
                    batteryChar = null
                    engineSubscribed = false
                    engineChar = null
                    commandChar = null
                    pendingGattOps.clear()
                    gattOpInFlight = false
                    // Disconnect implies nav data is no longer valid. Drop it
                    // now rather than waiting for the staleness watchdog, so
                    // the UI and TCP output reflect the lost link immediately.
                    handler.removeCallbacks(navStaleRunnable)
                    _navigationState.value = null
                    if (running) {
                        onConnectionStateChanged(false, "Reconnecting in 3s...")
                        handler.postDelayed({ doConnect() }, RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !running) return

            val service = g.getService(NMEA_SERVICE_UUID)
            if (service == null) {
                onConnectionStateChanged(false, "NMEA service not found on device")
                return
            }

            val notifyChar = service.getCharacteristic(NMEA_NOTIFY_UUID)
            if (notifyChar == null) {
                onConnectionStateChanged(false, "NMEA characteristic not found")
                return
            }

            // Optional — only present on firmware that exposes engine telemetry
            engineChar = service.getCharacteristic(ENGINE_STATE_UUID)

            pendingGattOps.clear()
            gattOpInFlight = false

            // Queue: subscribe to FF01 nav notify
            pendingGattOps.addLast {
                enableNotifyOp(g, notifyChar)
            }

            // Check for BoatWatch auth service
            val bwService = g.getService(BW_SERVICE_UUID)
            if (bwService != null) {
                val autopilotChar = bwService.getCharacteristic(BW_AUTOPILOT_UUID)
                commandChar = bwService.getCharacteristic(BW_COMMAND_UUID)
                batteryChar = bwService.getCharacteristic(BW_BATTERY_UUID)

                if (autopilotChar != null && commandChar != null) {
                    // Queue: subscribe to AA01 (auth response + autopilot state)
                    pendingGattOps.addLast {
                        enableNotifyOp(g, autopilotChar)
                    }
                    // Queue: write auth command (characteristic write, waits for onCharacteristicWrite)
                    pendingGattOps.addLast {
                        sendAuthCommand(g)
                    }
                    onConnectionStateChanged(false, "Authenticating...")
                } else {
                    onConnectionStateChanged(true, null)
                }
            } else {
                // No auth service (e.g. old simulator) — just connect
                onConnectionStateChanged(true, null)
            }

            kickQueue()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!running) return
            handler.post { onGattOpCompleted() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!running) return
            handler.post { onGattOpCompleted() }
        }

        // API 33+ callback
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic.uuid, value)
        }

        // API < 33 callback
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleNotification(characteristic.uuid, value)
        }
    }

    /**
     * Mark the current in-flight op as complete and dispatch the next queued op (if any).
     * Always invoked on the main-looper thread.
     */
    private fun onGattOpCompleted() {
        gattOpInFlight = false
        kickQueue()
    }

    /**
     * Dispatch the next queued op if no write is currently in flight. Safe to call repeatedly.
     * Must be invoked on the main-looper thread.
     */
    private fun kickQueue() {
        if (gattOpInFlight) return
        val op = pendingGattOps.removeFirstOrNull() ?: return
        gattOpInFlight = true
        handler.post(op)
    }

    /**
     * Shared helper for "enable notifications on this characteristic" ops. Writes the CCC
     * descriptor; on failure or when there is no CCC, releases the in-flight flag so the
     * queue keeps moving.
     */
    @SuppressLint("MissingPermission")
    private fun enableNotifyOp(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        safeGattOp(onFailure = {}) {
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (desc == null || !writeCccDescriptor(g, desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                onGattOpCompleted()
            }
        }
    }

    private fun handleNotification(charUuid: UUID, value: ByteArray) {
        if (charUuid == BW_AUTOPILOT_UUID && value.size >= 2 && value[0] == MAGIC_AUTH_RESP) {
            val accepted = value[1] == 0x01.toByte()
            if (accepted) {
                authenticated = true
                onConnectionStateChanged(true, null)
                // Kick off any subscriptions the user requested before auth completed
                if (batteryWanted && !batterySubscribed) {
                    handler.post { applyBatterySubscription(true) }
                }
                if (engineWanted && !engineSubscribed) {
                    handler.post { applyEngineSubscription(true) }
                }
            } else {
                onConnectionStateChanged(false, "Authentication failed: wrong PIN")
            }
            return
        }
        if (charUuid == BW_BATTERY_UUID) {
            val parsed = BmsProtocol.decode(value)
            if (parsed != null) _batteryState.value = parsed
            return
        }
        if (charUuid == ENGINE_STATE_UUID) {
            val parsed = EngineProtocol.decode(value)
            if (parsed != null) _engineState.value = parsed
            return
        }
        processIncomingBytes(value)
    }

    @SuppressLint("MissingPermission")
    private fun writeCccDescriptor(
        g: BluetoothGatt,
        desc: BluetoothGattDescriptor,
        value: ByteArray = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    ): Boolean {
        // Guard every GATT call: the underlying BluetoothGatt binder can die
        // (DeadObjectException) or refuse work in unexpected states
        // (IllegalStateException). If we let those escape, the caller never
        // calls onGattOpCompleted() and kickQueue() stalls forever — the
        // queue holds gattOpInFlight=true and no further ops dispatch.
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.setValue(value)
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Run a GATT op, clearing the in-flight flag on exception so the queue
     * keeps moving. Also resets the "subscribed" intent flag the caller
     * eagerly set, so a retry will re-attempt the subscription rather than
     * short-circuiting as a no-op.
     */
    private inline fun safeGattOp(onFailure: () -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            onFailure()
            onGattOpCompleted()
        }
    }

    /**
     * Toggle the battery characteristic (0xAA03) subscription. Safe to call from any thread
     * and before connection is established — intent is remembered and applied once the GATT
     * is connected and authenticated.
     */
    fun setBatterySubscribed(enabled: Boolean) {
        batteryWanted = enabled
        // Clear stale data when turning off so the UI doesn't show ghost values next time
        if (!enabled) _batteryState.value = null
        handler.post { applyBatterySubscription(enabled) }
    }

    @SuppressLint("MissingPermission")
    private fun applyBatterySubscription(enabled: Boolean) {
        val g = gatt ?: return
        val c = batteryChar ?: return
        if (!authenticated) return
        if (enabled == batterySubscribed) return

        // Update the flag EAGERLY (before the op dispatches). Otherwise, if two
        // toggles are posted back-to-back on the main looper (e.g. screen A
        // disposes with setEngineMonitoring(false) and screen B composes with
        // (true) in the same frame), the second call would read the stale flag,
        // see no change required, and return — leaving the subscription off.
        batterySubscribed = enabled
        pendingGattOps.addLast {
            safeGattOp(onFailure = { batterySubscribed = !enabled }) {
                g.setCharacteristicNotification(c, enabled)
                val desc = c.getDescriptor(CCC_DESCRIPTOR_UUID)
                val v = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                if (desc == null || !writeCccDescriptor(g, desc, v)) {
                    onGattOpCompleted()
                }
            }
        }
        kickQueue()
    }

    /**
     * Toggle the engine characteristic (0xFF02) subscription. Safe to call from any thread
     * and before connection is established — intent is remembered and applied once the GATT
     * is connected and authenticated.
     */
    fun setEngineSubscribed(enabled: Boolean) {
        engineWanted = enabled
        if (!enabled) _engineState.value = null
        handler.post { applyEngineSubscription(enabled) }
    }

    @SuppressLint("MissingPermission")
    private fun applyEngineSubscription(enabled: Boolean) {
        val g = gatt ?: return
        val c = engineChar ?: return
        if (!authenticated) return
        if (enabled == engineSubscribed) return

        // Eager flag update — see comment in applyBatterySubscription.
        engineSubscribed = enabled
        pendingGattOps.addLast {
            safeGattOp(onFailure = { engineSubscribed = !enabled }) {
                g.setCharacteristicNotification(c, enabled)
                val desc = c.getDescriptor(CCC_DESCRIPTOR_UUID)
                val v = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                if (desc == null || !writeCccDescriptor(g, desc, v)) {
                    onGattOpCompleted()
                }
            }
        }
        kickQueue()
    }

    /**
     * Send an enable/disable-WiFi command on the BoatWatch command characteristic
     * (0xAA02). No-op if the source isn't yet connected and authenticated — the
     * firmware requires prior auth on all 0xAA02 commands, and there is no
     * response characteristic to confirm the WiFi state, so the caller tracks
     * intent locally. Safe to call from any thread.
     */
    fun sendWifiCommand(enable: Boolean) {
        handler.post { queueWifiCommand(enable) }
    }

    @SuppressLint("MissingPermission")
    private fun queueWifiCommand(enable: Boolean) {
        val g = gatt ?: return
        val c = commandChar ?: return
        if (!authenticated) return
        val cmdByte = if (enable) CMD_ENABLE_WIFI else CMD_DISABLE_WIFI
        pendingGattOps.addLast {
            safeGattOp(onFailure = {}) {
                val data = byteArrayOf(MAGIC_AUTOPILOT, cmdByte)
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.setValue(data)
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
                if (!ok) onGattOpCompleted()
            }
        }
        kickQueue()
    }

    @SuppressLint("MissingPermission")
    private fun sendAuthCommand(g: BluetoothGatt) {
        val cmd = commandChar ?: run {
            onGattOpCompleted()
            return
        }
        val pinBytes = pin.toByteArray(Charsets.US_ASCII)
        val data = byteArrayOf(MAGIC_AUTOPILOT, CMD_AUTH,
            pinBytes.getOrElse(0) { '0'.code.toByte() },
            pinBytes.getOrElse(1) { '0'.code.toByte() },
            pinBytes.getOrElse(2) { '0'.code.toByte() },
            pinBytes.getOrElse(3) { '0'.code.toByte() }
        )
        safeGattOp(onFailure = {}) {
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(cmd, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cmd.setValue(data)
                cmd.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(cmd)
            }
            if (!ok) onGattOpCompleted()
        }
    }

    private fun processIncomingBytes(value: ByteArray) {
        // Check for binary protocol frame (magic 0xCC, 29 bytes)
        if (value.size == 29 && value[0] == 0xCC.toByte()) {
            processBinaryFrame(value)
            return
        }

        // Accumulate into buffer looking for a complete frame
        for (b in value) {
            if (framePos == 0 && b != 0xCC.toByte()) continue // wait for magic
            frameBuffer[framePos++] = b
            if (framePos == 29) {
                processBinaryFrame(frameBuffer.copyOf(29))
                framePos = 0
            }
            if (framePos >= frameBuffer.size) framePos = 0 // overflow safety
        }
    }

    private fun processBinaryFrame(data: ByteArray) {
        val nav = BinaryProtocol.decode(data) ?: return

        // Publish navigation state for the UI
        _navigationState.value = nav

        // Reset the staleness watchdog: another nav frame within
        // NAV_STALENESS_MS keeps the state live; silence triggers
        // navStaleRunnable which clears the state and flips the indicator.
        // postDelayed/removeCallbacks must run on the main looper thread.
        handler.removeCallbacks(navStaleRunnable)
        handler.postDelayed(navStaleRunnable, NAV_STALENESS_MS)

        // Convert to NMEA sentences for TCP clients
        val sentences = BinaryProtocol.toNmeaSentences(nav)
        sentences.forEach { sink?.tryEmit(it) }
    }


    override suspend fun start(sink: MutableSharedFlow<String>) {
        this.sink = sink
        running = true
        framePos = 0
        doConnect()

        while (running) {
            kotlinx.coroutines.delay(500)
        }
    }

    @SuppressLint("MissingPermission")
    private fun doConnect() {
        if (!running) return
        onConnectionStateChanged(false, "Connecting to $deviceAddress...")

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onConnectionStateChanged(false, "Bluetooth not available")
            return
        }

        try {
            val device = adapter.getRemoteDevice(deviceAddress)
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            onConnectionStateChanged(false, "Connect failed: ${e.message}")
            if (running) {
                handler.postDelayed({ doConnect() }, RECONNECT_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: Exception) {}
        }
        gatt = null
        sink = null
    }
}
