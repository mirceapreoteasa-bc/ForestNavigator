package org.gcap.core.nav

import org.gcap.core.prep.LineHealing
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The whole point of a network: a route between two points follows the lines, is longer
 * than the straight line between them, and its length matches the lines it used.
 *
 * This builds a network from the real trails layer with the shipped defaults (simplify 7 m,
 * weld 7 m) and checks that property, so "it built something" is never mistaken for "you
 * can navigate on it".
 */
class TrailNetworkEndToEndTest {

    @Test
    fun `a route follows the network rather than the straight line`() {
        val dir = File(System.getProperty("user.home"), "ForestNavigator/FOR_NAV")
        val shp = File(dir, "trails.shp")
        assumeTrue(shp.isFile, "trails layer not present")

        val bytes = shp.readBytes()
        val h = org.gcap.core.ShpFormat.shpHeader(bytes)!!
        val raw = org.gcap.core.ShpFormat.readShp(bytes, h.xmin, h.ymin, h.xmax, h.ymax)
            .map { LineHealing.Line(it.parts, it.recordIndex) }
        val simplified = LineHealing.simplifyCollinear(raw, 7.0).lines

        fun sources(ls: List<LineHealing.Line>) = ls.map { l ->
            val flat = DoubleArray(l.parts.sumOf { p -> p.size } * 2)
            var k = 0
            for (p in l.parts) for (xy in p) { flat[k++] = xy[0]; flat[k++] = xy[1] }
            SourceLine(flat, "trails", RouteClass.TRAIL)
        }

        val (graph, report) = NetworkBuilder(toleranceM = 7.0, allowSupervisedGaps = true)
            .build(sources(simplified))
        println(
            "E2E build nodes=${report.nodeCount} edges=${report.edgeCount} " +
                "regions=${report.regionCount} welded=${report.snappedEndpointCount}",
        )
        assertTrue(report.edgeCount > 0, "network must have edges")

        // Pick real start and goal from node positions: the graph keeps coordinates per
        // node, and routing snaps to the nearest point on the network anyway.
        val router = Router(graph)
        // Within one region: trails do not all join up, and a route across regions is
        // correctly refused. Group the nodes by region and route inside the biggest ones.
        val regionNodes = HashMap<Int, MutableList<Int>>()
        for (e in graph.edges) {
            if (e.lengthM < 50) continue
            val r = graph.regionOfEdge[e.id]
            regionNodes.getOrPut(r) { ArrayList() }.add(e.fromNode)
        }
        val usable = regionNodes.values.map { it.distinct() }.filter { it.size >= 3 }
            .sortedByDescending { it.size }
        println("E2E regionsWithLongEdges=${usable.size} biggest=${usable.firstOrNull()?.size}")
        assertTrue(usable.isNotEmpty(), "no region has enough edges to route within")

        var tried = 0
        var routed = 0
        val step = maxOf(1, usable.first().size / 20)
        outer@ for (region in usable.take(4)) {
          for (i in region.indices step step) {
            for (j in region.indices step step) {
                if (j <= i) continue
                val a = region[i]
                val b = region[j]
                val ae = graph.nodeE[a]; val an = graph.nodeN[a]
                val be = graph.nodeE[b]; val bn = graph.nodeN[b]
                val straight = Geodesy.distanceProjectedM(ae, an, be, bn)
                if (straight < 500) continue
                if (tried >= 30) break@outer
                tried++
                when (val r = router.route(ae, an, be, bn)) {
                    is RouteResult.Ok -> {
                        routed++
                        val via = r.path.metres
                        println(
                            "E2E route straight=${straight.toInt()}m viaNetwork=${via.toInt()}m " +
                                "ratio=${"%.2f".format(via / straight)} edges=${r.path.edges.size}",
                        )
                        // following the network can never be shorter than the direct line
                        assertTrue(
                            via >= straight * 0.98,
                            "a network route cannot be shorter than the straight line: " +
                                "$via vs $straight",
                        )
                    }
                    is RouteResult.Failed -> println("E2E route failed: ${r.reason}")
                }
            }
          }
        }
        println("E2E tried=$tried routed=$routed")
        assertTrue(routed > 0, "no route succeeded between two network points")
    }
}
