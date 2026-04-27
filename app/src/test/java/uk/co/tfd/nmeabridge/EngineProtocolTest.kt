package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.tfd.nmeabridge.nmea.EngineAlarm
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EngineProtocolTest {

    private fun buildFrame(
        rpmRaw: Int = 7200,                 // 1800 rpm
        hoursSec: Long = 1_954_800L,        // 543 h
        coolantRaw: Int = 35815,            // 85.0 °C
        altTempRaw: Int = 34815,            // 75.0 °C
        altVoltsRaw: Int = 1420,            // 14.20 V
        oilRaw: Int = 3500,                 // 3.5 bar
        exhaustRaw: Int = 59315,            // 320.0 °C
        roomRaw: Int = 29515,               // 22.0 °C
        engineBattRaw: Int = 1260,          // 12.60 V
        fuelRaw: Int = 18750,               // 75.0 %
        status1: Int = 0,
        status2: Int = 0
    ): ByteArray {
        val buf = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0xDD.toByte())
        buf.putShort(rpmRaw.toShort())
        buf.putInt(hoursSec.toInt())
        buf.putShort(coolantRaw.toShort())
        buf.putShort(altTempRaw.toShort())
        buf.putShort(altVoltsRaw.toShort())
        buf.putShort(oilRaw.toShort())
        buf.putShort(exhaustRaw.toShort())
        buf.putShort(roomRaw.toShort())
        buf.putShort(engineBattRaw.toShort())
        buf.putShort(fuelRaw.toShort())
        buf.putShort(status1.toShort())
        buf.putShort(status2.toShort())
        return buf.array()
    }

    @Test
    fun decode_happyPath() {
        val e = EngineProtocol.decode(buildFrame())
        assertNotNull(e)
        e!!
        assertEquals(1800, e.rpm)
        assertEquals(1_954_800L, e.engineHoursSec)
        assertEquals(85.0, e.coolantC!!, 1e-6)
        assertEquals(75.0, e.alternatorC!!, 1e-6)
        assertEquals(14.20, e.alternatorV!!, 1e-6)
        assertEquals(3.5, e.oilBar!!, 1e-6)
        assertEquals(320.0, e.exhaustC!!, 1e-6)
        assertEquals(22.0, e.engineRoomC!!, 1e-6)
        assertEquals(12.60, e.engineBattV!!, 1e-6)
        assertEquals(75.0, e.fuelPct!!, 1e-6)
        assertTrue(e.alarms!!.isEmpty())
    }

    @Test
    fun decode_engineOffSentinels() {
        // Engine stopped: RPM, coolant, alternator V/T, oil, exhaust unavailable.
        // Fuel, engine-room, engine-battery remain valid per the docs.
        val bytes = buildFrame(
            rpmRaw = 0xFFFF,
            coolantRaw = 0xFFFF,
            altTempRaw = 0xFFFF,
            altVoltsRaw = 0xFFFF,
            oilRaw = 0xFFFF,
            exhaustRaw = 0xFFFF
        )
        val e = EngineProtocol.decode(bytes)!!
        assertNull(e.rpm)
        assertNull(e.coolantC)
        assertNull(e.alternatorC)
        assertNull(e.alternatorV)
        assertNull(e.oilBar)
        assertNull(e.exhaustC)
        // These continue to report under the engine-off staleness rule.
        assertEquals(22.0, e.engineRoomC!!, 1e-6)
        assertEquals(12.60, e.engineBattV!!, 1e-6)
        assertEquals(75.0, e.fuelPct!!, 1e-6)
    }

    @Test
    fun decode_engineHoursSentinel() {
        val bytes = buildFrame(hoursSec = 0xFFFFFFFFL)
        val e = EngineProtocol.decode(bytes)!!
        assertNull(e.engineHoursSec)
    }

    @Test
    fun decode_largeEngineHoursIsUnsigned() {
        // 0xFFFFFFFC = 4,294,967,292 seconds — just below the NMEA 2000
        // reserved sentinel band (FFFD/FFFE/FFFF), so this is valid data.
        val bytes = buildFrame(hoursSec = 0xFFFFFFFCL)
        val e = EngineProtocol.decode(bytes)!!
        assertEquals(0xFFFFFFFCL, e.engineHoursSec)
    }

    @Test
    fun decode_engineHoursReservedBandIsNoData() {
        // 0xFFFFFFFE is the "error" sentinel (one below N/A). NMEA 2000
        // consumers treat the entire reserved band as no-data.
        val bytes = buildFrame(hoursSec = 0xFFFFFFFEL)
        val e = EngineProtocol.decode(bytes)!!
        assertNull(e.engineHoursSec)
    }

    @Test
    fun decode_rpmQuarterResolution() {
        val e = EngineProtocol.decode(buildFrame(rpmRaw = 7201))!!
        // 7201 * 0.25 = 1800.25 — truncated to Int for display
        assertEquals(1800, e.rpm)
    }

    @Test
    fun decode_status1Bits() {
        fun alarms(bit: Int) = EngineProtocol.decode(buildFrame(status1 = bit))!!.alarms!!
        assertTrue(alarms(0x0001).contains(EngineAlarm.CHECK_ENGINE))
        assertTrue(alarms(0x0002).contains(EngineAlarm.OVER_TEMPERATURE))
        assertTrue(alarms(0x0004).contains(EngineAlarm.LOW_OIL_PRESSURE))
        assertTrue(alarms(0x0020).contains(EngineAlarm.LOW_SYSTEM_VOLTAGE))
        assertTrue(alarms(0x0080).contains(EngineAlarm.WATER_FLOW))
        assertTrue(alarms(0x0200).contains(EngineAlarm.CHARGE_INDICATOR))
        assertTrue(alarms(0x8000).contains(EngineAlarm.EMERGENCY_STOP))
    }

    @Test
    fun decode_status2Bits() {
        fun alarms(bit: Int) = EngineProtocol.decode(buildFrame(status2 = bit))!!.alarms!!
        assertTrue(alarms(0x0008).contains(EngineAlarm.MAINTENANCE_NEEDED))
        assertTrue(alarms(0x0010).contains(EngineAlarm.ENGINE_COMM_ERROR))
        assertTrue(alarms(0x0080).contains(EngineAlarm.ENGINE_SHUTTING_DOWN))
    }

    @Test
    fun decode_statusReservedBitsIgnored() {
        // All non-defined bits set on both status words — none should map to alarms.
        val reservedMask1 = 0xFFFF and
            0x0001.inv() and 0x0002.inv() and 0x0004.inv() and 0x0020.inv() and
            0x0080.inv() and 0x0200.inv() and 0x8000.inv()
        val reservedMask2 = 0xFFFF and
            0x0008.inv() and 0x0010.inv() and 0x0080.inv()
        val e = EngineProtocol.decode(
            buildFrame(status1 = reservedMask1, status2 = reservedMask2)
        )!!
        assertTrue(e.alarms!!.isEmpty())
    }

    @Test
    fun decode_statusUnavailable() {
        // Both status words at 0xFFFF means the firmware has no status info —
        // must decode as null (not "all alarms active"), otherwise every
        // defined alarm bit reads as set.
        val e = EngineProtocol.decode(
            buildFrame(status1 = 0xFFFF, status2 = 0xFFFF)
        )!!
        assertNull(e.alarms)
    }

    @Test
    fun decode_rejectsBadMagic() {
        val bytes = buildFrame()
        bytes[0] = 0xAA.toByte()
        assertNull(EngineProtocol.decode(bytes))
    }

    @Test
    fun decode_rejectsTruncated() {
        val bytes = ByteArray(10) { 0 }
        bytes[0] = 0xDD.toByte()
        assertNull(EngineProtocol.decode(bytes))
    }

    @Test
    fun decode_rejectsOversized() {
        val bytes = ByteArray(32) { 0 }
        bytes[0] = 0xDD.toByte()
        assertNull(EngineProtocol.decode(bytes))
    }
}
