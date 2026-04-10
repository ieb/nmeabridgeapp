package com.example.nmeabridge.simulator

import com.example.nmeabridge.nmea.NmeaChecksum
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
        courseDegrees: Double = 0.0
    ): String {
        val date = Date(timeMillis)
        val time = utcTimeFormat.format(date)
        val dateStr = utcDateFormat.format(date)
        val (lat, latDir) = toNmeaLatitude(latitude)
        val (lon, lonDir) = toNmeaLongitude(longitude)
        val body = "GPRMC,$time,A,$lat,$latDir,$lon,$lonDir," +
                "%.1f,%.1f,$dateStr,,,A".format(speedKnots, courseDegrees)
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

    fun buildVTG(
        courseDegrees: Double = 0.0,
        speedKnots: Double = 0.0
    ): String {
        val speedKmh = speedKnots * 1.852
        val body = "GPVTG,%.1f,T,,M,%.1f,N,%.1f,K,A".format(
            courseDegrees, speedKnots, speedKmh
        )
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
