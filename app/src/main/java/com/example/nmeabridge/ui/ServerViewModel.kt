package com.example.nmeabridge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nmeabridge.bluetooth.BluetoothDeviceInfo
import com.example.nmeabridge.bluetooth.BluetoothDeviceSelector
import com.example.nmeabridge.service.NmeaForegroundService
import com.example.nmeabridge.service.ServiceState
import com.example.nmeabridge.service.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServerViewModel : ViewModel() {

    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _port = MutableStateFlow(10110)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _sourceType = MutableStateFlow(SourceType.SIMULATOR)
    val sourceType: StateFlow<SourceType> = _sourceType.asStateFlow()

    private val _selectedDevice = MutableStateFlow<BluetoothDeviceInfo?>(null)
    val selectedDevice: StateFlow<BluetoothDeviceInfo?> = _selectedDevice.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDeviceInfo>> = _pairedDevices.asStateFlow()

    private var service: NmeaForegroundService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as NmeaForegroundService.LocalBinder).service
            bound = true
            viewModelScope.launch {
                service!!.state.collect { _serviceState.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
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

    fun startServer(context: Context) {
        val intent = Intent(context, NmeaForegroundService::class.java).apply {
            putExtra(NmeaForegroundService.EXTRA_PORT, _port.value)
            putExtra(NmeaForegroundService.EXTRA_SOURCE_TYPE, _sourceType.value.name)
            if (_sourceType.value == SourceType.BLUETOOTH) {
                putExtra(NmeaForegroundService.EXTRA_BT_ADDRESS, _selectedDevice.value?.address)
            }
        }
        ContextCompat.startForegroundService(context, intent)
        bindService(context)
    }

    fun stopServer(context: Context) {
        unbindService(context)
        context.stopService(Intent(context, NmeaForegroundService::class.java))
        _serviceState.value = ServiceState()
    }
}
