package com.example.hoochwindgrassodb2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdMetricsTest {
    private fun metric(id: String) = ObdMetrics.find(id)!!

    @Test
    fun idsAreUnique() {
        assertEquals(ObdMetrics.ALL.size, ObdMetrics.ALL.map { it.id }.toSet().size)
        assertTrue(ObdMetrics.ALL.map { it.label }.toSet().size == ObdMetrics.ALL.size)
    }

    @Test
    fun defaultsAreTheOriginalFour() {
        assertEquals(
            listOf("010C", "010D", "0105", "0111"),
            ObdMetrics.parseIds(null).map { it.id }
        )
    }

    @Test
    fun parseIdsIgnoresUnknownDuplicatesAndExtras() {
        val ids = ObdMetrics.parseIds("0104, nope,0104,010F,015C,0133,0146").map { it.id }
        assertEquals(listOf("0104", "010F", "015C", "0133"), ids)
    }

    @Test
    fun serializeRoundTrips() {
        val metrics = listOf(metric("0142"), metric("ATRV"))
        assertEquals(metrics.map { it.id }, ObdMetrics.parseIds(ObdMetrics.serialize(metrics)).map { it.id })
    }

    @Test
    fun decodesValues() {
        assertEquals("1726", metric("010C").format(metric("010C").decode("41 0C 1A F8")))
        assertEquals("50", metric("0105").format(metric("0105").decode("41 05 5A")))
        assertEquals("0.0", metric("0106").format(metric("0106").decode("41 06 80")))
        assertEquals("13.81", metric("0142").format(metric("0142").decode("41 42 35 F4")))
    }

    @Test
    fun everyMetricHasASaneDialRange() {
        ObdMetrics.ALL.forEach {
            val (low, high) = ObdMetrics.range(it)
            assertTrue("${it.label} range", low < high)
        }
        assertEquals(0.0 to 8000.0, ObdMetrics.range(metric("010C")))
    }

    @Test
    fun gaugeStyleFallsBackToPlain() {
        assertEquals(GaugeStyle.PLAIN, GaugeStyle.fromName(null))
        assertEquals(GaugeStyle.PLAIN, GaugeStyle.fromName("nonsense"))
        assertEquals(GaugeStyle.CLUSTER, GaugeStyle.fromName("CLUSTER"))
    }

    @Test
    fun missingOrShortReplyIsNull() {
        assertNull(metric("010C").decode("NO DATA"))
        assertNull(metric("010C").decode("41 0C 1A"))
        assertNull(metric("010C").format(null))
    }
}
