package org.gcap.core.nav

import org.junit.jupiter.api.Test
import java.io.File

/**
 * What weld tolerance does the trails layer actually need?
 *
 * The 3 m default is right for roads. Trails recorded on foot often miss each other by
 * several metres where two passes cross or a spur meets a track, and that shows up as a
 * network broken into far more regions than the ground warrants. This measures the region
 * count across tolerances on the simplified real layer, so the choice is evidence-based.
 */
class TrailToleranceProbeTest {

    @Test
    fun `report regions against weld tolerance`() {
        val dir = File(System.getProperty("user.home"), "ForestNavigator/FOR_NAV")
        val shp = File(dir, "trails.shp")
        org.junit.jupiter.api.Assumptions.assumeTrue(shp.isFile, "trails layer not present")

        val bytes = shp.readBytes()
        val h = org.gcap.core.ShpFormat.shpHeader(bytes)!!
        val raw = org.gcap.core.ShpFormat.readShp(bytes, h.xmin, h.ymin, h.xmax, h.ymax)
            .map { org.gcap.core.prep.LineHealing.Line(it.parts, it.recordIndex) }
        val simplified = org.gcap.core.prep.LineHealing.simplifyCollinear(raw, 7.0).lines

        fun sources(ls: List<org.gcap.core.prep.LineHealing.Line>) = ls.map { l ->
            val flat = DoubleArray(l.parts.sumOf { p -> p.size } * 2)
            var k = 0
            for (p in l.parts) for (xy in p) { flat[k++] = xy[0]; flat[k++] = xy[1] }
            SourceLine(flat, "trails", RouteClass.TRAIL)
        }

        for (tol in listOf(3.0, 6.0, 10.0, 20.0)) {
            val t0 = System.currentTimeMillis()
            try {
                val (_, r) = NetworkBuilder(toleranceM = tol, allowSupervisedGaps = true)
                    .build(sources(simplified))
                println(
                    "TOL trails tol=${tol.toInt()}m ms=${System.currentTimeMillis() - t0} " +
                        "nodes=${r.nodeCount} edges=${r.edgeCount} regions=${r.regionCount} " +
                        "welded=${r.snappedEndpointCount}",
                )
            } catch (e: Exception) {
                println("TOL trails tol=${tol.toInt()}m failed ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
