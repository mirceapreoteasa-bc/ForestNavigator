package org.gcap.core.prep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Deciding which line a tap meant.
 *
 * A cut removes a road, so the wrong answer is not a cosmetic error: it can sever a line
 * the crew still needs and leave the one that collapsed intact. Every case here is about
 * refusing rather than guessing.
 */
class LineCutTargetTest {

    private fun line(vararg pts: DoubleArray) = LineHealing.Line(listOf(pts.toList()))

    /** A single straight line from (0,0) to (1000,0), so fractions are easy to reason about. */
    private val road = listOf(
        line(doubleArrayOf(0.0, 0.0), doubleArrayOf(500.0, 0.0), doubleArrayOf(1000.0, 0.0)),
    )

    @Test
    fun aTapOnTheLineFindsItAndTheMiddle() {
        val r = LineCutTarget.find(road, 500.0, 0.0, maxOffsetM = 20.0)
        val t = r.targetOrNull
        assertTrue(r is LineCutTarget.Result.Found, "expected a hit, got $r")
        assertEquals(0, t!!.recordIndex)
        assertEquals(0.5, t.fraction, 1e-9, "the middle of a 1000 m line is 0.5")
        assertEquals(0.0, t.offsetM, 1e-9)
    }

    @Test
    fun aTapSlightlyOffTheLineStillFindsIt() {
        val r = LineCutTarget.find(road, 500.0, 8.0, maxOffsetM = 20.0)
        assertTrue(r is LineCutTarget.Result.Found, "8 m off, within a 20 m reach")
        assertEquals(8.0, r.targetOrNull!!.offsetM, 1e-6)
    }

    @Test
    fun aTapTooFarFromAnyLineIsRefused() {
        // nothing is within reach, so nothing is cut - the app must not guess
        val r = LineCutTarget.find(road, 500.0, 400.0, maxOffsetM = 20.0)
        assertEquals(LineCutTarget.Result.NothingClose, r)
    }

    @Test
    fun theNearestOfSeveralLinesWins() {
        val two = listOf(
            line(doubleArrayOf(0.0, 0.0), doubleArrayOf(1000.0, 0.0)),   // along y = 0
            line(doubleArrayOf(0.0, 40.0), doubleArrayOf(1000.0, 40.0)), // along y = 40
        )
        val r = LineCutTarget.find(two, 500.0, 30.0, maxOffsetM = 50.0)
        assertEquals(1, r.targetOrNull!!.recordIndex, "the y=40 line is closer to a tap at y=30")
        assertEquals(10.0, r.targetOrNull!!.offsetM, 1e-6)
    }

    @Test
    fun theFractionIsAlongTheWholePartNotTheNearestSegment() {
        // a vertex sits at the halfway point, so a tap left of it must fall below 0.5
        val r = LineCutTarget.find(road, 250.0, 0.0, maxOffsetM = 10.0)
        assertEquals(0.25, r.targetOrNull!!.fraction, 1e-9)
    }

    @Test
    fun aTapOnAPartTooShortToCutIsSkipped() {
        // a 15 m stub: any cut would leave pieces shorter than the gap itself
        val stub = listOf(line(doubleArrayOf(0.0, 0.0), doubleArrayOf(15.0, 0.0)))
        val r = LineCutTarget.find(stub, 7.0, 0.0, maxOffsetM = 10.0)
        assertEquals(LineCutTarget.Result.NothingClose, r)
    }

    @Test
    fun aPartTooShortToHoldTheGapIsNotOfferedAsACutTarget() {
        // 3 m from the end of a 1000 m line is fine; but a 30 m part cannot take a 20 m
        // gap and keep 10 m either side, so it must not be offered.
        val shortPiece = listOf(line(doubleArrayOf(0.0, 0.0), doubleArrayOf(30.0, 0.0)))
        assertEquals(
            LineCutTarget.Result.NothingClose,
            LineCutTarget.find(shortPiece, 15.0, 0.0, maxOffsetM = 10.0, gapM = 20.0),
        )
        assertTrue(LineCutTarget.tooShortFor(shortPiece[0].parts[0], 20.0))
        assertTrue(!LineCutTarget.tooShortFor(road[0].parts[0], 20.0))
        assertEquals(40.0, LineCutTarget.lengthNeededFor(20.0), 1e-9)
    }

