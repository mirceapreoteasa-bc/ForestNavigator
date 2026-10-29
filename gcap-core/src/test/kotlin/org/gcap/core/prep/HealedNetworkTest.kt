package org.gcap.core.prep

import org.gcap.core.nav.NetworkBuilder
import org.gcap.core.nav.RouteClass
import org.gcap.core.nav.SourceLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The point of healing at a tolerance wider than the routing engine will accept
 * automatically.
 *
 * The engine refuses to weld gaps over 5 m on its own, because doing so would invent a
 * road across ground nobody can cross. Healing is the supervised version of that decision:
 * a person has looked, chosen a tolerance, and seen the before-and-after. Once healed the
 * endpoints are not merely close - they are the same coordinates - so the engine's cap no
 * longer applies to them, and the network can be built at its conservative default.
 *
 * This is what makes a wide healing tolerance safe to adopt: it does not raise the engine's
 * automatic limit, it moves the decision to a step where it is reviewed.
 */
class HealedNetworkTest {

    private fun line(vararg pts: DoubleArray) = LineHealing.Line(listOf(pts.toList()))

    /** The engine takes flat coordinate arrays, one source line per feature. */
    private fun sources(lines: List<LineHealing.Line>): List<SourceLine> =
        lines.map { l ->
            val flat = DoubleArray(l.parts.sumOf { it.size } * 2)
            var k = 0
            for (part in l.parts) for (p in part) {
                flat[k++] = p[0]
                flat[k++] = p[1]
            }
            SourceLine(flat, "test", RouteClass.ROAD)
        }

    /** Two access lines that meet, drawn 6 m apart - beyond the engine's 5 m cap. */
    private val drawnApart = listOf(
        line(doubleArrayOf(0.0, 0.0), doubleArrayOf(100.0, 0.0)),
        line(doubleArrayOf(106.0, 0.0), doubleArrayOf(200.0, 0.0)),
    )

    @Test
    fun anUnhealedWideGapIsRefusedByTheEngine() {
        // the automatic build must still refuse this: nothing has reviewed it
        val refused = runCatching {
            NetworkBuilder(toleranceM = 6.0).build(sources(drawnApart))
        }
        assertTrue(refused.isFailure, "the engine must not weld a 6 m gap on its own")
    }

    @Test
    fun healingAtSevenMetresMakesTheSameJunctionUsable() {
        val healed = LineHealing.heal(drawnApart, 7.0)
        val gap = abs(healed[0].parts[0].last()[0] - healed[1].parts[0].first()[0])
        assertTrue(gap < 1e-9, "healed endpoints must be the same coordinate, gap was $gap")

        // and now the conservative engine tolerance connects them
        val (_, report) = NetworkBuilder(toleranceM = 3.0).build(sources(healed))
        assertTrue(report.nodeCount >= 2, "the healed junction should be part of one network")
    }

    @Test
    fun healingDoesNotRaiseTheEnginesAutomaticCap() {
        // the engine's own limit must be untouched by any healing tolerance
        assertEquals(5.0, NetworkBuilder.MAX_UNSUPERVISED_TOLERANCE_M)
        val stillRefused = runCatching {
            NetworkBuilder(toleranceM = 6.0).build(sources(drawnApart))
        }
        assertTrue(stillRefused.isFailure, "healing must not have relaxed the engine limit")
    }

    @Test
    fun aCutStillBlocksTheNetworkAfterHealingAtSevenMetres() {
        // The whole reason a cut has a real gap: heal, then build, and the road must stay
        // interrupted. If healing closed this, the app would route over a collapsed bridge.
        val continuous = listOf(line(doubleArrayOf(0.0, 0.0), doubleArrayOf(200.0, 0.0)))
        val cut = LineHealing.cutAtFractions(continuous, 0, listOf(0.5), 30.0)
        val healed = LineHealing.heal(cut, 7.0)

        val gap = abs(healed[0].parts[0].last()[0] - healed[1].parts[0].first()[0])
        assertEquals(30.0, gap, 1e-6, "the cut gap must survive healing untouched")

        val (_, report) = NetworkBuilder(toleranceM = 5.0).build(sources(healed))
        assertEquals(
            2, LineHealing.connectivity(healed).components,
            "the two halves must not be one component",
        )
        // the engine must not have welded the 30 m gap: that would route over the cut
        assertTrue(
            report.snappedEndpointCount == 0,
            "no endpoint should have been snapped across a 30 m cut, got ${report.snappedEndpointCount}",
        )
    }

    @Test
    fun healingTheRealAccessLayerStillBuildsANetworkAfterwards() {
        val f = File(System.getProperty("user.home"), "ForestNavigator/FOR_NAV/access.shp")
        org.junit.jupiter.api.Assumptions.assumeTrue(f.exists(), "real project not present")
        val shapes = org.gcap.core.ShpFormat.readShp(f.readBytes(), -1e18, -1e18, 1e18, 1e18)
        val lines = shapes.map { LineHealing.Line(it.parts, it.recordIndex) }

        val healed = LineHealing.heal(lines, 7.0)
        val before = LineHealing.connectivity(lines)
        val after = LineHealing.connectivity(healed)
        assertTrue(
            after.components < before.components,
            "healing must join the real layer: ${before.components} -> ${after.components}",
        )

        // the healed layer must still be valid input to the engine at its default tolerance
        val t = System.currentTimeMillis()
        val (_, report) = NetworkBuilder(toleranceM = 3.0).build(sources(healed))
        println(
            "HN healed access builds in ${System.currentTimeMillis() - t} ms: " +
                "nodes=${report.nodeCount} edges=${report.edgeCount} " +
                "regions=${report.regionCount} maxRegionKm=${report.maxRegionLengthKm} " +
                "components ${before.components} -> ${after.components}",
        )
        assertTrue(report.nodeCount > 1000, "the real network should be substantial")
    }
}