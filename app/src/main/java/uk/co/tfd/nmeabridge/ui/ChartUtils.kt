package uk.co.tfd.nmeabridge.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Compute a padded Y-axis range from a list of values.
 *
 * - Falls back to [fallbackLo]..[fallbackHi] when [values] is empty.
 * - When [includeZero] is true, ensures 0 is inside the range (useful for signed
 *   quantities like battery current where the zero line is semantically meaningful).
 * - Guarantees a minimum span of [minSpan] so that an otherwise-flat series still
 *   renders readable gridlines.
 * - Adds 10 % padding above and below the computed span so the topmost / bottommost
 *   sample never grazes the plot edge.
 */
internal fun niceRange(
    values: List<Double>,
    fallbackLo: Double,
    fallbackHi: Double,
    minSpan: Double,
    includeZero: Boolean
): Pair<Double, Double> {
    if (values.isEmpty()) return fallbackLo to fallbackHi
    var lo = values.min()
    var hi = values.max()
    if (includeZero) {
        lo = min(lo, 0.0)
        hi = max(hi, 0.0)
    }
    if (hi - lo < minSpan) {
        val mid = (hi + lo) / 2
        lo = mid - minSpan / 2
        hi = mid + minSpan / 2
    }
    val pad = (hi - lo) * 0.1
    return (lo - pad) to (hi + pad)
}

/** Compact human-readable window label: "45s", "12m", "3h". */
internal fun formatWindow(windowMs: Long): String {
    val s = windowMs / 1000
    return when {
        s < 120 -> "${s}s"
        s < 7200 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}
