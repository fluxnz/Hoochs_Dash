package com.example.hoochwindgrassodb2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDiagnosticsTest {
    @Test
    fun decodesDtcLettersAndDigits() {
        assertEquals("P0133", ObdDiagnostics.decodeDtc(0x01, 0x33))
        assertEquals("C0300", ObdDiagnostics.decodeDtc(0x43, 0x00))
        assertEquals("B1234", ObdDiagnostics.decodeDtc(0x92, 0x34))
        assertEquals("U0100", ObdDiagnostics.decodeDtc(0xC1, 0x00))
    }

    @Test
    fun parsesCanSingleFrameWithCount() {
        assertEquals(listOf("P0133", "P0234"), ObdDiagnostics.parseDtcs("43 02 01 33 02 34\r", 3))
    }

    @Test
    fun parsesLegacyPaddedFrames() {
        val reply = "43 01 33 01 71 00 00\r43 00 00 00 00 00 00\r"
        assertEquals(listOf("P0133", "P0171"), ObdDiagnostics.parseDtcs(reply, 3))
    }

    @Test
    fun parsesMultiFrameCanReply() {
        val reply = "00A\r0: 43 04 01 33 01 71\r1: 03 00 03 01 00 00\r"
        assertEquals(listOf("P0133", "P0171", "P0300", "P0301"), ObdDiagnostics.parseDtcs(reply, 3))
    }

    @Test
    fun noCodesAndNoData() {
        assertTrue(ObdDiagnostics.parseDtcs("43 00\r", 3).isEmpty())
        assertTrue(ObdDiagnostics.parseDtcs("NO DATA\r", 7).isEmpty())
    }

    @Test
    fun parsesFreezeFrame() {
        assertEquals("P0301", ObdDiagnostics.freezeFrameDtc("42 02 00 03 01\r"))
        assertNull(ObdDiagnostics.freezeFrameDtc("42 02 00 00 00\r"))
        val rpm = ObdMetrics.find("010C")!!
        assertEquals("2000", ObdDiagnostics.freezeFrameValue(rpm, "42 0C 00 1F 40\r"))
        assertNull(ObdDiagnostics.freezeFrameValue(rpm, "NO DATA\r"))
    }

    @Test
    fun decodesMonitorStatus() {
        // MIL on, 2 codes; spark ignition; misfire ready, fuel not ready, components n/a.
        val status = ObdDiagnostics.monitorStatus("41 01 82 23 E5 A1\r", "0101")!!
        assertTrue(status.milOn)
        assertEquals(2, status.dtcCount)
        val byName = status.monitors.toMap()
        assertEquals(MonitorState.COMPLETE, byName["Misfire"])
        assertEquals(MonitorState.INCOMPLETE, byName["Fuel system"])
        assertEquals(MonitorState.UNAVAILABLE, byName["Components"])
        assertEquals(MonitorState.INCOMPLETE, byName["Catalyst"])
        assertEquals(MonitorState.COMPLETE, byName["Oxygen sensor heater"])
        assertEquals(MonitorState.UNAVAILABLE, byName["Secondary air"])
    }

    @Test
    fun decodesSupportedPidBitmap() {
        // BE1FA813: PIDs 01,03,04,05,06,07,0C,0D,0E,0F,10,11,13,15,1F,20
        val supported = ObdDiagnostics.supportedPids("41 00 BE 1F A8 13\r", 0x00)!!
        assertTrue(0x0C in supported && 0x0D in supported && 0x05 in supported)
        assertTrue(0x02 !in supported && 0x08 !in supported)
        assertTrue(0x20 in supported)
        val next = ObdDiagnostics.supportedPids("41 20 80 00 00 01\r", 0x20)!!
        assertEquals(setOf(0x21, 0x40), next)
        assertNull(ObdDiagnostics.supportedPids("NO DATA\r", 0x00))
    }

    @Test
    fun parsesVinFromCanAndLegacyReplies() {
        val can = "014\r0: 49 02 01 57 50 30\r1: 5A 5A 5A 39 39 5A 54\r2: 53 33 39 32 31 32 34\r"
        assertEquals("WP0ZZZ99ZTS392124", ObdDiagnostics.parseVin(can))
        val legacy = "49 02 01 00 00 00 57\r49 02 02 50 30 5A 5A\r49 02 03 5A 39 39 5A\r" +
            "49 02 04 54 53 33 39\r49 02 05 32 31 32 34\r"
        assertEquals("WP0ZZZ99ZTS392124", ObdDiagnostics.parseVin(legacy))
        assertNull(ObdDiagnostics.parseVin("NO DATA\r"))
    }
}
