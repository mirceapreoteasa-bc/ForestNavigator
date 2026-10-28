package org.gcap.core.prep

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Does merging parallel duplicates let a network build over the survey's dense layers? */
class ParallelMergeRealDataTest {

    private fun load(dir: File, name: String): List<LineHealing.Line> {
        val bytes = File(dir, "$name.shp").readBytes()
        val h = org.gcap.core.ShpFormat.shpHeader(bytes)!!
        return org.gcap.core.ShpFormat.readShp(bytes, h.xmin, h.ymin, h.xmax, h.ymax)
            .map { LineHealing.Line(it.parts, it.recordIndex) }
    }

    @Test
    fun `trails get thin enough to build a network`() {
        val dir = File("/home/mircea/ForestNavigator/FOR_NAV")
        assumeTrue(dir.isDirectory)
        val before = load(dir, "trails")
        println("MERGE trails before features=${before.size} " +
            "vertices=${before.sumOf { l -> l.parts.sumOf { p -> p.size } }}")

        val t0 = System.currentTimeMillis()
        val p = ParallelMerge.plan(before, maxGapM = 4.0)
        println("MERGE trails plan groups=${p.groups} dropped=${p.dropped} " +
            "removedM=${p.removedM.toInt()} example=${p.example} " +
            "planMs=${System.currentTimeMillis() - t0}")
        assertTrue(p.groups > 0, "expected duplicate runs in trails")

        val t1 = System.currentTimeMillis()
        val after = ParallelMerge.apply(before, maxGapM = 4.0)
        println("MERGE trails after features=${after.size} applyMs=${System.currentTimeMillis() - t1}")
        assertTrue(after.size < before.size, "merge must remove something")

        // The point of the merge: does a network now build over trails?
        val cls = org.gcap.core.nav.RouteClass.TRAIL
        fun toSources(ls: List<LineHealing.Line>) = ls.map { l ->
            val flat = DoubleArray(l.parts.sumOf { p -> p.size } * 2)
            var k = 0
            for (p in l.parts) for (xy in p) { flat[k++] = xy[0]; flat[k++] = xy[1] }
            org.gcap.core.nav.SourceLine(flat, "trails", cls)
        }
        // What actually drives the blowup: tolerance against vertex count, and the
        // concentration of vertices. Measure the pair rather than guessing.
        for (tol in listOf(3.0, 1.0, 0.5)) {
            val t3 = System.currentTimeMillis()
            try {
                val (_, r) = org.gcap.core.nav.NetworkBuilder(toleranceM = tol).build(toSources(after))
                println("MERGE trails tol=${tol} ok ms=${System.currentTimeMillis() - t3} " +
                    "nodes=${r.nodeCount} edges=${r.edgeCount}")
            } catch (e: OutOfMemoryError) {
                println("MERGE trails tol=${tol} OUT_OF_MEMORY after ${System.currentTimeMillis() - t3}ms")
            } catch (e: Exception) {
                println("MERGE trails tol=${tol} failed ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        for ((tag, ls) in listOf("before" to before, "after" to after)) {
            val t2 = System.currentTimeMillis()
            try {
                val (_, r) = org.gcap.core.nav.NetworkBuilder(toleranceM = 3.0).build(toSources(ls))
                println("MERGE trails network $tag ok ms=${System.currentTimeMillis() - t2} " +
                    "nodes=${r.nodeCount} edges=${r.edgeCount} regions=${r.regionCount}")
            } catch (e: OutOfMemoryError) {
                println("MERGE trails network $tag OUT_OF_MEMORY after ${System.currentTimeMillis() - t2}ms")
            } catch (e: Exception) {
                println("MERGE trails network $tag failed ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    @Test
    fun `roads merge without touching distinct roads`() {
        val dir = File("/home/mircea/ForestNavigator/FOR_NAV")
        assumeTrue(dir.isDirectory)
        val roads = File(dir, "roads.shp")
        assumeTrue(roads.isFile)
        // roads holds ~92k features; only plan, which is the part the user sees first
        val before = load(dir, "roads")
        val p = ParallelMerge.plan(before, maxGapM = 4.0)
        println("MERGE roads features=${before.size} groups=${p.groups} dropped=${p.dropped} " +
            "removedKm=${p.removedM / 1000.0}")
        assertTrue(p.dropped <= before.size / 4,
            "must not merge away real roads: dropped ${p.dropped} of ${before.size}")
    }
}
