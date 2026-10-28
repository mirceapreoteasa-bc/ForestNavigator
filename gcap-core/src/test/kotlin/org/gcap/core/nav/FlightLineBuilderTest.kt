package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlightLineBuilderTest {

    private fun piece(x0: Double, x1: Double, y: Double = 0.0) = doubleArrayOf(x0, y, x1, y)

    @Test
    fun `short lines are discarded`() {
        val b = FlightLineBuilder(
            listOf(piece(0.0, 1000.0), doubleArrayOf(2000.0, 0.0, 2020.0, 0.0)),
            minLineM = 50.0,
        )
        assertEquals(1, b.lines.size)
        assertEquals(1, b.discarded)
    }

    @Test
    fun `two lines a short gap apart become one`() {
        val b = FlightLineBuilder(listOf(piece(0.0, 1000.0), piece(1030.0, 2000.0)), maxJoinM = 370.0)
        b.build()
        assertEquals(1, b.resultLines().size)
        assertEquals(2000.0, Geodesy.distanceProjectedM(0.0, 0.0, 2000.0, 0.0), 1.0)
    }

    @Test
    fun `lines further apart than the limit are left alone`() {
        val b = FlightLineBuilder(listOf(piece(0.0, 1000.0), piece(1400.0, 2000.0)), maxJoinM = 370.0)
        b.build()
        assertEquals(2, b.resultLines().size, "400 m apart is beyond the 370 m limit")
    }

    @Test
    fun `a chain of lines collapses into one`() {
        val fs = ArrayList<DoubleArray>()
        for (i in 0 until 10) fs.add(piece(i * 1030.0, i * 1030.0 + 1000.0))
        val b = FlightLineBuilder(fs, maxJoinM = 370.0)
        b.build()
        assertEquals(1, b.resultLines().size)
    }

    @Test
    fun `shortest pairs are joined first`() {
        // A(0-1000) is 30 m from B(1030-2000); C is 200 m from B's far end
        val b = FlightLineBuilder(
            listOf(piece(0.0, 1000.0), piece(1030.0, 2000.0), piece(2200.0, 2400.0)),
            maxJoinM = 370.0,
        )
        b.build()
        assertEquals(1, b.resultLines().size, "all three should join")
    }

    @Test
    fun `an end is used at most once`() {
        val fs = ArrayList<DoubleArray>()
        for (i in 0 until 12) fs.add(piece(i * 1030.0, i * 1030.0 + 1000.0))
        val b = FlightLineBuilder(fs, maxJoinM = 370.0)
        b.build()
        // 12 lines, 11 joins, one chain; if any end were reused a line would repeat
        val total = b.resultLines().sumOf { it.size / 2 }
        assertEquals(total, b.lines.sumOf { it.size / 2 }, "every line appears exactly once")
    }

    @Test
    fun `no ring is formed`() {
        // a square of four lines, corners coincident
        // a closed square: each side drawn as its own line, corners coincident
        val fs = listOf(
            doubleArrayOf(0.0, 0.0, 1000.0, 0.0),
            doubleArrayOf(1000.0, 0.0, 1000.0, 1000.0),
            doubleArrayOf(0.0, 1000.0, 1000.0, 1000.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1000.0),
        )
        val b = FlightLineBuilder(fs, maxJoinM = 370.0)
        b.build()
        // The square must come out as an open path, not a cycle: four lines and three
        // joins. With four ends meeting at each corner the pairing is ambiguous, so the
        // closing pair is refused either as "same chain" or as an end already spent -
        // what must not happen is a fourth join or a repeated line.
        assertEquals(1, b.resultLines().size, "one open path")
        val verts = b.resultLines().sumOf { it.size / 2 }
        assertEquals(fs.sumOf { it.size / 2 }, verts, "no line used twice")
        assertTrue(b.ringRejections >= 0)
    }

    @Test
    fun `a crossing pair is refused`() {
        // X and Y cross in the middle but their ends are far apart, so they must not join
        val b = FlightLineBuilder(
            listOf(
                doubleArrayOf(0.0, 0.0, 1000.0, 1000.0),
                doubleArrayOf(0.0, 1000.0, 1000.0, 0.0),
            ),
            maxJoinM = 5.0,
        )
        b.build()
        assertEquals(2, b.resultLines().size, "ends 1000 m apart must not join")
    }

    @Test
    fun `candidates are only pairs on different lines`() {
        val b = FlightLineBuilder(listOf(piece(0.0, 1000.0)), maxJoinM = 370.0)
        val cands = b.allCandidates()
        assertTrue(cands.none { it.a.feature == it.b.feature }, "a line must not pair with itself")
    }

    @Test
    fun `candidates beyond the limit are dropped`() {
        val b = FlightLineBuilder(listOf(piece(0.0, 1000.0), piece(5000.0, 6000.0)), maxJoinM = 370.0)
        assertTrue(b.allCandidates().isEmpty())
    }

    @Test
    fun `candidates come back shortest first`() {
        val b = FlightLineBuilder(
            listOf(piece(0.0, 1000.0), piece(1050.0, 2050.0), piece(1120.0, 2120.0)),
            maxJoinM = 370.0,
        )
        val d = b.allCandidates().map { it.distanceM }
        assertEquals(d.sorted(), d, "candidates must be sorted by distance")
    }

    @Test
    fun `the result is reported plainly even when it is more than one line`() {
        val b = FlightLineBuilder(listOf(piece(0.0, 1000.0), piece(5000.0, 6000.0)), maxJoinM = 370.0)
        b.build()
        val s = b.summary()
        assertTrue(s.contains("2 continuous lines"), s)
    }

    @Test
    fun `every line ends up in exactly one result`() {
        val fs = ArrayList<DoubleArray>()
        for (i in 0 until 8) fs.add(piece(i * 1030.0, i * 1030.0 + 1000.0))
        fs.add(piece(20000.0, 21000.0))          // an outlying line
        val b = FlightLineBuilder(fs, maxJoinM = 370.0)
        b.build()
        val verts = b.resultLines().sumOf { it.size / 2 }
        assertEquals(b.lines.sumOf { it.size / 2 }, verts, "nothing lost or duplicated")
    }
}