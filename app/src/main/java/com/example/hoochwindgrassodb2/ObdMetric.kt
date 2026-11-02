package com.example.hoochwindgrassodb2

import java.util.Locale

/** A value that can be shown on the dashboard. [id] is the Mode 01 PID (e.g. "010C"). */
class ObdMetric(
    val id: String,
    val label: String,
    val unit: String,
    private val byteCount: Int,
    private val decimals: Int = 0,
    private val formula: (List<Int>) -> Double
) {
    val isAdapterVoltage: Boolean get() = id == ObdMetrics.ADAPTER_VOLTAGE_ID

    fun decode(response: String): Double? {
        val bytes = ObdParser.hexBytesAfter(response, "41" + id.substring(2)) ?: return null
        return if (bytes.size >= byteCount) formula(bytes) else null
    }

    fun format(value: Double?): String? = when {
        value == null -> null
        decimals == 0 -> Math.round(value).toString()
        else -> String.format(Locale.US, "%.${decimals}f", value)
    }
}

object ObdMetrics {
    const val ADAPTER_VOLTAGE_ID = "ATRV"
    const val MAX_SELECTED = 4
    val DEFAULT_IDS = listOf("010C", "010D", "0105", "0111")

    private fun word(b: List<Int>) = (b[0] * 256 + b[1]).toDouble()
    private fun percent(b: List<Int>) = b[0] * 100.0 / 255
    private fun temperature(b: List<Int>) = b[0] - 40.0
    private fun trim(b: List<Int>) = b[0] * 100.0 / 128 - 100

    val ALL: List<ObdMetric> = listOf(
        ObdMetric("010C", "Engine RPM", "rpm", 2) { word(it) / 4 },
        ObdMetric("010D", "Vehicle speed", "km/h", 1) { it[0].toDouble() },
        ObdMetric("0105", "Coolant temperature", "°C", 1) { temperature(it) },
        ObdMetric("0111", "Throttle position", "%", 1) { percent(it) },
        ObdMetric(ADAPTER_VOLTAGE_ID, "Battery voltage (adapter)", "V", 0, 1) { 0.0 },
        ObdMetric("0104", "Engine load", "%", 1) { percent(it) },
        ObdMetric("0143", "Absolute load", "%", 2) { word(it) * 100 / 255 },
        ObdMetric("010F", "Intake air temperature", "°C", 1) { temperature(it) },
        ObdMetric("010B", "Intake manifold pressure", "kPa", 1) { it[0].toDouble() },
        ObdMetric("0110", "Air flow rate (MAF)", "g/s", 2, 1) { word(it) / 100 },
        ObdMetric("010E", "Timing advance", "°", 1, 1) { it[0] / 2.0 - 64 },
        ObdMetric("0106", "Short fuel trim, bank 1", "%", 1, 1) { trim(it) },
        ObdMetric("0107", "Long fuel trim, bank 1", "%", 1, 1) { trim(it) },
        ObdMetric("0108", "Short fuel trim, bank 2", "%", 1, 1) { trim(it) },
        ObdMetric("0109", "Long fuel trim, bank 2", "%", 1, 1) { trim(it) },
        ObdMetric("010A", "Fuel pressure", "kPa", 1) { it[0] * 3.0 },
        ObdMetric("0122", "Fuel rail pressure (vacuum)", "kPa", 2, 1) { word(it) * 0.079 },
        ObdMetric("0123", "Fuel rail pressure (gauge)", "kPa", 2) { word(it) * 10 },
        ObdMetric("0159", "Fuel rail pressure (absolute)", "kPa", 2) { word(it) * 10 },
        ObdMetric("012F", "Fuel tank level", "%", 1) { percent(it) },
        ObdMetric("015E", "Engine fuel rate", "L/h", 2, 1) { word(it) / 20 },
        ObdMetric("0152", "Ethanol fuel", "%", 1) { percent(it) },
        ObdMetric("015D", "Fuel injection timing", "°", 2, 1) { word(it) / 128 - 210 },
        ObdMetric("0144", "Commanded air/fuel ratio (lambda)", "λ", 2, 3) { word(it) / 32768 },
        ObdMetric("0114", "O2 sensor B1S1 voltage", "V", 1, 2) { it[0] / 200.0 },
        ObdMetric("0115", "O2 sensor B1S2 voltage", "V", 1, 2) { it[0] / 200.0 },
        ObdMetric("0116", "O2 sensor B1S3 voltage", "V", 1, 2) { it[0] / 200.0 },
        ObdMetric("0117", "O2 sensor B1S4 voltage", "V", 1, 2) { it[0] / 200.0 },
        ObdMetric("0118", "O2 sensor B2S1 voltage", "V", 1, 2) { it[0] / 200.0 },
        ObdMetric("0119", "O2 sensor B2S2 voltage", "V", 1, 2) { it[0] / 200.0 },
        ObdMetric("011A", "O2 sensor B2S3 voltage", "V", 1, 2) { it[0] / 200.0 },
        ObdMetric("011B", "O2 sensor B2S4 voltage", "V", 1, 2) { it[0] / 200.0 },
        ObdMetric("013C", "Catalyst temp B1S1", "°C", 2, 1) { word(it) / 10 - 40 },
        ObdMetric("013D", "Catalyst temp B2S1", "°C", 2, 1) { word(it) / 10 - 40 },
        ObdMetric("013E", "Catalyst temp B1S2", "°C", 2, 1) { word(it) / 10 - 40 },
        ObdMetric("013F", "Catalyst temp B2S2", "°C", 2, 1) { word(it) / 10 - 40 },
        ObdMetric("012C", "Commanded EGR", "%", 1) { percent(it) },
        ObdMetric("012D", "EGR error", "%", 1, 1) { trim(it) },
        ObdMetric("012E", "Commanded evaporative purge", "%", 1) { percent(it) },
        ObdMetric("0153", "Evap system vapour pressure", "kPa", 2, 2) { word(it) / 200 },
        ObdMetric("0133", "Barometric pressure", "kPa", 1) { it[0].toDouble() },
        ObdMetric("0146", "Ambient air temperature", "°C", 1) { temperature(it) },
        ObdMetric("015C", "Engine oil temperature", "°C", 1) { temperature(it) },
        ObdMetric("0142", "Control module voltage", "V", 2, 2) { word(it) / 1000 },
        ObdMetric("0145", "Relative throttle position", "%", 1) { percent(it) },
        ObdMetric("0147", "Throttle position B", "%", 1) { percent(it) },
        ObdMetric("014C", "Commanded throttle actuator", "%", 1) { percent(it) },
        ObdMetric("0149", "Accelerator pedal position D", "%", 1) { percent(it) },
        ObdMetric("014A", "Accelerator pedal position E", "%", 1) { percent(it) },
        ObdMetric("015A", "Relative accelerator pedal position", "%", 1) { percent(it) },
        ObdMetric("015B", "Hybrid battery remaining life", "%", 1) { percent(it) },
        ObdMetric("0161", "Driver's demand torque", "%", 1) { it[0] - 125.0 },
        ObdMetric("0162", "Actual engine torque", "%", 1) { it[0] - 125.0 },
        ObdMetric("0163", "Engine reference torque", "Nm", 2) { word(it) },
        ObdMetric("011F", "Run time since engine start", "s", 2) { word(it) },
        ObdMetric("0130", "Warm-ups since codes cleared", "", 1) { it[0].toDouble() },
        ObdMetric("0131", "Distance since codes cleared", "km", 2) { word(it) },
        ObdMetric("0121", "Distance with warning light on", "km", 2) { word(it) },
        ObdMetric("014E", "Time since codes cleared", "min", 2) { word(it) },
        ObdMetric("014D", "Time with warning light on", "min", 2) { word(it) }
    )

