package uk.co.tfd.nmeabridge.nmea

import uk.co.tfd.nmeabridge.history.RingSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the BLE Engine State frame from characteristic 0xFF02.
 * Frame is 27 bytes little-endian, magic 0xDD. See doc/ble-transport.md §Engine State.
 */
object EngineProtocol {

    internal const val MAGIC: Byte = 0xDD.toByte()
    internal const val FRAME_SIZE = 27

    // Field offsets from magic byte at 0. See doc/ble-transport.md §Engine State.
    internal const val OFF_RPM = 1            // U16, 0.25 rpm
    internal const val OFF_HOURS = 3          // U32, 1 s
    internal const val OFF_COOLANT = 7        // U16, 0.01 K
    internal const val OFF_ALTERNATOR_T = 9   // U16, 0.01 K
    internal const val OFF_ALTERNATOR_V = 11  // U16, 0.01 V
    internal const val OFF_OIL = 13           // U16, 0.001 bar
    internal const val OFF_EXHAUST = 15       // U16, 0.01 K
    internal const val OFF_ROOM = 17          // U16, 0.01 K
    internal const val OFF_ENGINE_BATT = 19   // U16, 0.01 V
    internal const val OFF_FUEL = 21          // U16, 0.004 %
    internal const val OFF_STATUS1 = 23       // U16 bitmask
    internal const val OFF_STATUS2 = 25       // U16 bitmask

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

    // --- Per-field accessors over a history RingSnapshot -----------------
    //
    // Charts read one field per sample per metric. Accessors decode only
    // the bytes they need. Parity with decode(frame).<field> guaranteed
    // by AccessorParityTest.

    fun rpmAt(s: RingSnapshot, i: Int): Int? =
        u16OrNull(s.readU16(i, OFF_RPM))?.let { (it * 0.25).toInt() }

    fun engineHoursSecAt(s: RingSnapshot, i: Int): Long? =
        u32OrNull(s.readU32(i, OFF_HOURS))

    fun coolantCAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_COOLANT))?.let { it * 0.01 - 273.15 }

    fun alternatorCAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_ALTERNATOR_T))?.let { it * 0.01 - 273.15 }

    fun alternatorVAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_ALTERNATOR_V))?.let { it * 0.01 }

    fun oilBarAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_OIL))?.let { it * 0.001 }

    fun exhaustCAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_EXHAUST))?.let { it * 0.01 - 273.15 }

    fun engineRoomCAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_ROOM))?.let { it * 0.01 - 273.15 }

    fun engineBattVAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_ENGINE_BATT))?.let { it * 0.01 }

    fun fuelPctAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_FUEL))?.let { it * 0.004 }

    /**
     * 27 B sentinel frame used by the gap-filler ticker when no engine
     * frame arrived in the current second. Every data field holds its
     * "not available" sentinel so accessors return null and charts draw
     * a gap.
     */
    internal val SENTINEL_FRAME: ByteArray = ByteArray(FRAME_SIZE).apply {
        this[0] = MAGIC
        for (i in 1 until size) this[i] = 0xFF.toByte()
    }
}
