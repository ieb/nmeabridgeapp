package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import uk.co.tfd.nmeabridge.nmea.BmsAlarm
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BmsProtocolTest {

    private fun buildFrame(
        packCv: Int = 1342,           // 13.42 V
        currentCa: Int = -1280,       // -12.80 A (discharge)
        remainingCah: Int = 7820,     // 78.20 Ah
        fullCah: Int = 10000,         // 100.00 Ah
        soc: Int = 78,
        cycles: Int = 42,
        errors: Int = 0,
        fet: Int = 0x03,              // both on
        cellsMv: IntArray = intArrayOf(3358, 3352, 3360, 3366),
        ntcDeciK: IntArray = intArrayOf(2953, 2960, 2963) // ~22.1, 22.8, 23.0 °C
    ): ByteArray {
        val size = 17 + cellsMv.size * 2 + ntcDeciK.size * 2
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0xBB.toByte())
        buf.putShort(packCv.toShort())
        buf.putShort(currentCa.toShort())
        buf.putShort(remainingCah.toShort())
        buf.putShort(fullCah.toShort())
        buf.put(soc.toByte())
        buf.putShort(cycles.toShort())
        buf.putShort(errors.toShort())
        buf.put(fet.toByte())
        buf.put(cellsMv.size.toByte())
        for (v in cellsMv) buf.putShort(v.toShort())
        buf.put(ntcDeciK.size.toByte())
        for (t in ntcDeciK) buf.putShort(t.toShort())
        return buf.array()
    }

    @Test
    fun decode_happyPath4Cells3Ntc() {
        val bytes = buildFrame()
        val s = BmsProtocol.decode(bytes)
        assertNotNull(s)
        s!!
        assertEquals(13.42, s.packV, 1e-6)
        assertEquals(-12.80, s.currentA, 1e-6)
        assertEquals(78.20, s.remainingAh, 1e-6)
        assertEquals(100.00, s.fullAh, 1e-6)
        assertEquals(78, s.soc)
        assertEquals(42, s.cycles)
        assertEquals(listOf(3.358, 3.352, 3.360, 3.366), s.cellVoltagesV)
        assertEquals(3, s.tempsC.size)
        assertEquals(22.15, s.tempsC[0], 0.01)
        assertTrue(s.chargeFet)
        assertTrue(s.dischargeFet)
        assertTrue(s.alarms.isEmpty())
    }

    @Test
    fun decode_signedCurrent() {
        val charging = BmsProtocol.decode(buildFrame(currentCa = 500))!!
        assertEquals(5.0, charging.currentA, 1e-6)
        val idle = BmsProtocol.decode(buildFrame(currentCa = 0))!!
        assertEquals(0.0, idle.currentA, 1e-6)
    }

    @Test
    fun decode_fetBits() {
        val onlyCharge = BmsProtocol.decode(buildFrame(fet = 0x01))!!
        assertTrue(onlyCharge.chargeFet)
        assertFalse(onlyCharge.dischargeFet)
        val onlyDischarge = BmsProtocol.decode(buildFrame(fet = 0x02))!!
        assertFalse(onlyDischarge.chargeFet)
        assertTrue(onlyDischarge.dischargeFet)
        val both = BmsProtocol.decode(buildFrame(fet = 0x00))!!
        assertFalse(both.chargeFet)
        assertFalse(both.dischargeFet)
    }

    @Test
    fun decode_alarmBitmap() {
        val s = BmsProtocol.decode(buildFrame(errors = 0x0002 or 0x0100))!!
        assertTrue(s.alarms.contains(BmsAlarm.CELL_UNDERVOLT))
        assertTrue(s.alarms.contains(BmsAlarm.CHARGE_OVERCURRENT))
        assertEquals(2, s.alarms.size)
    }

    @Test
    fun decode_allAlarmBitsMap() {
        val all = 0x1FFF
        val s = BmsProtocol.decode(buildFrame(errors = all))!!
        assertEquals(13, s.alarms.size)
    }

    @Test
    fun decode_rejectsBadMagic() {
        val bytes = buildFrame()
        bytes[0] = 0xAA.toByte()
        assertNull(BmsProtocol.decode(bytes))
    }

    @Test
    fun decode_rejectsTruncated() {
        val short = ByteArray(8) { 0 }
        short[0] = 0xBB.toByte()
        assertNull(BmsProtocol.decode(short))
    }

    @Test
    fun decode_rejectsCellCountOverrun() {
        // Claim 10 cells but only supply 2 cells worth of bytes (plus nNtc byte)
        val bytes = buildFrame(cellsMv = intArrayOf(3300, 3300), ntcDeciK = intArrayOf())
        // Flip the nCells byte (index 15) to claim 10 cells instead of 2
        bytes[15] = 10
        assertNull(BmsProtocol.decode(bytes))
    }

    @Test
    fun decode_rejectsNtcCountOverrun() {
        // 0 cells, claim 5 NTCs but supply none
        val bytes = buildFrame(cellsMv = intArrayOf(), ntcDeciK = intArrayOf())
        // nNtc byte sits at index 16 (right after nCells at 15, with 0 cell bytes)
        bytes[16] = 5
        assertNull(BmsProtocol.decode(bytes))
    }
}
