package uk.co.tfd.nmeabridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.co.tfd.nmeabridge.history.HistoryDataSource
import uk.co.tfd.nmeabridge.history.persist.FrameLog
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import uk.co.tfd.nmeabridge.history.persist.HistoryLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * Verifies HistoryDataSource against files produced by FrameLog. The
 * data source's behaviour we want to lock in:
 *  - reads from multiple day files transparently
 *  - skips sentinel slots
 *  - applies stride sub-sampling for wide windows
 *  - returns empty snapshots when no files match
 *  - caches identical-window calls
 */
class HistoryDataSourceTest {

    @get:Rule val tempDir = TemporaryFolder()

    private val clock = AtomicLong(0)

    // 2026-04-28 00:00:00 UTC.
    private val MIDNIGHT_28_APR_2026_MS = 1_777_334_400L * 1000L

    private fun navLog(): FrameLog = FrameLog(
        dir = tempDir.root,
        streamName = "nav",
        streamType = HistoryLogger.STREAM_NAV,
        recordSize = BinaryProtocol.FRAME_SIZE,
        secondsPerRecord = 1,
        sentinel = BinaryProtocol.SENTINEL_FRAME,
        clock = { clock.get() },
    )

    private fun engineLog(): FrameLog = FrameLog(
        dir = tempDir.root,
        streamName = "engine",
        streamType = HistoryLogger.STREAM_ENGINE,
        recordSize = EngineProtocol.FRAME_SIZE,
        secondsPerRecord = 1,
        sentinel = EngineProtocol.SENTINEL_FRAME,
        clock = { clock.get() },
    )

    private fun bmsLog(): FrameLog = FrameLog(
        dir = tempDir.root,
        streamName = "bms",
        streamType = HistoryLogger.STREAM_BMS,
        recordSize = BmsProtocol.HISTORY_SLOT_SIZE,
        secondsPerRecord = 5,
        sentinel = BmsProtocol.SENTINEL_SLOT,
        clock = { clock.get() },
    )

    @Before
    fun setUp() {
        clock.set(MIDNIGHT_28_APR_2026_MS)
    }

    /**
     * Build a 29-byte "nav frame" that is in fact three concatenated
     * 10-byte autopilot state messages — the artefact of the pre-fix BLE
     * accumulator bug. Layout reproduces what was observed in the wild
     * (`/tmp/nmea_archive/nav-2026-05-09.bin` slot 22583):
     *   0..9  : msg1 bytes 2..9 = hdg_lo hdg_hi tgt_lo tgt_hi 00 00 00 00
     *           then msg2 magic + mode = AA 00
     *   10..19: msg2 bytes 2..9 + msg3 magic+mode = AA 00
     *   20..28: msg3 bytes 2..9 + msg4 magic = AA
     */
    private fun corruptAutopilotStitchFrame(): ByteArray {
        val frame = ByteArray(29)
        frame[0] = 0xCC.toByte()                 // accidental nav-frame magic from msg1.hdg_lo
        // Bytes 1..7: rest of msg1 (hdg_hi, tgt_lo, tgt_hi, wind_lo, wind_hi, 00, 00).
        frame[1] = 0x77; frame[2] = 0x26; frame[3] = 0x79
        frame[4] = 0x00; frame[5] = 0x00
        frame[6] = 0x00; frame[7] = 0x00
        // Bytes 8..9: msg2 magic + mode.
        frame[8] = 0xAA.toByte(); frame[9] = 0x00
        // Bytes 10..17: msg2 bytes 2..9.
        frame[10] = 0xCB.toByte(); frame[11] = 0x77; frame[12] = 0x26; frame[13] = 0x79
        frame[14] = 0x00; frame[15] = 0x00; frame[16] = 0x00; frame[17] = 0x00
        // Bytes 18..19: msg3 magic + mode.
        frame[18] = 0xAA.toByte(); frame[19] = 0x00
        // Bytes 20..27: msg3 bytes 2..9.
        frame[20] = 0xCA.toByte(); frame[21] = 0x77; frame[22] = 0x26; frame[23] = 0x79
        frame[24] = 0x00; frame[25] = 0x00; frame[26] = 0x00; frame[27] = 0x00
        // Byte 28: msg4 magic.
        frame[28] = 0xAA.toByte()
        return frame
    }

