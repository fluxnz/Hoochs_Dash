package com.example.hoochwindgrassodb2

/** Pure parsing helpers for ELM327 / OBD-II text responses. */
object ObdParser {
    private val WHITESPACE = Regex("\\s+")
    private val HEX_TOKEN = Regex("^[0-9A-Fa-f]{2,}$")
    private val VOLTAGE = Regex("(\\d{1,2}\\.\\d+)")

    // Keeping only hex-looking tokens ignores noise such as "SEARCHING..." or "BUS INIT: OK".
    fun hexBytesAfter(response: String, marker: String): List<Int>? {
        val clean = response.split(WHITESPACE)
            .filter { HEX_TOKEN.matches(it) }
            .joinToString("")
            .uppercase()
        val key = marker.replace(" ", "").uppercase()
        val start = clean.indexOf(key)
        if (start < 0) return null
        return clean.substring(start + key.length)
            .chunked(2)
            .filter { it.length == 2 }
            .map { it.toInt(16) }
    }

    fun rpm(response: String): Int? {
        val bytes = hexBytesAfter(response, "410C") ?: return null
        return if (bytes.size >= 2) ((bytes[0] * 256) + bytes[1]) / 4 else null
    }

    fun speedKmh(response: String): Int? = hexBytesAfter(response, "410D")?.firstOrNull()

    fun coolantCelsius(response: String): Int? =
        hexBytesAfter(response, "4105")?.firstOrNull()?.let { it - 40 }

    fun throttlePercent(response: String): Int? =
        hexBytesAfter(response, "4111")?.firstOrNull()?.let { it * 100 / 255 }

    fun voltage(response: String): Double? =
        VOLTAGE.find(response)?.groupValues?.get(1)?.toDoubleOrNull()

    fun hasSupportedPids(response: String): Boolean =
        (hexBytesAfter(response, "4100")?.size ?: 0) >= 4

    // True for replies that mean the adapter could not talk to the vehicle at all.
    fun isLinkFailure(response: String): Boolean {
        val text = response.uppercase()
        return "UNABLE TO CONNECT" in text ||
            ("BUS INIT" in text && "ERROR" in text) ||
            "CAN ERROR" in text ||
            "BUS ERROR" in text ||
            "BUS BUSY" in text
    }

    fun describeFailure(response: String): String {
        val text = response.uppercase().trim()
        return when {
            text.isEmpty() -> "No reply from the vehicle"
            "UNABLE TO CONNECT" in text -> "Unable to connect to the vehicle (is the ignition on?)"
            "BUS INIT" in text && "ERROR" in text -> "Bus initialisation failed"
            "CAN ERROR" in text -> "CAN bus error"
            "BUS BUSY" in text -> "Bus busy"
            "BUS ERROR" in text -> "Bus error"
            "NO DATA" in text -> "No data from the vehicle"
            "LV RESET" in text -> "Adapter reset (low voltage)"
            text == "?" -> "The adapter did not understand the command"
            "ERROR" in text -> "The adapter reported an error"
            else -> "Unrecognised reply: ${response.trim().take(60)}"
        }
    }
}
