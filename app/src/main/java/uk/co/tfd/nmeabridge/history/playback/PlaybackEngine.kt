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
import uk.co.tfd.nmeabridge.nmea.BatteryState
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import uk.co.tfd.nmeabridge.nmea.BmsProtocol
import uk.co.tfd.nmeabridge.nmea.EngineProtocol
import uk.co.tfd.nmeabridge.nmea.EngineState
import uk.co.tfd.nmeabridge.nmea.NavigationState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Replays recorded frames out as both decoded state and (for nav)
 * NMEA-0183 sentences at a constant 1 Hz cadence (matching the
 * recorded sample rate). Speed multipliers scale how far the
 * *playback clock* advances between ticks, not how fast sentences
 * arrive on the wire — Navionics & friends keep receiving fixes at
 * 1 Hz at every speed.
 *
 * Three streams are replayed in lock-step at the same UTC ms:
 *  - nav    (1 s/slot) → [navigationState] + NMEA sentences on [sink]
 *  - engine (1 s/slot) → [engineState]
 *  - bms    (5 s/slot) → [batteryState]
 *
 * Engine and BMS don't go on the NMEA0183 wire — matches the live
 * path. Their decoded states drive the on-device Engine and Battery
 * screens during playback.
 *
 * Pause behaviour: the engine keeps emitting at 1 Hz; only the
 * playback position stops advancing. The same frames are re-emitted
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

    // Decoded state for the on-device screens. Null when the slot at
    // the current playback position is a sentinel, missing, or before
    // the start of recorded history for that stream.
    private val _navigationState = MutableStateFlow<NavigationState?>(null)
    val navigationState: StateFlow<NavigationState?> = _navigationState.asStateFlow()

    private val _engineState = MutableStateFlow<EngineState?>(null)
    val engineState: StateFlow<EngineState?> = _engineState.asStateFlow()

    private val _batteryState = MutableStateFlow<BatteryState?>(null)
    val batteryState: StateFlow<BatteryState?> = _batteryState.asStateFlow()

    private var job: Job? = null

    // One slot per stream. Each holds the currently-open reader and the
    // UTC date that reader is open for. Reader is re-opened lazily when
    // the playback clock crosses a UTC date boundary.
    private val navSlot = StreamSlot(
        streamName = "nav",
        expectedStreamType = HistoryLogger.STREAM_NAV,
        expectedRecordSize = BinaryProtocol.FRAME_SIZE,
        expectedSecondsPerRecord = 1,
    )
    private val engineSlot = StreamSlot(
        streamName = "engine",
        expectedStreamType = HistoryLogger.STREAM_ENGINE,
        expectedRecordSize = EngineProtocol.FRAME_SIZE,
        expectedSecondsPerRecord = 1,
    )
    private val bmsSlot = StreamSlot(
        streamName = "bms",
        expectedStreamType = HistoryLogger.STREAM_BMS,
        expectedRecordSize = BmsProtocol.HISTORY_SLOT_SIZE,
        expectedSecondsPerRecord = 5,
    )

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

    /** Stop playback. Cancels the loop, closes any open readers, and
     *  clears decoded state so the on-device screens fall back to "—"
     *  while the live source repopulates. The service should call this
     *  when switching back to LIVE. */
    fun stop() {
        _state.value = State.STOPPED
        job?.cancel()
        job = null
        closeReaders()
        _navigationState.value = null
        _engineState.value = null
        _batteryState.value = null
    }

    private suspend fun runLoop() {
        while (_state.value != State.STOPPED) {
            tick()
            delay(tickDelayMs)
        }
    }

    private fun tick() {
        val pos = _positionMs.value

        val nav = decodeNav(pos)
        _navigationState.value = nav
        // Always emit nav sentences — at minimum an empty NavigationState's
        // ZDA — so the TCP client never sees the stream go quiet, no
        // matter how dark the playback region is.
        for (sentence in BinaryProtocol.toNmeaSentences(nav ?: NavigationState())) {
            sink.tryEmit(sentence)
        }

        _engineState.value = decodeEngine(pos)
        _batteryState.value = decodeBattery(pos)

        if (_state.value == State.PLAYING) {
            advance(pos)
        }
    }

    private fun decodeNav(positionMs: Long): NavigationState? {
        val bytes = readSlotBytes(navSlot, positionMs) ?: return null
        return BinaryProtocol.decode(bytes)
    }

    private fun decodeEngine(positionMs: Long): EngineState? {
        val bytes = readSlotBytes(engineSlot, positionMs) ?: return null
        return EngineProtocol.decode(bytes)
    }

    private fun decodeBattery(positionMs: Long): BatteryState? {
        val bytes = readSlotBytes(bmsSlot, positionMs) ?: return null
        // The canonical 48 B history slot has fixed offsets — the wire
        // decode (BmsProtocol.decode) walks variable-length cells and
        // would misread the padding band between cells and n_ntc.
        return BmsProtocol.decodeSlot(bytes)
    }

    private fun readSlotBytes(slot: StreamSlot, positionMs: Long): ByteArray? {
        val r = readerFor(slot, positionMs) ?: return null
        return r.slotAt(positionMs)?.bytes
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

    private fun readerFor(slot: StreamSlot, positionMs: Long): FrameLogReader? {
        val date = dateFormat.format(Date(positionMs))
        if (date == slot.readerDate) return slot.reader
        slot.close()
        val file = FrameLogReader.fileFor(historyDir, slot.streamName, date)
        slot.reader = FrameLogReader.open(
            file,
            expectedStreamType = slot.expectedStreamType,
            expectedRecordSize = slot.expectedRecordSize,
            expectedSecondsPerRecord = slot.expectedSecondsPerRecord,
        )
        slot.readerDate = date
        return slot.reader
    }

    private fun closeReaders() {
        navSlot.close()
        engineSlot.close()
        bmsSlot.close()
    }

    /**
     * Per-stream playback state: the currently-open reader, the UTC date
     * it covers, and the file-format expectations used to validate the
     * header when re-opening across day boundaries.
     */
    private class StreamSlot(
        val streamName: String,
        val expectedStreamType: Int,
        val expectedRecordSize: Int,
        val expectedSecondsPerRecord: Int,
    ) {
        var reader: FrameLogReader? = null
        var readerDate: String? = null

        fun close() {
            reader?.close()
            reader = null
            readerDate = null
        }
    }
}
