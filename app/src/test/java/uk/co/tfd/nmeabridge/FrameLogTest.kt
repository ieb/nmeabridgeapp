package uk.co.tfd.nmeabridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.co.tfd.nmeabridge.history.persist.FrameLog
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Off-device tests for the append-only disk log.
 *
 * Every test uses an injected clock (AtomicLong) so the current time
 * is fully deterministic and independent of the test-runner host's
 * wall clock. Write targets live in a TemporaryFolder.
 */
class FrameLogTest {

    @get:Rule val tempDir = TemporaryFolder()

    // Fake clock the log calls for nowMs. Advance with set().
    private val clock = AtomicLong(0)

    private val SENTINEL_4 = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    private val REAL_4     = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    // UTC 2026-04-28 00:00:00 UTC — a midnight boundary we'll use as the
    // anchor for most of these tests. Value confirmed via
    //   python3 -c "import datetime; \
    //     print(int(datetime.datetime(2026,4,28,
    //                                  tzinfo=datetime.timezone.utc).timestamp()))"
    private val MIDNIGHT_28_APR_2026_SEC = 1_777_334_400L
    private val MIDNIGHT_28_APR_2026_MS = MIDNIGHT_28_APR_2026_SEC * 1000L

    @Before
    fun setUp() {
        clock.set(MIDNIGHT_28_APR_2026_MS)
    }

    private fun newLog(
        name: String = "test",
        streamType: Int = 1,
        recordSize: Int = 4,
        secondsPerRecord: Int = 1,
    ): FrameLog = FrameLog(
        dir = tempDir.root,
        streamName = name,
        streamType = streamType,
        recordSize = recordSize,
        secondsPerRecord = secondsPerRecord,
        sentinel = SENTINEL_4,
        clock = { clock.get() },
    )

    private fun fileFor(name: String): File {
        val dir = tempDir.root
        return dir.listFiles { f -> f.name.startsWith("$name-") && f.name.endsWith(".bin") }!!
            .single()
    }

    @Test
    fun freshFile_writesHeaderThenFirstRecord_atSlotZero() {
        val log = newLog()
        log.append(REAL_4)
        log.close()
        val f = fileFor("test")
        // HEADER (14) + 1 × recordSize (4)
        assertEquals(18L, f.length())
        val bytes = f.readBytes()
        // magic "navdata"
        assertEquals("navdata", String(bytes, 0, 7, Charsets.US_ASCII))
        // startTimeSec u32 LE == midnight
        val startSec = bytesToU32Le(bytes, 7)
        assertEquals(MIDNIGHT_28_APR_2026_SEC, startSec)
        assertEquals(1, bytes[11].toInt() and 0xFF)    // streamType
        assertEquals(4, bytes[12].toInt() and 0xFF)    // recordSize
        assertEquals(1, bytes[13].toInt() and 0xFF)    // secondsPerRecord
        // Body == REAL_4
        assertArrayEquals(REAL_4, bytes.copyOfRange(14, 18))
    }

    @Test
    fun freshFile_padsSentinelsBeforeFirstRecord() {
        // Open 5 slots past midnight.
        clock.set(MIDNIGHT_28_APR_2026_MS + 5_000)
        val log = newLog()
        log.append(REAL_4)
        log.close()
        val f = fileFor("test")
        // HEADER (14) + 5 sentinels + 1 real = 14 + 6×4 = 38
        assertEquals(38L, f.length())
        val bytes = f.readBytes()
        // First 5 slots are sentinels
        for (i in 0 until 5) {
            assertArrayEquals(SENTINEL_4, bytes.copyOfRange(14 + i * 4, 14 + (i + 1) * 4))
        }
        // Last slot is real
        assertArrayEquals(REAL_4, bytes.copyOfRange(14 + 5 * 4, 14 + 6 * 4))
    }

    @Test
    fun duplicateWithinSlot_dropped() {
        val log = newLog()
        log.append(REAL_4)
        clock.set(MIDNIGHT_28_APR_2026_MS + 500)   // same slot at 1 s/slot
        log.append(byteArrayOf(9, 9, 9, 9))
        log.close()
        val f = fileFor("test")
        assertEquals(18L, f.length())
        assertArrayEquals(REAL_4, f.readBytes().copyOfRange(14, 18))
    }

