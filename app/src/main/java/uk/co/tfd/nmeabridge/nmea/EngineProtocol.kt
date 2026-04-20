package uk.co.tfd.nmeabridge.nmea

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the BLE Engine State frame from characteristic 0xFF02.
 * Frame is 27 bytes little-endian, magic 0xDD. See doc/ble-transport.md §Engine State.
 */
object EngineProtocol {

    private const val MAGIC: Byte = 0xDD.toByte()
    private const val FRAME_SIZE = 27
    private const val NA_U16 = 0xFFFF
    private const val NA_U32 = 0xFFFFFFFF.toInt()

    fun decode(data: ByteArray): EngineState? {
        if (data.size != FRAME_SIZE) return null
        if (data[0] != MAGIC) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)

        val rpmRaw = buf.short.toInt() and 0xFFFF
        val hoursRaw = buf.int
        val coolantRaw = buf.short.toInt() and 0xFFFF
        val altTempRaw = buf.short.toInt() and 0xFFFF
        val altVoltsRaw = buf.short.toInt() and 0xFFFF
        val oilRaw = buf.short.toInt() and 0xFFFF
        val exhaustRaw = buf.short.toInt() and 0xFFFF
        val roomRaw = buf.short.toInt() and 0xFFFF
        val engineBattRaw = buf.short.toInt() and 0xFFFF
        val fuelRaw = buf.short.toInt() and 0xFFFF
        val status1 = buf.short.toInt() and 0xFFFF
        val status2 = buf.short.toInt() and 0xFFFF
        // Per NMEA 2000 convention, 0xFFFF means "data not available". Treat
        // each status word independently — a firmware that only populates one
        // of the two words shouldn't have the unpopulated word decoded as
        // "every bit set" (= every defined alarm active).
        val alarms = when {
            status1 == NA_U16 && status2 == NA_U16 -> null
            else -> EngineAlarm.decode(
                if (status1 == NA_U16) 0 else status1,
                if (status2 == NA_U16) 0 else status2
            )
        }

        return EngineState(
            rpm = if (rpmRaw == NA_U16) null else (rpmRaw * 0.25).toInt(),
            engineHoursSec = if (hoursRaw == NA_U32) null else (hoursRaw.toLong() and 0xFFFFFFFFL),
            coolantC = if (coolantRaw == NA_U16) null else coolantRaw * 0.01 - 273.15,
            alternatorC = if (altTempRaw == NA_U16) null else altTempRaw * 0.01 - 273.15,
            alternatorV = if (altVoltsRaw == NA_U16) null else altVoltsRaw * 0.01,
            oilBar = if (oilRaw == NA_U16) null else oilRaw * 0.001,
            exhaustC = if (exhaustRaw == NA_U16) null else exhaustRaw * 0.01 - 273.15,
            engineRoomC = if (roomRaw == NA_U16) null else roomRaw * 0.01 - 273.15,
            engineBattV = if (engineBattRaw == NA_U16) null else engineBattRaw * 0.01,
            fuelPct = if (fuelRaw == NA_U16) null else fuelRaw * 0.004,
            alarms = alarms
        )
    }
}
