package uk.co.tfd.nmeabridge.nmea

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the BLE Battery State frame from characteristic 0xAA03.
 * Frame is little-endian, magic 0xBB, variable length depending on n_cells / n_ntc.
 * See doc/ble-transport.md and lib/jdbbms/README.md for the full specification.
 */
object BmsProtocol {

    private const val MAGIC: Byte = 0xBB.toByte()
    private const val MIN_HEADER_SIZE = 16

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
}