    private fun realNavFrame(seed: Int): ByteArray {
        // Build a frame that's not a sentinel: the magic byte is 0xCC and
        // the U16 sog field varies per seed so we can spot sub-sampling.
        val frame = ByteArray(BinaryProtocol.FRAME_SIZE)
        // Make every byte non-0xFF (or 0x7F where signed-NA would land) to
        // avoid colliding with the sentinel pattern.
        for (i in frame.indices) frame[i] = 0x10.toByte()
        frame[0] = 0xCC.toByte()
        // sog raw at offset 11 (U16 LE): seed.
        frame[11] = (seed and 0xFF).toByte()
        frame[12] = ((seed ushr 8) and 0xFF).toByte()
        return frame
    }

    @Test
    fun emptyDir_returnsEmptySnapshots() = runBlocking {
        val ds = HistoryDataSource(tempDir.root)
        val snap = ds.loadWindow(MIDNIGHT_28_APR_2026_MS, MIDNIGHT_28_APR_2026_MS + 60_000)
        assertEquals(0, snap.nav.size)
        assertEquals(0, snap.engine.size)
        assertEquals(0, snap.battery.size)
    }

    @Test
    fun singleDay_navWindow_loadsRealSlots_skipsSentinels() = runBlocking {
        val log = navLog()
        // 5 real frames at slots 0..4.
        for (i in 0 until 5) {
            clock.set(MIDNIGHT_28_APR_2026_MS + i * 1_000L)
            log.append(realNavFrame(i))
        }
        // Then a 4-slot gap (sentinels) and one more real frame at slot 9.
        clock.set(MIDNIGHT_28_APR_2026_MS + 9_000L)
        log.append(realNavFrame(99))
        log.close()

        val ds = HistoryDataSource(tempDir.root)
        val snap = ds.loadWindow(
            MIDNIGHT_28_APR_2026_MS,
            MIDNIGHT_28_APR_2026_MS + 9_000,
        )
        // Sentinel slots 5..8 are dropped, so 6 real slots remain.
        assertEquals(6, snap.nav.size)
        // Last slot's timestamp is at +9s.
        assertEquals(MIDNIGHT_28_APR_2026_MS + 9_000, snap.nav.timestampAt(5))
        // sog field reads back correctly: snap.nav[5] should be seed=99.
        val sog = BinaryProtocol.sogAt(snap.nav, 5)
        // Decoded sog = 99 * 0.01 m/s ≈ 0.99 m/s ≈ 1.92 kn.
        assertTrue("sog $sog should be in (1.5, 2.5)", sog != null && sog!! in 1.5..2.5)
    }

    @Test
    fun crossesDayBoundary_loadsBothFiles() = runBlocking {
        val log = navLog()
        // One frame at 23:59:00 on 2026-04-28
        clock.set(MIDNIGHT_28_APR_2026_MS + 86_340_000L)
        log.append(realNavFrame(1))
        // One frame at 00:00:30 on 2026-04-29
        clock.set(MIDNIGHT_28_APR_2026_MS + 86_400_000L + 30_000L)
        log.append(realNavFrame(2))
        log.close()

        val ds = HistoryDataSource(tempDir.root)
        val snap = ds.loadWindow(
            MIDNIGHT_28_APR_2026_MS + 86_340_000L,        // 23:59:00 on day 1
            MIDNIGHT_28_APR_2026_MS + 86_400_000L + 60_000L, // 00:01:00 on day 2
        )
        assertEquals(2, snap.nav.size)
        assertEquals(MIDNIGHT_28_APR_2026_MS + 86_340_000L, snap.nav.timestampAt(0))
        assertEquals(MIDNIGHT_28_APR_2026_MS + 86_400_000L + 30_000L, snap.nav.timestampAt(1))
    }

