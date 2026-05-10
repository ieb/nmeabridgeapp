package uk.co.tfd.nmeabridge.ui

import uk.co.tfd.nmeabridge.history.HistoryWindowSnapshot
import uk.co.tfd.nmeabridge.history.RingSnapshot
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import uk.co.tfd.nmeabridge.nmea.NavigationState
import uk.co.tfd.nmeabridge.nmea.Performance
import uk.co.tfd.nmeabridge.nmea.PolarTable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Which ring on a [HistoryWindowSnapshot] a field indexes into. */
enum class HistoryStream(val displayLabel: String) {
    NAV("Navigation"),
    ENGINE("Engine"),
    BMS("Battery"),
}

/**
 * A unit family. Fields with the same [UnitGroup] can share a chart's
 * left-or-right Y axis. Fields with different groups must split onto
 * the other axis (or onto another chart).
 *
 * The label is what appears at the axis tick gutter.
 */
enum class UnitGroup(val label: String) {
    SPEED_KN("kn"),
    ANGLE_REL_DEG("° rel"),     // ±180 signed, e.g. AWA, TWA, variation
    ANGLE_TRUE_DEG("° T"),      // 0-360, e.g. COG, heading
    DEPTH_M("m"),
    DISTANCE_NM("Nm"),
    LAT_DEG("° lat"),
    LON_DEG("° lon"),
    VOLTS("V"),
    AMPS("A"),
    TEMP_C("°C"),
    PRESSURE_BAR("bar"),
    RPM("rpm"),
    PERCENT("%"),
    AMPHOURS("Ah"),
    HOURS("h"),
    DIMENSIONLESS(""),
}

/**
 * Descriptor for one plottable history field.
 *
 * `id` is the persisted identifier — must remain stable across app
 * upgrades. `stream` is the ring on the [HistoryWindowSnapshot] this
 * field indexes into; the chart iterates `0 until snap.<stream>.size`
 * to draw the polyline.
 */
data class HistoryField(
    val id: String,
    val label: String,
    val stream: HistoryStream,
    val unit: UnitGroup,
    val format: String = "%.1f",
    /**
     * Read a numeric value at slot `i` of the relevant stream's ring,
     * or null when no data is available (sentinel slot, missing field
     * in this frame, or polar unavailable for polar-dependent fields).
     */
    val read: (HistoryWindowSnapshot, Int, PolarTable?) -> Double?,
)

/**
 * The full set of plottable fields. Single source of truth for the
 * field picker UI and the chart legend. Derived (computed) fields
 * read from the nav stream and may consult the active polar.
 */
object HistoryFieldCatalog {

    private val DEG_PER_RAD = 180.0 / PI
    private val RAD_PER_DEG = PI / 180.0

    /** Wraps an [Int] sample as a [Double]; null passes through. */
    private fun Int?.asDouble(): Double? = this?.toDouble()

    /** Wraps a [Long] sample as a [Double]; null passes through. */
    private fun Long?.asDouble(): Double? = this?.toDouble()

    /**
     * Compute (twaDeg, twsKn, vmgKn) for nav slot `i`. Mirrors the
     * polar-independent prefix of [Performance.derive]; duplicating the
     * math here keeps polar-free derived fields available even when no
     * polar table is loaded.
     */
    private fun trueWindAt(snap: HistoryWindowSnapshot, i: Int): Triple<Double, Double, Double>? {
        val awa = BinaryProtocol.awaAt(snap.nav, i) ?: return null
        val aws = BinaryProtocol.awsAt(snap.nav, i) ?: return null
        val stw = BinaryProtocol.stwAt(snap.nav, i) ?: return null
        val awaRad = awa * RAD_PER_DEG
        val appX = aws * cos(awaRad)
        val appY = aws * sin(awaRad)
        val twaRad = atan2(appY, appX - stw)
        val tws = hypot(appY, appX - stw)
        val vmg = stw * cos(twaRad)
        return Triple(twaRad * DEG_PER_RAD, tws, vmg)
    }

    /** Decode a full [NavigationState] from the nav ring at `i`. */
    private fun navStateAt(snap: HistoryWindowSnapshot, i: Int): NavigationState =
        NavigationState(
            latitude = BinaryProtocol.latAt(snap.nav, i),
            longitude = BinaryProtocol.lonAt(snap.nav, i),
            cog = BinaryProtocol.cogAt(snap.nav, i),
            sog = BinaryProtocol.sogAt(snap.nav, i),
            variation = BinaryProtocol.variationAt(snap.nav, i),
            heading = BinaryProtocol.headingAt(snap.nav, i),
            depth = BinaryProtocol.depthAt(snap.nav, i),
            awa = BinaryProtocol.awaAt(snap.nav, i),
            aws = BinaryProtocol.awsAt(snap.nav, i),
            stw = BinaryProtocol.stwAt(snap.nav, i),
            logNm = BinaryProtocol.logNmAt(snap.nav, i),
        )

