package uk.co.tfd.nmeabridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.co.tfd.nmeabridge.history.persist.FrameLog
import uk.co.tfd.nmeabridge.history.persist.FrameLogReader
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Round-trip tests for [FrameLogReader] against files written by the
 * existing [FrameLog]. The two classes share the on-disk format, so
 * round-trip is the strongest correctness check we can run off-device.
 */
class FrameLogReaderTest {

    @get:Rule val tempDir = TemporaryFolder()

    private val clock = AtomicLong(0)

    private val SENTINEL_4 = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    private val REAL_A = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val REAL_B = byteArrayOf(0x10, 0x20, 0x30, 0x40)
    private val REAL_C = byteArrayOf(0x55, 0x66, 0x77, 0x00)

    // 2026-04-28 00:00:00 UTC — same anchor used by FrameLogTest.
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

    private fun fileFor(name: String, date: String = "2026-04-28"): File =
        File(tempDir.root, "$name-$date.bin")

    @Test
    fun reader_returnsNull_whenFileMissing() {
        assertNull(FrameLogReader.open(File(tempDir.root, "absent-2026-04-28.bin")))
    }

    @Test
    fun reader_returnsNull_whenFileTooShort() {
        val f = File(tempDir.root, "short-2026-04-28.bin")
        f.writeBytes(byteArrayOf(0, 1, 2))
        assertNull(FrameLogReader.open(f))
    }

    @Test
    fun reader_returnsNull_whenMagicWrong() {
        val f = File(tempDir.root, "badmagic-2026-04-28.bin")
        f.writeBytes(ByteArray(20) { 0x42 })
        assertNull(FrameLogReader.open(f))
    }

    @Test
    fun openSingleSlotFile_headerFieldsParsedCorrectly() {
        val log = newLog()
        log.append(REAL_A)
        log.close()
        FrameLogReader.open(fileFor("test"))!!.use { r ->
            assertEquals(1, r.streamType)
            assertEquals(4, r.recordSize)
            assertEquals(1, r.secondsPerRecord)
            assertEquals(MIDNIGHT_28_APR_2026_SEC, r.startTimeSec)
            assertEquals(1, r.slotCount)
            assertEquals(MIDNIGHT_28_APR_2026_MS, r.timestampMsOf(0))
            val buf = ByteArray(4)
            r.readSlot(0, buf)
            assertArrayEquals(REAL_A, buf)
        }
    }

    @Test
    fun streamSlots_yieldsEverySlotByDefault() {
        // Slots 0..2 with sentinels in 1, real in 0 and 2.
        val log = newLog()
        log.append(REAL_A)                                       // slot 0
        clock.set(MIDNIGHT_28_APR_2026_MS + 2_000)
        log.append(REAL_B)                                       // slot 2 (slot 1 padded)
        log.close()
        FrameLogReader.open(fileFor("test"))!!.use { r ->
            val slots = r.streamSlots().toList()
            assertEquals(3, slots.size)
            assertArrayEquals(REAL_A, slots[0].bytes)
            assertArrayEquals(SENTINEL_4, slots[1].bytes)
            assertArrayEquals(REAL_B, slots[2].bytes)
            assertEquals(MIDNIGHT_28_APR_2026_MS, slots[0].timestampMs)
            assertEquals(MIDNIGHT_28_APR_2026_MS + 1_000, slots[1].timestampMs)
            assertEquals(MIDNIGHT_28_APR_2026_MS + 2_000, slots[2].timestampMs)
        }
    }

    @Test
    fun streamSlots_skipsSentinelsWhenAsked() {
        val log = newLog()
        log.append(REAL_A)
        clock.set(MIDNIGHT_28_APR_2026_MS + 5_000)
        log.append(REAL_B)
        log.close()
        FrameLogReader.open(fileFor("test"))!!.use { r ->
            val slots = r.streamSlots(skipSentinel = SENTINEL_4).toList()
            // Slots 1..4 are sentinel-padded; only slots 0 and 5 survive.
            assertEquals(2, slots.size)
            assertArrayEquals(REAL_A, slots[0].bytes)
            assertArrayEquals(REAL_B, slots[1].bytes)
            assertEquals(MIDNIGHT_28_APR_2026_MS, slots[0].timestampMs)
            assertEquals(MIDNIGHT_28_APR_2026_MS + 5_000, slots[1].timestampMs)
        }
    }

    @Test
    fun streamSlots_obeysStride() {
        val log = newLog()
        // Six slots, all real (one per second).
        for (i in 0 until 6) {
            clock.set(MIDNIGHT_28_APR_2026_MS + i * 1_000L)
            log.append(byteArrayOf(i.toByte(), 0, 0, 0))
        }
        log.close()
        FrameLogReader.open(fileFor("test"))!!.use { r ->
            val slots = r.streamSlots(stride = 2).toList()
            // Slots 0, 2, 4 only.
            assertEquals(3, slots.size)
            assertEquals(0.toByte(), slots[0].bytes[0])
            assertEquals(2.toByte(), slots[1].bytes[0])
            assertEquals(4.toByte(), slots[2].bytes[0])
        }
    }

