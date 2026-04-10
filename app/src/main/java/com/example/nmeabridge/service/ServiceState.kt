package com.example.nmeabridge.service

data class ServiceState(
    val isRunning: Boolean = false,
    val serverAddress: String = "",
    val port: Int = 10110,
    val connectedClients: Int = 0,
    val sourceType: SourceType = SourceType.SIMULATOR,
    val bluetoothDeviceName: String = "",
    val bluetoothConnected: Boolean = false,
    val bluetoothStatus: String? = null,
    val lastSentence: String = "",
    val sentenceCount: Long = 0,
    val errorMessage: String? = null
)

enum class SourceType {
    BLUETOOTH, SIMULATOR
}
