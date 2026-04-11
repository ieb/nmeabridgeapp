package uk.co.tfd.nmeabridge.nmea

data class NavigationState(
    val latitude: Double? = null,       // degrees (+ N, - S)
    val longitude: Double? = null,      // degrees (+ E, - W)
    val cog: Double? = null,            // degrees 0-360
    val sog: Double? = null,            // knots
    val variation: Double? = null,      // degrees (+ E, - W)
    val heading: Double? = null,        // degrees 0-360
    val depth: Double? = null,          // metres
    val awa: Double? = null,            // degrees ±180 (- port, + starboard)
    val aws: Double? = null,            // knots
    val stw: Double? = null,            // knots
    val logNm: Double? = null,          // nautical miles
)
