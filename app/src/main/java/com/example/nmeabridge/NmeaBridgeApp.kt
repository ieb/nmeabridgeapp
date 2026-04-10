package com.example.nmeabridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class NmeaBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NMEA Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the NMEA TCP server is running"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "nmea_server_channel"
        const val NOTIFICATION_ID = 1
    }
}
