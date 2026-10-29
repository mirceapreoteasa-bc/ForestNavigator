package org.gcap.core.prep

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** How close do parallel lines actually run in the survey layers? */
class ParallelProbeTest {

    @Test
    fun `report nearby parallel pairs`() {
        val dir = File(System.getProperty("user.home"), "ForestNavigator/FOR_NAV")
        assumeTrue(dir.isDirectory)
        for (name in listOf("trails", "roads", "access")) {
            val shp = File(dir, "$name.shp")
            if (!shp.isFile) continue
            val bytes = shp.readBytes()
            val h = org.gcap.core.ShpFormat.shpHeader(bytes)!!
            val shapes = org.gcap.core.ShpFormat.readShp(bytes, h.xmin, h.ymin, h.xmax, h.ymax)
            // sample vertices into a grid, then measure how many land close to another
            val cell = 20.0
            val grid = HashMap<Long, MutableList<DoubleArray>>()
            var pts = 0
            for (s in shapes) for (p in s.parts) for (xy in p) {
                val gx = Math.floor(xy[0] / cell).toLong()
                val gy = Math.floor(xy[1] / cell).toLong()
                grid.getOrPut(gx * 73856093L xor gy * 19349663L) { ArrayList() }
                    .add(doubleArrayOf(xy[0], xy[1]))
                pts++
            }
            // count pairs of vertices within 3 m that are not adjacent on the same line
            var close = 0
            val buckets = ArrayList<MutableList<DoubleArray>>()
            for (v in grid.values) for (p in v) {
                for (dx in -1..1) for (dy in -1..1) {
                    grid[(Math.floor(p[0] / cell).toLong() + dx) * 73856093L xor
                        (Math.floor(p[1] / cell).toLong() + dy) * 19349663L]?.let { near ->
                        for (q in near) {
                            val ddx = p[0] - q[0]; val ddy = p[1] - q[1]
                            if (ddx == 0.0 && ddy == 0.0) continue
                            if (sqrt(ddx * ddx + ddy * ddy) <= 3.0) close++
                        }
                    }
                }
            }
            println("PARALLEL $name features=${shapes.size} vertices=$pts nearPairs3m=$close")
        }
    }
}
