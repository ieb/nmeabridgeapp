package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.tfd.nmeabridge.history.FrameRing
import uk.co.tfd.nmeabridge.history.RingSnapshot
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Guarantees the per-field *At(snap, i) accessors used by the history
 * charts produce the same result as the full decode(frame).<field> path.
 * If this ever drifts, charts show different numbers from the dials.
 */
class AccessorParityTest {

    // --- Helpers --------------------------------------------------------

    private fun ringFrom(frameSize: Int, frames: List<ByteArray>): RingSnapshot {
        val ring = FrameRing(frameSize, frames.size)
        for (f in frames) ring.append(0L, f)
        return ring.snapshot()
    }

    // --- Nav ------------------------------------------------------------

    private fun navFrame(
        latE7: Int = 500_700_000,       // 50.07 N
        lonE7: Int = -95_000_000,       // -9.50 E
        cogRaw: Int = 6283,             // ~36°
        sogRaw: Int = 257,              // 2.57 m/s
        variationRaw: Int = 5,
        headingRaw: Int = 6283,
        depthRaw: Int = 1350,           // 13.5 m
        awaRaw: Int = 10000,
        awsRaw: Int = 800,
        stwRaw: Int = 245,
        logM: Long = 1_000_000L,
    ): ByteArray {
        val buf = ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0xCC.toByte())
        buf.putInt(latE7)
        buf.putInt(lonE7)
        buf.putShort(cogRaw.toShort())
        buf.putShort(sogRaw.toShort())
        buf.putShort(variationRaw.toShort())
        buf.putShort(headingRaw.toShort())
        buf.putShort(depthRaw.toShort())
        buf.putShort(awaRaw.toShort())
        buf.putShort(awsRaw.toShort())
        buf.putShort(stwRaw.toShort())
        buf.putInt(logM.toInt())
        return buf.array()
    }

    @Test
    fun navAccessors_matchDecode() {
        val frame = navFrame()
        val decoded = BinaryProtocol.decode(frame)!!
        val snap = ringFrom(29, listOf(frame))
        assertEquals(decoded.latitude!!, BinaryProtocol.latAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.longitude!!, BinaryProtocol.lonAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.cog!!, BinaryProtocol.cogAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.sog!!, BinaryProtocol.sogAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.variation!!, BinaryProtocol.variationAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.heading!!, BinaryProtocol.headingAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.depth!!, BinaryProtocol.depthAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.awa!!, BinaryProtocol.awaAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.aws!!, BinaryProtocol.awsAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.stw!!, BinaryProtocol.stwAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.logNm!!, BinaryProtocol.logNmAt(snap, 0)!!, 1e-9)
    }

    @Test
    fun navAccessors_honorReservedBand() {
        // depthRaw = 0xFFFE should be treated as no-data, not 655.34 m.
        val frame = navFrame(depthRaw = 0xFFFE)
        val snap = ringFrom(29, listOf(frame))
        assertNull(BinaryProtocol.depthAt(snap, 0))
        // decode() agrees.
        assertNull(BinaryProtocol.decode(frame)!!.depth)
    }

    // --- Engine ---------------------------------------------------------

    private fun engineFrame(
        rpmRaw: Int = 7200,
        hoursSec: Long = 1_954_800L,
        coolantRaw: Int = 35815,
        altTempRaw: Int = 34815,
        altVoltsRaw: Int = 1420,
        oilRaw: Int = 3500,
        exhaustRaw: Int = 59315,
        roomRaw: Int = 29515,
        engineBattRaw: Int = 1260,
        fuelRaw: Int = 18750,
        status1: Int = 0,
        status2: Int = 0,
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
    fun engineAccessors_matchDecode() {
        val frame = engineFrame()
        val decoded = EngineProtocol.decode(frame)!!
        val snap = ringFrom(27, listOf(frame))
        assertEquals(decoded.rpm, EngineProtocol.rpmAt(snap, 0))
        assertEquals(decoded.engineHoursSec, EngineProtocol.engineHoursSecAt(snap, 0))
        assertEquals(decoded.coolantC!!, EngineProtocol.coolantCAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.alternatorC!!, EngineProtocol.alternatorCAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.alternatorV!!, EngineProtocol.alternatorVAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.oilBar!!, EngineProtocol.oilBarAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.exhaustC!!, EngineProtocol.exhaustCAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.engineRoomC!!, EngineProtocol.engineRoomCAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.engineBattV!!, EngineProtocol.engineBattVAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.fuelPct!!, EngineProtocol.fuelPctAt(snap, 0)!!, 1e-9)
    }

    @Test
    fun engineAccessors_honorReservedBand() {
        val frame = engineFrame(rpmRaw = 0xFFFE, coolantRaw = 0xFFFF)
        val snap = ringFrom(27, listOf(frame))
        assertNull(EngineProtocol.rpmAt(snap, 0))
        assertNull(EngineProtocol.coolantCAt(snap, 0))
    }

    // --- BMS ------------------------------------------------------------

    private fun bmsFrame(
        packVCentiV: Int = 1326,    // 13.26 V
        currentCentiA: Int = -250,  // -2.50 A (discharging)
        remainingAhCentiAh: Int = 7800,  // 78.00 Ah
        fullAhCentiAh: Int = 10000,      // 100.00 Ah
        soc: Int = 78,
        cycles: Int = 42,
        errors: Int = 0,
        fetStatus: Int = 0x03,      // both FETs on
        cells: List<Int> = listOf(3315, 3316, 3317, 3318), // mV each
        ntcsK10: List<Int> = listOf(2950, 2960),           // 21.85°C, 22.85°C
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + cells.size * 2 + 1 + ntcsK10.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0xBB.toByte())
        buf.putShort(packVCentiV.toShort())
        buf.putShort(currentCentiA.toShort())
        buf.putShort(remainingAhCentiAh.toShort())
        buf.putShort(fullAhCentiAh.toShort())
        buf.put(soc.toByte())
        buf.putShort(cycles.toShort())
        buf.putShort(errors.toShort())
        buf.put(fetStatus.toByte())
        buf.put(cells.size.toByte())
        for (c in cells) buf.putShort(c.toShort())
        buf.put(ntcsK10.size.toByte())
        for (t in ntcsK10) buf.putShort(t.toShort())
        return buf.array()
    }

    @Test
    fun bmsHistorySlot_preservesHeader() {
        val wire = bmsFrame()
        val slot = BmsProtocol.encodeHistorySlot(wire)!!
        assertEquals(BmsProtocol.HISTORY_SLOT_SIZE, slot.size)
        val decoded = BmsProtocol.decode(wire)!!
        val snap = ringFrom(BmsProtocol.HISTORY_SLOT_SIZE, listOf(slot))
        assertEquals(decoded.packV, BmsProtocol.packVAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.currentA, BmsProtocol.currentAAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.remainingAh, BmsProtocol.remainingAhAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.fullAh, BmsProtocol.fullAhAt(snap, 0)!!, 1e-9)
        assertEquals(decoded.soc, BmsProtocol.socAt(snap, 0))
        assertEquals(decoded.cycles, BmsProtocol.cyclesAt(snap, 0))
    }

    @Test
    fun bmsHistorySlot_truncatesOversizedCells() {
        // 10 cells — more than MAX_CELLS (8). Slot should report 8.
        val wire = bmsFrame(
            cells = (1..10).map { 3000 + it },
            ntcsK10 = listOf(2950)
        )
        val slot = BmsProtocol.encodeHistorySlot(wire)!!
        assertNotNull(slot)
        assertEquals(BmsProtocol.MAX_CELLS, slot[BmsProtocol.OFF_N_CELLS].toInt() and 0xFF)
        // NTC should still round-trip since its count is within limits.
        val snap = ringFrom(BmsProtocol.HISTORY_SLOT_SIZE, listOf(slot))
        // packV still correct (header was not disturbed by truncation).
        assertEquals(13.26, BmsProtocol.packVAt(snap, 0)!!, 1e-9)
    }

    @Test
    fun bmsHistorySlot_currentSignPreserved() {
        val wire = bmsFrame(currentCentiA = -5_000) // -50 A
        val slot = BmsProtocol.encodeHistorySlot(wire)!!
        val snap = ringFrom(BmsProtocol.HISTORY_SLOT_SIZE, listOf(slot))
        assertEquals(-50.0, BmsProtocol.currentAAt(snap, 0)!!, 1e-9)
    }

    @Test
    fun sentinelSlot_allAccessorsReturnNull() {
        val snap = ringFrom(BmsProtocol.HISTORY_SLOT_SIZE, listOf(BmsProtocol.SENTINEL_SLOT))
        assertNull(BmsProtocol.packVAt(snap, 0))
        assertNull(BmsProtocol.currentAAt(snap, 0))
        assertNull(BmsProtocol.remainingAhAt(snap, 0))
        assertNull(BmsProtocol.fullAhAt(snap, 0))
        assertNull(BmsProtocol.socAt(snap, 0))
        assertNull(BmsProtocol.cyclesAt(snap, 0))
    }

    @Test
    fun sentinelEngineFrame_allAccessorsReturnNull() {
        val snap = ringFrom(EngineProtocol.FRAME_SIZE, listOf(EngineProtocol.SENTINEL_FRAME))
        assertNull(EngineProtocol.rpmAt(snap, 0))
        assertNull(EngineProtocol.engineHoursSecAt(snap, 0))
        assertNull(EngineProtocol.coolantCAt(snap, 0))
        assertNull(EngineProtocol.exhaustCAt(snap, 0))
        assertNull(EngineProtocol.alternatorCAt(snap, 0))
        assertNull(EngineProtocol.fuelPctAt(snap, 0))
    }

    @Test
    fun sentinelNavFrame_allAccessorsReturnNull() {
        val snap = ringFrom(BinaryProtocol.FRAME_SIZE, listOf(BinaryProtocol.SENTINEL_FRAME))
        assertNull(BinaryProtocol.latAt(snap, 0))
        assertNull(BinaryProtocol.lonAt(snap, 0))
        assertNull(BinaryProtocol.cogAt(snap, 0))
        assertNull(BinaryProtocol.sogAt(snap, 0))
        assertNull(BinaryProtocol.depthAt(snap, 0))
        assertNull(BinaryProtocol.logNmAt(snap, 0))
    }
}