    @Test
    fun streamSlots_yieldsDistinctCopies() {
        val log = newLog()
        log.append(REAL_A)
        clock.set(MIDNIGHT_28_APR_2026_MS + 1_000)
        log.append(REAL_B)
        log.close()
        FrameLogReader.open(fileFor("test"))!!.use { r ->
            val slots = r.streamSlots().toList()
            // Mutating one returned array must not poison the next.
            slots[0].bytes[0] = 0x77
            assertEquals(0x10.toByte(), slots[1].bytes[0])
        }
    }

    @Test
    fun expectedHeaderMismatch_returnsNull() {
        val log = newLog(name = "test", streamType = 1, recordSize = 4, secondsPerRecord = 1)
        log.append(REAL_A)
        log.close()
        // Wrong recordSize.
        assertNull(FrameLogReader.open(fileFor("test"), expectedRecordSize = 8))
        // Wrong streamType.
        assertNull(FrameLogReader.open(fileFor("test"), expectedStreamType = 2))
        // Wrong secondsPerRecord.
        assertNull(FrameLogReader.open(fileFor("test"), expectedSecondsPerRecord = 5))
        // All correct → ok.
        assertNotNull(
            FrameLogReader.open(
                fileFor("test"),
                expectedStreamType = 1,
                expectedRecordSize = 4,
                expectedSecondsPerRecord = 1,
            )
        )
    }

    @Test
    fun crossMidnight_twoFiles_readSeparately_timestampsContiguous() {
        val log = newLog()
        log.append(REAL_A)                                 // 2026-04-28 slot 0
        clock.set(MIDNIGHT_28_APR_2026_MS + 86_400_000L + 3_000)
        log.append(REAL_B)                                 // 2026-04-29 slot 3
        log.close()

        val today = FrameLogReader.open(fileFor("test", "2026-04-28"))!!
        val tomorrow = FrameLogReader.open(fileFor("test", "2026-04-29"))!!
        try {
            assertEquals(1, today.slotCount)
            assertEquals(4, tomorrow.slotCount)            // 3 sentinels + 1 real
            assertEquals(MIDNIGHT_28_APR_2026_MS, today.timestampMsOf(0))
            // Tomorrow's slot 3 = midnight + 1 day + 3 s.
            val expectedTomorrowSlot3 = MIDNIGHT_28_APR_2026_MS + 86_400_000L + 3_000L
            assertEquals(expectedTomorrowSlot3, tomorrow.timestampMsOf(3))
            val merged = today.streamSlots(skipSentinel = SENTINEL_4).toList() +
                    tomorrow.streamSlots(skipSentinel = SENTINEL_4).toList()
            assertEquals(2, merged.size)
            assertArrayEquals(REAL_A, merged[0].bytes)
            assertArrayEquals(REAL_B, merged[1].bytes)
            // Continuity: ts increases monotonically across the file boundary.
            assertTrue(merged[1].timestampMs > merged[0].timestampMs)
            assertEquals(86_403_000L, merged[1].timestampMs - merged[0].timestampMs)
        } finally {
            today.close()
            tomorrow.close()
        }
    }

    @Test
    fun readSlot_throwsOnOutOfRange() {
        val log = newLog()
        log.append(REAL_A)
        log.close()
        FrameLogReader.open(fileFor("test"))!!.use { r ->
            val buf = ByteArray(4)
            try {
                r.readSlot(1, buf)
                throw AssertionError("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun bms_5sPerRecord_timestampsScale() {
        val log = newLog(name = "bms", streamType = 3, recordSize = 4, secondsPerRecord = 5)
        log.append(REAL_A)                                 // slot 0 (= midnight)
        clock.set(MIDNIGHT_28_APR_2026_MS + 5_000)
        log.append(REAL_B)                                 // slot 1 (= midnight + 5 s)
        clock.set(MIDNIGHT_28_APR_2026_MS + 15_000)
        log.append(REAL_C)                                 // slot 3 (slot 2 padded)
        log.close()
        FrameLogReader.open(fileFor("bms"))!!.use { r ->
            assertEquals(5, r.secondsPerRecord)
            assertEquals(4, r.slotCount)
            assertEquals(MIDNIGHT_28_APR_2026_MS, r.timestampMsOf(0))
            assertEquals(MIDNIGHT_28_APR_2026_MS + 5_000, r.timestampMsOf(1))
            assertEquals(MIDNIGHT_28_APR_2026_MS + 10_000, r.timestampMsOf(2))
            assertEquals(MIDNIGHT_28_APR_2026_MS + 15_000, r.timestampMsOf(3))
        }
    }
}
