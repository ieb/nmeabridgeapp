package com.example.nmeabridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.example.nmeabridge.nmea.NmeaChecksum
import com.example.nmeabridge.nmea.NmeaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

class BluetoothGpsSource(
    private val device: BluetoothDevice,
    private val onConnectionStateChanged: (Boolean, String?) -> Unit = { _, _ -> }
) : NmeaSource {

    @Volatile
    private var running = false
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    override suspend fun start(sink: MutableSharedFlow<String>) = withContext(Dispatchers.IO) {
        running = true

        while (currentCoroutineContext().isActive && running) {
            try {
                onConnectionStateChanged(false, "Connecting to ${device.name ?: device.address}...")
                val btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = btSocket
                btSocket.connect()
                onConnectionStateChanged(true, null)

                val reader = BufferedReader(
                    InputStreamReader(btSocket.inputStream, Charsets.US_ASCII)
                )

                while (currentCoroutineContext().isActive && running) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    if (trimmed[0] != '$' && trimmed[0] != '!') continue

                    // Optionally validate checksum — pass through even if invalid
                    // since some devices send proprietary sentences
                    if (trimmed.contains('*') && !NmeaChecksum.isValid(trimmed)) {
                        continue
                    }

                    sink.tryEmit(trimmed)
                }
            } catch (_: IOException) {
                // Connection lost or failed
            } finally {
                closeSocket()
                onConnectionStateChanged(false, null)
            }

            // Auto-reconnect after delay if still running
            if (running && currentCoroutineContext().isActive) {
                onConnectionStateChanged(false, "Reconnecting in 3s...")
                delay(3000)
            }
        }
    }

    override fun stop() {
        running = false
        closeSocket()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
