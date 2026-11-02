package com.example.hoochwindgrassodb2

enum class MonitorState { UNAVAILABLE, COMPLETE, INCOMPLETE }

class MonitorStatus(val milOn: Boolean, val dtcCount: Int, val monitors: List<Pair<String, MonitorState>>)

/** Pure parsing helpers for fault codes, freeze frame, monitor status and VIN replies. */
object ObdDiagnostics {
    const val MODE_STORED = 0x03
    const val MODE_PENDING = 0x07
    const val MODE_PERMANENT = 0x0A

    /** Mode 01 PIDs worth showing from the freeze frame, when the ECU recorded them. */
    val FREEZE_FRAME_IDS = listOf("0104", "0105", "0106", "0107", "010B", "010C", "010D", "010E", "010F", "0111")

    private val DATA_LINE = Regex("^(?:([0-9A-F]):)?\\s*((?:[0-9A-F]{2}\\s*)+)$")

    /** Splits an ELM327 reply into messages of bytes. Multi-line CAN frames ("0:", "1:") are joined. */
    fun messages(reply: String): List<List<Int>> {
        val result = mutableListOf<MutableList<Int>>()
        for (raw in reply.split('\r', '\n')) {
            val line = raw.trim().uppercase()
            if (line.isEmpty()) continue
            val match = DATA_LINE.matchEntire(line) ?: continue
            val bytes = match.groupValues[2].replace(" ", "").chunked(2).map { it.toInt(16) }
            val index = match.groupValues[1]
            if (index.isNotEmpty() && index != "0" && result.isNotEmpty()) result.last().addAll(bytes)
            else result.add(bytes.toMutableList())
        }
        return result
    }

    fun parseDtcs(reply: String, mode: Int): List<String> {
        val marker = mode + 0x40
        val codes = linkedSetOf<String>()
        for (message in messages(reply)) {
            if (message.firstOrNull() != marker) continue
            var rest = message.drop(1)
            // CAN replies carry a count byte, which makes the remainder an odd length.
            if (rest.size % 2 == 1) rest = rest.drop(1)
            rest.chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
                .forEach { codes.add(decodeDtc(it[0], it[1])) }
        }
        return codes.toList()
    }

    fun decodeDtc(high: Int, low: Int): String {
        val letter = "PCBU"[high shr 6]
        val digit = (high shr 4) and 0x3
        return String.format("%c%d%X%02X", letter, digit, high and 0xF, low)
    }

    fun category(code: String): String = when (code.firstOrNull()) {
        'P' -> "Powertrain"
        'C' -> "Chassis"
        'B' -> "Body"
        'U' -> "Network"
        else -> ""
    }

    fun describe(code: String): String? = DESCRIPTIONS[code.uppercase()]

    /** Returns the data bytes of a Mode 02 reply for [pid] (e.g. "0C"), or null. */
    fun freezeFrameBytes(reply: String, pid: String): List<Int>? {
        val pidByte = pid.toInt(16)
        for (message in messages(reply)) {
            if (message.size >= 3 && message[0] == 0x42 && message[1] == pidByte) return message.drop(3)
        }
        return null
    }

    fun freezeFrameDtc(reply: String): String? {
        val bytes = freezeFrameBytes(reply, "02") ?: return null
        if (bytes.size < 2 || (bytes[0] == 0 && bytes[1] == 0)) return null
        return decodeDtc(bytes[0], bytes[1])
    }

    /** Decodes a freeze-frame reply using the matching live-data metric. */
    fun freezeFrameValue(metric: ObdMetric, reply: String): String? {
        val bytes = freezeFrameBytes(reply, metric.id.substring(2)) ?: return null
        val asLive = "41" + metric.id.substring(2) + bytes.joinToString("") { String.format("%02X", it) }
        return metric.format(metric.decode(asLive))
    }

