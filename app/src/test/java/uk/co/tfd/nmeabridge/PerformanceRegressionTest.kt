package uk.co.tfd.nmeabridge

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uk.co.tfd.nmeabridge.nmea.NavigationState
import uk.co.tfd.nmeabridge.nmea.Performance
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * Regression test against the firmware's reference dataset
 * (N2KNMEA0183Wifi/test/test_performance/reference_output.csv), copied
 * into src/test/resources/performance_reference.csv.
 *
 * Each row of the CSV contains:
 *   inputs  : awa (rad), aws (m/s), stw (m/s), roll, hdm, variation
 *   outputs : tws, twa, leeway, polarSpeed, polarSpeedRatio, polarVmg, vmg,
 *             targetTwa, targetVmg, targetStw, polarVmgRatio,
 *             windDirectionTrue, windDirectionMagnetic,
 *             oppositeTrackHeadingTrue, oppositeTrackHeadingMagnetic,
 *             oppositeTrackTrue, oppositeTrackMagnetic
 *
 * Angles are radians, speeds are m/s, ratios are dimensionless.
 * Empty fields are the firmware's -1e9 "not available" sentinel.
 *
 * We compare the 8 derived values the app actually produces:
 * tws, twa, polarSpeed, polarSpeedRatio, vmg, polarVmgRatio,
 * targetTwa, targetStw. We skip leeway (requires roll — not in the BLE
 * frame), polarVmg and targetVmg (intermediates we don't expose), and
 * the wind-direction / opposite-track bearings (not requested).
 */
class PerformanceRegressionTest {

    // Match the firmware's exact literal so the unit round-trip is identical.
    private val MS_TO_KN = 1.9438452

    // Tight tolerances — CSV is printed with 6 decimals so the round-trip
    // rounding floor is ~5e-7. Loosen only slightly above that to allow
    // Kotlin Double vs C++ float differences.
    private val ABS_TOL = 1e-3
    private val REL_TOL = 1e-3

    private data class Row(
        val line: Int,
        val iter: Int,
        val awaRad: Double,
        val awsMs: Double,
        val stwMs: Double,
        val expectedTwsMs: Double?,
        val expectedTwaRad: Double?,
        val expectedPolarSpeedMs: Double?,
        val expectedPolarSpeedRatio: Double?,
        val expectedVmgMs: Double?,
        val expectedTargetTwaRad: Double?,
        val expectedTargetStwMs: Double?,
        val expectedPolarVmgRatio: Double?
    )

    @Test
    fun matchesFirmwareReferenceDataset() {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("performance_reference.csv")
        assertNotNull("performance_reference.csv not found on test classpath", stream)

        val rows = parse(stream!!)
        assertTrue("dataset should have hundreds of rows, got ${rows.size}", rows.size > 400)

        var rowMismatches = 0
        var fieldMismatches = 0
        val reports = StringBuilder()
        val maxReports = 20

        for (r in rows) {
            val awaDeg = normalizeDeg(r.awaRad * 180.0 / PI)
            val awsKn = r.awsMs * MS_TO_KN
            val stwKn = r.stwMs * MS_TO_KN
            val derived = Performance.derive(
                NavigationState(awa = awaDeg, aws = awsKn, stw = stwKn)
            )
            if (derived == null) {
                fieldMismatches++
                rowMismatches++
                if (reports.lineCount() < maxReports) {
                    reports.appendLine("line ${r.line} iter=${r.iter}: derive() returned null for awa=${r.awaRad} aws=${r.awsMs} stw=${r.stwMs}")
                }
                continue
            }

            // Convert Kotlin outputs back to firmware units (m/s, rad) for
            // comparison against the stored reference values.
            val gotTwsMs = derived.twsKn / MS_TO_KN
            val gotTwaRad = derived.twaDeg * PI / 180.0
            val gotPolarSpeedMs = derived.polarSpeedKn / MS_TO_KN
            val gotPolarSpeedRatio = derived.polarSpeedRatio
            val gotVmgMs = derived.vmgKn / MS_TO_KN
            val gotTargetTwaRad = derived.targetTwaDeg?.let { it * PI / 180.0 }
            val gotTargetStwMs = derived.targetStwKn?.let { it / MS_TO_KN }
            val gotPolarVmgRatio = derived.polarVmgRatio

            val cmp = mutableListOf<Triple<String, Double?, Double?>>()
            cmp += Triple("tws", r.expectedTwsMs, gotTwsMs)
            cmp += Triple("twa", r.expectedTwaRad, gotTwaRad)
            cmp += Triple("polarSpeed", r.expectedPolarSpeedMs, gotPolarSpeedMs)
            cmp += Triple("polarSpeedRatio", r.expectedPolarSpeedRatio, gotPolarSpeedRatio)
            cmp += Triple("vmg", r.expectedVmgMs, gotVmgMs)
            cmp += Triple("targetTwa", r.expectedTargetTwaRad, gotTargetTwaRad)
            cmp += Triple("targetStw", r.expectedTargetStwMs, gotTargetStwMs)
            cmp += Triple("polarVmgRatio", r.expectedPolarVmgRatio, gotPolarVmgRatio)

            var rowHasMismatch = false
            for ((name, exp, got) in cmp) {
                val match = when {
                    exp == null && got == null -> true
                    exp == null || got == null -> false
                    name == "twa" || name == "targetTwa" -> angularCloseRad(exp, got)
                    else -> numericallyClose(exp, got)
                }
                if (!match) {
                    fieldMismatches++
                    rowHasMismatch = true
                    if (reports.lineCount() < maxReports) {
                        reports.appendLine(
                            "line ${r.line} iter=${r.iter} field=$name expected=${fmt(exp)} got=${fmt(got)}"
                        )
                    }
                }
            }
            if (rowHasMismatch) rowMismatches++
        }

        if (fieldMismatches != 0) {
            fail(
                "PerformanceRegressionTest: ${rows.size} rows checked, " +
                        "$rowMismatches rows with mismatches, " +
                        "$fieldMismatches field mismatches total.\n" +
                        "First ${maxReports} diffs:\n$reports"
            )
        }
    }

    private fun parse(stream: java.io.InputStream): List<Row> {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.US_ASCII))
        val out = mutableListOf<Row>()
        var lineNo = 0
        reader.useLines { seq ->
            for (raw in seq) {
                lineNo++
                val line = raw.trimEnd()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) continue
                if (line.startsWith("iter")) continue

                val toks = line.split(',')
                if (toks.size < 19) {
                    fail("malformed line $lineNo: ${toks.size} fields < 19")
                }

                val iter = toks[0].trim().toInt()
                val awaRad = toks[1].toDouble()
                val awsMs = toks[2].toDouble()
                val stwMs = toks[3].toDouble()
                // toks[4]=roll, toks[5]=hdm, toks[6]=variation, toks[7]=tag "P"
                val expectedTwsMs = toks[8].asFloatOrNull()
                val expectedTwaRad = toks[9].asFloatOrNull()
                // toks[10] = leeway
                val expectedPolarSpeedMs = toks[11].asFloatOrNull()
                val expectedPolarSpeedRatio = toks[12].asFloatOrNull()
                // toks[13] = polarVmg
                val expectedVmgMs = toks[14].asFloatOrNull()
                val expectedTargetTwaRad = toks[15].asFloatOrNull()
                // toks[16] = targetVmg
                val expectedTargetStwMs = toks[17].asFloatOrNull()
                val expectedPolarVmgRatio = toks[18].asFloatOrNull()

                out += Row(
                    line = lineNo,
                    iter = iter,
                    awaRad = awaRad,
                    awsMs = awsMs,
                    stwMs = stwMs,
                    expectedTwsMs = expectedTwsMs,
                    expectedTwaRad = expectedTwaRad,
                    expectedPolarSpeedMs = expectedPolarSpeedMs,
                    expectedPolarSpeedRatio = expectedPolarSpeedRatio,
                    expectedVmgMs = expectedVmgMs,
                    expectedTargetTwaRad = expectedTargetTwaRad,
                    expectedTargetStwMs = expectedTargetStwMs,
                    expectedPolarVmgRatio = expectedPolarVmgRatio,
                )
            }
        }
        return out
    }

    private fun String.asFloatOrNull(): Double? {
        val t = this.trim()
        return if (t.isEmpty()) null else t.toDouble()
    }

    private fun normalizeDeg(d: Double): Double {
        var x = d
        while (x > 180.0) x -= 360.0
        while (x <= -180.0) x += 360.0
        return x
    }

    private fun numericallyClose(a: Double, b: Double): Boolean {
        if (a == b) return true
        val diff = abs(a - b)
        if (diff <= ABS_TOL) return true
        val mag = max(abs(a), abs(b))
        return diff <= REL_TOL * mag
    }

    private fun angularCloseRad(a: Double, b: Double): Boolean {
        // Wrap the (signed) difference to [-π, π] so ±π is treated as equal.
        var d = (a - b) % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return abs(d) <= ABS_TOL
    }

    private fun fmt(v: Double?): String = v?.let { "%.6f".format(it) } ?: "<NA>"

    private fun StringBuilder.lineCount(): Int =
        if (isEmpty()) 0 else this.count { it == '\n' }
}