    @Test
    fun aTapBesideAnInternalVertexStillFindsTheLine() {
        // A GPS fix sits on a vertex, and a crew tapping the road does too. The stub guard
        // must be about the line's ends, not every vertex: rejecting taps near internal
        // vertices made the road untappable exactly where people point at it.
        val withMiddleVertex = listOf(
            line(
                doubleArrayOf(0.0, 0.0),
                doubleArrayOf(500.0, 0.0),   // the vertex the fix lands on
                doubleArrayOf(1000.0, 0.0),
            ),
        )
        for (tapped in listOf(498.0, 500.0, 502.0, 497.0, 503.0)) {
            val r = LineCutTarget.find(withMiddleVertex, tapped, 0.0, maxOffsetM = 20.0)
            assertTrue(
                r is LineCutTarget.Result.Found,
                "a tap at x=$tapped beside the middle vertex must still find the line, got $r",
            )
        }
    }

    @Test
    fun aTapAtTheVeryStartOfAPartIsNeverAFractionZeroCut() {
        // Found on device: a tap on a feature's first vertex gave fraction 0.0, which the
        // cutter refuses and which failed the whole save. Such a tap must find nothing (or
        // another segment), never a cut at the boundary.
        val r = LineCutTarget.find(road, 0.5, 0.0, maxOffsetM = 10.0, gapM = 20.0)
        assertEquals(LineCutTarget.Result.NothingClose, r)
        val nearEnd = LineCutTarget.find(road, 995.0, 0.0, maxOffsetM = 10.0, gapM = 20.0)
        assertEquals(LineCutTarget.Result.NothingClose, nearEnd)
    }

    @Test
    fun theCutFractionIsStillCorrectBesideAVertex() {
        val withMiddleVertex = listOf(
            line(doubleArrayOf(0.0, 0.0), doubleArrayOf(500.0, 0.0), doubleArrayOf(1000.0, 0.0)),
        )
        val r = LineCutTarget.find(withMiddleVertex, 750.0, 0.0, maxOffsetM = 20.0)
        assertEquals(0.75, r.targetOrNull!!.fraction, 1e-9)
    }

    // ---- the gap must survive healing ---------------------------------------

    @Test
    fun aGapWiderThanTheHealToleranceIsAccepted() {
        val r = LineCutTarget.find(road, 500.0, 0.0, maxOffsetM = 20.0, gapM = 20.0)
        assertTrue(r is LineCutTarget.Result.Found, "a 20 m gap clears a 7 m tolerance: $r")
    }

    @Test
    fun aGapNoWiderThanHealingIsRefused() {
        // A 12 m gap is above the 10 m minimum, but if the layer is healed at 15 m it
        // would be welded straight back together - so it must be refused for that reason,
        // not accepted because it merely clears the minimum.
        val r = LineCutTarget.find(
            road, 500.0, 0.0, maxOffsetM = 20.0, toleranceM = 15.0, gapM = 12.0,
        )
        assertTrue(r is LineCutTarget.Result.Refused, "got $r")
        assertEquals(
            LineCutTarget.BlockReason.TOO_CLOSE_TO_HEAL_TOLERANCE,
            (r as LineCutTarget.Result.Refused).reason,
        )
    }

    @Test
    fun aGapBelowTheMinimumIsRefusedEvenWhenToleranceIsTight() {
        // 7 m trips the minimum first: it is too small to be worth making whatever the
        // healing setting is, so it must not be reported as a tolerance problem.
        val r = LineCutTarget.find(
            road, 500.0, 0.0, maxOffsetM = 20.0, toleranceM = 7.0, gapM = 7.0,
        )
        assertEquals(
            LineCutTarget.BlockReason.GAP_TOO_SMALL,
            (r as LineCutTarget.Result.Refused).reason,
        )
    }

    @Test
    fun anAbsurdlySmallOrLargeGapIsRefused() {
        val small = LineCutTarget.find(road, 500.0, 0.0, maxOffsetM = 20.0, gapM = 2.0)
        assertEquals(
            LineCutTarget.BlockReason.GAP_TOO_SMALL,
            (small as LineCutTarget.Result.Refused).reason,
        )
        val large = LineCutTarget.find(road, 500.0, 0.0, maxOffsetM = 20.0, gapM = 500.0)
        assertEquals(
            LineCutTarget.BlockReason.GAP_TOO_LARGE,
            (large as LineCutTarget.Result.Refused).reason,
        )
    }

