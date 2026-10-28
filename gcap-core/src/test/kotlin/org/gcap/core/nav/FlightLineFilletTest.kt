package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class FlightLineFilletTest {

    /**
     * Join of two straight pieces meeting at (200,0): one running east, one running north.
     *
     * The corner vertex appears twice, which is what a real weld looks like: each fragment
     * carries its own copy of the point they share.
     */
    private fun rightAngle(): DoubleArray =
        doubleArrayOf(0.0, 0.0, 100.0, 0.0, 200.0, 0.0, 200.0, 0.0, 200.0, 100.0, 200.0, 200.0)

    private val boundary = intArrayOf(0, 3)

    private fun noTransects() = { _: Int -> false }
    private fun allTransects() = { _: Int -> true }

    @Test
    fun `a right-angle corner is rounded`() {
        val (out, rep) = FlightLineFillet.apply(rightAngle(), boundary, noTransects())
        assertEquals(1, rep.rounded)
        assertTrue(out.size / 2 > rightAngle().size / 2, "the arc adds vertices")
    }

    @Test
    fun `the rounded line starts and ends where the original did`() {
        val src = rightAngle()
        val (out, _) = FlightLineFillet.apply(src, boundary, noTransects())
        assertEquals(src[0], out[0], 1e-9)
        assertEquals(src[1], out[1], 1e-9)
        assertEquals(src[src.size - 2], out[out.size - 2], 1e-9)
        assertEquals(src[src.size - 1], out[out.size - 1], 1e-9)
    }

    @Test
    fun `a straight-through join is left alone`() {
        val straight = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 200.0, 0.0, 200.0, 0.0, 300.0, 0.0, 400.0, 0.0)
        val (out, rep) = FlightLineFillet.apply(straight, boundary, noTransects())
        assertEquals(0, rep.rounded)
        assertEquals(1, rep.tooShallow)
        assertEquals(straight.size, out.size, "an untouched line is unchanged")
    }

    @Test
    fun `a gentle bend under the angle limit is left alone`() {
        // about 170 degrees, i.e. a 10 degree bend
        val gentle = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 200.0, 0.0, 200.0, 0.0, 300.0, 17.4, 400.0, 34.9)
        val (out, rep) = FlightLineFillet.apply(gentle, boundary, noTransects())
        assertEquals(0, rep.rounded)
        assertEquals(1, rep.tooShallow)
        assertEquals(gentle.size, out.size)
    }

    @Test
    fun `a transect end is never rounded`() {
        val (out, rep) = FlightLineFillet.apply(rightAngle(), boundary, allTransects())
        assertEquals(0, rep.rounded)
        assertEquals(1, rep.transectEnd)
        assertEquals(rightAngle().size, out.size)
    }

    @Test
    fun `a corner with a transect on only one side is still left alone`() {
        // feature 0 is a transect, feature 1 is not
        val onlyFirst = { i: Int -> i == 0 }
        val (_, rep) = FlightLineFillet.apply(rightAngle(), boundary, onlyFirst)
        assertEquals(0, rep.rounded)
        assertEquals(1, rep.transectEnd)
    }

    @Test
    fun `a line with no boundaries is returned untouched`() {
        val src = rightAngle()
        val (out, rep) = FlightLineFillet.apply(src, intArrayOf(0), noTransects())
        assertEquals(src.size, out.size)
        assertEquals(0, rep.rounded)
    }

    @Test
    fun `a corner right at the end of the line is skipped for want of line`() {
        // the second boundary sits on the final vertex
        // the boundary is the last vertex, so there is no outgoing leg to measure
        val src = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 200.0, 0.0, 200.0, 0.0)
        val (_, rep) = FlightLineFillet.apply(src, intArrayOf(0, 3), noTransects())
        assertEquals(0, rep.rounded)
        assertEquals(1, rep.tooShort)
    }

    @Test
    fun `the arc bulges towards the inside of the turn`() {
        val src = rightAngle()
        val radius = 40.0
        val (out, _) = FlightLineFillet.apply(src, boundary, noTransects(), radiusM = radius)
        val vs = out.indices step 2
        // the sharp corner sat at (100,0) and a fillet cuts it away
        val hitsCorner = vs.any { abs(out[it] - 200.0) < 1e-6 && abs(out[it + 1]) < 1e-6 }
        assertTrue(!hitsCorner, "the sharp corner should have been cut away")
        // the arc meets the incoming leg one radius short of the corner, at (200-radius, 0)
        val hasTangent = vs.any { abs(out[it] - (200.0 - radius)) < 1e-6 && abs(out[it + 1]) < 1e-6 }
        assertTrue(hasTangent, "the curve should start at the tangent point 200-radius")
        // and it meets the outgoing leg at (200, radius)
        val hasTangent2 = vs.any { abs(out[it] - 200.0) < 1e-6 && abs(out[it + 1] - radius) < 1e-6 }
        assertTrue(hasTangent2, "the curve should end at the tangent point on the far leg")
        // cutting a corner makes the path shorter than the two straight legs
        val sharp = 400.0
        var len = 0.0
        var k = 0
        while (k + 3 < out.size) { len += Geodesy.distanceProjectedM(out[k], out[k + 1], out[k + 2], out[k + 3]); k += 2 }
        assertTrue(len < sharp, "a filleted corner should be shorter than the sharp one: $len")
    }

    @Test
    fun `radius controls how far the curve reaches`() {
        // the tangent point sits one radius back from the corner at (100,0)
        fun tangentX(r: Double): Double {
            val a = FlightLineFillet.apply(rightAngle(), boundary, noTransects(), radiusM = r).first
            val idx = (a.indices step 2).first { kotlin.math.hypot(a[it] - 200.0, a[it + 1]) < r + 1.0 }
            return a[idx]
        }
        assertTrue(tangentX(80.0) < tangentX(10.0), "a wider radius reaches further back")
    }

    @Test
    fun `a corner with short legs is rounded at a reduced radius`() {
        // the outgoing leg is only 50 m, so the radius has to shrink to fit rather than skip
        val stubby = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 200.0, 0.0, 200.0, 0.0, 200.0, 50.0)
        val (out, rep) = FlightLineFillet.apply(stubby, boundary, noTransects(), radiusM = 60.0)
        assertEquals(1, rep.rounded)
        // the tangent points must stay inside the two legs, so nothing doubles back
        val t2x = out.indices.step(2).last { kotlin.math.hypot(out[it] - 200.0, out[it + 1]) < 60.0 }
        assertTrue(kotlin.math.hypot(out[t2x] - 200.0, out[t2x + 1]) <= 50.0 + 1e-6)
    }

    @Test
    fun `a leg far too short to hold any curve is left alone`() {
        val tiny = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 200.0, 0.0, 200.0, 0.0, 200.0, 0.4)
        val (_, rep) = FlightLineFillet.apply(tiny, boundary, noTransects(), radiusM = 60.0)
        assertEquals(0, rep.rounded)
        assertEquals(1, rep.tooShort)
    }

    @Test
    fun `several corners on one line are all handled`() {
        // east, north, east - each joint carries its corner vertex twice
        val src = doubleArrayOf(
            0.0, 0.0, 100.0, 0.0,
            100.0, 0.0, 100.0, 100.0,
            100.0, 100.0, 200.0, 100.0,
        )
        val (out, rep) = FlightLineFillet.apply(src, intArrayOf(0, 2, 4), noTransects())
        assertEquals(2, rep.rounded)
        assertEquals(src[0], out[0], 1e-9)
        assertEquals(src[src.size - 2], out[out.size - 2], 1e-9)
    }
}