package uk.co.tfd.nmeabridge.simulator

import uk.co.tfd.nmeabridge.nmea.NmeaSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

class SimulatorNmeaSource(
    private val centerLat: Double = 50.07,
    private val centerLon: Double = -9.50,
    private val radiusDegrees: Double = 0.005,
    private val speedKnots: Double = 5.0
) : NmeaSource {

    @Volatile
    private var running = false

    override suspend fun start(sink: MutableSharedFlow<String>) {
        running = true
        var bearing = 0.0

        while (currentCoroutineContext().isActive && running) {
            val lat = centerLat + radiusDegrees * cos(Math.toRadians(bearing))
            val lon = centerLon + radiusDegrees * sin(Math.toRadians(bearing))
            val time = System.currentTimeMillis()

            // Vary depth slowly between 8m and 18m
            val depth = 13.0 + 5.0 * sin(Math.toRadians(bearing * 2.0))

            sink.tryEmit(NmeaSentenceBuilder.buildDBT(depthMetres = depth))
            sink.tryEmit(NmeaSentenceBuilder.buildGGA(
                timeMillis = time,
                latitude = lat,
                longitude = lon,
                quality = 1,
                satellites = 8,
                hdop = 1.2,
                altitude = 0.0
            ))
            sink.tryEmit(NmeaSentenceBuilder.buildGLL(
                timeMillis = time,
                latitude = lat,
                longitude = lon
            ))
            sink.tryEmit(NmeaSentenceBuilder.buildRMC(
                timeMillis = time,
                latitude = lat,
                longitude = lon,
                speedKnots = speedKnots,
                courseDegrees = bearing,
                magneticVariation = 0.5,
                variationDirection = "E"
            ))
            sink.tryEmit(NmeaSentenceBuilder.buildVTG(
                courseDegrees = bearing,
                speedKnots = speedKnots,
                magneticVariation = 0.5
            ))
            sink.tryEmit(NmeaSentenceBuilder.buildZDA(timeMillis = time))

            bearing = (bearing + 3.6) % 360.0
            delay(1000)
        }
    }

    override fun stop() {
        running = false
    }
}
