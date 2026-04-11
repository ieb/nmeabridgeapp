package uk.co.tfd.nmeabridge.simulator

import uk.co.tfd.nmeabridge.nmea.NmeaChecksum
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

object NmeaSentenceBuilder {

    private val utcTimeFormat = SimpleDateFormat("HHmmss.00", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcDateFormat = SimpleDateFormat("ddMMyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcDayFormat = SimpleDateFormat("dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcMonthFormat = SimpleDateFormat("MM", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcYearFormat = SimpleDateFormat("yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcHourFormat = SimpleDateFormat("HH", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcMinuteFormat = SimpleDateFormat("mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcSecondFormat = SimpleDateFormat("ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun buildGGA(
        timeMillis: Long,
        latitude: Double,
        longitude: Double,
        quality: Int = 1,
        satellites: Int = 8,
        hdop: Double = 1.2,
        altitude: Double = 25.0
    ): String {
        val time = utcTimeFormat.format(Date(timeMillis))
        val (lat, latDir) = toNmeaLatitude(latitude)
        val (lon, lonDir) = toNmeaLongitude(longitude)
        val body = "GPGGA,$time,$lat,$latDir,$lon,$lonDir,$quality," +
                "%02d".format(satellites) +
                ",%.1f,%.1f,M,,M,,".format(hdop, altitude)
        return "\$$body*${NmeaChecksum.compute(body)}"
    }

    fun buildRMC(
        timeMillis: Long,
        latitude: Double,
        longitude: Double,
        speedKnots: Double = 0.0,
        courseDegrees: Double = 0.0,
        magneticVariation: Double = 0.5,
        variationDirection: String = "E"
    ): String {
        val date = Date(timeMillis)
        val time = utcTimeFormat.format(date)
        val dateStr = utcDateFormat.format(date)
        val (lat, latDir) = toNmeaLatitude(latitude)
        val (lon, lonDir) = toNmeaLongitude(longitude)
        val body = "GPRMC,$time,A,$lat,$latDir,$lon,$lonDir," +
                "%.1f,%.1f,$dateStr,%.1f,$variationDirection,A".format(
                    speedKnots, courseDegrees, magneticVariation
                )
        return "\$$body*${NmeaChecksum.compute(body)}"
    }

    fun buildGLL(
        timeMillis: Long,
        latitude: Double,
        longitude: Double
    ): String {
        val time = utcTimeFormat.format(Date(timeMillis))
        val (lat, latDir) = toNmeaLatitude(latitude)
        val (lon, lonDir) = toNmeaLongitude(longitude)
        val body = "GPGLL,$lat,$latDir,$lon,$lonDir,$time,A,A"
        return "\$$body*${NmeaChecksum.compute(body)}"
    }

    fun buildVTG(
        courseDegrees: Double = 0.0,
        speedKnots: Double = 0.0,
        magneticVariation: Double = 0.5
    ): String {
        val speedKmh = speedKnots * 1.852
        val magneticCourse = (courseDegrees - magneticVariation + 360.0) % 360.0
        val body = "GPVTG,%.1f,T,%.1f,M,%.1f,N,%.1f,K,A".format(
            courseDegrees, magneticCourse, speedKnots, speedKmh
        )
        return "\$$body*${NmeaChecksum.compute(body)}"
    }

    fun buildGSA(
        mode: Char = 'A',
        fix: Int = 3,
        satellitePrns: List<Int> = listOf(2, 5, 9, 12, 15, 18, 21, 24),
        pdop: Double = 1.8,
        hdop: Double = 1.2,
        vdop: Double = 1.3
    ): String {
        val prns = (0 until 12).joinToString(",") { i ->
            if (i < satellitePrns.size) "%02d".format(satellitePrns[i]) else ""
        }
        val body = "GPGSA,$mode,$fix,$prns,%.1f,%.1f,%.1f".format(pdop, hdop, vdop)
        return "\$$body*${NmeaChecksum.compute(body)}"
    }

    fun buildDBT(
        depthMetres: Double = 12.5
    ): String {
        val depthFeet = depthMetres * 3.28084
        val depthFathoms = depthMetres * 0.546807
        val body = "SDDBT,%.1f,f,%.1f,M,%.1f,F".format(depthFeet, depthMetres, depthFathoms)
        return "\$$body*${NmeaChecksum.compute(body)}"
    }

    fun buildZDA(
        timeMillis: Long
    ): String {
        val date = Date(timeMillis)
        val time = utcTimeFormat.format(date)
        val day = utcDayFormat.format(date)
        val month = utcMonthFormat.format(date)
        val year = utcYearFormat.format(date)
        val body = "GPZDA,$time,$day,$month,$year,00,00"
        return "\$$body*${NmeaChecksum.compute(body)}"
    }

    private fun toNmeaLatitude(degrees: Double): Pair<String, String> {
        val absDeg = abs(degrees)
        val d = absDeg.toInt()
        val m = (absDeg - d) * 60.0
        return Pair("%02d%07.4f".format(d, m), if (degrees >= 0) "N" else "S")
    }

    private fun toNmeaLongitude(degrees: Double): Pair<String, String> {
        val absDeg = abs(degrees)
        val d = absDeg.toInt()
        val m = (absDeg - d) * 60.0
        return Pair("%03d%07.4f".format(d, m), if (degrees >= 0) "E" else "W")
    }
}
