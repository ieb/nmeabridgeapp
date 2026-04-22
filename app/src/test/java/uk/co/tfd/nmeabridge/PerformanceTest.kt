package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.tfd.nmeabridge.nmea.NavigationState
import uk.co.tfd.nmeabridge.nmea.Performance
import uk.co.tfd.nmeabridge.nmea.PolarTable
import kotlin.math.abs

class PerformanceTest {

    private val polar: PolarTable by lazy {
        val csv = javaClass.classLoader!!
            .getResourceAsStream("polars/pogo1250.csv")!!
            .bufferedReader().use { it.readText() }
        PolarTable.parseCsv("pogo1250", csv).getOrThrow()
    }

    // --- Performance.derive ---

    @Test
    fun derive_returnsNullWhenInputsMissing() {
        assertNull(Performance.derive(NavigationState(), polar))
        assertNull(Performance.derive(NavigationState(awa = 45.0, aws = 10.0), polar)) // no stw
    }

    @Test
    fun derive_starboardTackProducesPositiveTwaAndTargetTwa() {
        val d = Performance.derive(
            NavigationState(awa = 45.0, aws = 15.0, stw = 5.0)
        , polar)
        assertNotNull(d); d!!
        assertTrue("twa should be positive (starboard) got ${d.twaDeg}", d.twaDeg > 0)
        val tt = d.targetTwaDeg!!
        assertTrue("targetTwa should match tack sign, got $tt", tt > 0)
        assertTrue("targetTwa upwind should be in (0, 90) deg", tt in 0.0..90.0)
        assertTrue("tws should be positive", d.twsKn > 0)
    }

    @Test
    fun derive_portTackProducesNegativeTwaAndTargetTwa() {
        val d = Performance.derive(
            NavigationState(awa = -45.0, aws = 15.0, stw = 5.0)
        , polar)!!
        assertTrue(d.twaDeg < 0)
        assertTrue(d.targetTwaDeg!! < 0)
    }

    @Test
    fun derive_downwindPicksTargetTwaAbove90() {
        val d = Performance.derive(
            NavigationState(awa = 160.0, aws = 20.0, stw = 5.0)
        , polar)!!
        assertTrue("twa should be > 90° off the bow: ${d.twaDeg}", abs(d.twaDeg) > 90)
        val tt = d.targetTwaDeg!!
        assertTrue(
            "downwind target should be in (90, 180), got $tt",
            abs(tt) in 90.0..180.0
        )
    }

    @Test
    fun derive_polarSpeedRatioNullWhenPolarZero() {
        // TWS=0 (awa=0, aws=stw) → true wind is zero, polar lookup → 0
        val d = Performance.derive(
            NavigationState(awa = 0.0, aws = 5.0, stw = 5.0)
        , polar)!!
        assertEquals(0.0, d.twsKn, 1e-6)
        assertEquals(0.0, d.polarSpeedKn, 1e-6)
        assertNull(d.polarSpeedRatio)
    }

    @Test
    fun derive_vmgSignMatchesTackDirection() {
        // Starboard close-hauled: boat makes good toward wind → +vmg
        val d = Performance.derive(
            NavigationState(awa = 45.0, aws = 15.0, stw = 5.0)
        , polar)!!
        assertTrue("vmg to windward should be positive, got ${d.vmgKn}", d.vmgKn > 0)
        // Broad reach / run: vmg to windward is negative (making good downwind)
        val dn = Performance.derive(
            NavigationState(awa = 160.0, aws = 15.0, stw = 5.0)
        , polar)!!
        assertTrue("vmg downwind should be negative, got ${dn.vmgKn}", dn.vmgKn < 0)
    }

    @Test
    fun derive_polarVmgRatioIsNumeric() {
        val d = Performance.derive(
            NavigationState(awa = 45.0, aws = 15.0, stw = 5.0)
        , polar)!!
        assertNotNull(d.polarVmgRatio)
        assertFalse(d.polarVmgRatio!!.isNaN())
    }
}
