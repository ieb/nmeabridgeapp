package uk.co.tfd.nmeabridge.nmea

// NMEA 2000 reserves the top of each unsigned range (or the top positive
// stretch for signed) for non-data sentinels:
//   0xFFFF / 0x7FFF — not available
//   0xFFFE / 0x7FFE — out of range / error
//   0xFFFD / 0x7FFD — reserved
// Firmware that clips an unrepresentable source value (e.g. -1E9 from a
// disconnected N2K depth transducer) produces 0xFFFE. Treat the whole
// reserved band as no-data so the UI shows "—" instead of 655.3 m.

internal const val RESERVED_U16_MIN = 0xFFFD
internal const val RESERVED_U32_MIN = 0xFFFFFFFDL
internal const val RESERVED_S16_MIN = 0x7FFD
internal const val RESERVED_S32_MIN = 0x7FFFFFFD

internal fun u16OrNull(raw: Int): Int? =
    if (raw >= RESERVED_U16_MIN) null else raw

internal fun u32OrNull(raw: Int): Long? {
    val u = raw.toLong() and 0xFFFFFFFFL
    return if (u >= RESERVED_U32_MIN) null else u
}

internal fun s16OrNull(raw: Short): Int? =
    if (raw.toInt() >= RESERVED_S16_MIN) null else raw.toInt()

internal fun s32OrNull(raw: Int): Int? =
    if (raw >= RESERVED_S32_MIN) null else raw
