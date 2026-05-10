package uk.co.tfd.nmeabridge.history

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.co.tfd.nmeabridge.history.persist.FrameLogReader
import uk.co.tfd.nmeabridge.history.persist.HistoryLogger
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Loads multi-day windows of binary history files into in-memory
 * [FrameRing]s, one per stream (nav / engine / bms). The chart code
 * reads from the resulting [RingSnapshot]s using the same per-field
 * accessors that work against the live in-memory rings; the file
 * origin is invisible to it.
 *
 * Memory bounds: every stream is capped at [MAX_DISPLAY_SAMPLES] slots
 * regardless of the requested window. For windows wider than ~1.1 hours
 * (nav/engine at 1 Hz) or ~5.5 hours (bms at 0.2 Hz) the loader applies
 * a uniform stride so the ring still fits.
 *
 * The instance keeps the last result cached. A subsequent call with
 * identical (tStartMs, tEndMs) returns the cached snapshot without
 * touching disk.
 */
class HistoryDataSource(private val historyDir: File) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var cached: HistoryWindowSnapshot? = null

    /**
     * Read every history file whose UTC date falls in
     * [floorDate(tStartMs), floorDate(tEndMs)] and build a per-stream
     * snapshot covering all slots whose timestamp is in
     * [tStartMs, tEndMs] (inclusive).
     *
     * Sentinel slots are skipped silently — they convey "no data" and
     * the per-field accessors would return null for them anyway.
     *
     * Runs on Dispatchers.IO. Caller is expected to hold this off the
     * main thread (typically by calling from a coroutine on a screen-
     * level scope).
     */
    suspend fun loadWindow(tStartMs: Long, tEndMs: Long): HistoryWindowSnapshot {
        require(tEndMs >= tStartMs) { "tEndMs ($tEndMs) < tStartMs ($tStartMs)" }
        cached?.let { c ->
            if (c.tStartMs == tStartMs && c.tEndMs == tEndMs) return c
        }
        val result = withContext(Dispatchers.IO) {
            val nav = loadStream(
                streamName = "nav",
                streamType = HistoryLogger.STREAM_NAV,
                recordSize = BinaryProtocol.FRAME_SIZE,
                secondsPerRecord = 1,
                sentinel = BinaryProtocol.SENTINEL_FRAME,
                tStartMs = tStartMs,
                tEndMs = tEndMs,
            )
            val engine = loadStream(
                streamName = "engine",
                streamType = HistoryLogger.STREAM_ENGINE,
                recordSize = EngineProtocol.FRAME_SIZE,
                secondsPerRecord = 1,
                sentinel = EngineProtocol.SENTINEL_FRAME,
                tStartMs = tStartMs,
                tEndMs = tEndMs,
            )
            val battery = loadStream(
                streamName = "bms",
                streamType = HistoryLogger.STREAM_BMS,
                recordSize = BmsProtocol.HISTORY_SLOT_SIZE,
                secondsPerRecord = 5,
                sentinel = BmsProtocol.SENTINEL_SLOT,
                tStartMs = tStartMs,
                tEndMs = tEndMs,
            )
            HistoryWindowSnapshot(
                nav = nav.snapshot,
                engine = engine.snapshot,
                battery = battery.snapshot,
                tStartMs = tStartMs,
                tEndMs = tEndMs,
                strideNav = nav.stride,
                strideEngine = engine.stride,
                strideBattery = battery.stride,
            )
        }
        cached = result
        return result
    }

    /**
     * Drop the cached snapshot. Call when the underlying files have
     * changed in a way the cache wouldn't catch (e.g. after the live
     * writer has appended new records and the user wants the live tail).
     */
    fun invalidate() {
        cached = null
    }

    private fun loadStream(
        streamName: String,
        streamType: Int,
        recordSize: Int,
        secondsPerRecord: Int,
        sentinel: ByteArray,
        tStartMs: Long,
        tEndMs: Long,
    ): StreamLoad {
        // Worst-case slot count over the whole window before stride.
        val windowSec = ((tEndMs - tStartMs) / 1000L).coerceAtLeast(0L)
        val rawSlots = (windowSec / secondsPerRecord).toInt() + 1
        val stride = if (rawSlots <= MAX_DISPLAY_SAMPLES) 1
                     else ((rawSlots + MAX_DISPLAY_SAMPLES - 1) / MAX_DISPLAY_SAMPLES)
        val ringCapacity = ((rawSlots + stride - 1) / stride).coerceAtLeast(1)
        val ring = FrameRing(frameSize = recordSize, capacity = ringCapacity)

        // Drop nav frames that match the autopilot-stitch corruption
        // pattern produced by the pre-2026-05-10 BLE accumulator bug.
        // Old nav-YYYY-MM-DD.bin files still carry these records; the
        // chart would otherwise spike to absurd values once per ~5 min.
        val isNav = streamName == "nav"

        // Day-by-day. floorMidnight(tStartMs) up to floorMidnight(tEndMs).
        var dayMs = floorMidnightUtcMs(tStartMs)
        val lastDayMs = floorMidnightUtcMs(tEndMs)
        var droppedCorrupt = 0
        while (dayMs <= lastDayMs) {
            val date = dateFormat.format(Date(dayMs))
            val file = FrameLogReader.fileFor(historyDir, streamName, date)
            val reader = FrameLogReader.open(
                file,
                expectedStreamType = streamType,
                expectedRecordSize = recordSize,
                expectedSecondsPerRecord = secondsPerRecord,
            )
            if (reader != null) {
                try {
                    for (slot in reader.streamSlots(stride = stride, skipSentinel = sentinel)) {
                        // streamSlots yields slots from i=0; the slot's timestamp
                        // tells us whether it's in the requested window. Slots
                        // before tStartMs are skipped, slots after tEndMs end the
                        // file scan early (timestamps are monotonically increasing).
                        if (slot.timestampMs < tStartMs) continue
                        if (slot.timestampMs > tEndMs) break
                        if (isNav && BinaryProtocol.isCorruptAutopilotStitch(slot.bytes)) {
                            droppedCorrupt++
                            continue
                        }
                        ring.append(slot.timestampMs, slot.bytes)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "read $streamName for $date: ${e.message}")
                } finally {
                    reader.close()
                }
            }
            dayMs += MS_PER_DAY
        }
        if (isNav && droppedCorrupt > 0) {
            Log.i(TAG, "dropped $droppedCorrupt corrupt autopilot-stitch nav frames in window")
        }
        return StreamLoad(ring.snapshot(), stride)
    }

    private fun floorMidnightUtcMs(ms: Long): Long =
        (ms / MS_PER_DAY) * MS_PER_DAY

    private data class StreamLoad(val snapshot: RingSnapshot, val stride: Int)

    companion object {
        private const val TAG = "HistoryDataSource"

        /**
         * Upper bound on the number of samples per stream after striding.
         *
         * Calibrated for typical chart widths (400 dp portrait phone up
         * to ~1500 dp tablet/Chromebook landscape) — at one sample per
         * 1–2 pixels the polyline already looks smooth. Larger values
         * just multiply the per-window CPU cost (axis-range scan and
         * polyline draw both scan every sample) without making the
         * chart visibly better.
         *
         * On a 12 h nav window (86 400 raw slots) this gives stride 86
         * → 1004 samples → ~30 KB body in the working ring. Reload
         * after a pan or zoom completes in well under a second.
         */
        const val MAX_DISPLAY_SAMPLES = 1024

        private const val MS_PER_DAY = 86_400_000L
    }
}

/**
 * One unit of work the chart code consumes. Carries snapshots for all
 * three streams plus the window bounds and the stride that was applied
 * (mostly for debugging — the chart doesn't need it directly).
 */
data class HistoryWindowSnapshot(
    val nav: RingSnapshot,
    val engine: RingSnapshot,
    val battery: RingSnapshot,
    val tStartMs: Long,
    val tEndMs: Long,
    val strideNav: Int,
    val strideEngine: Int,
    val strideBattery: Int,
) {
    companion object {
        val EMPTY = HistoryWindowSnapshot(
            nav = RingSnapshot.EMPTY,
            engine = RingSnapshot.EMPTY,
            battery = RingSnapshot.EMPTY,
            tStartMs = 0L,
            tEndMs = 0L,
            strideNav = 1,
            strideEngine = 1,
            strideBattery = 1,
        )
    }
}
