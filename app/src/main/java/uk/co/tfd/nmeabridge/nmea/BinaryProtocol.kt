package uk.co.tfd.nmeabridge.nmea

import uk.co.tfd.nmeabridge.history.RingSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Decodes/encodes the 29-byte binary BLE navigation protocol (magic 0xCC).
 * See doc/ble-transport.md for the full specification.
 */
object BinaryProtocol {

    internal const val MAGIC: Byte = 0xCC.toByte()
    internal const val FRAME_SIZE = 29

    // Field offsets from magic byte at 0. See doc/ble-transport.md §Navigation State.
    internal const val OFF_LAT = 1       // S32, 1e-7 deg
    internal const val OFF_LON = 5       // S32, 1e-7 deg
    internal const val OFF_COG = 9       // U16, 0.0001 rad
    internal const val OFF_SOG = 11      // U16, 0.01 m/s
    internal const val OFF_VARIATION = 13 // S16, 0.0001 rad
    internal const val OFF_HEADING = 15  // U16, 0.0001 rad
    internal const val OFF_DEPTH = 17    // U16, 0.01 m
    internal const val OFF_AWA = 19      // U16, 0.0001 rad
    internal const val OFF_AWS = 21      // U16, 0.01 m/s
    internal const val OFF_STW = 23      // U16, 0.01 m/s
    internal const val OFF_LOG = 25      // U32, 1 m

    internal const val MS_TO_KNOTS = 1.0 / 0.514444
    internal const val M_TO_NM = 1.0 / 1852.0
    internal const val RAD_TO_DEG = 180.0 / Math.PI

    private val utcTimeFormat = SimpleDateFormat("HHmmss.00", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcDateFormat = SimpleDateFormat("ddMMyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Decode a 29-byte binary frame into NavigationState.
     * Returns null if the frame is invalid (wrong magic or size).
     */
    fun decode(data: ByteArray): NavigationState? {
        if (data.size != FRAME_SIZE) return null
        if (data[0] != MAGIC) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)

        val latRaw = buf.int
        val lonRaw = buf.int
        val cogRaw = buf.short.toInt() and 0xFFFF
        val sogRaw = buf.short.toInt() and 0xFFFF
        val varRaw = buf.short
        val hdgRaw = buf.short.toInt() and 0xFFFF
        val depthRaw = buf.short.toInt() and 0xFFFF
        val awaRaw = buf.short.toInt() and 0xFFFF
        val awsRaw = buf.short.toInt() and 0xFFFF
        val stwRaw = buf.short.toInt() and 0xFFFF
        val logRaw = buf.int

        return NavigationState(
            latitude = s32OrNull(latRaw)?.let { it / 1e7 },
            longitude = s32OrNull(lonRaw)?.let { it / 1e7 },
            cog = u16OrNull(cogRaw)?.let { it * 0.0001 * RAD_TO_DEG },
            sog = u16OrNull(sogRaw)?.let { it * 0.01 * MS_TO_KNOTS },
            variation = s16OrNull(varRaw)?.let { it * 0.0001 * RAD_TO_DEG },
            heading = u16OrNull(hdgRaw)?.let { it * 0.0001 * RAD_TO_DEG },
            depth = u16OrNull(depthRaw)?.let { it * 0.01 },
            awa = u16OrNull(awaRaw)?.let {
                val deg = it * 0.0001 * RAD_TO_DEG
                // Convert 0-360 to ±180 (port negative, starboard positive)
                if (deg > 180.0) deg - 360.0 else deg
            },
            aws = u16OrNull(awsRaw)?.let { it * 0.01 * MS_TO_KNOTS },
            stw = u16OrNull(stwRaw)?.let { it * 0.01 * MS_TO_KNOTS },
            logNm = u32OrNull(logRaw)?.let { it * M_TO_NM },
        )
    }

    /**
     * Convert a NavigationState to NMEA 0183 sentences for TCP clients.
     *
     * Missing values propagate as empty fields between commas (NMEA 0183
     * convention for "not available"), rather than being substituted with
     * 0.0 — which would otherwise transmit a real numeric reading to
     * downstream plotters when the source was actually unavailable. Whole
     * sentences are suppressed when none of their non-time fields are known.
     */
    fun toNmeaSentences(nav: NavigationState): List<String> {
        val now = Date()
        val time = utcTimeFormat.format(now)
        val date = utcDateFormat.format(now)

        val sentences = mutableListOf<String>()

        // DBT — Depth
        nav.depth?.let { d ->
            val ft = d * 3.28084
            val fathoms = d * 0.546807
            sentences.add(sentence("SDDBT,%.1f,f,%.1f,M,%.1f,F".format(ft, d, fathoms)))
        }

        val lat = nav.latitude
        val lon = nav.longitude
        if (lat != null && lon != null) {
            val (latStr, latDir) = toNmeaLat(lat)
            val (lonStr, lonDir) = toNmeaLon(lon)

            val quality = "1"
            val sats = "08"
            val hdop = "1.0"

            // GGA
            sentences.add(sentence(
                "GPGGA,$time,$latStr,$latDir,$lonStr,$lonDir,$quality,$sats,$hdop,0.0,M,,M,,"
            ))

            // GLL
            sentences.add(sentence(
                "GPGLL,$latStr,$latDir,$lonStr,$lonDir,$time,A,A"
            ))

            // RMC — empty fields for missing sog/cog/variation
            val sogStr = nav.sog?.let { "%.1f".format(it) } ?: ""
            val cogStr = nav.cog?.let { "%.1f".format(it) } ?: ""
            val varStr = nav.variation?.let { "%.1f".format(abs(it)) } ?: ""
            val varDir = nav.variation?.let { if (it >= 0) "E" else "W" } ?: ""
            sentences.add(sentence(
                "GPRMC,$time,A,$latStr,$latDir,$lonStr,$lonDir," +
                        "$sogStr,$cogStr,$date,$varStr,$varDir,A"
            ))
        }

        // VTG — position-independent; emit whenever cog or sog is known.
        // Magnetic course needs both cog and variation, so it's empty when
        // either is missing.
        if (nav.cog != null || nav.sog != null) {
            val cogStr = nav.cog?.let { "%.1f".format(it) } ?: ""
            val magCourseStr = if (nav.cog != null && nav.variation != null) {
                "%.1f".format(((nav.cog - nav.variation) + 360.0) % 360.0)
            } else ""
            val sogStr = nav.sog?.let { "%.1f".format(it) } ?: ""
            val sogKmhStr = nav.sog?.let { "%.1f".format(it * 1.852) } ?: ""
            sentences.add(sentence(
                "GPVTG,$cogStr,T,$magCourseStr,M,$sogStr,N,$sogKmhStr,K,A"
            ))
        }

        // ZDA — always emitted; the Android wall-clock is the time source.
        val dayFmt = SimpleDateFormat("dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val monthFmt = SimpleDateFormat("MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val yearFmt = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        sentences.add(sentence(
            "GPZDA,$time,${dayFmt.format(now)},${monthFmt.format(now)},${yearFmt.format(now)},00,00"
        ))

        return sentences
    }

    private fun sentence(body: String): String {
        return "\$$body*${NmeaChecksum.compute(body)}"
    }

    private fun toNmeaLat(degrees: Double): Pair<String, String> {
        val absDeg = abs(degrees)
        val d = absDeg.toInt()
        val m = (absDeg - d) * 60.0
        return Pair("%02d%07.4f".format(d, m), if (degrees >= 0) "N" else "S")
    }

    private fun toNmeaLon(degrees: Double): Pair<String, String> {
        val absDeg = abs(degrees)
        val d = absDeg.toInt()
        val m = (absDeg - d) * 60.0
        return Pair("%03d%07.4f".format(d, m), if (degrees >= 0) "E" else "W")
    }

    // --- Per-field accessors over a history RingSnapshot -----------------
    //
    // Each reads the 2-4 bytes it needs, applies the reserved-band sentinel
    // check (Sentinels.kt), and scales into the public unit. Unit-testable
    // in isolation and guaranteed equivalent to decode(frame).<field> by
    // AccessorParityTest.

    fun latAt(s: RingSnapshot, i: Int): Double? =
        s32OrNull(s.readS32(i, OFF_LAT))?.let { it / 1e7 }

    fun lonAt(s: RingSnapshot, i: Int): Double? =
        s32OrNull(s.readS32(i, OFF_LON))?.let { it / 1e7 }

    fun cogAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_COG))?.let { it * 0.0001 * RAD_TO_DEG }