    fun find(id: String): ObdMetric? = ALL.firstOrNull { it.id == id }

    /** Dial range (min, max) used by the gauge skins; values outside it just peg the dial. */
    fun range(metric: ObdMetric): Pair<Double, Double> = when {
        metric.id == "010C" -> 0.0 to 8000.0
        metric.id == "010D" -> 0.0 to 240.0
        metric.id == "010E" -> -64.0 to 64.0
        metric.id == "0110" -> 0.0 to 300.0
        metric.id == "010A" -> 0.0 to 765.0
        metric.id == "0123" || metric.id == "0159" -> 0.0 to 30000.0
        metric.id == "0163" -> 0.0 to 1000.0
        metric.id == "011F" -> 0.0 to 3600.0
        metric.id == "0144" -> 0.0 to 2.0
        metric.label.startsWith("O2 sensor") -> 0.0 to 1.3
        metric.label.contains("fuel trim") || metric.id == "012D" -> -25.0 to 25.0
        metric.id == "0161" || metric.id == "0162" -> -125.0 to 125.0
        metric.id == "015E" -> 0.0 to 50.0
        metric.id == "015D" -> -210.0 to 300.0
        metric.id == "0153" -> 0.0 to 300.0
        metric.id.startsWith("013C") || metric.id in listOf("013D", "013E", "013F") -> 0.0 to 1000.0
        else -> when (metric.unit) {
            "%" -> 0.0 to 100.0
            "°C" -> 0.0 to 150.0
            "V" -> 0.0 to 16.0
            "kPa" -> 0.0 to 255.0
            "°" -> 0.0 to 360.0
            else -> 0.0 to 1000.0
        }
    }

    private fun defaults(): List<ObdMetric> = DEFAULT_IDS.mapNotNull { find(it) }

    /** Parses a saved comma-separated id list, falling back to the defaults if nothing valid remains. */
    fun parseIds(saved: String?): List<ObdMetric> {
        val parsed = saved.orEmpty().split(',')
            .mapNotNull { find(it.trim()) }
            .distinct()
            .take(MAX_SELECTED)
        return parsed.ifEmpty { defaults() }
    }

    fun serialize(metrics: List<ObdMetric>): String = metrics.joinToString(",") { it.id }
}
