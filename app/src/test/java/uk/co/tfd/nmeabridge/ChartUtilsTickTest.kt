package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.tfd.nmeabridge.ui.firstTickAtOrAfter
import uk.co.tfd.nmeabridge.ui.pickTickIntervalMs

class ChartUtilsTickTest {

    @Test
    fun pickInterval_smallWindow_picksSeconds() {
        // 30s window with target ~6 ticks → ideal step = 5s, picks 5s.
        assertEquals(5_000L, pickTickIntervalMs(30_000L))
    }

    @Test
    fun pickInterval_5min_picksMinute() {
        // 5min window / 6 ≈ 50s, smallest interval ≥ 50s is 60s.
        assertEquals(60_000L, pickTickIntervalMs(5L * 60_000L))
    }

    @Test
    fun pickInterval_1h_picksTenMinutes() {
        // 1h / 6 = 10min, smallest >= is 10min.
        assertEquals(10L * 60_000L, pickTickIntervalMs(3_600_000L))
    }

    @Test
    fun pickInterval_24h_pickFourHour() {
        // 24h / 6 = 4h.
        assertEquals(4L * 3_600_000L, pickTickIntervalMs(24L * 3_600_000L))
    }

    @Test
    fun pickInterval_7d_picksDay() {
        // 7d / 6 = 28h, smallest >= is 2d.
        assertEquals(2L * 86_400_000L, pickTickIntervalMs(7L * 86_400_000L))
    }

    @Test
    fun pickInterval_neverReturnsZero() {
        assertTrue(pickTickIntervalMs(0L) > 0)
        assertTrue(pickTickIntervalMs(-1L) > 0)
    }

    @Test
    fun firstTickAtOrAfter_alignsToInterval() {
        // 2026-04-28 00:00:00.500 UTC
        val t = 1_777_334_400_500L
        // For a 1s interval, first tick at or after 00:00:00.500 is 00:00:01.000.
        val tick = firstTickAtOrAfter(t, 1_000L)
        assertEquals(0L, tick % 1_000L)
        assertTrue(tick >= t)
        assertTrue(tick - t < 1_000L)
    }

    @Test
    fun firstTickAtOrAfter_exactBoundary_returnsSame() {
        // Already on a tick boundary — no advancement.
        val t = 1_777_334_400_000L  // 2026-04-28 00:00:00 UTC
        assertEquals(t, firstTickAtOrAfter(t, 60_000L))
        assertEquals(t, firstTickAtOrAfter(t, 3_600_000L))
        assertEquals(t, firstTickAtOrAfter(t, 86_400_000L))
    }

    @Test
    fun firstTickAtOrAfter_hourInterval_landsOnHourBoundary() {
        // Anywhere in 00:00 .. 00:59 should snap forward to 01:00.
        val midnight = 1_777_334_400_000L  // 2026-04-28 00:00:00 UTC
        val midOfHour = midnight + 27 * 60_000L  // 00:27 UTC
        val tick = firstTickAtOrAfter(midOfHour, 3_600_000L)
        assertEquals(midnight + 3_600_000L, tick)
    }
}
