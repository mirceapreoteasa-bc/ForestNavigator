package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlightPathRepairTest {

    /** A straight long feature from (x0,0) to (x1,0). */
    private fun piece(x0: Double, x1: Double) = doubleArrayOf(x0, 0.0, x1, 0.0)

    @Test
    fun `two lines whose ends are within the weld distance become one`() {
        val r = FlightPathRepair(listOf(piece(0.0, 1000.0), piece(1010.0, 2000.0)), weldM = 30.0)
        assertEquals(1, r.repaired.size, "a 10 m gap should weld")
        assertEquals(2, r.repaired[0].featureIds.size, "two features joined")
        assertEquals(2000.0, r.repaired[0].lengthM(), 1.0, "joined into one 2 km line")
    }

    @Test
    fun `lines further apart than the weld are left alone`() {
        val r = FlightPathRepair(listOf(piece(0.0, 1000.0), piece(1100.0, 2000.0)), weldM = 30.0)
        assertEquals(2, r.repaired.size, "a 100 m gap must not weld at 30 m")
        assertEquals(2, r.unjoinedLines, "both lines are left on their own")
    }

    @Test
    fun `a chain of many lines collapses into one`() {
        val fs = ArrayList<DoubleArray>()
        for (i in 0 until 20) fs.add(piece(i * 1005.0, i * 1005.0 + 1000.0))
        val r = FlightPathRepair(fs, weldM = 30.0)
        assertEquals(1, r.repaired.size)
        assertEquals(20, r.repaired[0].featureIds.size)
        assertEquals(19, r.repaired[0].joinCount)
    }

    @Test
    fun `vertices run in order along the joined line`() {
        // second feature is drawn right-to-left, so it must be reversed when appended
        val r = FlightPathRepair(listOf(piece(0.0, 1000.0), piece(2000.0, 1010.0)), weldM = 30.0)
        assertEquals(1, r.repaired.size)
        val pts = r.repaired[0].points
        var mono = true
        var i = 0
        while (i + 3 < pts.size) {
            if (pts[i + 2] < pts[i]) mono = false
            i += 2
        }
        assertTrue(mono, "easting must increase monotonically along the joined line")
        assertEquals(0.0, pts[0], 0.01)
        assertEquals(2000.0, pts[pts.size - 2], 0.01)
    }

    @Test
    fun `short features are discarded so they cannot make joins ambiguous`() {
        val fs = listOf(
            piece(0.0, 1000.0),
            doubleArrayOf(1010.0, 0.0, 1015.0, 0.0),      // 5 m stub
            piece(1015.0, 2000.0),
        )
        val r = FlightPathRepair(fs, weldM = 30.0, minFeatureM = 200.0)
        assertEquals(1, r.discarded, "the stub is dropped")
        assertEquals(2, r.kept.size)
        assertEquals(1, r.repaired.size, "the two long lines still weld")
    }

    @Test
    fun `no feature is used twice`() {
        val fs = ArrayList<DoubleArray>()
        for (i in 0 until 12) fs.add(piece(i * 1005.0, i * 1005.0 + 1000.0))
        val r = FlightPathRepair(fs, weldM = 30.0)
        val all = r.repaired.flatMap { it.featureIds }
        assertEquals(all.size, all.distinct().size, "a feature was welded into two lines")
    }

    @Test
    fun `each repaired line has exactly two free ends to connect onwards`() {
        val fs = ArrayList<DoubleArray>()
        for (i in 0 until 6) fs.add(piece(i * 1005.0, i * 1005.0 + 1000.0))
        val r = FlightPathRepair(fs, weldM = 30.0)
        // a plain chain uses n-1 joins, leaving 2 free ends; here one line results
        assertEquals(1, r.repaired.size)
        assertEquals(6, r.repaired[0].featureIds.size)
    }

    @Test
    fun `a ring is detected rather than welded into an odd shape`() {
        // four lines forming a square, each end 5 m from the next
        val f = listOf(
            doubleArrayOf(0.0, 0.0, 1000.0, 0.0),
            doubleArrayOf(1000.0, 0.0, 1000.0, 1000.0),
            doubleArrayOf(1000.0, 1000.0, 0.0, 1000.0),
            doubleArrayOf(0.0, 1000.0, 0.0, 0.0),
        )
        val r = FlightPathRepair(f, weldM = 30.0)
        assertEquals(1, r.repaired.size)
        assertTrue(r.repaired[0].closed, "a square should close into a ring")
    }

    @Test
    fun `an ambiguous end is reported`() {
        // three lines all with an end in the same 30 m spot
        val f = listOf(
            doubleArrayOf(0.0, 0.0, 1000.0, 0.0),
            doubleArrayOf(1010.0, 10.0, 2000.0, 0.0),
            doubleArrayOf(1005.0, -10.0, 2000.0, 20.0),
        )
        val r = FlightPathRepair(f, weldM = 30.0)
        assertTrue(r.ambiguous.isNotEmpty(), "a choice between neighbours must be reported")
        assertTrue(r.summary().contains("ambiguous"), r.summary())
    }

    @Test
    fun `the summary states what the repair did`() {
        val r = FlightPathRepair(listOf(piece(0.0, 1000.0), piece(1010.0, 2000.0)), weldM = 30.0)
        val s = r.summary()
        assertTrue(s.contains("2 long features"), s)
        assertTrue(s.contains("1 lines") || s.contains("into 1 "), s)
    }

    @Test
    fun `nothing in means nothing out`() {
        val r = FlightPathRepair(emptyList(), weldM = 30.0)
        assertEquals(0, r.repaired.size)
        assertTrue(r.summary().contains("0 long features"), r.summary())
    }

    @Test
    fun `a single unjoined line is passed through unchanged`() {
        val r = FlightPathRepair(listOf(piece(0.0, 5000.0)), weldM = 30.0)
        assertEquals(1, r.repaired.size)
        assertEquals(1, r.repaired[0].featureIds.size)
        assertFalse(r.repaired[0].closed)
        assertEquals(5000.0, r.repaired[0].lengthM(), 1.0)
    }
}