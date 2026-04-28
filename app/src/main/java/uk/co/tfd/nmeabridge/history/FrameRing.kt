package uk.co.tfd.nmeabridge.history

/**
 * Fixed-stride circular buffer for BLE wire frames. Payload is opaque
 * bytes (the wire format); decoding is the caller's responsibility via
 * per-field accessors in the nmea package (BinaryProtocol, EngineProtocol,
 * BmsProtocol).
 *
 * Thread safety: writes (append) and reads (readFrame/timestamp) are
 * synchronized on the ring. Chart readers typically take the lock once
 * for a slice and copy the visible frames into a scratch array.
 *
 * The ring stores a single ByteArray of capacity * frameSize plus a
 * parallel LongArray of capture timestamps. Both grow with a single
 * preallocation at construction time — no allocations at append time.
 */
class FrameRing(
    val frameSize: Int,
    val capacity: Int,
) {
    private val bytes = ByteArray(frameSize * capacity)
    private val times = LongArray(capacity)

    // head = physical index of the oldest logical entry; on overflow,
    // head advances and count stays at capacity.
    private var head = 0
    private var count = 0

    // Monotonically increasing version bumped on every append. Published
    // snapshots inherit this so Compose treats them as distinct.
    private var version = 0

    @Synchronized
    fun append(tMs: Long, frame: ByteArray) {
        require(frame.size == frameSize) {
            "frame size ${frame.size} != ring frameSize $frameSize"
        }
        val physical = if (count < capacity) {
            (head + count) % capacity
        } else {
            // Full: overwrite the oldest slot and advance head.
            val p = head
            head = (head + 1) % capacity
            p
        }
        System.arraycopy(frame, 0, bytes, physical * frameSize, frameSize)
        times[physical] = tMs
        if (count < capacity) count++
        version++
    }

    @Synchronized
    fun snapshot(): RingSnapshot =
        RingSnapshot(this, head, count, version, if (count > 0) times[(head + count - 1) % capacity] else 0L)

    // Package-private accessors used by RingSnapshot under its own lock.
    @Synchronized
    internal fun readTimestamp(logicalIndex: Int, snapshotHead: Int, snapshotCount: Int): Long {
        require(logicalIndex in 0 until snapshotCount)
        return times[(snapshotHead + logicalIndex) % capacity]
    }

    @Synchronized
    internal fun readU8(logicalIndex: Int, fieldOffset: Int,
                        snapshotHead: Int, snapshotCount: Int): Int {
        require(logicalIndex in 0 until snapshotCount)
        val base = ((snapshotHead + logicalIndex) % capacity) * frameSize + fieldOffset
        return bytes[base].toInt() and 0xFF
    }

    @Synchronized
    internal fun readU16(logicalIndex: Int, fieldOffset: Int,
                         snapshotHead: Int, snapshotCount: Int): Int {
        require(logicalIndex in 0 until snapshotCount)
        val base = ((snapshotHead + logicalIndex) % capacity) * frameSize + fieldOffset
        return (bytes[base].toInt() and 0xFF) or
               ((bytes[base + 1].toInt() and 0xFF) shl 8)
    }

    @Synchronized
    internal fun readS16(logicalIndex: Int, fieldOffset: Int,
                         snapshotHead: Int, snapshotCount: Int): Short {
        require(logicalIndex in 0 until snapshotCount)
        val base = ((snapshotHead + logicalIndex) % capacity) * frameSize + fieldOffset
        val lo = bytes[base].toInt() and 0xFF
        val hi = bytes[base + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }

    @Synchronized
    internal fun readU32(logicalIndex: Int, fieldOffset: Int,
                         snapshotHead: Int, snapshotCount: Int): Int {
        require(logicalIndex in 0 until snapshotCount)
        val base = ((snapshotHead + logicalIndex) % capacity) * frameSize + fieldOffset
        return (bytes[base].toInt() and 0xFF) or
               ((bytes[base + 1].toInt() and 0xFF) shl 8) or
               ((bytes[base + 2].toInt() and 0xFF) shl 16) or
               ((bytes[base + 3].toInt() and 0xFF) shl 24)
    }

    @Synchronized
    internal fun readS32(logicalIndex: Int, fieldOffset: Int,
                         snapshotHead: Int, snapshotCount: Int): Int =
        readU32(logicalIndex, fieldOffset, snapshotHead, snapshotCount)

    @Synchronized
    internal fun copyFrameInto(logicalIndex: Int, dst: ByteArray, dstOffset: Int,
                               snapshotHead: Int, snapshotCount: Int) {
        require(logicalIndex in 0 until snapshotCount)
        val srcOffset = ((snapshotHead + logicalIndex) % capacity) * frameSize
        System.arraycopy(bytes, srcOffset, dst, dstOffset, frameSize)
    }
}
