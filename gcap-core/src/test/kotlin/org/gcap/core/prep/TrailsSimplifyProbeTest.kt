package org.gcap.core.prep

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue as check
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which simplification tolerance lets a network be built over the survey's trails layer?
 *
 * A probe, not a threshold test: it prints what each tolerance buys and whether the build
 * then succeeds. The vertex counts it reports are a property of whatever trails layer is
 * present, which has changed with the survey data, so nothing here asserts a ratio - that
 * would only ever be a measurement of one dataset frozen as if it were a rule.
 */
class TrailsSimplifyProbeTest {

    private fun load(dir: File): List<LineHealing.Line> {
        val bytes = File(dir, "trails.shp").readBytes()
        val h = org.gcap.core.ShpFormat.shpHeader(bytes)!!
        return org.gcap.core.ShpFormat.readShp(bytes, h.xmin, h.ymin, h.xmax, h.ymax)
            .map { LineHealing.Line(it.parts, it.recordIndex) }
    }

    private fun vertices(ls: List<LineHealing.Line>) = ls.sumOf { l -> l.parts.sumOf { it.size } }

    @Test
    fun `simplification tolerances compared on trails`() {
        val dir = File("/home/mircea/ForestNavigator/FOR_NAV")
        assumeTrue(File(dir, "trails.shp").isFile, "survey trails layer not present")
        val original = load(dir)
        println("SIMPLIFY original features=${original.size} vertices=${vertices(original)}")

        fun sources(ls: List<LineHealing.Line>) = ls.map { l ->
            val flat = DoubleArray(l.parts.sumOf { p -> p.size } * 2)
            var k = 0
            for (p in l.parts) for (xy in p) { flat[k++] = xy[0]; flat[k++] = xy[1] }
            org.gcap.core.nav.SourceLine(flat, "trails", org.gcap.core.nav.RouteClass.TRAIL)
        }

        // Compare simplify alone against simplify+merge: the question is whether the
        // merge earns its complexity once the vertices are already under control.
        var res: LineHealing.SimplifyResult? = null
        for (tol in listOf(7.0)) {
            val t0 = System.currentTimeMillis()
            val r0 = LineHealing.simplifyCollinear(original, tol)
            res = r0
            val ms = System.currentTimeMillis() - t0
            val v = vertices(r0.lines)
            println(
                "SIMPLIFY tol=${tol.toInt()}m features=${r0.lines.size} vertices=$v " +
                    "pct=${(100.0 * (vertices(original) - v) / vertices(original)).toInt()}% ms=$ms",
            )
            // Simplification keeps every feature and drops vertices, so the claims to prove are
            // that it neither loses features nor invents them. How many it removes
            // depends on the trails layer present and is printed, not asserted.
            check(r0.lines.size == original.size, "simplification must keep every feature")
            check(v <= vertices(original), "simplification must not add vertices")
            val t1 = System.currentTimeMillis()
            try {
                val (_, r) = org.gcap.core.nav.NetworkBuilder(toleranceM = 3.0).build(sources(r0.lines))
                println(
                    "SIMPLIFY tol=${tol.toInt()}m NETWORK ok ms=${System.currentTimeMillis() - t1} " +
                        "nodes=${r.nodeCount} edges=${r.edgeCount} regions=${r.regionCount}",
                )
            } catch (e: OutOfMemoryError) {
                println("SIMPLIFY tol=${tol.toInt()}m NETWORK OUT_OF_MEMORY after ${System.currentTimeMillis() - t1}ms")
            } catch (e: Exception) {
                println("SIMPLIFY tol=${tol.toInt()}m NETWORK failed ${e.javaClass.simpleName}: ${e.message}")
            }

        }

        // What does the parallel merge add on top of simplification?
        val t2 = System.currentTimeMillis()
        val simplified = res!!.lines
        val merged = ParallelMerge.apply(simplified, maxGapM = 4.0)
        println(
            "COMPARE afterSimplify features=${simplified.size} vertices=${vertices(simplified)} " +
                "-> afterMerge features=${merged.size} vertices=${vertices(merged)} " +
                "mergeMs=${System.currentTimeMillis() - t2}",
        )
        val t3 = System.currentTimeMillis()
        try {
            val (_, r) = org.gcap.core.nav.NetworkBuilder(toleranceM = 3.0).build(sources(merged))
            println(
                "COMPARE merged NETWORK ok ms=${System.currentTimeMillis() - t3} " +
                    "nodes=${r.nodeCount} edges=${r.edgeCount}",
            )
        } catch (e: Exception) {
            println("COMPARE merged NETWORK failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
