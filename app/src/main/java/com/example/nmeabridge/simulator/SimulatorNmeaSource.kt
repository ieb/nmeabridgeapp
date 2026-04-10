package com.example.nmeabridge.simulator

import com.example.nmeabridge.nmea.NmeaSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

class SimulatorNmeaSource(
    private val centerLat: Double = 47.6062,
    private val centerLon: Double = -122.3321,
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

            sink.tryEmit(NmeaSentenceBuilder.buildGGA(
                timeMillis = time,
                latitude = lat,
                longitude = lon,
                quality = 1,
                satellites = 8,
                hdop = 1.2,
                altitude = 25.0
            ))
            sink.tryEmit(NmeaSentenceBuilder.buildRMC(
                timeMillis = time,
                latitude = lat,
                longitude = lon,
                speedKnots = speedKnots,
                courseDegrees = bearing
            ))
            sink.tryEmit(NmeaSentenceBuilder.buildGSA())
            sink.tryEmit(NmeaSentenceBuilder.buildVTG(
                courseDegrees = bearing,
                speedKnots = speedKnots
            ))

            bearing = (bearing + 3.6) % 360.0
            delay(1000)
        }
    }

    override fun stop() {
        running = false
    }
}
