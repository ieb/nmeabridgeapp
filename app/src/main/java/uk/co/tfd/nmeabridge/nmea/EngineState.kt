package uk.co.tfd.nmeabridge.nmea

enum class EngineAlarmSeverity { WARNING, ALARM }

enum class EngineAlarm(
    val label: String,
    val shortCode: String,
    val severity: EngineAlarmSeverity
) {
    CHECK_ENGINE("Check engine", "CHK", EngineAlarmSeverity.ALARM),
    OVER_TEMPERATURE("Over temperature", "TEMP", EngineAlarmSeverity.ALARM),
    LOW_OIL_PRESSURE("Low oil pressure", "OIL", EngineAlarmSeverity.ALARM),
    LOW_SYSTEM_VOLTAGE("Low system voltage", "VOLT", EngineAlarmSeverity.ALARM),
    WATER_FLOW("Water flow", "WATR", EngineAlarmSeverity.WARNING),
    CHARGE_INDICATOR("Charge indicator", "ALT", EngineAlarmSeverity.ALARM),
    EMERGENCY_STOP("Emergency stop", "ESTP", EngineAlarmSeverity.ALARM),
    MAINTENANCE_NEEDED("Maintenance needed", "SVC", EngineAlarmSeverity.WARNING),
    ENGINE_COMM_ERROR("Engine comm error", "COM", EngineAlarmSeverity.WARNING),
    ENGINE_SHUTTING_DOWN("Engine shutting down", "HALT", EngineAlarmSeverity.ALARM);

    companion object {
        fun decode(status1: Int, status2: Int): List<EngineAlarm> {
            val out = mutableListOf<EngineAlarm>()
            val s1 = status1 and 0xFFFF
            val s2 = status2 and 0xFFFF
            if ((s1 and 0x0001) != 0) out += CHECK_ENGINE
            if ((s1 and 0x0002) != 0) out += OVER_TEMPERATURE
            if ((s1 and 0x0004) != 0) out += LOW_OIL_PRESSURE
            if ((s1 and 0x0020) != 0) out += LOW_SYSTEM_VOLTAGE
            if ((s1 and 0x0080) != 0) out += WATER_FLOW
            if ((s1 and 0x0200) != 0) out += CHARGE_INDICATOR
            if ((s1 and 0x8000) != 0) out += EMERGENCY_STOP
            if ((s2 and 0x0008) != 0) out += MAINTENANCE_NEEDED
            if ((s2 and 0x0010) != 0) out += ENGINE_COMM_ERROR
            if ((s2 and 0x0080) != 0) out += ENGINE_SHUTTING_DOWN
            return out
        }
    }
}

data class EngineState(
    val rpm: Int? = null,
    val engineHoursSec: Long? = null,
    val coolantC: Double? = null,
    val alternatorC: Double? = null,
    val alternatorV: Double? = null,
    val oilBar: Double? = null,
    val exhaustC: Double? = null,
    val engineRoomC: Double? = null,
    val engineBattV: Double? = null,
    val fuelPct: Double? = null,
    // null when the firmware's status words are both unavailable (0xFFFF).
    // An empty list means status was reported but no alarms are active.
    val alarms: List<EngineAlarm>? = null
)