    @Test
    fun theRefusalStillReportsWhereTheTapWasSoTheUserCanSeeIt() {
        val r = LineCutTarget.find(road, 500.0, 5.0, maxOffsetM = 20.0, gapM = 5.0)
        assertTrue(r is LineCutTarget.Result.Refused)
        val t = r.targetOrNull!!
        assertEquals(500.0, t.x, 1e-9)
        assertEquals(0.0, t.y, 1e-9)
    }

    // ---- the cut that follows ----------------------------------------------

    @Test
    fun cuttingAtTheFoundFractionBreaksTheLineWhereTheUserTapped() {
        val target = LineCutTarget.find(road, 500.0, 0.0, maxOffsetM = 20.0).targetOrNull!!
        val cut = LineHealing.cutAtFractions(road, target.recordIndex, listOf(target.fraction), 20.0)
        assertEquals(2, cut.size, "one line becomes two")
        val gap = abs(cut[0].parts[0].last()[0] - cut[1].parts[0].first()[0])
        assertEquals(20.0, gap, 1e-6)
        // and the break is where the tap was, not somewhere convenient
        assertEquals(490.0, cut[0].parts[0].last()[0], 0.01)
        assertEquals(510.0, cut[1].parts[0].first()[0], 0.01)
    }

    @Test
    fun theCutSurvivesHealingAndBlocksTheNetwork() {
        val target = LineCutTarget.find(road, 500.0, 0.0, maxOffsetM = 20.0).targetOrNull!!
        val cut = LineHealing.cutAtFractions(road, target.recordIndex, listOf(target.fraction), 20.0)
        val healed = LineHealing.heal(cut, LineHealing.DEFAULT_TOLERANCE_M)
        assertEquals(2, healed.size, "healing at 7 m must not close a 20 m cut")
        assertEquals(2, LineHealing.connectivity(healed).components)
    }

    @Test
    fun attributesAreKeptWhenTheFeatureCountChanges() {
        // a cut adds a feature, so the attribute table has to grow with it or QGIS
        // reports the layer as broken
        val dbf = org.gcap.core.ShpFormat.buildDbfFields(
            listOf("OBJECTID" to 10, "SHAPE_Leng" to 19),
            listOf(listOf("1", "1000"), listOf("2", "2000"), listOf("3", "3000")),
        )
        val grown = org.gcap.core.prep.LayerPreparation.resizeDbf(dbf, 4)
        val header = org.gcap.core.ShpFormat.dbfHeader(grown)
        assertEquals(4, header!!.numRecords)
        assertEquals(listOf("OBJECTID", "SHAPE_LENG"), header.fields.map { it.name }, "DBF field names are upper case")
        val rows = org.gcap.core.ShpFormat.readDbfRows(grown)
        assertEquals(listOf("1", "1000"), rows[0].values)
        // The added row is blank rather than auto-numbered: OBJECTID is the user's own
        // scheme and inventing a number could collide with one they already use. A blank
        // cell is obvious in QGIS and gets filled in; a wrong number is not.
        assertEquals(listOf("", ""), rows[3].values, "the new row is blank, not guessed")
    }

    @Test
    fun resizingToTheSameCountLeavesTheTableAlone() {
        val dbf = org.gcap.core.ShpFormat.buildDbfFields(
            listOf("OBJECTID" to 10),
            listOf(listOf("1"), listOf("2")),
        )
        assertTrue(
            org.gcap.core.prep.LayerPreparation.resizeDbf(dbf, 2).contentEquals(dbf),
            "no change in feature count must not rewrite the table",
        )
    }

    @Test
    fun theRefusalNamesAShortPieceAndHowLongItNeeds() {
        val short = listOf(line(doubleArrayOf(0.0, 0.0), doubleArrayOf(30.0, 0.0)))
        val why = LineCutTarget.explainNoCut(short, 15.0, 1.0, maxOffsetM = 10.0, gapM = 20.0)
        assertTrue(why.contains("30 m"), "names the length: $why")
        assertTrue(why.contains("40 m"), "names the need: $why")
    }

    @Test
    fun theRefusalSaysSoWhenNothingIsNearTheTap() {
        val why = LineCutTarget.explainNoCut(road, 500.0, 400.0, maxOffsetM = 20.0, gapM = 20.0)
        assertTrue(why.contains("No line within"))
    }
}
