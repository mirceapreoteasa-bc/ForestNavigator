package org.gcap.core.prep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Healing is measured before it is applied, so these tests are mostly about what the
 * measurement *says* - because a tolerance that looks reasonable and quietly fuses two
 * separate roads is the failure that matters.
 */
class LineHealingTest {

    private fun line(vararg pts: DoubleArray) = LineHealing.Line(listOf(pts.toList()))
    private fun pt(x: Double, y: Double) = doubleArrayOf(x, y)

    private fun segment(x1: Double, y1: Double, x2: Double, y2: Double) =
        line(pt(x1, y1), pt(x2, y2))

    // ---- planning before writing -------------------------------------------

    @Test
    fun nothingIsWrittenByPlanning() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0), segment(100.5, 0.0, 200.0, 0.0))
        val before = lines
        LineHealing.plan(lines, 2.0)
        assertEquals(2, before.size, "plan must not touch the input")
        assertEquals(100.5, before[1].parts[0][0][0], 1e-9)
    }

    @Test
    fun aGapInsideToleranceIsCounted() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0), segment(100.5, 0.0, 200.0, 0.0))
        val p = LineHealing.plan(lines, 2.0)
        assertEquals(1, p.junctionCandidates)
        assertEquals(1, p.crossFeatureWelds)
    }

    @Test
    fun aGapOutsideToleranceIsNotCounted() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0), segment(103.0, 0.0, 200.0, 0.0))
        assertEquals(0, LineHealing.plan(lines, 2.0).junctionCandidates)
    }

    @Test
    fun healingActuallyJoinsTheTwoLines() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0), segment(100.5, 0.0, 200.0, 0.0))
        val healed = LineHealing.heal(lines, 2.0)
        // healing moves both ends onto the midpoint of the 0.5 m gap, so they coincide
        val ends = healed.flatMap { it.parts }.map { it.first() to it.last() }
        assertEquals(2, ends.size, "two parts, each now starting and ending at a weld point")
        val head = healed[0].parts[0].last()
        val tail = healed[1].parts[0].first()
        assertTrue(
            abs(head[0] - tail[0]) < 1e-9 && abs(head[1] - tail[1]) < 1e-9,
            "the two ends must meet exactly, got ${head[0]},${head[1]} vs ${tail[0]},${tail[1]}",
        )
        assertEquals(100.25, head[0], 1e-6, "welded onto the midpoint of the gap")
    }

    @Test
    fun twoSeparateLinesStayApart() {
        // the case the user was worried about: two lanes with a divider between them
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0), segment(0.0, 12.0, 100.0, 12.0))
        val healed = LineHealing.heal(lines, 7.0)
        assertEquals(2, healed.size, "a 12 m separation is not a digitising error")
        assertEquals(0, LineHealing.plan(lines, 7.0).crossFeatureWelds)
    }

    @Test
    fun wideningTheToleranceNeverReducesTheJunctionCount() {
        // the ladder the user compares against must be monotonic, or the preview lies
        val lines = listOf(
            segment(0.0, 0.0, 100.0, 0.0),
            segment(100.5, 0.0, 200.0, 0.0),
            segment(201.0, 1.0, 300.0, 1.0),
            segment(0.0, 30.0, 100.0, 30.0),
        )
        var last = -1
        for (t in listOf(0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0)) {
            val n = LineHealing.plan(lines, t).junctionCandidates
            assertTrue(n >= last, "at $t m the count fell from $last to $n")
            last = n
        }
    }

    @Test
    fun theLadderIsAvailableForOneLayer() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0), segment(103.0, 0.0, 200.0, 0.0))
        val ladder = LineHealing.toleranceLadder(lines)
        assertEquals(8, ladder.size)
        assertTrue(ladder.zipWithNext().all { (a, b) -> a.toleranceM < b.toleranceM })
    }

    @Test
    fun healingReducesTheComponentCount() {
        val lines = listOf(
            segment(0.0, 0.0, 100.0, 0.0),
            segment(100.4, 0.0, 200.0, 0.0),
            segment(200.4, 0.0, 300.0, 0.0),
        )
        val before = LineHealing.connectivity(lines).components
        val after = LineHealing.connectivity(LineHealing.heal(lines, 2.0)).components
        assertEquals(3, before)
        assertEquals(1, after, "three near-collinear lines become one network")
    }

    @Test
    fun theDefaultToleranceIsTheSevenMetresChosenAfterVisualReview() {
        // pinned deliberately: this is the value agreed after healed copies of the real
        // access layer were exported and inspected, not a value that should drift.
        assertEquals(7.0, LineHealing.DEFAULT_TOLERANCE_M)
    }

    @Test
    fun theDefaultStillHasThePlanAvailableSoItCanBeJudged() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0), segment(106.0, 0.0, 200.0, 0.0))
        val p = LineHealing.plan(lines, LineHealing.DEFAULT_TOLERANCE_M)
        assertEquals(1, p.junctionCandidates, "the default should join a 6 m gap")
        assertTrue(p.overAutomaticCap, "and it should say so, since 7 m exceeds the engine's cap")
    }

    @Test
    fun aToleranceOverTheAutomaticCapIsFlagged() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0))
        assertTrue(LineHealing.plan(lines, 5.0).withinAutomaticCap)
        assertTrue(LineHealing.plan(lines, 7.0).overAutomaticCap)
        assertEquals(5.0, LineHealing.AUTOMATIC_CAP_M)
    }

    @Test
    fun lengthChangeGrowsWithTolerance() {
        val lines = listOf(
            segment(0.0, 0.0, 100.0, 0.0),
            segment(103.0, 0.0, 200.0, 0.0),   // 3 m gap: only a 7 m tolerance closes it
            segment(206.0, 0.0, 300.0, 0.0),   // 6 m gap: only a 7 m tolerance closes it
        )
        val at2 = LineHealing.plan(lines, 2.0)
        val at7 = LineHealing.plan(lines, 7.0)
        assertEquals(0, at2.junctionCandidates, "2 m closes none of these gaps")
        assertEquals(2, at7.junctionCandidates, "7 m closes both")
        assertTrue(
            at7.lengthChangeM > at2.lengthChangeM,
            "a wider tolerance moves more geometry: ${at2.lengthChangeM} then ${at7.lengthChangeM}",
        )
    }

    @Test
    fun aChainOfShortSegmentsHealsIntoOneLine() {
        // what a recorded GPS track looks like: many pieces a couple of metres apart
        val pieces = (0 until 40).map { i -> segment(i * 1.8, 0.0, i * 1.8 + 1.0, 0.0) }
        val healed = LineHealing.heal(pieces, 2.0)
        assertEquals(1, LineHealing.connectivity(healed).components, "one connected component")
    }

    // ---- cuts ---------------------------------------------------------------

    @Test
    fun aCutSplitsTheLineIntoTwo() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0))
        val cut = LineHealing.cutAtFractions(lines, 0, listOf(0.5), 20.0)
        assertEquals(2, cut.size)
        assertEquals(40.0, cut[0].parts[0].last()[0], 0.001, "first piece stops early")
        assertEquals(60.0, cut[1].parts[0].first()[0], 0.001, "second piece starts late")
    }

    @Test
    fun theGapIsGenuinelyWideEnoughToSurviveHealing() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0))
        val cut = LineHealing.cutAtFractions(lines, 0, listOf(0.5), 20.0)
        val healed = LineHealing.heal(cut, 7.0)
        assertEquals(2, healed.size, "a 20 m cut must not be welded shut even at 7 m")
        assertEquals(2, LineHealing.connectivity(healed).components)
    }

    @Test
    fun aSmallGapIsReportedAsAtRiskRatherThanClosed() {
        // 3 m gap: close enough that healing would normally close it, so it must warn
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0))
        val cut = LineHealing.cutAtFractions(lines, 0, listOf(0.5), 3.0)
        val p = LineHealing.plan(cut, 5.0, listOf(LineHealing.Cut(0, 50.0, 0.0, 3.0)))
        assertTrue(p.cutsAtRisk >= 1, "healing must report the conflict, not apply it")
    }

    @Test
    fun aCutIsNeverActuallyWelded() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0))
        val cut = LineHealing.cutAtFractions(lines, 0, listOf(0.5), 3.0)
        val healed = LineHealing.heal(cut, 5.0, listOf(LineHealing.Cut(0, 50.0, 0.0, 3.0)))
        val gap = abs(healed[1].parts[0].first()[0] - healed[0].parts[0].last()[0])
        assertEquals(3.0, gap, 0.01, "the 3 m cut stays 3 m")
    }

    @Test
    fun aCutNeedsAGapToMeanAnything() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0))
        val zero = runCatching { LineHealing.cutAtFractions(lines, 0, listOf(0.5), 0.0) }
        assertTrue(zero.isFailure, "a cut with no gap would be noded back together")
    }

    @Test
    fun cuttingOutsideTheLineIsRefused() {
        val lines = listOf(segment(0.0, 0.0, 100.0, 0.0))
        val bad = runCatching { LineHealing.cutAtFractions(lines, 0, listOf(1.5), 20.0) }
        assertTrue(bad.isFailure)
        val missing = runCatching { LineHealing.cutAtFractions(lines, 9, listOf(0.5), 20.0) }
        assertTrue(missing.isFailure)
    }

    @Test
    fun twoCutsLeaveThreePieces() {
        val lines = listOf(segment(0.0, 0.0, 300.0, 0.0))
        val cut = LineHealing.cutAtFractions(lines, 0, listOf(0.33, 0.66), 20.0)
        assertEquals(3, cut.size)
        assertEquals(3, LineHealing.connectivity(cut).components)
    }

    // ---- simplification -----------------------------------------------------

    @Test
    fun verticesOnAStraightRunAreRemoved() {
        val straight = line(
            pt(0.0, 0.0), pt(10.0, 0.0), pt(20.0, 0.0), pt(30.0, 0.0), pt(40.0, 0.0),
        )
        val r = LineHealing.simplifyCollinear(listOf(straight), 0.5)
        assertEquals(2, r.lines[0].parts[0].size, "the three interior vertices are redundant")
    }

    @Test
    fun aCornerIsAlwaysKept() {
        val corner = line(pt(0.0, 0.0), pt(10.0, 0.0), pt(10.0, 10.0), pt(20.0, 10.0))
        val r = LineHealing.simplifyCollinear(listOf(corner), 0.5)
        assertTrue(r.lines[0].parts[0].size >= 3, "the corner must survive")
    }

    @Test
    fun aBendIsJudgedOnHowFarItBowNotOnSpacing() {
        // Two vertices 10 m apart can be dropped or kept on bow alone. This zig-zag bows
        // 4.7 m off the chord between every other vertex, so at 0.5 m tolerance all of
        // them must survive even though the segments are short and evenly spaced.
        val zigzag = line(
            pt(0.0, 0.0), pt(10.0, 3.0), pt(20.0, 0.0), pt(30.0, 3.0), pt(40.0, 0.0),
        )
        val strict = LineHealing.simplifyCollinear(listOf(zigzag), 0.5)
        assertEquals(5, strict.lines[0].parts[0].size, "a 4.7 m bow is not collinear at 0.5 m")
        assertEquals(0, strict.verticesRemoved)

        // The same line at a tolerance above the bow is genuinely redundant.
        val loose = LineHealing.simplifyCollinear(listOf(zigzag), 6.0)
        assertEquals(
            2, loose.lines[0].parts[0].size,
            "above the bow, the zigzag is collinear and reduces to its ends",
        )
        assertEquals(3, loose.verticesRemoved)
    }

    @Test
    fun aLongStraightRunShedsVerticesWhileItsShapeIsKept() {
        // 100 vertices along one straight line: the shape is preserved exactly, and only
        // the vertex count falls. This is what simplifying a GPS track is for.
        val straight = line(*Array(100) { i -> pt(i * 10.0, 0.0) })
        val r = LineHealing.simplifyCollinear(listOf(straight), 0.5)
        assertEquals(2, r.lines[0].parts[0].size, "100 collinear vertices reduce to the two ends")
        assertEquals(98, r.verticesRemoved)
        assertEquals(0.0, r.lines[0].parts[0].first()[0], 1e-9)
        assertEquals(990.0, r.lines[0].parts[0].last()[0], 1e-9)
    }

    @Test
    fun endpointsAlwaysSurvive() {
        val r = LineHealing.simplifyCollinear(listOf(line(pt(0.0, 0.0), pt(5.0, 0.0), pt(10.0, 0.0))), 10.0)
        val kept = r.lines[0].parts[0]
        assertEquals(0.0, kept.first()[0], 1e-9)
        assertEquals(10.0, kept.last()[0], 1e-9)
    }

    @Test
    fun simplificationNeverGrowsALayer() {
        val straight = line(*Array(50) { i -> pt(i * 10.0, 0.0) })
        val r = LineHealing.simplifyCollinear(listOf(straight), 0.5)
        assertEquals(2, r.lines[0].parts[0].size, "50 collinear vertices reduce to the two ends")
        assertEquals(48, r.verticesRemoved)
    }

}
