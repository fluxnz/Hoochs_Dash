package com.example.hoochwindgrassodb2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {
    @Test
    fun parsesRpm() {
        assertEquals(1726, ObdParser.rpm("41 0C 1A F8"))
    }

    @Test
    fun parsesRpmIgnoringSearchingNoise() {
        assertEquals(1726, ObdParser.rpm("SEARCHING... 41 0C 1A F8"))
    }

    @Test
    fun parsesSingleByteValues() {
        assertEquals(60, ObdParser.speedKmh("41 0D 3C"))
        assertEquals(50, ObdParser.coolantCelsius("41 05 5A"))
        assertEquals(100, ObdParser.throttlePercent("41 11 FF"))
    }

    @Test
    fun returnsNullWhenNoData() {
        assertNull(ObdParser.rpm("NO DATA"))
        assertNull(ObdParser.speedKmh(""))
    }

    @Test
    fun parsesVoltage() {
        assertEquals(12.4, ObdParser.voltage("12.4V")!!, 0.001)
        assertNull(ObdParser.voltage("?"))
    }

    @Test
    fun detectsSupportedPids() {
        assertTrue(ObdParser.hasSupportedPids("41 00 BE 3F A8 13"))
        assertFalse(ObdParser.hasSupportedPids("NO DATA"))
    }

    @Test
    fun classifiesFailures() {
        assertTrue(ObdParser.isLinkFailure("UNABLE TO CONNECT"))
        assertTrue(ObdParser.isLinkFailure("BUS INIT: ...ERROR"))
        assertFalse(ObdParser.isLinkFailure("NO DATA"))
        assertEquals("No data from the vehicle", ObdParser.describeFailure("NO DATA"))
        assertEquals("No reply from the vehicle", ObdParser.describeFailure(""))
    }
}
