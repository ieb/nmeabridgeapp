package uk.co.tfd.nmeabridge.history

/**
 * Immutable read handle for a FrameRing, published via StateFlow.
 *
 * Holds only metadata (head/count/version/newestMs) — the actual bytes
 * are read on demand from the producer ring under its lock. Chart code
 * acquires the snapshot once via StateFlow.collectAsState(), then calls
 * per-field accessors defined in the nmea package (e.g. EngineProtocol.rpmAt).
 *
 * Each publish creates a new RingSnapshot instance so Compose detects
 * the change via reference inequality.
 */
class RingSnapshot internal constructor(
    private val ring: FrameRing,
    private val head: Int,
    val size: Int,
    val version: Int,
    val newestMs: Long,
) {
    val frameSize: Int get() = ring.frameSize

    fun timestampAt(i: Int): Long =
        ring.readTimestamp(i, head, size)

    fun readU8(i: Int, fieldOffset: Int): Int =
        ring.readU8(i, fieldOffset, head, size)

    fun readU16(i: Int, fieldOffset: Int): Int =
        ring.readU16(i, fieldOffset, head, size)

    fun readS16(i: Int, fieldOffset: Int): Short =
        ring.readS16(i, fieldOffset, head, size)

    fun readU32(i: Int, fieldOffset: Int): Int =
        ring.readU32(i, fieldOffset, head, size)

    fun readS32(i: Int, fieldOffset: Int): Int =
        ring.readS32(i, fieldOffset, head, size)

    fun copyFrameInto(i: Int, dst: ByteArray, dstOffset: Int = 0) {
        ring.copyFrameInto(i, dst, dstOffset, head, size)
    }

    /**
     * Binary-search for the first logical index with timestamp >= tMs.
     * Returns size if no such index exists (tMs is newer than everything).
     * Timestamps are monotonically non-decreasing by construction (append
     * order = wall-clock order).
     */
    fun lowerBound(tMs: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timestampAt(mid) < tMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * First logical index with timestamp > tMs. Use `range = lowerBound(t0)..upperBound(t1) - 1`
     * to iterate samples in the inclusive time window [t0, t1].
     */
    fun upperBound(tMs: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timestampAt(mid) <= tMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY_RING = FrameRing(frameSize = 1, capacity = 1)
        val EMPTY = RingSnapshot(EMPTY_RING, head = 0, size = 0, version = 0, newestMs = 0L)
    }
}
