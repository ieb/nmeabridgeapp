package uk.co.tfd.nmeabridge.nmea

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

    private const val MAGIC: Byte = 0xCC.toByte()
    private const val FRAME_SIZE = 29

    // NMEA 2000 "not available" sentinel values
    private const val NA_U16 = 0xFFFF
    private const val NA_U32 = 0xFFFFFFFF.toInt() // stored as int, compared unsigned
    private const val NA_S16 = 0x7FFF.toShort()
    private const val NA_S32 = 0x7FFFFFFF

    private const val MS_TO_KNOTS = 1.0 / 0.514444
    private const val M_TO_NM = 1.0 / 1852.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

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
            latitude = if (latRaw == NA_S32) null else latRaw / 1e7,
            longitude = if (lonRaw == NA_S32) null else lonRaw / 1e7,
            cog = if (cogRaw == NA_U16) null else cogRaw * 0.0001 * RAD_TO_DEG,
            sog = if (sogRaw == NA_U16) null else sogRaw * 0.01 * MS_TO_KNOTS,
            variation = if (varRaw == NA_S16) null else varRaw * 0.0001 * RAD_TO_DEG,
            heading = if (hdgRaw == NA_U16) null else hdgRaw * 0.0001 * RAD_TO_DEG,
            depth = if (depthRaw == NA_U16) null else depthRaw * 0.01,
            awa = if (awaRaw == NA_U16) null else {
                val deg = awaRaw * 0.0001 * RAD_TO_DEG
                // Convert 0-360 to ±180 (port negative, starboard positive)
                if (deg > 180.0) deg - 360.0 else deg
            },
            aws = if (awsRaw == NA_U16) null else awsRaw * 0.01 * MS_TO_KNOTS,
            stw = if (stwRaw == NA_U16) null else stwRaw * 0.01 * MS_TO_KNOTS,
            logNm = if (logRaw == NA_U32) null else (logRaw.toLong() and 0xFFFFFFFFL) * M_TO_NM,
        )
    }

    /**
     * Convert a NavigationState to NMEA 0183 sentences for TCP clients.
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

            // RMC
            val sogKn = nav.sog ?: 0.0
            val cogDeg = nav.cog ?: 0.0
            val magVar = nav.variation ?: 0.0
            val varDir = if (magVar >= 0) "E" else "W"
            sentences.add(sentence(
                "GPRMC,$time,A,$latStr,$latDir,$lonStr,$lonDir," +
                        "%.1f,%.1f,$date,%.1f,$varDir,A".format(sogKn, cogDeg, abs(magVar))
            ))

            // VTG
            val magCourse = ((cogDeg - magVar) + 360.0) % 360.0
            val sogKmh = sogKn * 1.852
            sentences.add(sentence(
                "GPVTG,%.1f,T,%.1f,M,%.1f,N,%.1f,K,A".format(cogDeg, magCourse, sogKn, sogKmh)
            ))
        }

        // ZDA
        val nowDate = Date()
        val dayFmt = SimpleDateFormat("dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val monthFmt = SimpleDateFormat("MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val yearFmt = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        sentences.add(sentence(
            "GPZDA,$time,${dayFmt.format(nowDate)},${monthFmt.format(nowDate)},${yearFmt.format(nowDate)},00,00"
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
}