    fun sogAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_SOG))?.let { it * 0.01 * MS_TO_KNOTS }

    fun variationAt(s: RingSnapshot, i: Int): Double? =
        s16OrNull(s.readS16(i, OFF_VARIATION))?.let { it * 0.0001 * RAD_TO_DEG }

    fun headingAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_HEADING))?.let { it * 0.0001 * RAD_TO_DEG }

    fun depthAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_DEPTH))?.let { it * 0.01 }

    fun awaAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_AWA))?.let {
            val deg = it * 0.0001 * RAD_TO_DEG
            if (deg > 180.0) deg - 360.0 else deg
        }

    fun awsAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_AWS))?.let { it * 0.01 * MS_TO_KNOTS }

    fun stwAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_STW))?.let { it * 0.01 * MS_TO_KNOTS }

    fun logNmAt(s: RingSnapshot, i: Int): Double? =
        u32OrNull(s.readU32(i, OFF_LOG))?.let { it * M_TO_NM }

    /**
     * 29 B sentinel frame used by the gap-filler ticker when no nav frame
     * arrived in the current second. Each field encoded with its specific
     * "not available" value: 0x7FFFFFFF for S32 (lat/lon), 0x7FFF for S16
     * (variation), 0xFFFF for U16, 0xFFFFFFFF for U32 (log). A blanket
     * 0xFF fill would leave signed fields holding -1 (valid data).
     */
    internal val SENTINEL_FRAME: ByteArray = run {
        val buf = java.nio.ByteBuffer.allocate(FRAME_SIZE)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(0x7FFFFFFF)              // lat s32 N/A
        buf.putInt(0x7FFFFFFF)              // lon s32 N/A
        buf.putShort(0xFFFF.toShort())      // cog u16 N/A
        buf.putShort(0xFFFF.toShort())      // sog u16 N/A
        buf.putShort(0x7FFF)                // variation s16 N/A
        buf.putShort(0xFFFF.toShort())      // heading u16 N/A
        buf.putShort(0xFFFF.toShort())      // depth u16 N/A
        buf.putShort(0xFFFF.toShort())      // awa u16 N/A
        buf.putShort(0xFFFF.toShort())      // aws u16 N/A
        buf.putShort(0xFFFF.toShort())      // stw u16 N/A
        buf.putInt(-1)                      // log u32 N/A (0xFFFFFFFF)
        buf.array()
    }
}