    /**
     * Apply [Performance.derive] for polar-dependent fields. Returns
     * null when the polar is null or any of awa/aws/stw is missing
     * — same null-propagation as the live derived display.
     */
    private fun polarDerivedAt(
        snap: HistoryWindowSnapshot, i: Int, polar: PolarTable?
    ): uk.co.tfd.nmeabridge.nmea.DerivedNav? {
        if (polar == null) return null
        return Performance.derive(navStateAt(snap, i), polar)
    }

    val ALL: List<HistoryField> = listOf(
        // ---- Nav (raw) ----
        HistoryField("nav.lat", "Latitude", HistoryStream.NAV, UnitGroup.LAT_DEG, "%.6f") { s, i, _ ->
            BinaryProtocol.latAt(s.nav, i)
        },
        HistoryField("nav.lon", "Longitude", HistoryStream.NAV, UnitGroup.LON_DEG, "%.6f") { s, i, _ ->
            BinaryProtocol.lonAt(s.nav, i)
        },
        HistoryField("nav.cog", "COG", HistoryStream.NAV, UnitGroup.ANGLE_TRUE_DEG, "%.0f") { s, i, _ ->
            BinaryProtocol.cogAt(s.nav, i)
        },
        HistoryField("nav.sog", "SOG", HistoryStream.NAV, UnitGroup.SPEED_KN, "%.2f") { s, i, _ ->
            BinaryProtocol.sogAt(s.nav, i)
        },
        HistoryField("nav.heading", "Heading", HistoryStream.NAV, UnitGroup.ANGLE_TRUE_DEG, "%.0f") { s, i, _ ->
            BinaryProtocol.headingAt(s.nav, i)
        },
        HistoryField("nav.variation", "Variation", HistoryStream.NAV, UnitGroup.ANGLE_REL_DEG, "%.1f") { s, i, _ ->
            BinaryProtocol.variationAt(s.nav, i)
        },
        HistoryField("nav.depth", "Depth", HistoryStream.NAV, UnitGroup.DEPTH_M, "%.2f") { s, i, _ ->
            BinaryProtocol.depthAt(s.nav, i)
        },
        HistoryField("nav.awa", "AWA", HistoryStream.NAV, UnitGroup.ANGLE_REL_DEG, "%.0f") { s, i, _ ->
            BinaryProtocol.awaAt(s.nav, i)
        },
        HistoryField("nav.aws", "AWS", HistoryStream.NAV, UnitGroup.SPEED_KN, "%.2f") { s, i, _ ->
            BinaryProtocol.awsAt(s.nav, i)
        },
        HistoryField("nav.stw", "STW", HistoryStream.NAV, UnitGroup.SPEED_KN, "%.2f") { s, i, _ ->
            BinaryProtocol.stwAt(s.nav, i)
        },
        HistoryField("nav.logNm", "Log", HistoryStream.NAV, UnitGroup.DISTANCE_NM, "%.3f") { s, i, _ ->
            BinaryProtocol.logNmAt(s.nav, i)
        },

        // ---- Derived (computed from nav frame) ----
        HistoryField("derived.twa", "TWA", HistoryStream.NAV, UnitGroup.ANGLE_REL_DEG, "%.0f") { s, i, _ ->
            trueWindAt(s, i)?.first
        },
        HistoryField("derived.tws", "TWS", HistoryStream.NAV, UnitGroup.SPEED_KN, "%.2f") { s, i, _ ->
            trueWindAt(s, i)?.second
        },
        HistoryField("derived.vmg", "VMG", HistoryStream.NAV, UnitGroup.SPEED_KN, "%.2f") { s, i, _ ->
            trueWindAt(s, i)?.third
        },
        HistoryField("derived.polarSpeed", "Polar Speed", HistoryStream.NAV, UnitGroup.SPEED_KN, "%.2f") { s, i, polar ->
            polarDerivedAt(s, i, polar)?.polarSpeedKn
        },
        HistoryField("derived.targetTwa", "Target TWA", HistoryStream.NAV, UnitGroup.ANGLE_REL_DEG, "%.0f") { s, i, polar ->
            polarDerivedAt(s, i, polar)?.targetTwaDeg
        },
        HistoryField("derived.targetStw", "Target STW", HistoryStream.NAV, UnitGroup.SPEED_KN, "%.2f") { s, i, polar ->
            polarDerivedAt(s, i, polar)?.targetStwKn
        },

        // ---- Engine ----
        HistoryField("engine.rpm", "RPM", HistoryStream.ENGINE, UnitGroup.RPM, "%.0f") { s, i, _ ->
            EngineProtocol.rpmAt(s.engine, i).asDouble()
        },
        HistoryField("engine.hours", "Engine Hours", HistoryStream.ENGINE, UnitGroup.HOURS, "%.2f") { s, i, _ ->
            EngineProtocol.engineHoursSecAt(s.engine, i)?.let { it / 3600.0 }
        },
        HistoryField("engine.coolantC", "Coolant Temp", HistoryStream.ENGINE, UnitGroup.TEMP_C, "%.1f") { s, i, _ ->
            EngineProtocol.coolantCAt(s.engine, i)
        },
        HistoryField("engine.alternatorC", "Alternator Temp", HistoryStream.ENGINE, UnitGroup.TEMP_C, "%.1f") { s, i, _ ->
            EngineProtocol.alternatorCAt(s.engine, i)
        },
        HistoryField("engine.alternatorV", "Alternator V", HistoryStream.ENGINE, UnitGroup.VOLTS, "%.2f") { s, i, _ ->
            EngineProtocol.alternatorVAt(s.engine, i)
        },
        HistoryField("engine.oilBar", "Oil Pressure", HistoryStream.ENGINE, UnitGroup.PRESSURE_BAR, "%.2f") { s, i, _ ->
            EngineProtocol.oilBarAt(s.engine, i)
        },
        HistoryField("engine.exhaustC", "Exhaust Temp", HistoryStream.ENGINE, UnitGroup.TEMP_C, "%.1f") { s, i, _ ->
            EngineProtocol.exhaustCAt(s.engine, i)
        },
        HistoryField("engine.engineRoomC", "Engine Room Temp", HistoryStream.ENGINE, UnitGroup.TEMP_C, "%.1f") { s, i, _ ->
            EngineProtocol.engineRoomCAt(s.engine, i)
        },
        HistoryField("engine.engineBattV", "Engine Battery V", HistoryStream.ENGINE, UnitGroup.VOLTS, "%.2f") { s, i, _ ->
            EngineProtocol.engineBattVAt(s.engine, i)
        },
        HistoryField("engine.fuelPct", "Fuel Level", HistoryStream.ENGINE, UnitGroup.PERCENT, "%.1f") { s, i, _ ->
            EngineProtocol.fuelPctAt(s.engine, i)
        },

        // ---- Battery / BMS ----
        HistoryField("bms.packV", "Pack V", HistoryStream.BMS, UnitGroup.VOLTS, "%.2f") { s, i, _ ->
            BmsProtocol.packVAt(s.battery, i)
        },
        HistoryField("bms.currentA", "Pack Current", HistoryStream.BMS, UnitGroup.AMPS, "%.2f") { s, i, _ ->
            BmsProtocol.currentAAt(s.battery, i)
        },
        HistoryField("bms.remainingAh", "Remaining Ah", HistoryStream.BMS, UnitGroup.AMPHOURS, "%.1f") { s, i, _ ->
            BmsProtocol.remainingAhAt(s.battery, i)
        },
        HistoryField("bms.fullAh", "Full Ah", HistoryStream.BMS, UnitGroup.AMPHOURS, "%.1f") { s, i, _ ->
            BmsProtocol.fullAhAt(s.battery, i)
        },
        HistoryField("bms.soc", "SOC", HistoryStream.BMS, UnitGroup.PERCENT, "%.0f") { s, i, _ ->
            BmsProtocol.socAt(s.battery, i).asDouble()
        },
        HistoryField("bms.cycles", "Cycles", HistoryStream.BMS, UnitGroup.DIMENSIONLESS, "%.0f") { s, i, _ ->
            BmsProtocol.cyclesAt(s.battery, i).asDouble()
        },
    )

    private val byId: Map<String, HistoryField> = ALL.associateBy { it.id }

    fun byId(id: String): HistoryField? = byId[id]

    /** The ring corresponding to a stream within a snapshot. */
    fun ringFor(snap: HistoryWindowSnapshot, stream: HistoryStream): RingSnapshot = when (stream) {
        HistoryStream.NAV -> snap.nav
        HistoryStream.ENGINE -> snap.engine
        HistoryStream.BMS -> snap.battery
    }
}

/**
 * Persistable reference to one plotted trace: a stable field id plus
 * the colour the user has assigned to it. Resolved against
 * [HistoryFieldCatalog] at render time.
 */
data class HistorySeriesRef(val fieldId: String, val colorArgb: Int)

/**
 * Persistable layout of one chart panel. Up to two unit groups: every
 * series in [left] shares the chart's left Y axis; every series in
 * [right] shares the right axis. Empty lists are valid (chart with
 * only one axis or fully empty).
 */
data class HistoryChartConfig(
    val left: List<HistorySeriesRef> = emptyList(),
    val right: List<HistorySeriesRef> = emptyList(),
)
