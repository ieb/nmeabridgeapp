package uk.co.tfd.nmeabridge.nmea

import uk.co.tfd.nmeabridge.history.RingSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the BLE Battery State frame from characteristic 0xAA03.
 * Frame is little-endian, magic 0xBB, variable length depending on n_cells / n_ntc.
 * See doc/ble-transport.md and lib/jdbbms/README.md for the full specification.
 */
object BmsProtocol {

    internal const val MAGIC: Byte = 0xBB.toByte()
    private const val MIN_HEADER_SIZE = 16

    // --- History slot: fixed worst-case stride --------------------------
    //
    // Variable-length BMS frames don't fit a fixed-stride ring, so we
    // canonicalise to a 48-byte slot that preserves the full wire header
    // (matching offsets 0..15) and reserves space for up to MAX_CELLS cells
    // and MAX_NTCS temperatures. Unused cell / NTC slots are filled with
    // 0xFFFF (NMEA N/A sentinel) so accessors return null for them.
    //
    // If a peripheral ever publishes more cells / NTCs than reserved we
    // truncate (one warning log per session is left to the caller).

    internal const val HISTORY_SLOT_SIZE = 48
    internal const val MAX_CELLS = 8
    internal const val MAX_NTCS = 7

    // Header field offsets — identical to the wire header (bytes 0..15).
    internal const val OFF_MAGIC = 0
    internal const val OFF_PACK_V = 1         // U16, 0.01 V
    internal const val OFF_CURRENT_A = 3      // S16, 0.01 A
    internal const val OFF_REMAINING_AH = 5   // U16, 0.01 Ah
    internal const val OFF_FULL_AH = 7        // U16, 0.01 Ah
    internal const val OFF_SOC = 9            // U8, 1 %
    internal const val OFF_CYCLES = 10        // U16
    internal const val OFF_ERRORS = 12        // U16 bitmask
    internal const val OFF_FET = 14           // U8 bit0=charge, bit1=discharge
    internal const val OFF_N_CELLS = 15       // U8 (<= MAX_CELLS)
    internal const val OFF_CELLS = 16         // MAX_CELLS × U16, 0.001 V
    internal const val OFF_N_NTCS = 32        // U8 (<= MAX_NTCS)
    internal const val OFF_NTCS = 33          // MAX_NTCS × U16, 0.1 K
    // byte 47 reserved.

    fun decode(data: ByteArray): BatteryState? {
        if (data.size < MIN_HEADER_SIZE) return null
        if (data[0] != MAGIC) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)

        val packV = (buf.short.toInt() and 0xFFFF) * 0.01
        val currentA = buf.short.toInt() * 0.01  // signed
        val remainingAh = (buf.short.toInt() and 0xFFFF) * 0.01
        val fullAh = (buf.short.toInt() and 0xFFFF) * 0.01
        val soc = buf.get().toInt() and 0xFF
        val cycles = buf.short.toInt() and 0xFFFF
        val errors = buf.short.toInt() and 0xFFFF
        val fetStatus = buf.get().toInt() and 0xFF
        val nCells = buf.get().toInt() and 0xFF

        val cellBytes = nCells * 2
        if (buf.remaining() < cellBytes + 1) return null

        val cells = ArrayList<Double>(nCells)
        repeat(nCells) {
            cells += (buf.short.toInt() and 0xFFFF) * 0.001
        }

        val nNtc = buf.get().toInt() and 0xFF
        if (buf.remaining() < nNtc * 2) return null

        val temps = ArrayList<Double>(nNtc)
        repeat(nNtc) {
            val k10 = buf.short.toInt() and 0xFFFF
            temps += k10 * 0.1 - 273.15
        }

