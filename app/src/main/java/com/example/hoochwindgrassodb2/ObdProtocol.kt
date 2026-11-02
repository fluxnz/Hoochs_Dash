package com.example.hoochwindgrassodb2

/**
 * An ELM327 protocol configuration. [commands] are sent after the adapter has been reset
 * (ATZ), so every protocol starts from a clean adapter state.
 */
data class ObdProtocol(
    val id: String,
    val name: String,
    val description: String,
    val commands: List<String>,
    val scannable: Boolean = true
)

object ObdProtocols {
    const val CUSTOM_ID = "custom"

    val AUTOMATIC = ObdProtocol(
        "auto", "Automatic", "Let the adapter detect the vehicle bus",
        listOf("ATSP0"), scannable = false
    )

    // Order matters: it is also the order used when scanning for a working protocol.
    val ALL: List<ObdProtocol> = listOf(
        AUTOMATIC,
        ObdProtocol(
            "can11-500", "ISO 15765-4 CAN (11 bit ID, 500 kbaud)",
            "Most cars from about 2008 onwards", listOf("ATSP6")
        ),
        ObdProtocol(
            "can29-500", "ISO 15765-4 CAN (29 bit ID, 500 kbaud)",
            "CAN with extended identifiers", listOf("ATSP7")
        ),
        ObdProtocol(
            "can11-250", "ISO 15765-4 CAN (11 bit ID, 250 kbaud)",
            "Some Asian and older CAN vehicles", listOf("ATSP8")
        ),
        ObdProtocol(
            "can29-250", "ISO 15765-4 CAN (29 bit ID, 250 kbaud)",
            "CAN with extended identifiers at 250 kbaud", listOf("ATSP9")
        ),
        ObdProtocol(
            "kwp-fast", "ISO 14230-4 KWP (fast init, 10.4 kbaud)",
            "Keyword Protocol 2000, fast initialisation", listOf("ATSP5")
        ),
        ObdProtocol(
            "kwp-5baud", "ISO 14230-4 KWP (5 baud init, 10.4 kbaud)",
            "Keyword Protocol 2000, slow initialisation", listOf("ATSP4")
        ),
        ObdProtocol(
            "kwp-5baud-96", "ISO 14230-4 KWP (5 baud init, 9.6 kbaud)",
            "Keyword Protocol 2000 at 9600 baud", listOf("ATSP4", "ATIB 96")
        ),
        ObdProtocol(
            "kwp-fast-96", "ISO 14230-4 KWP (fast init, 9.6 kbaud)",
            "Keyword Protocol 2000 fast init at 9600 baud", listOf("ATSP5", "ATIB 96")
        ),
        ObdProtocol(
            "kwp-kzn185", "ISO 14230-4 KWP (5 baud init 0x8113F1, 9.6 kbaud) KZN185",
            "Toyota KZN185 style: header 81 13 F1, init address 13",
            listOf("ATSP4", "ATIB 96", "ATSH 8113F1", "ATIIA 13")
        ),
        ObdProtocol(
            "iso9141", "ISO 9141-2 (5 baud init, 10.4 kbaud)",
            "Older European and Asian vehicles", listOf("ATSP3")
        ),
        ObdProtocol(
            "iso9141-96", "ISO 9141-2 (5 baud init, 9.6 kbaud)",
            "ISO 9141-2 at 9600 baud", listOf("ATSP3", "ATIB 96")
        ),
        ObdProtocol(
            "j1850-pwm", "SAE J1850 PWM (41.6 kbaud)",
            "Ford-style PWM bus", listOf("ATSP1")
        ),
        ObdProtocol(
            "j1850-vpw", "SAE J1850 VPW (10.4 kbaud)",
            "GM-style VPW bus", listOf("ATSP2")
        ),
        ObdProtocol(
            "j1939", "SAE J1939 CAN (29 bit ID, 250 kbaud)",
            "Heavy vehicles (not scanned automatically)", listOf("ATSPA"), scannable = false
        ),
        ObdProtocol(
            "user1", "USER1 CAN (11 bit ID, 125 kbaud)",
            "Adapter user protocol 1 (not scanned automatically)", listOf("ATSPB"),
            scannable = false
        ),
        ObdProtocol(
            "user2", "USER2 CAN (11 bit ID, 50 kbaud)",
            "Adapter user protocol 2 (not scanned automatically)", listOf("ATSPC"),
            scannable = false
        )
    )

    fun find(id: String?): ObdProtocol? = ALL.firstOrNull { it.id == id }

    fun parseCommands(text: String): List<String> =
        text.split(';', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    fun custom(name: String, commands: List<String>) = ObdProtocol(
        CUSTOM_ID, name, "User-defined: ${commands.joinToString("; ")}", commands,
        scannable = false
    )
}