    @Test
    fun wideWindow_appliesStride() = runBlocking {
        val log = navLog()
        // 10 000 contiguous real nav frames (1 Hz). MAX_DISPLAY_SAMPLES is
        // 4000, so the data source must subsample with stride ≥ 3 to fit.
        for (i in 0 until 10_000) {
            clock.set(MIDNIGHT_28_APR_2026_MS + i * 1_000L)
            log.append(realNavFrame(i))
        }
        log.close()

        val ds = HistoryDataSource(tempDir.root)
        val snap = ds.loadWindow(
            MIDNIGHT_28_APR_2026_MS,
            MIDNIGHT_28_APR_2026_MS + 9_999_000L,
        )
        // Whatever stride was chosen, the result must fit and be < 4001 samples.
        assertTrue(
            "expected <= MAX_DISPLAY_SAMPLES samples, got ${snap.nav.size}",
            snap.nav.size <= HistoryDataSource.MAX_DISPLAY_SAMPLES + 1,
        )
        assertTrue("expected the chosen stride to be > 1, got ${snap.strideNav}", snap.strideNav > 1)
        // First and last samples bracket the requested window.
        assertEquals(MIDNIGHT_28_APR_2026_MS, snap.nav.timestampAt(0))
    }

    @Test
    fun sameWindow_returnsCachedSnapshot() = runBlocking {
        val log = navLog()
        clock.set(MIDNIGHT_28_APR_2026_MS)
        log.append(realNavFrame(1))
        log.close()

        val ds = HistoryDataSource(tempDir.root)
        val a = ds.loadWindow(MIDNIGHT_28_APR_2026_MS, MIDNIGHT_28_APR_2026_MS + 1_000)
        val b = ds.loadWindow(MIDNIGHT_28_APR_2026_MS, MIDNIGHT_28_APR_2026_MS + 1_000)
        // Identical window must return the same instance.
        assertTrue(a === b)
    }

    @Test
    fun invalidate_forcesReload() = runBlocking {
        val log = navLog()
        clock.set(MIDNIGHT_28_APR_2026_MS)
        log.append(realNavFrame(1))
        log.close()

        val ds = HistoryDataSource(tempDir.root)
        val a = ds.loadWindow(MIDNIGHT_28_APR_2026_MS, MIDNIGHT_28_APR_2026_MS + 1_000)
        ds.invalidate()
        val b = ds.loadWindow(MIDNIGHT_28_APR_2026_MS, MIDNIGHT_28_APR_2026_MS + 1_000)
        assertTrue("expected fresh snapshot after invalidate", a !== b)
    }

    @Test
    fun corruptAutopilotStitchFrames_areDroppedFromNavStream() = runBlocking {
        val log = navLog()
        // One real frame, one corrupt-stitch frame, one real frame.
        clock.set(MIDNIGHT_28_APR_2026_MS)
        log.append(realNavFrame(1))
        clock.set(MIDNIGHT_28_APR_2026_MS + 1_000)
        log.append(corruptAutopilotStitchFrame())
        clock.set(MIDNIGHT_28_APR_2026_MS + 2_000)
        log.append(realNavFrame(2))
        log.close()

        val ds = HistoryDataSource(tempDir.root)
        val snap = ds.loadWindow(
            MIDNIGHT_28_APR_2026_MS,
            MIDNIGHT_28_APR_2026_MS + 2_000,
        )
        // The corrupt frame is filtered out; we keep only the two real ones.
        assertEquals(2, snap.nav.size)
        assertEquals(MIDNIGHT_28_APR_2026_MS, snap.nav.timestampAt(0))
        assertEquals(MIDNIGHT_28_APR_2026_MS + 2_000, snap.nav.timestampAt(1))
    }

    @Test
    fun mismatchedHeader_skipsSilently() = runBlocking {
        // Engine file with the wrong record size on disk → reader returns
        // null, data source treats it as no data for the day.
        val bogus = java.io.File(tempDir.root, "engine-2026-04-28.bin")
        bogus.writeBytes("navdata".toByteArray() + ByteArray(7) { 0x42 })

        val ds = HistoryDataSource(tempDir.root)
        val snap = ds.loadWindow(MIDNIGHT_28_APR_2026_MS, MIDNIGHT_28_APR_2026_MS + 60_000)
        assertEquals(0, snap.engine.size)
    }
}
