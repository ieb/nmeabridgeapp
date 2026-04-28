package uk.co.tfd.nmeabridge.history.persist

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Append-only, position-indexed binary log for one history stream.
 *
 * File layout (see doc/history-log-format.md):
 *   [14 B header] [slot 0] [slot 1] [slot 2] ...
 *
 * Each slot is `recordSize` bytes and represents `secondsPerRecord` wall
 * clock seconds starting from `startTimeSec` (UTC midnight of the file's
 * day). Missing seconds hold the stream's sentinel frame, so a reader
 * can seek directly to any UTC time without scanning.
 *
 * One file per UTC day per stream. The writer rotates at midnight UTC.
 * If a file for today already exists (e.g. the app restarted) it is
 * reopened, the header validated, any partial final record truncated,
 * and appends continue at the correct slot.
 *
 * Thread safety: every mutating method is `@Synchronized`. Writes from
 * the service's raw-frame collector (Dispatchers.Default) and the
 * shutdown path from the main thread serialise on the same monitor.
 */
class FrameLog(
    private val dir: File,
    private val streamName: String,      // "nav" | "engine" | "bms"
    private val streamType: Int,         // 1 | 2 | 3
    private val recordSize: Int,         // 29 | 27 | 48
    private val secondsPerRecord: Int,   // 1 for nav/engine, 5 for bms
    private val sentinel: ByteArray,     // all-NA frame (SENTINEL_FRAME / SENTINEL_SLOT)
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(sentinel.size == recordSize) {
            "sentinel size ${sentinel.size} != recordSize $recordSize"
        }
        require(streamType in 1..255) { "streamType out of u8 range: $streamType" }
        require(recordSize in 1..255) { "recordSize out of u8 range: $recordSize" }
        require(secondsPerRecord in 1..255) { "secondsPerRecord out of u8 range: $secondsPerRecord" }
    }

    companion object {
        private const val TAG = "FrameLog"
        internal val MAGIC = "navdata".toByteArray(Charsets.US_ASCII)
        internal const val HEADER_SIZE = 14
        private const val SECONDS_PER_DAY = 86_400L
    }

    // Current open file state. All access under `this` monitor.
    private var currentDateUtc: String = ""       // "2026-04-28", empty when no file open
    private var currentStartSec: Long = 0          // epoch seconds of the file's UTC midnight
    private var nextSlotIndex: Long = 0            // index of next unwritten slot
    private var out: FileOutputStream? = null
    private var fd: FileDescriptor? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Append a real frame for the current wall-clock instant. If the
     * current slot already contains a record (duplicate within the
     * `secondsPerRecord` window), the new frame is dropped. If time has
     * advanced past the current slot, sentinel records are padded in
     * between. If midnight UTC has passed, rotates to tomorrow's file.
     *
     * IOException from the underlying writes is caught and logged; the
     * file is closed so the next append will try to re-open. The ring
     * buffer and the rest of the app are unaffected.
     */
    @Synchronized
    fun append(frame: ByteArray) {
        require(frame.size == recordSize) {
            "frame size ${frame.size} != recordSize $recordSize"
        }
        try {
            val nowMs = clock()
            ensureFileForTime(nowMs)
            val nowSec = nowMs / 1000
            val targetSlot = (nowSec - currentStartSec) / secondsPerRecord
            if (targetSlot < nextSlotIndex) {
                // Duplicate within the same slot, or clock regressed.
                // Neither case writes.
                return
            }
            padSentinelsTo(targetSlot)
            writeRecord(frame)
            nextSlotIndex = targetSlot + 1
        } catch (e: IOException) {
            Log.w(TAG, "append failed for $streamName: ${e.message}")
            closeQuietly()
        }
    }

    @Synchronized
    fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            out?.flush()
            fd?.sync()
        } catch (_: IOException) {
        }
        try { out?.close() } catch (_: IOException) {}
        out = null
        fd = null
        currentDateUtc = ""
        nextSlotIndex = 0
    }

    /**
     * Open (or reopen) the file matching `nowMs`'s UTC date if one isn't
     * already open for it. Rotates across midnight by closing the old
     * file and opening tomorrow's.
     */
    private fun ensureFileForTime(nowMs: Long) {
        val date = dateFormat.format(Date(nowMs))
        if (date == currentDateUtc && out != null) return
        // Either no file open, or we've crossed midnight UTC.
        closeQuietly()
        currentDateUtc = date
        currentStartSec = midnightUtcSec(nowMs)
        val file = File(dir, "$streamName-$date.bin")
        openExistingOrCreate(file)
    }

    /**
     * Open `file`, validating the header if it already exists. On any
     * validation failure the bad file is quarantined and a fresh one is
     * created. On success, seeks to end-of-file (truncated to an exact
     * slot boundary if a crash left a partial record).
     */
    private fun openExistingOrCreate(file: File) {
        dir.mkdirs()
        if (file.exists() && file.length() > 0) {
            if (!validateHeader(file)) {
                quarantine(file)
                createNew(file)
                return
            }
            // Truncate to slot boundary if the last write was interrupted.
            val len = file.length()
            val bodyLen = len - HEADER_SIZE
            val extra = bodyLen % recordSize
            if (extra != 0L) {
                try {
                    RandomAccessFile(file, "rw").use { it.setLength(len - extra) }
                    Log.w(TAG, "truncated $streamName tail of $extra bytes (partial record)")
                } catch (e: IOException) {
                    Log.w(TAG, "truncate failed on ${file.name}: ${e.message}")
                }
            }
            nextSlotIndex = (file.length() - HEADER_SIZE) / recordSize
            val fos = FileOutputStream(file, /* append = */ true)
            out = fos
            fd = fos.fd
        } else {
            createNew(file)
        }
    }

    private fun createNew(file: File) {
        val fos = FileOutputStream(file, /* append = */ false)
        out = fos
        fd = fos.fd
        val header = ByteArray(HEADER_SIZE)
        // bytes 0..6: magic "navdata"
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.size)
        // bytes 7..10: startTimeSec u32 LE
        val s = currentStartSec.toInt()
        header[7]  = (s        ).toByte()
        header[8]  = (s ushr  8).toByte()
        header[9]  = (s ushr 16).toByte()
        header[10] = (s ushr 24).toByte()
        // byte 11: streamType u8
        header[11] = streamType.toByte()
        // byte 12: recordSize u8
        header[12] = recordSize.toByte()
        // byte 13: secondsPerRecord u8
        header[13] = secondsPerRecord.toByte()
        fos.write(header)
        fos.flush()
        fos.fd.sync()
        nextSlotIndex = 0
    }

    /**
     * Validate an existing file's header. Returns true when magic,
     * streamType, recordSize, secondsPerRecord and startTimeSec all
     * match this writer's configuration; false otherwise.
     */
    private fun validateHeader(file: File): Boolean {
        if (file.length() < HEADER_SIZE) return false
        val header = ByteArray(HEADER_SIZE)
        try {
            RandomAccessFile(file, "r").use { raf -> raf.readFully(header) }
        } catch (_: IOException) {
            return false
        }
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) return false
        }
        val fileStart = (header[7].toLong() and 0xFFL) or
                        ((header[8].toLong() and 0xFFL) shl 8) or
                        ((header[9].toLong() and 0xFFL) shl 16) or
                        ((header[10].toLong() and 0xFFL) shl 24)
        if (fileStart != currentStartSec) return false
        if ((header[11].toInt() and 0xFF) != streamType) return false
        if ((header[12].toInt() and 0xFF) != recordSize) return false
        if ((header[13].toInt() and 0xFF) != secondsPerRecord) return false
        return true
    }

    /**
     * Rename a file with an unexpected or corrupt header so a fresh one
     * can replace it. Suffix includes a timestamp to avoid collisions
     * across multiple bad files on the same day.
     */
    private fun quarantine(file: File) {
        val suffix = ".corrupt-${clock() / 1000}"
        val target = File(file.parentFile, file.name + suffix)
        val ok = try { file.renameTo(target) } catch (_: SecurityException) { false }
        Log.w(TAG, "quarantined ${file.name} → ${target.name} (rename=${ok})")
    }

    private fun padSentinelsTo(exclusive: Long) {
        while (nextSlotIndex < exclusive) {
            writeRecord(sentinel)
            nextSlotIndex++
        }
    }

    /**
     * Write exactly `recordSize` bytes, flush through the file stream,
     * and `fdatasync()` so the record survives power loss even if the
     * process is killed microseconds later. At ≤1 write/s per stream
     * this is cheap on flash storage.
     */
    private fun writeRecord(bytes: ByteArray) {
        val o = out ?: throw IOException("no open file for $streamName")
        o.write(bytes)
        o.flush()
        fd?.sync()
    }

    /** Seconds since epoch at UTC midnight of the day containing `nowMs`. */
    private fun midnightUtcSec(nowMs: Long): Long {
        val nowSec = nowMs / 1000
        // Floor division is safe for positive nowSec; no need to worry about
        // negative wall-clock epochs on a device that has NTP-synced.
        return (nowSec / SECONDS_PER_DAY) * SECONDS_PER_DAY
    }

    // --- Test hooks ---

    internal val currentFilePathForTest: String
        get() = File(dir, "$streamName-$currentDateUtc.bin").path
}
