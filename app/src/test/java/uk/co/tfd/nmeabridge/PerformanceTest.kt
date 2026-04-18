package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.tfd.nmeabridge.nmea.NavigationState
import uk.co.tfd.nmeabridge.nmea.Performance
import uk.co.tfd.nmeabridge.nmea.Polar
import kotlin.math.abs

class PerformanceTest {

    // --- Polar ---

    @Test
    fun polar_gridPointLookup() {
        // Row 6 (twa=32°), col 5 (tws=10 kn) in the table = 67 → 6.7 kn
        assertEquals(6.7, Polar.polarSpeed(10.0, 32.0), 1e-6)
    }

    @Test
    fun polar_belowAndAboveAxesClamp() {
        val lo = Polar.polarSpeed(-5.0, -10.0)
        val hi = Polar.polarSpeed(1000.0, 1000.0)
        // Axis end at tws=55 kn, twa=180° → last cell of map
        assertEquals(3.9, hi, 1e-6)   // map[23*17+16] = 39 → 3.9 kn
        assertEquals(0.0, lo, 1e-6)   // map[0] = 0
    }

    @Test
    fun polar_offGridValueLiesBetweenNeighbours() {
        // tws=11, twa=47° — between table rows twa=45° / twa=52° and cols tws=10 / tws=12.
        val v = Polar.polarSpeed(11.0, 47.0)
        val neighbours = listOf(
            Polar.polarSpeed(10.0, 45.0),
            Polar.polarSpeed(12.0, 45.0),
            Polar.polarSpeed(10.0, 52.0),
            Polar.polarSpeed(12.0, 52.0)
        )
        assertTrue("interp between $neighbours got $v", v >= neighbours.min() - 1e-6)
        assertTrue("interp between $neighbours got $v", v <= neighbours.max() + 1e-6)
    }

    // --- Performance.derive ---

    @Test
    fun derive_returnsNullWhenInputsMissing() {
        assertNull(Performance.derive(NavigationState()))
        assertNull(Performance.derive(NavigationState(awa = 45.0, aws = 10.0))) // no stw
    }

    @Test
    fun derive_starboardTackProducesPositiveTwaAndTargetTwa() {
        val d = Performance.derive(
            NavigationState(awa = 45.0, aws = 15.0, stw = 5.0)
        )
        assertNotNull(d); d!!
        assertTrue("twa should be positive (starboard) got ${d.twaDeg}", d.twaDeg > 0)
        assertTrue("targetTwa should match tack sign, got ${d.targetTwaDeg}", d.targetTwaDeg > 0)
        assertTrue("targetTwa upwind should be in (0, 90) deg", d.targetTwaDeg in 0.0..90.0)
        assertTrue("tws should be positive", d.twsKn > 0)
    }

    @Test
    fun derive_portTackProducesNegativeTwaAndTargetTwa() {
        val d = Performance.derive(
            NavigationState(awa = -45.0, aws = 15.0, stw = 5.0)
        )!!
        assertTrue(d.twaDeg < 0)
        assertTrue(d.targetTwaDeg < 0)
    }

    @Test
    fun derive_downwindPicksTargetTwaAbove90() {
        val d = Performance.derive(
            NavigationState(awa = 160.0, aws = 20.0, stw = 5.0)
        )!!
        assertTrue("twa should be > 90° off the bow: ${d.twaDeg}", abs(d.twaDeg) > 90)
        assertTrue(
            "downwind target should be in (90, 180), got ${d.targetTwaDeg}",
            abs(d.targetTwaDeg) in 90.0..180.0
        )
    }

    @Test
    fun derive_polarSpeedRatioNullWhenPolarZero() {
        // TWS=0 (awa=0, aws=stw) → true wind is zero, polar lookup → 0
        val d = Performance.derive(
            NavigationState(awa = 0.0, aws = 5.0, stw = 5.0)
        )!!
        assertEquals(0.0, d.twsKn, 1e-6)
        assertEquals(0.0, d.polarSpeedKn, 1e-6)
        assertNull(d.polarSpeedRatio)
    }

    @Test
    fun derive_vmgSignMatchesTackDirection() {
        // Starboard close-hauled: boat makes good toward wind → +vmg
        val d = Performance.derive(
            NavigationState(awa = 45.0, aws = 15.0, stw = 5.0)
        )!!
        assertTrue("vmg to windward should be positive, got ${d.vmgKn}", d.vmgKn > 0)
        // Broad reach / run: vmg to windward is negative (making good downwind)
        val dn = Performance.derive(
            NavigationState(awa = 160.0, aws = 15.0, stw = 5.0)
        )!!
        assertTrue("vmg downwind should be negative, got ${dn.vmgKn}", dn.vmgKn < 0)
    }

    @Test
    fun derive_polarVmgRatioIsNumeric() {
        val d = Performance.derive(
            NavigationState(awa = 45.0, aws = 15.0, stw = 5.0)
        )!!
        assertNotNull(d.polarVmgRatio)
        assertFalse(d.polarVmgRatio!!.isNaN())
    }
}
