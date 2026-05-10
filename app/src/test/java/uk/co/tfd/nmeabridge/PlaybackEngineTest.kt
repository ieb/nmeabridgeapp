package uk.co.tfd.nmeabridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.co.tfd.nmeabridge.history.persist.FrameLog
import uk.co.tfd.nmeabridge.history.persist.HistoryLogger
import uk.co.tfd.nmeabridge.history.playback.PlaybackEngine
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Verifies the playback engine drives a [MutableSharedFlow] at a fixed
 * 1 Hz cadence and that pause/seek/speed/end-of-data semantics match
 * the plan. Uses a virtual-time TestScope so each tick advances
 * deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineTest {

    @get:Rule val tempDir = TemporaryFolder()

    // 2026-04-28 00:00:00 UTC.
    private val MIDNIGHT_MS = 1_777_334_400L * 1000L

    private val fakeClock = AtomicLong(MIDNIGHT_MS + 60_000L) // "now" = +60 s

    private fun navFrame(sogRaw: Int): ByteArray {
        // A non-sentinel nav frame with a fixed lat/lon and a varying
        // SOG so the test can pick out which slot was emitted.
        val buf = ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0xCC.toByte())
        buf.putInt(500_700_000)         // lat
        buf.putInt(-95_000_000)         // lon
        buf.putShort(0xFFFF.toShort())  // cog NA
        buf.putShort(sogRaw.toShort())  // sog raw
        buf.putShort(0x7FFF)            // variation NA
        buf.putShort(0xFFFF.toShort())  // hdg NA
        buf.putShort(1350.toShort())    // depth 13.5 m
        buf.putShort(0xFFFF.toShort())  // awa NA
        buf.putShort(0xFFFF.toShort())  // aws NA
        buf.putShort(0xFFFF.toShort())  // stw NA
        buf.putInt(1_000_000)           // log
        return buf.array()
    }

    private fun newNavLog(clock: AtomicLong): FrameLog = FrameLog(
        dir = tempDir.root,
        streamName = "nav",
        streamType = HistoryLogger.STREAM_NAV,
        recordSize = BinaryProtocol.FRAME_SIZE,
        secondsPerRecord = 1,
        sentinel = BinaryProtocol.SENTINEL_FRAME,
        clock = { clock.get() },
    )

    /**
     * Write `frames` consecutively from `MIDNIGHT_MS + startOffsetSec`.
     * Returns the log so the test can close it before reading.
     */
    private fun writeNavFixture(frames: List<ByteArray>, startOffsetSec: Long = 0) {
        val writeClock = AtomicLong(MIDNIGHT_MS + startOffsetSec * 1_000)
        val log = newNavLog(writeClock)
        for ((i, f) in frames.withIndex()) {
            writeClock.set(MIDNIGHT_MS + (startOffsetSec + i) * 1_000)
            log.append(f)
        }
        log.close()
    }

    private fun newSink(): MutableSharedFlow<String> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Number of GPRMC sentences in the sink — one per emitted nav fix
     * with valid lat/lon. Sentinel-only emissions only produce ZDA, so
     * filtering on RMC counts only "real" emissions.
     */
    private fun rmcCount(captured: List<String>): Int =
        captured.count { it.startsWith("\$GPRMC,") }

    /** Extract the SOG raw byte (low byte) from each RMC sentence. */
    private fun sogValuesIn(captured: List<String>): List<String> =
        captured.filter { it.startsWith("\$GPRMC,") }
            .map { it.split(",")[7] }   // sog is the 8th field (0-indexed 7) in RMC

    @Test
    fun playing_emits_oncePerSecond_advancesByOneSecond_at_speed1() = runTest {
        // 5 frames at slots 0..4, sog raw = 100..104.
        writeNavFixture((0..4).map { navFrame(100 + it) })
        val sink = newSink()
        val captured = mutableListOf<String>()
        val engine = PlaybackEngine(tempDir.root, sink, this, fakeClock::get)
        // Collect everything emitted so we can inspect later.
        val collectorJob = launch { sink.collect { captured += it } }

        engine.start(positionMs = MIDNIGHT_MS + 0, speedX = 1)
        // First tick fires immediately; subsequent ticks every 1000 ms.
        advanceTimeBy(50)            // fire first tick
        advanceTimeBy(1000)          // second tick
        advanceTimeBy(1000)          // third tick
        advanceTimeBy(1000)          // fourth tick

        // 4 ticks should have happened, each emitting one RMC.
        assertEquals(4, rmcCount(captured))
        // Position advance happens AFTER each tick's emit, so 4 ticks
        // → position has been bumped 4 times by 1 s.
        assertEquals(MIDNIGHT_MS + 4_000L, engine.positionMs.value)

        engine.stop()
        collectorJob.cancel()
    }

    @Test
    fun playing_at_speed10_advancesByTenSeconds_perTick() = runTest {
        // 30 frames so we have room to advance several 10 s steps.
        writeNavFixture((0..29).map { navFrame(it) })
        val sink = newSink()
        val captured = mutableListOf<String>()
        val engine = PlaybackEngine(tempDir.root, sink, this, fakeClock::get)
        val collectorJob = launch { sink.collect { captured += it } }

        // Move the wall clock far enough into the future that the engine
        // doesn't hit end-of-data during the test.
        fakeClock.set(MIDNIGHT_MS + 600_000L)

        engine.start(positionMs = MIDNIGHT_MS + 0, speedX = 10)
        advanceTimeBy(50)            // tick 1: pos=0
        advanceTimeBy(1000)          // tick 2: pos=10
        advanceTimeBy(1000)          // tick 3: pos=20

        // After 3 ticks at speed 10, the playhead has advanced 3 × 10 s.
        assertEquals(MIDNIGHT_MS + 30_000L, engine.positionMs.value)
        // 3 RMC emissions even though the engine skipped intermediate slots.
        assertEquals(3, rmcCount(captured))

        engine.stop()
        collectorJob.cancel()
    }

    @Test
    fun paused_keeps_emitting_at_same_position() = runTest {
        writeNavFixture((0..9).map { navFrame(100 + it) })
        val sink = newSink()
        val captured = mutableListOf<String>()
        val engine = PlaybackEngine(tempDir.root, sink, this, fakeClock::get)
        val collectorJob = launch { sink.collect { captured += it } }

        engine.start(positionMs = MIDNIGHT_MS + 0, speedX = 1)
        advanceTimeBy(50)              // tick at pos=0
        advanceTimeBy(1000)            // tick at pos=1
        engine.pause()
        val pausedAt = engine.positionMs.value
        val rmcCountBeforePause = rmcCount(captured)
        // Three more wall-clock seconds elapse while paused.
        advanceTimeBy(1000)
        advanceTimeBy(1000)
        advanceTimeBy(1000)

        // Position MUST NOT advance while paused.
        assertEquals(pausedAt, engine.positionMs.value)
        // But the engine MUST have continued emitting — at least 3 more
        // RMC sentences during the 3 paused seconds. Stream stays alive.
        val rmcDuringPause = rmcCount(captured) - rmcCountBeforePause
        assertTrue("expected ≥3 RMC during pause, got $rmcDuringPause",
            rmcDuringPause >= 3)

        engine.stop()
        collectorJob.cancel()
    }

    @Test
    fun seek_moves_playhead_immediately() = runTest {
        writeNavFixture((0..9).map { navFrame(100 + it) })
        val sink = newSink()
        val engine = PlaybackEngine(tempDir.root, sink, this, fakeClock::get)
        val collectorJob = launch { sink.collect { /* drain */ } }

        engine.start(positionMs = MIDNIGHT_MS + 0, speedX = 1)
        advanceTimeBy(50)              // tick at pos=0
        engine.seek(MIDNIGHT_MS + 5_000)
        advanceTimeBy(1000)            // next tick should emit at pos=5

        // The position should be at 6 s (after seek to 5 s + one 1× advance).
        assertEquals(MIDNIGHT_MS + 6_000L, engine.positionMs.value)

        engine.stop()
        collectorJob.cancel()
    }

    @Test
    fun setSpeed_appliesOnNextTick() = runTest {
        writeNavFixture((0..29).map { navFrame(it) })
        val sink = newSink()
        val engine = PlaybackEngine(tempDir.root, sink, this, fakeClock::get)
        val collectorJob = launch { sink.collect { /* drain */ } }

        // Move "now" far enough so end-of-data doesn't intervene.
        fakeClock.set(MIDNIGHT_MS + 600_000L)

        engine.start(positionMs = MIDNIGHT_MS + 0, speedX = 1)
        advanceTimeBy(50)            // tick 1: pos=0 → 1
        engine.setSpeed(10)
        advanceTimeBy(1000)          // tick 2: pos=1 → 11

        assertEquals(MIDNIGHT_MS + 11_000L, engine.positionMs.value)
        assertEquals(10, engine.speed.value)

        engine.stop()
        collectorJob.cancel()
    }

    @Test
    fun endOfData_clamps_pauses_and_keeps_emitting() = runTest {
        // 5 frames — file covers slots 0..4 (i.e. last record at +4 s).
        writeNavFixture((0..4).map { navFrame(100 + it) })
        // Wall clock pinned just past the last recorded slot.
        fakeClock.set(MIDNIGHT_MS + 5_500L)
        val sink = newSink()
        val captured = mutableListOf<String>()
        val engine = PlaybackEngine(tempDir.root, sink, this, fakeClock::get)
        val collectorJob = launch { sink.collect { captured += it } }

        engine.start(positionMs = MIDNIGHT_MS + 4_000L, speedX = 10)
        advanceTimeBy(50)            // tick at pos=4 (last slot)
        advanceTimeBy(1000)          // would advance to 14 → past now → pause

        val rmcAfterPause = rmcCount(captured)
        advanceTimeBy(1000)          // continue ticking while paused
        advanceTimeBy(1000)

        // Engine must be PAUSED at end-of-data.
        assertEquals(PlaybackEngine.State.PAUSED, engine.state.value)
        // Stream MUST have kept emitting after pause (+2 RMCs over the
        // two further wall-clock seconds we let elapse).
        assertTrue(
            "expected stream to keep flowing after end-of-data; got " +
                "${rmcCount(captured) - rmcAfterPause} extra RMCs",
            rmcCount(captured) - rmcAfterPause >= 2,
        )

        engine.stop()
        collectorJob.cancel()
    }

    @Test
    fun stop_cancelsLoop_andStopsEmitting() = runTest {
        writeNavFixture((0..9).map { navFrame(it) })
        val sink = newSink()
        val captured = mutableListOf<String>()
        val engine = PlaybackEngine(tempDir.root, sink, this, fakeClock::get)
        val collectorJob = launch { sink.collect { captured += it } }

        engine.start(positionMs = MIDNIGHT_MS + 0, speedX = 1)
        advanceTimeBy(50)
        advanceTimeBy(1000)
        engine.stop()
        val rmcAtStop = rmcCount(captured)
        advanceTimeBy(5_000)

        assertEquals(PlaybackEngine.State.STOPPED, engine.state.value)
        // No further emissions after stop.
        assertEquals(rmcAtStop, rmcCount(captured))
        collectorJob.cancel()
    }

    @Test
    fun decodedSentences_carryRecordedSog() = runTest {
        // Use distinctive SOG values. We can't easily pull SOG out of
        // the formatted RMC sentence, but we can at least confirm the
        // sentence is well-formed (starts with $GPRMC and is non-empty).
        // sog raw is in 0.01 m/s units → 257 → 2.57 m/s ≈ 5 kn.
        writeNavFixture(listOf(navFrame(257)))
        val sink = newSink()
        val captured = mutableListOf<String>()
        val engine = PlaybackEngine(tempDir.root, sink, this, fakeClock::get)
        val collectorJob = launch { sink.collect { captured += it } }

        engine.start(positionMs = MIDNIGHT_MS + 0, speedX = 1)
        advanceTimeBy(50)

        val sogs = sogValuesIn(captured)
        assertEquals(1, sogs.size)
        // SOG in RMC is in knots, formatted "%.1f". 2.57 m/s ≈ 4.99 kn → "5.0".
        assertEquals("5.0", sogs[0])

        engine.stop()
        collectorJob.cancel()
    }
}
