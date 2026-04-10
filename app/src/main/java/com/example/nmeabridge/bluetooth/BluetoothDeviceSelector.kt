package com.example.nmeabridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

object BluetoothDeviceSelector {

    fun isBluetoothAvailable(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter != null
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(context: Context): List<BluetoothDeviceInfo> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return emptyList()
        return adapter.bondedDevices.map { device ->
            BluetoothDeviceInfo(
                name = device.name ?: "Unknown",
                address = device.address,
                device = device
            )
        }.sortedBy { it.name }
    }
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val device: BluetoothDevice
)
