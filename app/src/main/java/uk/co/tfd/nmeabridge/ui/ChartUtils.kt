package uk.co.tfd.nmeabridge.ui

import uk.co.tfd.nmeabridge.history.RingSnapshot
import kotlin.math.abs
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

/**
 * Binary-search for the logical index whose timestamp is closest to
 * [tMs]. Returns -1 when [s] is empty. Used by chart code to look up
 * the sample under a tracking cursor.
 */
internal fun nearestIndex(s: RingSnapshot, tMs: Long): Int {
    if (s.size == 0) return -1
    val lo = s.lowerBound(tMs).coerceIn(0, s.size - 1)
    if (lo == 0) return 0
    val tLo = s.timestampAt(lo)
    val tPrev = s.timestampAt(lo - 1)
    return if (abs(tLo - tMs) <= abs(tPrev - tMs)) lo else lo - 1
}

// --- X-axis time-of-day ticks ---------------------------------------

/** A clean grid of step sizes, ascending. Picked to land on round
 *  clock-time values: seconds, common minute/hour boundaries, days.
 */
private val X_TICK_INTERVALS_MS: LongArray = longArrayOf(
    1_000L, 5_000L, 10_000L, 30_000L,                                  // seconds
    60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L,
    15 * 60_000L, 30 * 60_000L,                                         // minutes
    3_600_000L, 2 * 3_600_000L, 4 * 3_600_000L,
    6 * 3_600_000L, 12 * 3_600_000L,                                    // hours
    86_400_000L, 2 * 86_400_000L,
    7 * 86_400_000L, 30 * 86_400_000L,                                  // days
)

/** Roughly how many x-axis ticks we want across one chart's plot. */
private const val TARGET_TICK_COUNT = 6

/**
 * Pick a "nice" tick interval (ms) for an x-axis spanning [windowMs].
 * Chooses the smallest entry from [X_TICK_INTERVALS_MS] that yields
 * at most ~[TARGET_TICK_COUNT] ticks across the window.
 */
internal fun pickTickIntervalMs(windowMs: Long): Long {
    if (windowMs <= 0L) return X_TICK_INTERVALS_MS.first()
    val ideal = windowMs / TARGET_TICK_COUNT
    return X_TICK_INTERVALS_MS.firstOrNull { it >= ideal } ?: X_TICK_INTERVALS_MS.last()
}

/**
 * First Unix-ms ≥ [t] that is an integer multiple of [intervalMs].
 * Aligns ticks to round clock times in UTC; for IANA zones whose
 * offset is a whole number of hours (the common case in practice),
 * ticks also land on round local clock times for hour-or-larger
 * intervals.
 */
internal fun firstTickAtOrAfter(t: Long, intervalMs: Long): Long {
    val rem = ((t % intervalMs) + intervalMs) % intervalMs
    return if (rem == 0L) t else t + (intervalMs - rem)
}

/**
 * Build a [SimpleDateFormat] suitable for an x-axis tick at the given
 * interval. Sub-minute intervals show seconds, sub-day intervals show
 * `HH:mm`, daily intervals show the date. The format runs in the
 * device's local time zone — that's what users read off a clock.
 */
internal fun pickTickFormat(intervalMs: Long): java.text.SimpleDateFormat = when {
    intervalMs < 60_000L -> java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    intervalMs < 86_400_000L -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    else -> java.text.SimpleDateFormat("MMM-dd", java.util.Locale.US)
}
