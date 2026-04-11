package uk.co.tfd.nmeabridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
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
    private val onConnectionStateChanged: (Boolean, String?) -> Unit = { _, _ -> }
) : NmeaSource {

    companion object {
        val NMEA_SERVICE_UUID: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805f9b34fb")
        val NMEA_NOTIFY_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    @Volatile private var running = false
    private var sink: MutableSharedFlow<String>? = null

    private val _navigationState = MutableStateFlow<NavigationState?>(null)
    val navigationState: StateFlow<NavigationState?> = _navigationState.asStateFlow()

    // Buffer for accumulating bytes (in case a frame spans notifications)
    private val frameBuffer = ByteArray(64)
    private var framePos = 0

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

            g.setCharacteristicNotification(notifyChar, true)
            val desc = notifyChar.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (desc != null) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    g.writeDescriptor(desc)
                }
            }

            onConnectionStateChanged(true, null)
        }

        // API 33+ callback
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processIncomingBytes(value)
        }

        // API < 33 callback
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            processIncomingBytes(value)
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
