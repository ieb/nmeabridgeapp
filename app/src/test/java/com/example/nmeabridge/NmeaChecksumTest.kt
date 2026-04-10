package com.example.nmeabridge

import com.example.nmeabridge.nmea.NmeaChecksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaChecksumTest {

    @Test
    fun computeChecksum_knownGGA() {
        // $GPGGA,210230,3855.4487,N,09446.0071,W,1,07,1.1,370.5,M,-29.5,M,,*7A
        val body = "GPGGA,210230,3855.4487,N,09446.0071,W,1,07,1.1,370.5,M,-29.5,M,,"
        assertEquals("7A", NmeaChecksum.compute(body))
    }

    @Test
    fun computeChecksum_knownRMC() {
        // $GNRMC,001031.00,A,4404.13993,N,12118.86023,W,0.146,,100117,,,A*7B
        val body = "GNRMC,001031.00,A,4404.13993,N,12118.86023,W,0.146,,100117,,,A"
        assertEquals("7B", NmeaChecksum.compute(body))
    }

    @Test
    fun isValid_validSentence() {
        assertTrue(NmeaChecksum.isValid("\$GPGGA,210230,3855.4487,N,09446.0071,W,1,07,1.1,370.5,M,-29.5,M,,*7A"))
    }

    @Test
    fun isValid_invalidChecksum() {
        assertFalse(NmeaChecksum.isValid("\$GPGGA,210230,3855.4487,N,09446.0071,W,1,07,1.1,370.5,M,-29.5,M,,*FF"))
    }

    @Test
    fun isValid_tooShort() {
        assertFalse(NmeaChecksum.isValid("\$G"))
    }

    @Test
    fun isValid_noStar() {
        assertFalse(NmeaChecksum.isValid("\$GPGGA,210230"))
    }

    @Test
    fun isValid_aisMessage() {
        // AIS messages start with '!'
        val body = "AIVDM,1,1,,B,15MsK4PP00G?Up2@5IhAM?vN0<0e,0"
        val checksum = NmeaChecksum.compute(body)
        assertTrue(NmeaChecksum.isValid("!$body*$checksum"))
    }
}
