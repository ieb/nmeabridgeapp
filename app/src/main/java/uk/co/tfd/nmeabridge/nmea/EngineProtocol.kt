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

    // NMEA 2000 reserves the top three values of each unsigned range for
    // non-data sentinels (0xFFFD reserved, 0xFFFE error, 0xFFFF N/A). A
    // firmware that clips an unrepresentable source value (e.g. -1E9 from
    // a disconnected transducer) produces 0xFFFE, not 0xFFFF — treat the
    // whole reserved band as no-data.
    private const val RESERVED_U16_MIN = 0xFFFD
    private const val RESERVED_U32_MIN = 0xFFFFFFFDL

    private fun u16OrNull(raw: Int): Int? =
        if (raw >= RESERVED_U16_MIN) null else raw
    private fun u32OrNull(raw: Int): Long? {
        val u = raw.toLong() and 0xFFFFFFFFL
        return if (u >= RESERVED_U32_MIN) null else u
    }

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
        // Per NMEA 2000, the top sentinel band means "no data". Treat each
        // status word independently — a firmware that only populates one of
        // the two words shouldn't have the unpopulated word decoded as
        // "every bit set" (= every defined alarm active).
        val s1 = u16OrNull(status1)
        val s2 = u16OrNull(status2)
        val alarms = when {
            s1 == null && s2 == null -> null
            else -> EngineAlarm.decode(s1 ?: 0, s2 ?: 0)
        }

        return EngineState(
            rpm = u16OrNull(rpmRaw)?.let { (it * 0.25).toInt() },
            engineHoursSec = u32OrNull(hoursRaw),
            coolantC = u16OrNull(coolantRaw)?.let { it * 0.01 - 273.15 },
            alternatorC = u16OrNull(altTempRaw)?.let { it * 0.01 - 273.15 },
            alternatorV = u16OrNull(altVoltsRaw)?.let { it * 0.01 },
            oilBar = u16OrNull(oilRaw)?.let { it * 0.001 },
            exhaustC = u16OrNull(exhaustRaw)?.let { it * 0.01 - 273.15 },
            engineRoomC = u16OrNull(roomRaw)?.let { it * 0.01 - 273.15 },
            engineBattV = u16OrNull(engineBattRaw)?.let { it * 0.01 },
            fuelPct = u16OrNull(fuelRaw)?.let { it * 0.004 },
            alarms = alarms
        )
    }
}
