package com.example.nmeabridge.nmea

import kotlinx.coroutines.flow.MutableSharedFlow

interface NmeaSource {
    suspend fun start(sink: MutableSharedFlow<String>)
    fun stop()
}
