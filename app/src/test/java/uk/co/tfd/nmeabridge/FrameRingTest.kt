package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.tfd.nmeabridge.history.FrameRing

class FrameRingTest {

    private fun frame(b0: Int, b1: Int = 0): ByteArray =
        byteArrayOf(b0.toByte(), b1.toByte())

    @Test
    fun append_storesAndReadsInOrder() {
        val ring = FrameRing(frameSize = 2, capacity = 4)
        ring.append(100L, frame(1, 0))
        ring.append(200L, frame(2, 0))
        ring.append(300L, frame(3, 0))
        val s = ring.snapshot()
        assertEquals(3, s.size)
        assertEquals(100L, s.timestampAt(0))
        assertEquals(200L, s.timestampAt(1))
        assertEquals(300L, s.timestampAt(2))
        assertEquals(1, s.readU8(0, 0))
        assertEquals(2, s.readU8(1, 0))
        assertEquals(3, s.readU8(2, 0))
    }

    @Test
    fun append_pastCapacityWrapsOldestOut() {
        val ring = FrameRing(frameSize = 2, capacity = 3)
        ring.append(10L, frame(1))
        ring.append(20L, frame(2))
        ring.append(30L, frame(3))
        ring.append(40L, frame(4))   // evicts 10/1
        ring.append(50L, frame(5))   // evicts 20/2
        val s = ring.snapshot()
        assertEquals(3, s.size)
        assertEquals(30L, s.timestampAt(0))
        assertEquals(40L, s.timestampAt(1))
        assertEquals(50L, s.timestampAt(2))
        assertEquals(3, s.readU8(0, 0))
        assertEquals(4, s.readU8(1, 0))
        assertEquals(5, s.readU8(2, 0))
    }

    @Test
    fun snapshot_versionIncreasesMonotonically() {
        val ring = FrameRing(frameSize = 1, capacity = 2)
        val v0 = ring.snapshot().version
        ring.append(1L, byteArrayOf(0))
        val v1 = ring.snapshot().version
        ring.append(2L, byteArrayOf(0))
        val v2 = ring.snapshot().version
        assertTrue("v0=$v0 v1=$v1", v1 > v0)
        assertTrue("v1=$v1 v2=$v2", v2 > v1)
    }

    @Test
    fun lowerBound_findsFirstGe() {
        val ring = FrameRing(frameSize = 1, capacity = 8)
        listOf(10L, 20L, 30L, 40L, 50L).forEach { ring.append(it, byteArrayOf(0)) }
        val s = ring.snapshot()
        assertEquals(0, s.lowerBound(5L))    // all >= 5
        assertEquals(0, s.lowerBound(10L))   // first == 10
        assertEquals(2, s.lowerBound(25L))   // 30 is first >= 25
        assertEquals(4, s.lowerBound(50L))   // last element == 50
        assertEquals(5, s.lowerBound(60L))   // none; past end
    }

    @Test
    fun upperBound_findsFirstGt() {
        val ring = FrameRing(frameSize = 1, capacity = 8)
        listOf(10L, 20L, 30L).forEach { ring.append(it, byteArrayOf(0)) }
        val s = ring.snapshot()
        assertEquals(0, s.upperBound(5L))    // 10 is first > 5
        assertEquals(1, s.upperBound(10L))   // 20 is first > 10
        assertEquals(3, s.upperBound(30L))   // past end
    }

    @Test
    fun readU16_readsLittleEndian() {
        val ring = FrameRing(frameSize = 2, capacity = 1)
        // 0x1234 little-endian = 0x34, 0x12
        ring.append(0L, byteArrayOf(0x34, 0x12))
        val s = ring.snapshot()
        assertEquals(0x1234, s.readU16(0, 0))
    }

    @Test
    fun readS16_preservesSign() {
        val ring = FrameRing(frameSize = 2, capacity = 1)
        // -1 little-endian = 0xFF, 0xFF
        ring.append(0L, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        val s = ring.snapshot()
        assertEquals((-1).toShort(), s.readS16(0, 0))
    }

    @Test
    fun readU32_readsFullLittleEndian() {
        val ring = FrameRing(frameSize = 4, capacity = 1)
        ring.append(0L, byteArrayOf(0x78, 0x56, 0x34, 0x12))
        val s = ring.snapshot()
        // 0x12345678
        assertEquals(0x12345678, s.readU32(0, 0))
    }

    @Test
    fun copyFrameInto_copiesExactStride() {
        val ring = FrameRing(frameSize = 4, capacity = 2)
        ring.append(0L, byteArrayOf(1, 2, 3, 4))
        ring.append(0L, byteArrayOf(5, 6, 7, 8))
        val dst = ByteArray(4)
        ring.snapshot().copyFrameInto(1, dst)
        assertEquals(5, dst[0].toInt())
        assertEquals(8, dst[3].toInt())
    }
}
