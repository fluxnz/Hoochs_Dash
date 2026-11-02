package com.example.hoochwindgrassodb2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdProtocolsTest {
    @Test
    fun idsAreUnique() {
        assertEquals(ObdProtocols.ALL.size, ObdProtocols.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun everyProtocolSelectsABus() {
        assertTrue(ObdProtocols.ALL.all { p -> p.commands.any { it.startsWith("ATSP") } })
    }

    @Test
    fun includesKzn185() {
        val protocol = ObdProtocols.ALL.first { it.name.endsWith("KZN185") }
        assertTrue(protocol.name.contains("0x8113F1"))
        assertTrue(protocol.commands.contains("ATSH 8113F1"))
        assertTrue(protocol.commands.contains("ATIB 96"))
        assertNotNull(ObdProtocols.find(protocol.id))
    }

    @Test
    fun parsesCustomCommands() {
        assertEquals(listOf("ATSP4", "ATIB 96"), ObdProtocols.parseCommands(" atsp4; atib 96;\n"))
    }
}
