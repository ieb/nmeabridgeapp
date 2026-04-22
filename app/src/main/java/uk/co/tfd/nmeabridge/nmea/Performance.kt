package uk.co.tfd.nmeabridge.nmea

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Derived performance values computed on the phone from the received
 * navigation state (no firmware change). Mirrors the math in
 * /Users/boston/ieb/N2KNMEA0183Wifi/lib/performance/performance.cpp
 * restricted to the subset shown on the NavigationScreen.
 */
data class DerivedNav(
    val twaDeg: Double,            // signed; port negative, starboard positive
    val twsKn: Double,
    val polarSpeedKn: Double,      // 0 when polar returns 0
    val polarSpeedRatio: Double?,  // null when polarSpeed ≈ 0
    val vmgKn: Double,             // stw * cos(twa); signed (+ toward wind)
    val polarVmgRatio: Double?,    // null when targetVmg ≈ 0
    val targetTwaDeg: Double?,     // null when no target found (polar all 0 at this tws)
    val targetStwKn: Double?
)

object Performance {

    private const val DEG_PER_RAD = 180.0 / PI
    private const val RAD_PER_DEG = PI / 180.0

    /**
     * Derive performance values from a nav frame. Returns null when any of
     * awa/aws/stw is missing — without those there is no true-wind vector.
     */
    fun derive(nav: NavigationState, polar: PolarTable): DerivedNav? {
        val awaDeg = nav.awa ?: return null
        val awsKn = nav.aws ?: return null
        val stwKn = nav.stw ?: return null

        val awaRad = awaDeg * RAD_PER_DEG

        // True-wind vector: subtract boat velocity from apparent wind.
        // Work in knots throughout (polar is in knots).
        val appX = awsKn * cos(awaRad)
        val appY = awsKn * sin(awaRad)
        val twaRad = atan2(appY, appX - stwKn)
        val twsKn = hypot(appY, appX - stwKn)

        val absTwaRad = abs(twaRad)
        val absTwaDeg = absTwaRad * DEG_PER_RAD

        val polarSpeedKn = polar.polarSpeed(twsKn, absTwaDeg)

        val polarSpeedRatio = if (polarSpeedKn > 1e-8) stwKn / polarSpeedKn else null
        val vmg = stwKn * cos(twaRad)

        // Target TWA: iterate whole-degree TWAs on the current tack's
        // half-circle and maximise |stw · cos(twa)|.
        val twaLo: Int
        val twaHi: Int
        if (absTwaDeg < 90.0) {
            twaLo = 0; twaHi = 90
        } else {
            twaLo = 90; twaHi = 180
        }
        var bestVmg = 0.0
        var bestTwa = -1
        var bestStw = 0.0
        for (tt in twaLo..twaHi) {
            val tstw = polar.polarSpeed(twsKn, tt.toDouble())
            val tvmg = tstw * cos(tt * RAD_PER_DEG)
            if (abs(tvmg) > abs(bestVmg)) {
                bestVmg = tvmg
                bestTwa = tt
                bestStw = tstw
            }
        }

        val targetTwaDeg: Double? = if (bestTwa < 0) null
                                    else if (twaRad < 0) -bestTwa.toDouble()
                                    else bestTwa.toDouble()
        val targetStwKn: Double? = if (bestTwa < 0) null else bestStw
        val polarVmgRatio = if (abs(bestVmg) > 1e-8) vmg / bestVmg else null

        return DerivedNav(
            twaDeg = twaRad * DEG_PER_RAD,
            twsKn = twsKn,
            polarSpeedKn = polarSpeedKn,
            polarSpeedRatio = polarSpeedRatio,
            vmgKn = vmg,
            polarVmgRatio = polarVmgRatio,
            targetTwaDeg = targetTwaDeg,
            targetStwKn = targetStwKn
        )
    }
}