    /** [pid] is "0101" (since DTCs cleared) or "0141" (this drive cycle). */
    fun monitorStatus(reply: String, pid: String): MonitorStatus? {
        val bytes = ObdParser.hexBytesAfter(reply, "41" + pid.substring(2)) ?: return null
        if (bytes.size < 4) return null
        val (a, b, c, d) = bytes
        val compression = b and 0x08 != 0
        val monitors = mutableListOf<Pair<String, MonitorState>>()
        fun add(name: String, available: Boolean, incomplete: Boolean) {
            monitors.add(
                name to when {
                    !available -> MonitorState.UNAVAILABLE
                    incomplete -> MonitorState.INCOMPLETE
                    else -> MonitorState.COMPLETE
                }
            )
        }
        add("Misfire", b and 0x01 != 0, b and 0x10 != 0)
        add("Fuel system", b and 0x02 != 0, b and 0x20 != 0)
        add("Components", b and 0x04 != 0, b and 0x40 != 0)
        val names = if (compression) COMPRESSION_MONITORS else SPARK_MONITORS
        names.forEachIndexed { bit, name ->
            if (name != null) add(name, c shr bit and 1 != 0, d shr bit and 1 != 0)
        }
        return MonitorStatus(a and 0x80 != 0, a and 0x7F, monitors)
    }

    /** Mode 01 supported-PID bitmap reply for the block starting at [base] (0x00, 0x20, ...). */
    fun supportedPids(reply: String, base: Int): Set<Int>? {
        val bytes = ObdParser.hexBytesAfter(reply, String.format("41%02X", base)) ?: return null
        if (bytes.size < 4) return null
        val mask = bytes.take(4).fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        return (0 until 32).filter { mask shr (31 - it) and 1L != 0L }.map { base + it + 1 }.toSet()
    }

    /** Mode 09 PID 02. Handles both CAN (multi-frame) and older per-line replies. */
    fun parseVin(reply: String): String? {
        val chars = StringBuilder()
        for (message in messages(reply)) {
            if (message.size < 3 || message[0] != 0x49 || message[1] != 0x02) continue
            message.drop(3).forEach { if (it in 0x30..0x39 || it in 0x41..0x5A) chars.append(it.toChar()) }
        }
        return if (chars.length >= 17) chars.substring(chars.length - 17) else null
    }

    private val SPARK_MONITORS = listOf(
        "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air",
        "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system"
    )
    private val COMPRESSION_MONITORS = listOf(
        "NMHC catalyst", "NOx / SCR", null, "Boost pressure", null, "Exhaust gas sensor", "PM filter", "EGR / VVT"
    )

    private val DESCRIPTIONS = mapOf(
        "P0100" to "Mass air flow circuit malfunction",
        "P0101" to "Mass air flow range/performance",
        "P0105" to "Manifold pressure circuit malfunction",
        "P0110" to "Intake air temperature circuit malfunction",
        "P0115" to "Coolant temperature circuit malfunction",
        "P0116" to "Coolant temperature range/performance",
        "P0117" to "Coolant temperature circuit low",
        "P0118" to "Coolant temperature circuit high",
        "P0120" to "Throttle position sensor circuit malfunction",
        "P0125" to "Insufficient coolant temperature for closed loop",
        "P0130" to "O2 sensor circuit malfunction (bank 1 sensor 1)",
        "P0133" to "O2 sensor slow response (bank 1 sensor 1)",
        "P0135" to "O2 sensor heater circuit (bank 1 sensor 1)",
        "P0136" to "O2 sensor circuit malfunction (bank 1 sensor 2)",
        "P0141" to "O2 sensor heater circuit (bank 1 sensor 2)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0174" to "System too lean (bank 2)",
        "P0175" to "System too rich (bank 2)",
        "P0300" to "Random/multiple cylinder misfire detected",
        "P0301" to "Cylinder 1 misfire detected",
        "P0302" to "Cylinder 2 misfire detected",
        "P0303" to "Cylinder 3 misfire detected",
        "P0304" to "Cylinder 4 misfire detected",
        "P0305" to "Cylinder 5 misfire detected",
        "P0306" to "Cylinder 6 misfire detected",
        "P0325" to "Knock sensor circuit malfunction",
        "P0335" to "Crankshaft position sensor circuit malfunction",
        "P0340" to "Camshaft position sensor circuit malfunction",
        "P0400" to "EGR flow malfunction",
        "P0401" to "EGR flow insufficient",
        "P0420" to "Catalyst efficiency below threshold (bank 1)",
        "P0430" to "Catalyst efficiency below threshold (bank 2)",
        "P0440" to "Evaporative emission system malfunction",
        "P0442" to "Evaporative system small leak",
        "P0446" to "Evaporative vent control circuit",
        "P0455" to "Evaporative system large leak",
        "P0500" to "Vehicle speed sensor malfunction",
        "P0505" to "Idle control system malfunction",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0600" to "Serial communication link malfunction",
        "P0700" to "Transmission control system malfunction"
    )
}
