package uk.co.tfd.nmeabridge.nmea

enum class BmsAlarm(val label: String) {
    CELL_OVERVOLT("Cell overvolt"),
    CELL_UNDERVOLT("Cell undervolt"),
    PACK_OVERVOLT("Pack overvolt"),
    PACK_UNDERVOLT("Pack undervolt"),
    CHARGE_OVERTEMP("Charge overtemp"),
    CHARGE_UNDERTEMP("Charge undertemp"),
    DISCHARGE_OVERTEMP("Discharge overtemp"),
    DISCHARGE_UNDERTEMP("Discharge undertemp"),
    CHARGE_OVERCURRENT("Charge overcurrent"),
    DISCHARGE_OVERCURRENT("Discharge overcurrent"),
    SHORT_CIRCUIT("Short circuit"),
    FRONTEND_IC_ERROR("Frontend IC error"),
    FET_LOCKED("FET locked by config");

    companion object {
        fun decode(bitmap: Int): List<BmsAlarm> {
            val out = mutableListOf<BmsAlarm>()
            val b = bitmap and 0xFFFF
            if ((b and 0x0001) != 0) out += CELL_OVERVOLT
            if ((b and 0x0002) != 0) out += CELL_UNDERVOLT
            if ((b and 0x0004) != 0) out += PACK_OVERVOLT
            if ((b and 0x0008) != 0) out += PACK_UNDERVOLT
            if ((b and 0x0010) != 0) out += CHARGE_OVERTEMP
            if ((b and 0x0020) != 0) out += CHARGE_UNDERTEMP
            if ((b and 0x0040) != 0) out += DISCHARGE_OVERTEMP
            if ((b and 0x0080) != 0) out += DISCHARGE_UNDERTEMP
            if ((b and 0x0100) != 0) out += CHARGE_OVERCURRENT
            if ((b and 0x0200) != 0) out += DISCHARGE_OVERCURRENT
            if ((b and 0x0400) != 0) out += SHORT_CIRCUIT
            if ((b and 0x0800) != 0) out += FRONTEND_IC_ERROR
            if ((b and 0x1000) != 0) out += FET_LOCKED
            return out
        }
    }
}

data class BatteryState(
    val packV: Double,
    val currentA: Double,
    val remainingAh: Double,
    val fullAh: Double,
    val soc: Int,
    val cycles: Int,
    val cellVoltagesV: List<Double>,
    val tempsC: List<Double>,
    val chargeFet: Boolean,
    val dischargeFet: Boolean,
    val alarms: List<BmsAlarm>
)
