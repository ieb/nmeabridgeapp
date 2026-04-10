package com.example.nmeabridge

import com.example.nmeabridge.nmea.NmeaChecksum
import com.example.nmeabridge.simulator.NmeaSentenceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaSentenceBuilderTest {

    @Test
    fun buildGGA_hasValidFormat() {
        val sentence = NmeaSentenceBuilder.buildGGA(
            timeMillis = 1700000000000L,
            latitude = 47.6062,
            longitude = -122.3321
        )
        assertTrue("Should start with \$GPGGA", sentence.startsWith("\$GPGGA,"))
        assertTrue("Should have checksum", sentence.contains("*"))
        assertTrue("Should have valid checksum", NmeaChecksum.isValid(sentence))
    }

    @Test
    fun buildGGA_correctFieldCount() {
        val sentence = NmeaSentenceBuilder.buildGGA(
            timeMillis = 1700000000000L,
            latitude = 47.6062,
            longitude = -122.3321
        )
        val body = sentence.substringAfter('$').substringBefore('*')
        val fields = body.split(",")
        assertEquals("GGA should have 14 fields", 14, fields.size)
    }

    @Test
    fun buildRMC_hasValidFormat() {
        val sentence = NmeaSentenceBuilder.buildRMC(
            timeMillis = 1700000000000L,
            latitude = 47.6062,
            longitude = -122.3321,
            speedKnots = 5.0,
            courseDegrees = 180.0
        )
        assertTrue("Should start with \$GPRMC", sentence.startsWith("\$GPRMC,"))
        assertTrue("Should have valid checksum", NmeaChecksum.isValid(sentence))
    }

    @Test
    fun buildRMC_correctFieldCount() {
        val sentence = NmeaSentenceBuilder.buildRMC(
            timeMillis = 1700000000000L,
            latitude = 47.6062,
            longitude = -122.3321
        )
        val body = sentence.substringAfter('$').substringBefore('*')
        val fields = body.split(",")
        assertEquals("RMC should have 12 fields", 12, fields.size)
    }

    @Test
    fun buildGSA_hasValidFormat() {
        val sentence = NmeaSentenceBuilder.buildGSA()
        assertTrue("Should start with \$GPGSA", sentence.startsWith("\$GPGSA,"))
        assertTrue("Should have valid checksum", NmeaChecksum.isValid(sentence))
    }

    @Test
    fun buildVTG_hasValidFormat() {
        val sentence = NmeaSentenceBuilder.buildVTG(courseDegrees = 90.0, speedKnots = 5.0)
        assertTrue("Should start with \$GPVTG", sentence.startsWith("\$GPVTG,"))
        assertTrue("Should have valid checksum", NmeaChecksum.isValid(sentence))
    }

    @Test
    fun buildGGA_northernHemisphere() {
        val sentence = NmeaSentenceBuilder.buildGGA(
            timeMillis = 1700000000000L,
            latitude = 47.6062,
            longitude = -122.3321
        )
        assertTrue("Should contain N for northern latitude", sentence.contains(",N,"))
        assertTrue("Should contain W for western longitude", sentence.contains(",W,"))
    }

    @Test
    fun buildGGA_southernHemisphere() {
        val sentence = NmeaSentenceBuilder.buildGGA(
            timeMillis = 1700000000000L,
            latitude = -33.8688,
            longitude = 151.2093
        )
        assertTrue("Should contain S for southern latitude", sentence.contains(",S,"))
        assertTrue("Should contain E for eastern longitude", sentence.contains(",E,"))
    }

    @Test
    fun buildVTG_speedConversion() {
        val sentence = NmeaSentenceBuilder.buildVTG(courseDegrees = 0.0, speedKnots = 10.0)
        // 10 knots = 18.5 km/h
        assertTrue("Should contain km/h speed", sentence.contains("18.5"))
    }

    @Test
    fun allSentences_under82Characters() {
        val gga = NmeaSentenceBuilder.buildGGA(1700000000000L, 47.6062, -122.3321)
        val rmc = NmeaSentenceBuilder.buildRMC(1700000000000L, 47.6062, -122.3321)
        val gsa = NmeaSentenceBuilder.buildGSA()
        val vtg = NmeaSentenceBuilder.buildVTG(90.0, 5.0)

        // NMEA max is 82 chars including $ and CR+LF (we don't add CR+LF here)
        assertTrue("GGA too long: ${gga.length}", gga.length <= 80)
        assertTrue("RMC too long: ${rmc.length}", rmc.length <= 80)
        assertTrue("GSA too long: ${gsa.length}", gsa.length <= 80)
        assertTrue("VTG too long: ${vtg.length}", vtg.length <= 80)
    }
}
