package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.BydSignalBatch.isErrorCode

/** Traction-battery values for the battery card. Units: see [BydBatteryDetails]. */
enum class BydBatteryField {
    SOC, SOH, RANGE, REMAINING_ENERGY, POWER, VOLTAGE, CURRENT,
    CELL_VOLTAGE_MAX, CELL_VOLTAGE_MIN, CELL_VOLTAGE_DIFF,
    TEMP_MAX, TEMP_MIN, TEMP_AVG,
    INSULATION, MAX_CHARGE_POWER, MAX_DISCHARGE_POWER, CHARGE_LIMIT, CHARGE_TIME_LEFT,
}

/**
 * One battery sample. SOC/SOH/CHARGE_LIMIT %, RANGE km, REMAINING_ENERGY kWh, POWER and the allowed
 * powers kW (positive POWER discharges, negative charges), VOLTAGE V, CURRENT A, cell voltages V,
 * CELL_VOLTAGE_DIFF mV, temperatures °C, INSULATION kΩ, CHARGE_TIME_LEFT minutes. Absent fields
 * could not be read or were out of range. [charging] is null when the BMS state is unknown.
 */
data class BydBatteryDetails(val values: Map<BydBatteryField, Double>, val charging: Boolean?) {
    operator fun get(field: BydBatteryField): Double? = values[field]
}

/**
 * Read-only battery addresses. SOC, range and the charging state are the verified ones DiPlay
 * already reports to the iPhone; the others, and their scaling, were inferred from one parked and
 * one driving sample on a Fangchengbao Tai 7 EV (Di150VCP) and stay plausibility-checked.
 */
internal object BydBatterySignals {
    private const val STATISTIC = 1014
    private const val CHARGING = 1009
    private const val GB = 1039

    val SOC = BydSignal(STATISTIC, 0x44400030)              // STATISTIC_SOC_BATTERY_PERCENTAGE, %
    val SOH = BydSignal(STATISTIC, 0x44400028)              // STATISTIC_BATTERY_HEALTHY_INDEX, %
    val RANGE = BydSignal(STATISTIC, 0x4A50203E)            // STATISTIC_ELEC_DRIVING_RANGE, km
    val REMAINING = BydSignal(STATISTIC, 0x44700028)        // STATISTIC_REMAINING_BATTERY_POWER, 0.1 kWh
    val VOLTAGE = BydSignal(CHARGING, 0x44400008)           // CHARGING_CHARGE_BATTERY_VOLT, V
    val CURRENT = BydSignal(GB, 0x28600018)                 // GB_BATTERY_CCURRENT, 0.1 A offset -3000 A
    val CELL_MAX = BydSignal(STATISTIC, 0x44600030)         // STATISTIC_HIGHEST_BATTERY_VOLTAGE, mV
    val CELL_MIN = BydSignal(STATISTIC, 0x44600010)         // STATISTIC_LOWEST_BATTERY_VOLTAGE, mV
    val TEMP_MAX = BydSignal(STATISTIC, 0x44700020)         // STATISTIC_HIGHEST_BATTERY_TEMP, °C + 40
    val TEMP_MIN = BydSignal(STATISTIC, 0x44700010)         // STATISTIC_LOWEST_BATTERY_TEMP, °C + 40
    val TEMP_AVG = BydSignal(STATISTIC, 0x44700038)         // STATISTIC_AVERAGE_BATTERY_TEMP, °C + 40
    val INSULATION = BydSignal(GB, 0x43A00018)              // GB_BMC_INSULATION_VALUE, kΩ
    val MAX_CHARGE = BydSignal(STATISTIC, 0x34500018)       // STATISTIC_MAX_CHARGE_POWER_ALLOW, 0.1 kW
    val MAX_DISCHARGE = BydSignal(STATISTIC, 0x34500008)    // STATISTIC_MAX_DISCHARGE_POWER_ALLOW, 0.1 kW
    val CHARGE_LIMIT = BydSignal(CHARGING, 0x22D02820)      // CHARGING_CHARGE_SOC_LIMIT, %
    val TIME_HOURS = BydSignal(CHARGING, 0x4A50C03D)        // CHARGING_PHASE_SOC_REMAINING_HOURS (31 = none)
    val TIME_MINUTES = BydSignal(CHARGING, 0x4A50C042)      // CHARGING_PHASE_SOC_REMAINING_MINUTES (63 = none)