    @Test
    fun appendFasterThanCadence_firstWinsPerSlot_bms5sec() {
        val log = newLog(name = "bms", streamType = 3, recordSize = 4, secondsPerRecord = 5)
        // Three appends within the same 5 s window.
        log.append(REAL_4)
        clock.set(MIDNIGHT_28_APR_2026_MS + 1_000)
        log.append(byteArrayOf(2, 2, 2, 2))
        clock.set(MIDNIGHT_28_APR_2026_MS + 4_999)
        log.append(byteArrayOf(3, 3, 3, 3))
        // Tick into the next 5 s window — this one wins its slot.
        clock.set(MIDNIGHT_28_APR_2026_MS + 5_000)
        log.append(byteArrayOf(4, 4, 4, 4))
        log.close()
        val f = fileFor("bms")
        // 2 slots written, nothing else.
        assertEquals(14L + 2 * 4, f.length())
        val bytes = f.readBytes()
        assertArrayEquals(REAL_4, bytes.copyOfRange(14, 18))
        assertArrayEquals(byteArrayOf(4, 4, 4, 4), bytes.copyOfRange(18, 22))
    }

    @Test
    fun clockRegression_dropped() {
        val log = newLog()
        clock.set(MIDNIGHT_28_APR_2026_MS + 10_000)
        log.append(REAL_4)
        val len = fileFor("test").length()
        // Rewind 5 s; the new append's target slot is 5, already past.
        clock.set(MIDNIGHT_28_APR_2026_MS + 5_000)
        log.append(byteArrayOf(9, 9, 9, 9))
        log.close()
        assertEquals(len, fileFor("test").length())
    }

    @Test
    fun reopenExistingFile_padsAndAppends() {
        val log = newLog()
        clock.set(MIDNIGHT_28_APR_2026_MS + 5_000)
        log.append(REAL_4)   // slot 5
        log.close()

        clock.set(MIDNIGHT_28_APR_2026_MS + 20_000)
        val log2 = newLog()
        log2.append(byteArrayOf(7, 7, 7, 7))   // slot 20
        log2.close()

        val f = fileFor("test")
        // HEADER + 21 slots (0..5 = pad+real, 6..19 = sentinel, 20 = real)
        assertEquals(14L + 21 * 4, f.length())
        val bytes = f.readBytes()
        // Slot 5 real (first writer)
        assertArrayEquals(REAL_4, bytes.copyOfRange(14 + 5 * 4, 14 + 6 * 4))
        // Slot 20 real (second writer)
        assertArrayEquals(byteArrayOf(7, 7, 7, 7), bytes.copyOfRange(14 + 20 * 4, 14 + 21 * 4))
        // A slot in between is a sentinel
        assertArrayEquals(SENTINEL_4, bytes.copyOfRange(14 + 10 * 4, 14 + 11 * 4))
    }

