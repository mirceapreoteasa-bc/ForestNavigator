package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

class TransitTransitionTest {

    private val east = doubleArrayOf(1.0, 0.0)
    private val north = doubleArrayOf(0.0, 1.0)

    /** Bearing of the first segment of a flat vertex array. */
    private fun firstBearing(a: DoubleArray) =
        TransitTransition.direction(a, 0, 2)

    /** Bearing of the last segment of a flat vertex array. */
    private fun lastBearing(a: DoubleArray) =
        TransitTransition.direction(a, a.size - 4, a.size - 2)

    private fun dot(u: DoubleArray, v: DoubleArray) = u[0] * v[0] + u[1] * v[1]

    @Test
    fun `the curve starts and ends exactly where it was asked to`() {
        val p0 = doubleArrayOf(0.0, 0.0)
        val p1 = doubleArrayOf(300.0, 200.0)
        val c = TransitTransition.curve(p0, east, p1, north)
        assertEquals(0.0, c[0], 1e-9)
        assertEquals(0.0, c[1], 1e-9)
        assertEquals(300.0, c[c.size - 2], 1e-9)
        assertEquals(200.0, c[c.size - 1], 1e-9)
    }

    @Test
    fun `the curve leaves along the first line's bearing`() {
        val c = TransitTransition.curve(doubleArrayOf(0.0, 0.0), east, doubleArrayOf(300.0, 200.0), north)
        assertEquals(1.0, dot(firstBearing(c), east), 1e-6, "no corner where the curve leaves")
    }

    @Test
    fun `the curve arrives along the second line's bearing`() {
        val c = TransitTransition.curve(doubleArrayOf(0.0, 0.0), east, doubleArrayOf(300.0, 200.0), north)
        assertEquals(1.0, dot(lastBearing(c), north), 1e-6, "no corner where the curve arrives")
    }

    @Test
    fun `the bearing is held for the requested lead-in`() {
        val lead = 10.0
        val c = TransitTransition.curve(doubleArrayOf(0.0, 0.0), east, doubleArrayOf(300.0, 200.0), north, leadInM = lead)
        // p0 then the lead-in point: the second vertex is one lead along the bearing
        assertEquals(lead, c[2], 1e-6)
        assertEquals(0.0, c[3], 1e-6)
    }

    @Test
    fun `a straight-line connection stays straight`() {
        // leaving east and arriving east: nothing to turn through
        val c = TransitTransition.curve(doubleArrayOf(0.0, 0.0), east, doubleArrayOf(300.0, 0.0), east)
        for (i in c.indices step 2) {
            assertTrue(abs(c[i + 1]) < 1e-6, "should not bow off a straight line, got y=${c[i + 1]}")
        }
    }

    @Test
    fun `the curve never strays absurdly far from the straight connection`() {
        val p0 = doubleArrayOf(0.0, 0.0)
        val p1 = doubleArrayOf(400.0, 300.0)
        val c = TransitTransition.curve(p0, east, p1, north)
        for (i in c.indices step 2) {
            val d = Geodesy.pointSegmentDistance(c[i], c[i + 1], p0[0], p0[1], p1[0], p1[1])[0]
            assertTrue(d < 150.0, "point ${d} m off the straight connection")
        }
    }

    @Test
    fun `no two consecutive vertices coincide`() {
        val c = TransitTransition.curve(doubleArrayOf(0.0, 0.0), east, doubleArrayOf(50.0, 50.0), north)
        var k = 2
        while (k + 3 < c.size) {
            val d = hypot(c[k] - c[k + 2], c[k + 1] - c[k + 3])
            assertTrue(d > 1e-6, "duplicate vertex at $k")
            k += 2
        }
    }

    @Test
    fun `a zero-length connection still produces a usable curve`() {
        val p = doubleArrayOf(10.0, 10.0)
        val c = TransitTransition.curve(p, east, p, north)
        assertTrue(c.size >= 4)
        assertEquals(p[0], c[0], 1e-9)
        assertEquals(p[0], c[c.size - 2], 1e-9)
    }

    @Test
    fun `a short connection does not overshoot`() {
        val c = TransitTransition.curve(doubleArrayOf(0.0, 0.0), east, doubleArrayOf(4.0, 4.0), north)
        for (i in c.indices step 2) {
            assertTrue(c[i] >= -1.0 && c[i] <= 6.0, "x=${c[i]} out of range")
            assertTrue(c[i + 1] >= -1.0 && c[i + 1] <= 6.0, "y=${c[i + 1]} out of range")
        }
    }

    @Test
    fun `bearings read off a line point the way travel does`() {
        val line = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 20.0, 0.0)
        assertEquals(1.0, dot(TransitTransition.outBearingAtEnd(line), east), 1e-9)
        assertEquals(1.0, dot(TransitTransition.inBearingAtStart(line), east), 1e-9)
        val up = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 0.0, 20.0)
        assertEquals(1.0, dot(TransitTransition.outBearingAtEnd(up), north), 1e-9)
    }
}