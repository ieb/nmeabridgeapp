package uk.co.tfd.nmeabridge.nmea

import kotlinx.coroutines.flow.MutableSharedFlow

interface NmeaSource {
    suspend fun start(sink: MutableSharedFlow<String>)
    fun stop()
}