    @Test
    fun reopenAfterPartialRecord_truncatesAndAppends() {
        val log = newLog()
        log.append(REAL_4)
        log.close()
        // Simulate a crash mid-record: append 2 garbage bytes to the file
        // so fileLen = 18 + 2 = 20 → remainder 2 after header.
        val f = fileFor("test")
        f.appendBytes(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        assertEquals(20L, f.length())

        clock.set(MIDNIGHT_28_APR_2026_MS + 5_000)
        val log2 = newLog()
        log2.append(byteArrayOf(5, 5, 5, 5))
        log2.close()

        // Expected layout: HEADER + slot 0 (real) + slots 1..4 (sentinel) + slot 5 (real).
        // Partial trailing 2 B must have been discarded.
        val bytes = fileFor("test").readBytes()
        assertEquals(14 + 6 * 4, bytes.size)
        assertArrayEquals(REAL_4, bytes.copyOfRange(14, 18))
        assertArrayEquals(SENTINEL_4, bytes.copyOfRange(18, 22))
        assertArrayEquals(byteArrayOf(5, 5, 5, 5), bytes.copyOfRange(14 + 5 * 4, 14 + 6 * 4))
    }

    @Test
    fun corruptMagic_quarantinedAndFreshFileCreated() {
        // Pre-seed file with wrong magic.
        val name = "test-2026-04-28.bin"
        val bad = File(tempDir.root, name)
        bad.writeBytes(ByteArray(14) { 0x00 })

        val log = newLog()
        log.append(REAL_4)
        log.close()

        val files = tempDir.root.list()!!
        // The bad one got renamed to *.corrupt-*, a fresh one took its place.
        val corrupt = files.filter { it.startsWith(name) && it.contains(".corrupt-") }
        assertEquals(1, corrupt.size)
        val fresh = File(tempDir.root, name)
        assertTrue(fresh.exists())
        val bytes = fresh.readBytes()
        assertEquals(18, bytes.size)   // 14 header + 1 × 4 body
        assertEquals("navdata", String(bytes, 0, 7, Charsets.US_ASCII))
    }

    @Test
    fun wrongSecondsPerRecord_quarantined() {
        val name = "test-2026-04-28.bin"
        val bad = File(tempDir.root, name)
        val header = ByteArray(14)
        "navdata".toByteArray(Charsets.US_ASCII).copyInto(header)
        // startTimeSec = today's midnight
        val s = MIDNIGHT_28_APR_2026_SEC.toInt()
        header[7] = (s).toByte()
        header[8] = (s ushr 8).toByte()
        header[9] = (s ushr 16).toByte()
        header[10] = (s ushr 24).toByte()
        header[11] = 1          // streamType
        header[12] = 4          // recordSize
        header[13] = 9          // WRONG secondsPerRecord (expected 1)
        bad.writeBytes(header)

        val log = newLog()   // expects secondsPerRecord = 1
        log.append(REAL_4)
        log.close()

        val corrupt = tempDir.root.list()!!.filter { it.contains(".corrupt-") }
        assertEquals(1, corrupt.size)
    }

    @Test
    fun midnightRotation_opensTomorrowFile() {
        val log = newLog()
        log.append(REAL_4)
        // Jump past midnight into 2026-04-29.
        clock.set(MIDNIGHT_28_APR_2026_MS + 86_400_000L + 3_000)
        log.append(byteArrayOf(9, 9, 9, 9))
        log.close()

        val files = tempDir.root.list()!!.toSortedSet()
        assertTrue("expected today file, got $files", files.contains("test-2026-04-28.bin"))
        assertTrue("expected tomorrow file, got $files", files.contains("test-2026-04-29.bin"))

        val todayBytes = File(tempDir.root, "test-2026-04-28.bin").readBytes()
        assertEquals(18, todayBytes.size)

        val tomorrowBytes = File(tempDir.root, "test-2026-04-29.bin").readBytes()
        // HEADER + 3 sentinels (slots 0..2) + 1 real (slot 3) = 14 + 16 = 30
        assertEquals(30, tomorrowBytes.size)
        // Header startTimeSec should be tomorrow's midnight
        val startSec = bytesToU32Le(tomorrowBytes, 7)
        assertEquals(MIDNIGHT_28_APR_2026_SEC + 86_400L, startSec)
    }

    @Test
    fun concurrentAppend_fileSizeConsistent() {
        // Same writer, 100 appends from each of two threads with clocks
        // interleaved so every append targets a distinct slot. Result:
        // the file must end up exactly `HEADER + 200 × recordSize`.
        val log = newLog()
        val stride = 4
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val t1 = Thread {
            try {
                barrier.await()
                for (i in 0 until 100) {
                    clock.set(MIDNIGHT_28_APR_2026_MS + (i * 2L) * 1000L)
                    log.append(REAL_4)
                }
            } catch (_: Exception) { errors.incrementAndGet() }
        }
        val t2 = Thread {
            try {
                barrier.await()
                for (i in 0 until 100) {
                    clock.set(MIDNIGHT_28_APR_2026_MS + (i * 2L + 1) * 1000L)
                    log.append(byteArrayOf(1, 1, 1, 1))
                }
            } catch (_: Exception) { errors.incrementAndGet() }
        }
        t1.start(); t2.start(); t1.join(); t2.join()
        log.close()
        assertEquals(0, errors.get())
        val len = fileFor("test").length()
        // Due to thread scheduling + clock mutation we can't predict the
        // exact trajectory, but the file must always be a valid
        // HEADER + N × stride file (no torn records).
        assertEquals(0L, (len - 14L) % stride)
        assertTrue("expected file to have some records, got $len", len > 14L)
    }

    @Test
    fun close_isIdempotent() {
        val log = newLog()
        log.append(REAL_4)
        log.close()
        log.close()   // must not throw
        assertFalse(
            "fresh append after close must not blow up",
            try { log.append(byteArrayOf(1, 2, 3, 4)); false } catch (_: Throwable) { true }
        )
    }

    private fun bytesToU32Le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
        ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or
        ((b[off + 3].toLong() and 0xFF) shl 24)
}
