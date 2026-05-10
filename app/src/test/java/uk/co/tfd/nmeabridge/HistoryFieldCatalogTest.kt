package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.tfd.nmeabridge.history.FrameRing
import uk.co.tfd.nmeabridge.history.HistoryWindowSnapshot
import uk.co.tfd.nmeabridge.history.RingSnapshot
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import uk.co.tfd.nmeabridge.nmea.NavigationState
import uk.co.tfd.nmeabridge.nmea.Performance
import uk.co.tfd.nmeabridge.nmea.PolarTable
import uk.co.tfd.nmeabridge.ui.HistoryFieldCatalog
import uk.co.tfd.nmeabridge.ui.HistoryStream
import uk.co.tfd.nmeabridge.ui.UnitGroup
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for the catalog. Each entry decodes to the value the
 * corresponding raw or derived accessor would produce. The frame
 * builders mirror those in [AccessorParityTest] so the two suites
 * exercise the same wire-format inputs.
 */
class HistoryFieldCatalogTest {

    // --- Frame builders (copied from AccessorParityTest in spirit) ---

    private fun navFrame(
        latE7: Int = 500_700_000,
        lonE7: Int = -95_000_000,
        cogRaw: Int = 6283,
        sogRaw: Int = 257,
        variationRaw: Int = 5,
        headingRaw: Int = 6283,
        depthRaw: Int = 1350,
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

    private fun bmsWireFrame(
        packVCentiV: Int = 1326,
        currentCentiA: Int = -250,
        remainingAhCentiAh: Int = 7800,
        fullAhCentiAh: Int = 10000,
        soc: Int = 78,
        cycles: Int = 42,
        cells: List<Int> = listOf(3315, 3316, 3317, 3318),
        ntcsK10: List<Int> = listOf(2950, 2960),
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
        buf.putShort(0)                      // errors
        buf.put(0x03.toByte())               // fet
        buf.put(cells.size.toByte())
        for (c in cells) buf.putShort(c.toShort())
        buf.put(ntcsK10.size.toByte())
        for (t in ntcsK10) buf.putShort(t.toShort())
        return buf.array()
    }

    private fun ringFrom(frameSize: Int, frames: List<ByteArray>): RingSnapshot {
        val ring = FrameRing(frameSize, frames.size.coerceAtLeast(1))
        for ((idx, f) in frames.withIndex()) ring.append(1_000L * idx, f)
        return ring.snapshot()
    }

    private fun snapWith(
        nav: List<ByteArray> = emptyList(),
        engine: List<ByteArray> = emptyList(),
        bms: List<ByteArray> = emptyList(),
    ): HistoryWindowSnapshot {
        val bmsSlots = bms.map { BmsProtocol.encodeHistorySlot(it)!! }
        return HistoryWindowSnapshot(
            nav = if (nav.isEmpty()) RingSnapshot.EMPTY else ringFrom(BinaryProtocol.FRAME_SIZE, nav),
            engine = if (engine.isEmpty()) RingSnapshot.EMPTY else ringFrom(EngineProtocol.FRAME_SIZE, engine),
            battery = if (bmsSlots.isEmpty()) RingSnapshot.EMPTY else ringFrom(BmsProtocol.HISTORY_SLOT_SIZE, bmsSlots),
            tStartMs = 0L,
            tEndMs = 1_000L,
            strideNav = 1, strideEngine = 1, strideBattery = 1,
        )
    }

    // --- Tests ---

    @Test
    fun catalogEntries_haveUniqueIdsAndLabels() {
        val ids = HistoryFieldCatalog.ALL.map { it.id }
        assertEquals("ids must be unique", ids.distinct().size, ids.size)
        // Labels can collide in principle but we'd rather they didn't either.
        val labels = HistoryFieldCatalog.ALL.map { it.label }
        assertEquals("labels must be unique", labels.distinct().size, labels.size)
    }

    @Test
    fun byId_findsKnownEntries() {
        assertNotNull(HistoryFieldCatalog.byId("nav.sog"))
        assertNotNull(HistoryFieldCatalog.byId("engine.rpm"))
        assertNotNull(HistoryFieldCatalog.byId("bms.packV"))
        assertNotNull(HistoryFieldCatalog.byId("derived.twa"))
        assertNull(HistoryFieldCatalog.byId("nope"))
    }

    @Test
    fun navFields_decodeFromSnapshot() {
        val snap = snapWith(nav = listOf(navFrame()))
        val ref = BinaryProtocol.decode(navFrame())!!
        val read = { id: String -> HistoryFieldCatalog.byId(id)!!.read(snap, 0, null) }
        assertEquals(ref.latitude!!, read("nav.lat")!!, 1e-9)
        assertEquals(ref.longitude!!, read("nav.lon")!!, 1e-9)
        assertEquals(ref.cog!!, read("nav.cog")!!, 1e-9)
        assertEquals(ref.sog!!, read("nav.sog")!!, 1e-9)
        assertEquals(ref.heading!!, read("nav.heading")!!, 1e-9)
        assertEquals(ref.variation!!, read("nav.variation")!!, 1e-9)
        assertEquals(ref.depth!!, read("nav.depth")!!, 1e-9)
        assertEquals(ref.awa!!, read("nav.awa")!!, 1e-9)
        assertEquals(ref.aws!!, read("nav.aws")!!, 1e-9)
        assertEquals(ref.stw!!, read("nav.stw")!!, 1e-9)
        assertEquals(ref.logNm!!, read("nav.logNm")!!, 1e-9)
    }

    @Test
    fun engineFields_decodeFromSnapshot() {
        val snap = snapWith(engine = listOf(engineFrame()))
        val ref = EngineProtocol.decode(engineFrame())!!
        val read = { id: String -> HistoryFieldCatalog.byId(id)!!.read(snap, 0, null) }
        assertEquals(ref.rpm!!.toDouble(), read("engine.rpm")!!, 1e-9)
        assertEquals(ref.engineHoursSec!! / 3600.0, read("engine.hours")!!, 1e-9)
        assertEquals(ref.coolantC!!, read("engine.coolantC")!!, 1e-9)
        assertEquals(ref.alternatorC!!, read("engine.alternatorC")!!, 1e-9)
        assertEquals(ref.alternatorV!!, read("engine.alternatorV")!!, 1e-9)
        assertEquals(ref.oilBar!!, read("engine.oilBar")!!, 1e-9)
        assertEquals(ref.exhaustC!!, read("engine.exhaustC")!!, 1e-9)
        assertEquals(ref.engineRoomC!!, read("engine.engineRoomC")!!, 1e-9)
        assertEquals(ref.engineBattV!!, read("engine.engineBattV")!!, 1e-9)
        assertEquals(ref.fuelPct!!, read("engine.fuelPct")!!, 1e-9)
    }

    @Test
    fun bmsFields_decodeFromSnapshot() {
        val snap = snapWith(bms = listOf(bmsWireFrame()))
        val ref = BmsProtocol.decode(bmsWireFrame())!!
        val read = { id: String -> HistoryFieldCatalog.byId(id)!!.read(snap, 0, null) }
        assertEquals(ref.packV, read("bms.packV")!!, 1e-9)
        assertEquals(ref.currentA, read("bms.currentA")!!, 1e-9)
        assertEquals(ref.remainingAh, read("bms.remainingAh")!!, 1e-9)
        assertEquals(ref.fullAh, read("bms.fullAh")!!, 1e-9)
        assertEquals(ref.soc.toDouble(), read("bms.soc")!!, 1e-9)
        assertEquals(ref.cycles.toDouble(), read("bms.cycles")!!, 1e-9)
    }

    @Test
    fun sentinelSlot_yieldsNullForAllFields() {
        // Empty rings = no slots = HistoryFieldCatalog can't be called with i=0.
        // Instead, build a snapshot whose only entry is a sentinel slot.
        val snap = HistoryWindowSnapshot(
            nav = ringFrom(BinaryProtocol.FRAME_SIZE, listOf(BinaryProtocol.SENTINEL_FRAME)),
            engine = ringFrom(EngineProtocol.FRAME_SIZE, listOf(EngineProtocol.SENTINEL_FRAME)),
            battery = ringFrom(BmsProtocol.HISTORY_SLOT_SIZE, listOf(BmsProtocol.SENTINEL_SLOT)),
            tStartMs = 0L, tEndMs = 0L,
            strideNav = 1, strideEngine = 1, strideBattery = 1,
        )
        // Every catalog entry should return null for its sentinel slot. The
        // index used per entry is 0 in its respective stream.
        for (field in HistoryFieldCatalog.ALL) {
            val v = field.read(snap, 0, null)
            assertNull("field '${field.id}' should be null on sentinel, got $v", v)
        }
    }

    @Test
    fun derivedTwaTwsVmg_polarFree_matchManualMath() {
        // Pick wind 45° off port bow, AWS 12 kn, STW 5 kn → known TWA/TWS/VMG.
        // awa 270° is port (since awa is stored 0-360 then mapped to ±180 in
        // the accessor). Let's use awa raw = ((-45° + 360) / RAD) * 10000.
        val awaDeg = -45.0   // port
        val awsKn = 12.0
        val stwKn = 5.0
        // Encode raw fields. awaRaw is U16 in 0.0001 rad units, range 0..2π.
        val awaRad = if (awaDeg < 0) awaDeg + 360.0 else awaDeg
        val awaRaw = (awaRad * (Math.PI / 180.0) / 0.0001).toInt()
        val awsRaw = (awsKn * 0.514444 / 0.01).toInt()       // 12 kn → m/s → centi
        val stwRaw = (stwKn * 0.514444 / 0.01).toInt()
        val frame = navFrame(awaRaw = awaRaw, awsRaw = awsRaw, stwRaw = stwRaw)
        val snap = snapWith(nav = listOf(frame))

        val twa = HistoryFieldCatalog.byId("derived.twa")!!.read(snap, 0, null)!!
        val tws = HistoryFieldCatalog.byId("derived.tws")!!.read(snap, 0, null)!!
        val vmg = HistoryFieldCatalog.byId("derived.vmg")!!.read(snap, 0, null)!!

        // TWA must be in (-180, 0) since the wind is on port (apparent on port,
        // STW < AWS so true wind also on port) and TWS must be > 0.
        assertTrue("twa $twa should be on port (negative)", twa < 0.0)
        assertTrue("tws $tws should be positive", tws > 0.0)
        // VMG = stw * cos(twaRad). For twa around -45..-90, cos is positive
        // ⇒ vmg positive (toward wind).
        assertTrue("vmg $vmg should be > 0 with this geometry", vmg > 0.0)
    }

    @Test
    fun polarDerivedFields_returnNullWhenNoPolar() {
        val snap = snapWith(nav = listOf(navFrame()))
        for (id in listOf("derived.polarSpeed", "derived.targetTwa", "derived.targetStw")) {
            val v = HistoryFieldCatalog.byId(id)!!.read(snap, 0, null)
            assertNull("$id should be null without polar, got $v", v)
        }
    }

    @Test
    fun polarDerivedFields_matchPerformance_withPolar() {
        val snap = snapWith(nav = listOf(navFrame()))
        val polar = aPolarThatLikesWind()
        val expected = Performance.derive(decodedNavStateFromFrame(navFrame()), polar)!!
        val read = { id: String -> HistoryFieldCatalog.byId(id)!!.read(snap, 0, polar) }
        assertEquals(expected.polarSpeedKn, read("derived.polarSpeed")!!, 1e-9)
        assertEquals(expected.targetTwaDeg, read("derived.targetTwa"))
        assertEquals(expected.targetStwKn, read("derived.targetStw"))
    }

    @Test
    fun unitGroups_areAttachedAsExpected() {
        // Spot-check a few known assignments.
        assertEquals(UnitGroup.SPEED_KN, HistoryFieldCatalog.byId("nav.sog")!!.unit)
        assertEquals(UnitGroup.ANGLE_TRUE_DEG, HistoryFieldCatalog.byId("nav.cog")!!.unit)
        assertEquals(UnitGroup.ANGLE_REL_DEG, HistoryFieldCatalog.byId("nav.awa")!!.unit)
        assertEquals(UnitGroup.ANGLE_REL_DEG, HistoryFieldCatalog.byId("derived.twa")!!.unit)
        assertEquals(UnitGroup.SPEED_KN, HistoryFieldCatalog.byId("derived.tws")!!.unit)
        assertEquals(UnitGroup.VOLTS, HistoryFieldCatalog.byId("bms.packV")!!.unit)
        assertEquals(UnitGroup.AMPS, HistoryFieldCatalog.byId("bms.currentA")!!.unit)
        assertEquals(UnitGroup.RPM, HistoryFieldCatalog.byId("engine.rpm")!!.unit)
        assertEquals(UnitGroup.TEMP_C, HistoryFieldCatalog.byId("engine.coolantC")!!.unit)
    }

    @Test
    fun streams_areCorrectlyAttributed() {
        for (f in HistoryFieldCatalog.ALL) {
            when {
                f.id.startsWith("nav.") || f.id.startsWith("derived.") ->
                    assertEquals("$f.id", HistoryStream.NAV, f.stream)
                f.id.startsWith("engine.") ->
                    assertEquals("$f.id", HistoryStream.ENGINE, f.stream)
                f.id.startsWith("bms.") ->
                    assertEquals("$f.id", HistoryStream.BMS, f.stream)
                else -> throw AssertionError("unknown id prefix: ${f.id}")
            }
        }
    }

    // --- Helpers used by the polar-dependent test ---

    private fun decodedNavStateFromFrame(frame: ByteArray): NavigationState =
        BinaryProtocol.decode(frame)!!

    /**
     * A toy polar that returns a small non-zero hull speed across all
     * (tws, twa) — enough for [Performance.derive] to land non-null
     * values in every field, without actually being calibrated.
     */
    private fun aPolarThatLikesWind(): PolarTable {
        val twaAxis = doubleArrayOf(0.0, 30.0, 45.0, 60.0, 90.0, 120.0, 150.0, 180.0)
        val twsAxis = doubleArrayOf(0.0, 5.0, 10.0, 15.0, 20.0)
        val speeds = Array(twaAxis.size) { row ->
            DoubleArray(twsAxis.size) { col ->
                // Crude: more wind = faster, off the wind = a bit faster.
                val wind = twsAxis[col]
                val twa = twaAxis[row]
                val angleFactor = if (twa <= 30) 0.0
                                  else 0.4 + 0.6 * Math.sin(Math.toRadians(twa - 30))
                wind * 0.6 * angleFactor
            }
        }
        return PolarTable(name = "test", twsAxis = twsAxis, twaAxis = twaAxis, speeds = speeds)
    }
}
