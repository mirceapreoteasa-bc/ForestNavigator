package org.gcap.core.prep

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Healing has to run on a phone, on a folder with thousands of features, so the timing
 * matters as much as the result. These assert wall-clock budgets against the real survey
 * layer rather than a small fixture, because a synthetic 10-line case is exactly the sort
 * of test that hides a quadratic.
 */
class HealPerformanceTest {

    private fun realAccess(): List<LineHealing.Line> {
        val f = File("/home/mircea/ForestNavigator/FOR_NAV/access.shp")
        assumeTrue(f.exists(), "real project not present")
        // the extent arguments filter by bounding box: pass the whole plane
        val shapes = ShpFormat.readShp(f.readBytes(), -1e18, -1e18, 1e18, 1e18)
        return shapes.map { LineHealing.Line(it.parts, it.recordIndex) }
    }

    @Test
    fun connectivityOverTheRealLayerIsFast() {
        val lines = realAccess()
        assertTrue(lines.size > 1000, "expected the real access layer, got ${lines.size}")
        val t = System.currentTimeMillis()
        val c = LineHealing.connectivity(lines)
        val ms = System.currentTimeMillis() - t
        println("PERF connectivity ${lines.size} features in $ms ms -> ${c.components} components")
        assertTrue(ms < 20_000, "connectivity took $ms ms")
    }

    @Test
    fun planningHealingOverTheRealLayerIsFast() {
        val lines = realAccess()
        for (tol in listOf(2.0, 7.0, 15.0)) {
            val t = System.currentTimeMillis()
            val p = LineHealing.plan(lines, tol)
            val ms = System.currentTimeMillis() - t
            println("PERF plan @${tol}m ${lines.size} features in $ms ms: " +
                "junctions=${p.junctionCandidates} lenChange=${"%.1f".format(p.lengthChangeM)}m " +
                "comps ${p.componentsBefore}->${p.componentsAfter}")
            assertTrue(ms < 30_000, "plan at $tol m took $ms ms")
        }
    }

    @Test
    fun theCheapLadderIsFastEnoughToShowWhileTheUserWaits() {
        // The full ladder measured 3.5 s on a desktop, which was 76 s on the emulator -
        // long enough that the settings screen looked dead. This is the path the UI takes.
        val lines = realAccess()
        val t = System.currentTimeMillis()
        val cheap = LineHealing.summariseLadder(lines)
        val ms = System.currentTimeMillis() - t
        println("PERF cheap ladder (${cheap.size} tolerances) in $ms ms")
        assertEquals(8, cheap.size)
        assertTrue(ms < 15_000, "cheap ladder took $ms ms")
    }

    @Test
    fun theCheapLadderAgreesWithTheFullPlanOnWhatItShows() {
        // The ladder shows junctions and moved distance; the numbers must match the full
        // plan, or the table misleads exactly where it is being used to choose.
        val lines = realAccess()
        for (tol in listOf(2.0, 7.0)) {
            val cheap = LineHealing.summarise(lines, tol)
            val full = LineHealing.plan(lines, tol)
            assertEquals(full.junctionCandidates, cheap.junctionCandidates, "junctions at $tol m")
            assertEquals(full.lengthChangeM, cheap.lengthChangeM, 1e-6, "moved distance at $tol m")
            assertEquals(full.withinAutomaticCap, cheap.withinAutomaticCap, "cap flag at $tol m")
        }
    }

    @Test
    fun theToleranceLadderOverTheRealLayerIsFast() {
        val lines = realAccess()
        val t = System.currentTimeMillis()
        val ladder = LineHealing.toleranceLadder(lines)
        val ms = System.currentTimeMillis() - t
        println("PERF ladder (${ladder.size} tolerances) in $ms ms")
        assertEquals(8, ladder.size)
        assertTrue(ms < 60_000, "ladder took $ms ms")
    }
}