    private val fixed = listOf(SOC, SOH, RANGE, REMAINING, VOLTAGE, CURRENT, CELL_MAX, CELL_MIN, TEMP_MAX, TEMP_MIN,
        TEMP_AVG, INSULATION, MAX_CHARGE, MAX_DISCHARGE, CHARGE_LIMIT, TIME_HOURS, TIME_MINUTES)

    /** Fallbacks for SOC and range: the protocol addresses DiPlay already uses for the iPhone. */
    private fun protocolSoc(protocol: BydBatteryProtocol) = BydSignal(STATISTIC, protocol.percentId, BydSignal.GET_FLOAT)
    private fun protocolRange(protocol: BydBatteryProtocol) = BydSignal(STATISTIC, protocol.rangeId)

    fun all(protocol: BydBatteryProtocol?): List<BydSignal> = (fixed + listOfNotNull(
        protocol?.let(::protocolSoc), protocol?.let(::protocolRange), protocol?.let(BydVehicleSignals::charging))).distinct()

    fun decode(values: Map<BydSignal, Int?>, protocol: BydBatteryProtocol?): BydBatteryDetails {
        fun raw(signal: BydSignal) = values[signal]?.takeUnless { it.isErrorCode() }
        fun int(signal: BydSignal, range: IntRange) = raw(signal)?.takeIf { it in range }
        val out = LinkedHashMap<BydBatteryField, Double>()
        fun put(field: BydBatteryField, value: Double?) { if (value != null && value.isFinite()) out[field] = value }

        val socFloat = protocol?.let { raw(protocolSoc(it)) }?.let { java.lang.Float.intBitsToFloat(it).toDouble() }
            ?.takeIf { it in 0.0..100.0 }
        put(BydBatteryField.SOC, int(SOC, 0..100)?.toDouble() ?: socFloat)
        put(BydBatteryField.SOH, int(SOH, 1..100)?.toDouble())
        put(BydBatteryField.RANGE, (int(RANGE, 0..3000) ?: protocol?.let { int(protocolRange(it), 0..3000) })?.toDouble())
        put(BydBatteryField.REMAINING_ENERGY, int(REMAINING, 0..3000)?.let { it / 10.0 })
        val voltage = int(VOLTAGE, 50..1200)?.toDouble()
        val current = int(CURRENT, 0..60000)?.let { it / 10.0 - 3000 }
        put(BydBatteryField.VOLTAGE, voltage)
        put(BydBatteryField.CURRENT, current)
        if (voltage != null && current != null) put(BydBatteryField.POWER, voltage * current / 1000)
        val cellMax = int(CELL_MAX, 1000..5000)
        val cellMin = int(CELL_MIN, 1000..5000)
        put(BydBatteryField.CELL_VOLTAGE_MAX, cellMax?.let { it / 1000.0 })
        put(BydBatteryField.CELL_VOLTAGE_MIN, cellMin?.let { it / 1000.0 })
        if (cellMax != null && cellMin != null && cellMax >= cellMin) put(BydBatteryField.CELL_VOLTAGE_DIFF, (cellMax - cellMin).toDouble())
        fun temperature(signal: BydSignal) = int(signal, 0..250)?.let { (it - 40).toDouble() }
        put(BydBatteryField.TEMP_MAX, temperature(TEMP_MAX))
        put(BydBatteryField.TEMP_MIN, temperature(TEMP_MIN))
        put(BydBatteryField.TEMP_AVG, temperature(TEMP_AVG))
        put(BydBatteryField.INSULATION, int(INSULATION, 0..65000)?.toDouble())
        put(BydBatteryField.MAX_CHARGE_POWER, int(MAX_CHARGE, 0..65000)?.let { it / 10.0 })
        put(BydBatteryField.MAX_DISCHARGE_POWER, int(MAX_DISCHARGE, 0..65000)?.let { it / 10.0 })
        put(BydBatteryField.CHARGE_LIMIT, int(CHARGE_LIMIT, 1..100)?.toDouble())
        val hours = int(TIME_HOURS, 0..24)
        val minutes = int(TIME_MINUTES, 0..59)
        if (hours != null && minutes != null) put(BydBatteryField.CHARGE_TIME_LEFT, (hours * 60 + minutes).toDouble())

        val charging = protocol?.let { raw(BydVehicleSignals.charging(it)) }?.let { it == 1 }
        return BydBatteryDetails(out, charging)
    }
}
