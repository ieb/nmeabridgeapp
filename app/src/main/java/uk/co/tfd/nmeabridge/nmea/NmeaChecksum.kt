package uk.co.tfd.nmeabridge.nmea

object NmeaChecksum {

    /**
     * Compute the NMEA checksum for the body string (everything between '$' and '*').
     * Returns a two-character uppercase hex string.
     */
    fun compute(body: String): String {
        var checksum = 0
        for (c in body) {
            checksum = checksum xor c.code
        }
        return "%02X".format(checksum)
    }

    /**
     * Validate a complete NMEA sentence (including '$' prefix and '*XX' suffix).
     * Returns true if the checksum matches.
     */
    fun isValid(sentence: String): Boolean {
        val trimmed = sentence.trim()
        if (trimmed.length < 4) return false
        if (trimmed[0] != '$' && trimmed[0] != '!') return false

        val starIndex = trimmed.lastIndexOf('*')
        if (starIndex < 0 || starIndex + 3 > trimmed.length) return false

        val body = trimmed.substring(1, starIndex)
        val expected = trimmed.substring(starIndex + 1, starIndex + 3).uppercase()
        return compute(body) == expected
    }
}