        return BatteryState(
            packV = packV,
            currentA = currentA,
            remainingAh = remainingAh,
            fullAh = fullAh,
            soc = soc,
            cycles = cycles,
            cellVoltagesV = cells,
            tempsC = temps,
            chargeFet = (fetStatus and 0x01) != 0,
            dischargeFet = (fetStatus and 0x02) != 0,
            alarms = BmsAlarm.decode(errors)
        )
    }

    /**
     * Canonicalise a variable-length wire frame into a fixed 48 B history
     * slot. Header bytes 0-15 copy through unchanged. Cells and NTCs are
     * placed in their reserved bands and padded with 0xFFFF. Returns null
     * if the input frame is too short to decode its header.
     */
    fun encodeHistorySlot(data: ByteArray): ByteArray? {
        if (data.size < MIN_HEADER_SIZE) return null
        if (data[0] != MAGIC) return null

        val slot = ByteArray(HISTORY_SLOT_SIZE)
        // Copy header verbatim (bytes 0..15 including n_cells).
        System.arraycopy(data, 0, slot, 0, MIN_HEADER_SIZE)

        val wireNCells = data[OFF_N_CELLS].toInt() and 0xFF
        val nCells = minOf(wireNCells, MAX_CELLS)
        // Truncate the n_cells field to what we actually stored so decoders
        // don't over-read.
        slot[OFF_N_CELLS] = nCells.toByte()

        // Fill cell slots.
        val cellsEnd = MIN_HEADER_SIZE + nCells * 2
        if (cellsEnd <= data.size) {
            System.arraycopy(data, MIN_HEADER_SIZE, slot, OFF_CELLS, nCells * 2)
        }
        // Pad unused cells with 0xFFFF.
        for (b in OFF_CELLS + nCells * 2 until OFF_N_NTCS) {
            slot[b] = 0xFF.toByte()
        }

        // Locate n_ntc in the wire frame: it follows nCells * 2 cell bytes.
        val wireNNtcOffset = MIN_HEADER_SIZE + wireNCells * 2
        val wireNNtc = if (wireNNtcOffset < data.size) data[wireNNtcOffset].toInt() and 0xFF else 0
        val nNtc = minOf(wireNNtc, MAX_NTCS)
        slot[OFF_N_NTCS] = nNtc.toByte()

        val wireNtcStart = wireNNtcOffset + 1
        val ntcEnd = wireNtcStart + nNtc * 2
        if (ntcEnd <= data.size) {
            System.arraycopy(data, wireNtcStart, slot, OFF_NTCS, nNtc * 2)
        }
        // Pad unused NTC slots.
        for (b in OFF_NTCS + nNtc * 2 until HISTORY_SLOT_SIZE - 1) {
            slot[b] = 0xFF.toByte()
        }
        // byte 47 reserved (left zero).

        return slot
    }

    // --- Per-field accessors over a history RingSnapshot -----------------
    //
    // These read the 48 B history slot produced by encodeHistorySlot().
    // Nullable return types: the gap-filler ticker (see ServerViewModel)
    // appends a sentinel slot every second when no BLE frame arrived in
    // that window, so reserved-band values are observable here.

    fun packVAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_PACK_V))?.let { it * 0.01 }

    fun currentAAt(s: RingSnapshot, i: Int): Double? =
        s16OrNull(s.readS16(i, OFF_CURRENT_A))?.let { it * 0.01 }

    fun remainingAhAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_REMAINING_AH))?.let { it * 0.01 }

    fun fullAhAt(s: RingSnapshot, i: Int): Double? =
        u16OrNull(s.readU16(i, OFF_FULL_AH))?.let { it * 0.01 }

    fun socAt(s: RingSnapshot, i: Int): Int? {
        val raw = s.readU8(i, OFF_SOC)
        return if (raw == 0xFF) null else raw
    }

    fun cyclesAt(s: RingSnapshot, i: Int): Int? =
        u16OrNull(s.readU16(i, OFF_CYCLES))

    /**
     * 48 B sentinel slot used by the gap-filler ticker when no BMS frame
     * arrived. Each field encoded with the "not available" value that its
     * accessor recognises: 0xFFFF for U16 / U16-unsigned readings, 0x7FFF
     * for S16 (currentA), 0xFF for the SoC byte, 0 for the count fields
     * (otherwise a 0xFF would decode as 255 cells).
     */
    internal val SENTINEL_SLOT: ByteArray = run {
        val buf = java.nio.ByteBuffer.allocate(HISTORY_SLOT_SIZE)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putShort(0xFFFF.toShort())  // packV u16
        buf.putShort(0x7FFF)            // currentA s16 — signed N/A
        buf.putShort(0xFFFF.toShort())  // remainingAh u16
        buf.putShort(0xFFFF.toShort())  // fullAh u16
        buf.put(0xFF.toByte())          // soc u8 — sentinel
        buf.putShort(0xFFFF.toShort())  // cycles u16
        buf.putShort(0xFFFF.toShort())  // errors u16
        buf.put(0xFF.toByte())          // fet (no accessor publishes nullable)
        buf.put(0)                      // n_cells = 0 (no cells encoded)
        // cells band (16..31) left zero; n_cells=0 makes it irrelevant.
        buf.position(OFF_N_NTCS)
        buf.put(0)                      // n_ntc = 0
        // ntc band (33..46) and reserved byte 47 left zero.
        buf.array()
    }
}
