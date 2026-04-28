package uk.co.tfd.nmeabridge.history.persist

import android.content.Context
import android.util.Log
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import java.io.File

/**
 * Owner of the three per-stream FrameLog writers (nav, engine, bms).
 *
 * Files live under `Context.getExternalFilesDir("history")`, which
 * resolves on-device to
 *   /sdcard/Android/data/uk.co.tfd.nmeabridge/files/history/
 * and is accessible via the Files app and `adb pull` without any
 * manifest permissions. Falls back to the app-private `filesDir` if
 * external storage is unavailable (emulators, some low-end devices).
 *
 * Per-stream `secondsPerRecord` is tuned to the real BLE cadence so
 * the files aren't bloated with sentinels:
 *   nav    — 1 Hz from firmware → 1 s/slot
 *   engine — 1 Hz from firmware → 1 s/slot
 *   bms    — 0.2 Hz from BoatWatch board → 5 s/slot
 */
class HistoryLogger(context: Context) {

    private val dir: File = resolveHistoryDir(context)

    val nav: FrameLog = FrameLog(
        dir = dir,
        streamName = "nav",
        streamType = STREAM_NAV,
        recordSize = BinaryProtocol.FRAME_SIZE,
        secondsPerRecord = 1,
        sentinel = BinaryProtocol.SENTINEL_FRAME,
    )

    val engine: FrameLog = FrameLog(
        dir = dir,
        streamName = "engine",
        streamType = STREAM_ENGINE,
        recordSize = EngineProtocol.FRAME_SIZE,
        secondsPerRecord = 1,
        sentinel = EngineProtocol.SENTINEL_FRAME,
    )

    val bms: FrameLog = FrameLog(
        dir = dir,
        streamName = "bms",
        streamType = STREAM_BMS,
        recordSize = BmsProtocol.HISTORY_SLOT_SIZE,
        secondsPerRecord = 5,
        sentinel = BmsProtocol.SENTINEL_SLOT,
    )

    /** For diagnostics / user messaging. */
    val directory: File get() = dir

    fun close() {
        nav.close()
        engine.close()
        bms.close()
    }

    companion object {
        private const val TAG = "HistoryLogger"

        const val STREAM_NAV = 1
        const val STREAM_ENGINE = 2
        const val STREAM_BMS = 3

        private fun resolveHistoryDir(context: Context): File {
            val ext = context.getExternalFilesDir("history")
            val chosen = if (ext != null) {
                ext
            } else {
                Log.w(TAG, "external files dir unavailable, falling back to internal filesDir")
                File(context.filesDir, "history")
            }
            chosen.mkdirs()
            return chosen
        }
    }
}
