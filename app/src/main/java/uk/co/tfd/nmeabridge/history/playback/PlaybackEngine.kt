package uk.co.tfd.nmeabridge.history.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.tfd.nmeabridge.history.persist.FrameLogReader
import uk.co.tfd.nmeabridge.history.persist.HistoryLogger
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.NavigationState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Replays recorded nav frames out as NMEA-0183 sentences at a constant
 * 1 Hz cadence (matching the recorded sample rate). Speed multipliers
 * scale how far the *playback clock* advances between ticks, not how
 * fast sentences arrive on the wire — Navionics & friends keep
 * receiving fixes at 1 Hz at every speed.
 *
 * Pause behaviour: the engine keeps emitting at 1 Hz; only the
 * playback position stops advancing. The same nav frame is re-emitted
 * each tick so downstream chartplotters don't see the stream go quiet
 * (which would trigger reconnects) — they just see a stationary boat
 * with a moving ZDA timestamp.
 *
 * End of data: when the next playback position would catch up with
 * wall-clock now, the engine clamps to the last recordable second
 * and switches to PAUSED. The keep-alive emit continues from that
 * pinned position.
 */
class PlaybackEngine(
    private val historyDir: File,
    private val sink: MutableSharedFlow<String>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickDelayMs: Long = 1000L,
) {

    enum class State { STOPPED, PLAYING, PAUSED }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _speed = MutableStateFlow(1)
    val speed: StateFlow<Int> = _speed.asStateFlow()

    private var job: Job? = null

    // Currently-open reader. Re-opened lazily when the playback clock
    // crosses a UTC date boundary.
    private var reader: FrameLogReader? = null
    private var readerDate: String? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Begin playback. Idempotent: if the loop is already running this
     *  just seeks + resets speed and flips state to PLAYING.  */
    fun start(positionMs: Long, speedX: Int = 1) {
        require(speedX > 0) { "speed must be > 0, got $speedX" }
        _positionMs.value = positionMs.coerceIn(0L, clock())
        _speed.value = speedX
        _state.value = State.PLAYING
        if (job?.isActive != true) {
            job = scope.launch { runLoop() }
        }
    }

    /** Pause: stop advancing the playhead. Keep emitting the current
     *  slot's sentences each tick so the TCP client stays connected. */
    fun pause() {
        if (_state.value == State.PLAYING) _state.value = State.PAUSED
    }

    /** Resume from PAUSED at the current position and current speed. */
    fun resume() {
        if (_state.value == State.PAUSED) _state.value = State.PLAYING
    }

    /** Move the playhead. Clamped to [0, now] — playback never runs
     *  ahead of the wall clock. */
    fun seek(positionMs: Long) {
        _positionMs.value = positionMs.coerceIn(0L, clock())
    }

    /** 1, 2 or 10 (or any positive int). Takes effect on the next tick. */
    fun setSpeed(speedX: Int) {
        require(speedX > 0) { "speed must be > 0, got $speedX" }
        _speed.value = speedX
    }

    /** Stop playback. Cancels the loop, closes any open reader. The
     *  service should call this when switching back to LIVE. */
    fun stop() {
        _state.value = State.STOPPED
        job?.cancel()
        job = null
        closeReader()
    }

    private suspend fun runLoop() {
        while (_state.value != State.STOPPED) {
            tick()
            delay(tickDelayMs)
        }
    }

    private fun tick() {
        val pos = _positionMs.value
        // Always emit something — at minimum an empty NavigationState's
        // ZDA — so the TCP client never sees the stream go quiet, no
        // matter how dark the playback region is.
        val nav = decodeAt(pos) ?: NavigationState()
        for (sentence in BinaryProtocol.toNmeaSentences(nav)) {
            sink.tryEmit(sentence)
        }
        if (_state.value == State.PLAYING) {
            advance(pos)
        }
    }

    private fun decodeAt(positionMs: Long): NavigationState? {
        val r = readerFor(positionMs) ?: return null
        val slot = r.slotAt(positionMs) ?: return null
        return BinaryProtocol.decode(slot.bytes)
    }

    private fun advance(currentPos: Long) {
        val nextPos = currentPos + 1000L * _speed.value
        val now = clock()
        if (nextPos >= now) {
            // Reached wall-clock now — clamp to the latest second we
            // could possibly have recorded and pause. The next tick
            // will keep emitting the same slot at the pinned position.
            _positionMs.value = (now - 1000L).coerceAtLeast(currentPos)
            _state.value = State.PAUSED
        } else {
            _positionMs.value = nextPos
        }
    }

    private fun readerFor(positionMs: Long): FrameLogReader? {
        val date = dateFormat.format(Date(positionMs))
        if (date == readerDate) return reader
        closeReader()
        val file = FrameLogReader.fileFor(historyDir, "nav", date)
        reader = FrameLogReader.open(
            file,
            expectedStreamType = HistoryLogger.STREAM_NAV,
            expectedRecordSize = BinaryProtocol.FRAME_SIZE,
            expectedSecondsPerRecord = 1,
        )
        readerDate = date
        return reader
    }

    private fun closeReader() {
        reader?.close()
        reader = null
        readerDate = null
    }
}
