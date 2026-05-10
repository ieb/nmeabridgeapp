package uk.co.tfd.nmeabridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.tfd.nmeabridge.nmea.BinaryProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for [BinaryProtocol.isCorruptAutopilotStitch]. Real nav frames
 * must read as not-corrupt; the canonical bug pattern observed in
 * `nav-2026-05-09.bin` (slots 22583, 22584, 22679, 22801) must read as
 * corrupt; the all-NA sentinel frame must not be misclassified.
 */
class CorruptAutopilotStitchTest {

    @Test
    fun realFrame_notDetectedAsCorrupt() {
        // A typical UK-area nav frame from the boat's logs.
        val buf = ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0xCC.toByte())
        buf.putInt(518_406_281)        // lat 51.84° N
        buf.putInt(9_988_259)          // lon 0.998° E
        buf.putShort(0xFFFF.toShort()) // cog NA
        buf.putShort(6.toShort())      // sog 0.06 m/s
        buf.putShort(197.toShort())    // variation 1.13°
        buf.putShort(53521.toShort())  // heading 306.66°
        buf.putShort(550.toShort())    // depth 5.5 m
        buf.putShort(17275.toShort())  // awa 98.95°
        buf.putShort(488.toShort())    // aws 4.88 m/s
        buf.putShort(0.toShort())      // stw 0
        buf.putInt(6_924_628)          // log 3739 nm
        assertFalse(BinaryProtocol.isCorruptAutopilotStitch(buf.array()))
    }

    @Test
    fun knownCorruptFrame_22583_detected() {
        // Verbatim bytes from the analysis in /tmp/nmea_archive — the
        // first observed corrupt-stitch frame on the boat's history.
        val hex = "CC 77 26 79 00 00 00 00 AA 00 CB 77 26 79 00 00 00 00 AA 00 CA 77 26 79 00 00 00 00 AA"
        val frame = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(BinaryProtocol.isCorruptAutopilotStitch(frame))
    }

    @Test
    fun knownCorruptFrame_22584_detected() {
        // Variant where the heading-low byte stayed 0xCC for several
        // consecutive autopilot messages.
        val hex = "CC 77 26 79 00 00 00 00 AA 00 CC 77 26 79 00 00 00 00 AA 00 CC 77 26 79 00 00 00 00 AA"
        val frame = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(BinaryProtocol.isCorruptAutopilotStitch(frame))
    }

    @Test
    fun knownCorruptFrame_22801_detected() {
        // Variant captured later when boat was on a different heading
        // (316.92°) but still produced the stitch on the heading-LSB
        // crossing.
        val hex = "CC 7B 26 79 00 00 00 00 AA 00 CA 7B 26 79 00 00 00 00 AA 00 C8 7B 26 79 00 00 00 00 AA"
        val frame = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(BinaryProtocol.isCorruptAutopilotStitch(frame))
    }

    @Test
    fun knownCorruptFrame_targetLowByteVariant_detected() {
        // From nav-2026-05-09.bin slot 36793 (10:13:13 UTC).
        // The slow-path accumulator latched onto a 0xCC at autopilot
        // offset 4 (target-heading low byte) instead of offset 2 (the
        // current-heading low byte). Result: AA at frame offsets
        // 6 / 16 / 26 instead of 8 / 18 / 28. The pre-fix detector
        // missed this and the synthetic frame leaked through the
        // History screen as depth = 537.60 m, stw = 0.23 kn.
        val hex = "CC 05 E9 0C 00 00 AA 00 CF 05 CF 05 E9 0C 00 00 AA 00 D2 05 D0 05 E9 0C 00 00 AA 00 D2"
        val frame = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(BinaryProtocol.isCorruptAutopilotStitch(frame))
    }

    @Test
    fun synthetic_windLowByteVariant_detected() {
        // 0xCC at autopilot offset 6 (wind low byte). AA bytes land at
        // frame offsets 4, 14, 24.
        // Build msg N partial (CC at autopilot[6], then [7][8][9]):
        //   wind_hi, 0x00, 0x00, then 4 full msgs.
        // We don't have a concrete wild capture of this variant; the
        // test verifies the detector handles all four plausible offsets.
        val frame = ByteArray(29)
        frame[0] = 0xCC.toByte()                  // wind_lo (latched magic)
        frame[1] = 0x12                            // wind_hi
        frame[2] = 0x00                            // res
        frame[3] = 0x00                            // res
        // msg N+1: AA 02 hdg_lo hdg_hi tgt_lo tgt_hi wind_lo wind_hi 00 00
        frame[4] = 0xAA.toByte()                   // AA
        frame[5] = 0x02                            // mode = WIND_AWA (within 0..3)
        frame[6] = 0x55; frame[7] = 0x10           // hdg
        frame[8] = 0x66; frame[9] = 0x10           // tgt
        frame[10] = 0x77; frame[11] = 0x12         // wind
        frame[12] = 0x00; frame[13] = 0x00         // res
        // msg N+2
        frame[14] = 0xAA.toByte()
        frame[15] = 0x02
        frame[16] = 0x55; frame[17] = 0x10
        frame[18] = 0x66; frame[19] = 0x10
        frame[20] = 0x77; frame[21] = 0x12
        frame[22] = 0x00; frame[23] = 0x00
        // msg N+3 first 5 bytes
        frame[24] = 0xAA.toByte()
        frame[25] = 0x02
        frame[26] = 0x55; frame[27] = 0x10
        frame[28] = 0x66
        assertTrue(BinaryProtocol.isCorruptAutopilotStitch(frame))
    }

    @Test
    fun synthetic_windHighByteVariant_detected() {
        // 0xCC at autopilot offset 7 (wind high byte). AA bytes land at
        // frame offsets 3, 13, 23.
        val frame = ByteArray(29)
        frame[0] = 0xCC.toByte()                   // wind_hi (latched magic)
        frame[1] = 0x00                            // res
        frame[2] = 0x00                            // res
        frame[3] = 0xAA.toByte()                   // AA
        frame[4] = 0x00                            // mode
        frame[5] = 0x55; frame[6] = 0x10           // hdg
        frame[7] = 0x66; frame[8] = 0x10           // tgt
        frame[9] = 0x77; frame[10] = 0xCC.toByte() // wind (high byte CC again is fine)
        frame[11] = 0x00; frame[12] = 0x00         // res
        frame[13] = 0xAA.toByte()
        frame[14] = 0x00
        frame[15] = 0x55; frame[16] = 0x10
        frame[17] = 0x66; frame[18] = 0x10
        frame[19] = 0x77; frame[20] = 0xCC.toByte()
        frame[21] = 0x00; frame[22] = 0x00
        frame[23] = 0xAA.toByte()
        frame[24] = 0x00
        frame[25] = 0x55; frame[26] = 0x10
        frame[27] = 0x66; frame[28] = 0x10
        assertTrue(BinaryProtocol.isCorruptAutopilotStitch(frame))
    }

    @Test
    fun sentinelFrame_notDetectedAsCorrupt() {
        // The gap-filler all-NA sentinel must not be misclassified;
        // its byte 8 is 0x7F (lat S32 NA top byte), not 0xAA.
        val sentinel = ByteArray(29)
        // Recreate the layout used by BinaryProtocol.SENTINEL_FRAME.
        val buf = ByteBuffer.wrap(sentinel).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0xCC.toByte())
        buf.putInt(0x7FFFFFFF)
        buf.putInt(0x7FFFFFFF)
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0x7FFF)
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putShort(0xFFFF.toShort())
        buf.putInt(-1)
        assertFalse(BinaryProtocol.isCorruptAutopilotStitch(sentinel))
    }

    @Test
    fun wrongMagic_returnsFalse() {
        // A 29-byte buffer that happens to have the AA / 00 pattern at
        // offsets 8/9/18/19/28 but doesn't start with 0xCC isn't even
        // a candidate nav frame.
        val frame = ByteArray(29)
        frame[0] = 0xDD.toByte()  // engine magic, not nav
        frame[8] = 0xAA.toByte()
        frame[18] = 0xAA.toByte()
        frame[28] = 0xAA.toByte()
        assertFalse(BinaryProtocol.isCorruptAutopilotStitch(frame))
    }

    @Test
    fun wrongSize_returnsFalse() {
        assertFalse(BinaryProtocol.isCorruptAutopilotStitch(ByteArray(28)))
        assertFalse(BinaryProtocol.isCorruptAutopilotStitch(ByteArray(30)))
        assertFalse(BinaryProtocol.isCorruptAutopilotStitch(ByteArray(0)))
    }
}
