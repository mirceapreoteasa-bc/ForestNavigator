package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlightLineSequenceTest {

    @Test
    fun `a call projects onto the nearest line`() {
        val line = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val seq = FlightLineSequence(listOf(line), listOf(AirCall("001A", 500.0, 30.0)))
        assertEquals(1, seq.feet.size)
        val f = seq.feet[0]
        assertEquals(0, f.lineIndex)
        assertEquals(500.0, f.alongM, 0.01)
        assertEquals(30.0, f.offsetM, 0.01)
    }

    @Test
    fun `bare calls are not part of the sequence`() {
        val line = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val seq = FlightLineSequence(
            listOf(line),
            listOf(AirCall("001A", 100.0, 0.0), AirCall("001", 150.0, 0.0), AirCall("001B", 200.0, 0.0)),
        )
        assertEquals(2, seq.route.size)
    }

    @Test
    fun `calls running forwards along the line are recognised`() {
        val line = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val seq = FlightLineSequence(
            listOf(line),
            listOf(
                AirCall("001A", 100.0, 0.0),
                AirCall("001B", 300.0, 0.0),
                AirCall("002A", 600.0, 0.0),
                AirCall("002B", 900.0, 0.0),
            ),
        )
        val c = seq.check().first()
        assertTrue(c.forward)
        assertEquals(1.0, c.agreement, 0.001)
        assertEquals(4, c.callCount)
    }

    @Test
    fun `a line built from the wrong end still counts as in order`() {
        // same line, but the aircraft travelled towards decreasing alongM
        val line = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val seq = FlightLineSequence(
            listOf(line),
            listOf(
                AirCall("001A", 900.0, 0.0),
                AirCall("001B", 700.0, 0.0),
                AirCall("002A", 400.0, 0.0),
                AirCall("002B", 100.0, 0.0),
            ),
        )
        val c = seq.check().first()
        // the line has no inherent direction, so a correctly ordered but reversed line
        // scores full agreement with forward=false
        assertEquals(1.0, c.agreement, 0.001)
        assertTrue(!c.forward, "these calls run against the line's own direction")
    }

    @Test
    fun `scrambled calls are reported as out of order`() {
        val line = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val seq = FlightLineSequence(
            listOf(line),
            listOf(
                AirCall("001A", 100.0, 0.0),
                AirCall("001B", 900.0, 0.0),
                AirCall("002A", 200.0, 0.0),
                AirCall("002B", 800.0, 0.0),
            ),
        )
        val c = seq.check().first()
        assertTrue(c.agreement < 0.75, "scrambled calls should not look ordered, was ${c.agreement}")
    }

    @Test
    fun `a call far from every line is not matched`() {
        val line = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val seq = FlightLineSequence(
            listOf(line), listOf(AirCall("001A", 500.0, 5000.0)), maxOffsetM = 200.0,
        )
        assertEquals(0, seq.feet.size)
    }

    @Test
    fun `calls spread over several lines are grouped`() {
        val a = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val b = doubleArrayOf(0.0, 5000.0, 1000.0, 5000.0)
        val seq = FlightLineSequence(
            listOf(a, b),
            listOf(
                AirCall("001A", 100.0, 0.0),
                AirCall("001B", 900.0, 0.0),
                AirCall("002A", 100.0, 5000.0),
                AirCall("002B", 900.0, 5000.0),
            ),
        )
        val checks = seq.check()
        assertEquals(2, checks.size)
        assertTrue(checks.all { it.callCount == 2 })
    }

    @Test
    fun `the summary reports coverage and how many lines are in order`() {
        val line = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val seq = FlightLineSequence(
            listOf(line),
            listOf(AirCall("001A", 100.0, 0.0), AirCall("001B", 900.0, 0.0)),
        )
        val s = seq.summary()
        assertTrue(s.contains("2 of 2 calls projected"), s)
        assertTrue(s.contains("in flying order"), s)
    }
}