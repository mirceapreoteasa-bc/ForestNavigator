package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlightLineBuilderHealTest {

    /** A straight line of [n] vertices starting at [x0], stepping [step] east. */
    private fun line(x0: Double, n: Int, step: Double = 10.0) =
        DoubleArray(n * 2) { i -> if (i % 2 == 0) x0 + (i / 2) * step else 0.0 }

    @Test
    fun healingIsANoOpOnASingleLine() {
        val b = FlightLineBuilder(listOf(line(0.0, 10)), minLineM = 0.0, maxJoinM = 100.0).build()
        assertEquals(1, b.resultLines().size)
        val h = b.heal()
        assertEquals(1, h.linesBefore)
        assertEquals(1, h.linesAfter)
        assertEquals(0, h.joins)
    }

    @Test
    fun healingJoinsLinesTheFirstRoundLeftApart() {
        // p and q are the closest pair, so round 1 spends them on each other and r is
        // left over: exactly the situation healing exists for
        val p = line(0.0, 3)              // 0..20
        val q = line(30.0, 3)             // 30..50, 10 m from p
        val r = line(200.0, 3)            // 200..220, 150 m from q's end
        val b = FlightLineBuilder(listOf(p, q, r), minLineM = 0.0, maxJoinM = 40.0).build()
        assertEquals(2, b.resultLines().size, "round 1 should join only p and q")
        val pass = b.heal(maxJoinM = 200.0)
        assertEquals(2, pass.linesBefore)
        assertEquals(1, pass.linesAfter)
        assertEquals(1, pass.joins)
    }

    @Test
    fun healingChainsThreeLinesOverTwoRounds() {
        // a chain of near pairs: round 1 fuses a..b and c..d, healing closes the rest
        val segs = listOf(
            line(0.0, 3),      // 0..20
            line(30.0, 3),     // 30..50    10 m gap
            line(500.0, 3),    // 500..520
            line(530.0, 3),    // 530..550  10 m gap, 480 m from the first pair
        )
        val b = FlightLineBuilder(segs, minLineM = 0.0, maxJoinM = 40.0).build()
        assertEquals(2, b.resultLines().size)
        val pass = b.heal(maxJoinM = 600.0)
        assertEquals(2, pass.linesBefore)
        assertEquals(1, pass.linesAfter, "healing should join the two chains")
    }

    @Test
    fun healingIsIdempotentOnceOneLineRemains() {
        val segs = listOf(line(0.0, 3), line(50.0, 3))
        val b = FlightLineBuilder(segs, minLineM = 0.0, maxJoinM = 100.0).build()
        b.heal()
        val again = b.heal()
        assertEquals(1, again.linesAfter, "a single line cannot be healed further")
    }

    @Test
    fun healingRespectsTheDistanceLimit() {
        // 500 m apart, but the healing round is only allowed 100 m
        val a = line(0.0, 3)
        val b = line(600.0, 3)
        val pass = FlightLineBuilder(listOf(a, b), minLineM = 0.0, maxJoinM = 100.0).build()
            .heal(maxJoinM = 100.0)
        assertEquals(2, pass.linesAfter, "ends beyond the limit must stay apart")
        assertEquals(0, pass.joins)
    }

    @Test
    fun healingHonoursALargerDistanceLimitThanTheFirstRound() {
        val a = line(0.0, 3)      // 0..20
        val b = line(200.0, 3)    // 200..220
        val pass = FlightLineBuilder(listOf(a, b), minLineM = 0.0, maxJoinM = 50.0).build()
            .heal(maxJoinM = 300.0)
        assertEquals(1, pass.linesAfter)
    }

    @Test
    fun healingConservesEveryVertex() {
        val segs = listOf(line(0.0, 3), line(30.0, 3), line(500.0, 3), line(530.0, 3))
        val b = FlightLineBuilder(segs, minLineM = 0.0, maxJoinM = 40.0).build()
        val before = b.resultLines().sumOf { it.size / 2 }
        val pass = b.heal(maxJoinM = 600.0)
        val after = b.healedLines!!.sumOf { it.size / 2 }
        assertEquals(before, after, "healing must not add or lose vertices")
        assertEquals(1, pass.linesAfter)
    }

    @Test
    fun healingReportsWhatItRefused() {
        val far = line(600.0, 3)
        val builder = FlightLineBuilder(listOf(line(0.0, 3), far), minLineM = 0.0, maxJoinM = 100.0).build()
        val pass = builder.heal()
        assertTrue(pass.describe().contains("2 lines -> 2 lines"))
        assertNotNull(builder.healedLines)
    }
}