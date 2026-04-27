package uk.co.tfd.nmeabridge.service

import uk.co.tfd.nmeabridge.nmea.BatteryState
import uk.co.tfd.nmeabridge.nmea.EngineState
import uk.co.tfd.nmeabridge.nmea.NavigationState

data class ServiceState(
    val isRunning: Boolean = false,
    val serverAddress: String = "",
    val port: Int = 10110,
    val connectedClients: Int = 0,
    val sourceType: SourceType = SourceType.SIMULATOR,
    val bluetoothDeviceName: String = "",
    val bluetoothConnected: Boolean = false,
    val bluetoothStatus: String? = null,
    val navigationState: NavigationState? = null,
    val batteryState: BatteryState? = null,
    val engineState: EngineState? = null,
    val errorMessage: String? = null
)

/**
 * Per-sentence debug info — updated on every NMEA sentence. Kept out of
 * [ServiceState] so screens that don't display it don't recompose at
 * sentence rate.
 */
data class DebugState(
    val lastSentence: String = "",
    val recentSentences: List<String> = emptyList(),
    val sentenceCount: Long = 0
)

enum class SourceType {
    BLUETOOTH, BLE_GPS, SIMULATOR
}
