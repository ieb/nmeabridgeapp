package uk.co.tfd.nmeabridge.nmea

/**
 * Pogo 1250 polar table, ported from
 * /Users/boston/ieb/N2KNMEA0183Wifi/lib/performance/pogo1250polar.h.
 *
 * The firmware defines NTWS = 18 but each row in `polar_map` is 17 values
 * wide; the 18th tws entry (60 kn) is unreachable in practice. We drop it
 * and use a 17-col stride, which matches the real shape of the data.
 *
 * TWS axis is knots, TWA axis is degrees (both sorted). Speeds are stored
 * as tenths of a knot.
 */
object Polar {

    private val tws: IntArray = intArrayOf(
        0, 0, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 35, 40, 45, 50, 55
    )
    private val twa: IntArray = intArrayOf(
        0, 5, 10, 15, 20, 25, 32, 36, 40, 45, 52, 60, 70, 80, 90,
        100, 110, 120, 130, 140, 150, 160, 170, 180
    )
    private val ntws = tws.size   // 17
    private val ntwa = twa.size   // 24

    // 24 rows × 17 cols, speeds in 0.1 kn.
    private val map: IntArray = intArrayOf(
        0,  0,  0,  0,  0,  0,  0,   0,   0,   0,   0,   0,   0,   0,   0,   0,   0,
        0,  4,  6,  8,  9, 10, 10,  10,  11,  11,  11,  11,   1,   1,   1,   0,   0,
        0,  8, 12, 16, 18, 20, 20,  21,  21,  22,  22,  22,   5,   2,   2,   0,   0,
        0, 12, 18, 24, 27, 29, 30,  31,  32,  33,  33,  33,  12,   5,   3,   0,   0,
        0, 14, 21, 27, 31, 34, 35,  36,  36,  37,  38,  37,  17,   7,   4,   0,   0,
        0, 17, 25, 32, 37, 40, 41,  43,  43,  44,  45,  44,  26,  11,   4,   0,   0,
        0, 28, 42, 54, 62, 67, 69,  71,  72,  74,  75,  74,  56,  22,   7,   0,   0,
        0, 31, 47, 59, 67, 70, 72,  74,  76,  78,  79,  79,  65,  26,   8,   0,   0,
        0, 35, 51, 63, 70, 73, 75,  77,  79,  81,  82,  83,  74,  29,  12,   0,   0,
        0, 38, 56, 67, 73, 76, 78,  80,  82,  84,  85,  86,  82,  30,  13,   0,   0,
        0, 42, 60, 70, 77, 80, 82,  83,  86,  89,  90,  91,  89,  32,  14,   0,   0,
        0, 46, 63, 73, 80, 83, 85,  87,  90,  93,  95,  96,  96,  38,  19,   0,   0,
        0, 48, 66, 75, 82, 86, 89,  91,  95,  98, 101, 104, 104,  42,  21,   0,   0,
        0, 50, 69, 79, 83, 88, 92,  94,  99, 104, 109, 113, 113,  45,  23,   0,   0,
        0, 53, 71, 81, 86, 89, 93,  97, 104, 111, 118, 125, 125,  56,  31,   6,   6,
        0, 54, 71, 82, 88, 92, 95,  99, 109, 119, 128, 141, 141,  71,  42,   7,   7,
        0, 53, 70, 81, 88, 94, 98, 103, 112, 127, 143, 150, 150,  83,  53,  15,  15,
        0, 50, 68, 78, 86, 94,100, 106, 118, 132, 149, 157, 157,  94,  63,  16,  16,
        0, 45, 63, 74, 83, 90, 98, 106, 123, 144, 156, 166, 166, 108,  75,  25,  25,
        0, 38, 56, 69, 78, 85, 92, 100, 122, 150, 163, 176, 176, 132,  97,  35,  26,
        0, 32, 48, 61, 71, 79, 86,  93, 109, 144, 168, 186, 186, 149, 112,  37,  37,
        0, 27, 41, 53, 64, 73, 80,  87, 100, 124, 154, 179, 179, 152, 116,  45,  36,
        0, 24, 36, 48, 59, 68, 76,  82,  94, 114, 143, 166, 166, 158, 125,  50,  42,
        0, 22, 33, 44, 55, 64, 72,  79,  90, 106, 128, 154, 154, 154, 123,  46,  39
    )

    init {
        require(map.size == ntws * ntwa) {
            "polar map size ${map.size} != $ntws * $ntwa"
        }
    }

    /**
     * Polar boat speed at the given true wind speed (kn) and absolute
     * true wind angle (deg). Uses bilinear interpolation over the table,
     * clamped at the axis ends. Returns knots.
     */
    fun polarSpeed(twsKn: Double, absTwaDeg: Double): Double {
        val (twsLo, twsHi) = findBracket(twsKn, tws)
        val (twaLo, twaHi) = findBracket(absTwaDeg, twa)

        val xTws0 = tws[twsLo].toDouble()
        val xTws1 = tws[twsHi].toDouble()
        val xTwa0 = twa[twaLo].toDouble()
        val xTwa1 = twa[twaHi].toDouble()

        // 0.1 kn → kn
        val s00 = map[twaLo * ntws + twsLo] * 0.1
        val s01 = map[twaLo * ntws + twsHi] * 0.1
        val s10 = map[twaHi * ntws + twsLo] * 0.1
        val s11 = map[twaHi * ntws + twsHi] * 0.1

        // Interpolate along TWA for each TWS column, then along TWS.
        val sLo = interpolate(absTwaDeg, xTwa0, xTwa1, s00, s10)
        val sHi = interpolate(absTwaDeg, xTwa0, xTwa1, s01, s11)
        return interpolate(twsKn, xTws0, xTws1, sLo, sHi)
    }

    /**
     * Return (lo, hi) indices in [0, axis.size) bracketing v. If v is
     * below or above the axis, both indices point at the end.
     */
    private fun findBracket(v: Double, axis: IntArray): Pair<Int, Int> {
        if (v <= axis[0]) return 0 to 0
        for (i in 1 until axis.size) {
            if (axis[i].toDouble() > v) return (i - 1) to i
        }
        val last = axis.size - 1
        return last to last
    }

    /**
     * Linear interpolation with clamped ends and a degenerate-range
     * safeguard, mirroring the firmware's `interpolateForY`.
     */
    private fun interpolate(x: Double, xl: Double, xh: Double, yl: Double, yh: Double): Double {
        return when {
            x >= xh -> yh
            x <= xl -> yl
            kotlin.math.abs(xh - xl) < 1.0e-4 -> (yl + yh) / 2
            else -> yl + (yh - yl) * ((x - xl) / (xh - xl))
        }
    }
}
