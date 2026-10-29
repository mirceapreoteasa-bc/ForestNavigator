package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Sweeps the repair to find out whether discarding short features is what stops the
 * welding, and what weld distance the long features would actually need.
 */
class RepairSweepTest {

    private val dir = File(System.getenv("FORNAV_DATA") ?: (System.getProperty("user.home") + "/ForestNavigator/FOR_NAV"))

    private fun lines(): List<DoubleArray> {
        val h = ShpFormat.shpHeader(File(dir, "flight_path.shp").readBytes())!!
        val out = ArrayList<DoubleArray>()
        for (s in ShpFormat.readShp(
            File(dir, "flight_path.shp").readBytes(), h.xmin, h.ymin, h.xmax, h.ymax,
        )) {
            for (part in s.parts) {
                val f = DoubleArray(part.size * 2)
                part.forEachIndexed { i, p -> f[i * 2] = p[0]; f[i * 2 + 1] = p[1] }
                if (f.size >= 4) out.add(f)
            }
        }
        return out
    }

    @Test
    fun `sweeps weld distance and the minimum feature length kept`() {
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val raw = lines()

        println("SWEEP source features=${raw.size}")
        println("SWEEP minFeatureM  weldM  kept  lines  joins  neverJoined  longestKm")

        for (minF in listOf(0.0, 50.0, 100.0, 150.0, 200.0)) {
            for (weld in listOf(30.0, 50.0, 100.0, 200.0)) {
                val r = FlightPathRepair(raw, weldM = weld, minFeatureM = minF)
                val longest = r.repaired.maxOfOrNull { it.lengthM() } ?: 0.0
                println(
                    String.format(
                        "SWEEP %10.0f %6.0f %5d %6d %6d %12d  %8.1f",
                        minF, weld, r.kept.size, r.repaired.size,
                        r.repaired.sumOf { it.joinCount }, r.unjoinedLines, longest / 1000.0,
                    ),
                )
            }
        }
    }

    @Test
    fun `measures how far apart the ends of long features actually are`() {
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val raw = lines()
        val long = raw.filter { FlightRouteLines.lengthM(it) >= 200.0 }
        val ends = ArrayList<DoubleArray>()
        for (f in long) {
            ends.add(doubleArrayOf(f[0], f[1]))
            ends.add(doubleArrayOf(f[f.size - 2], f[f.size - 1]))
        }
        println("ENDS long features=${long.size}, ends=${ends.size}")
        val nearest = ArrayList<Double>()
        for (i in ends.indices) {
            var best = Double.MAX_VALUE
            for (j in ends.indices) {
                if (i == j) continue
                val d = Geodesy.distanceProjectedM(ends[i][0], ends[i][1], ends[j][0], ends[j][1])
                if (d < best) best = d
            }
            nearest.add(best)
        }
        nearest.sort()
        fun pc(p: Double) = nearest[(nearest.size * p).toInt().coerceAtMost(nearest.size - 1)]
        println("ENDS nearest other end: p10 ${"%.0f".format(pc(0.10))} p25 ${"%.0f".format(pc(0.25))} " +
            "p50 ${"%.0f".format(pc(0.50))} p75 ${"%.0f".format(pc(0.75))} p90 ${"%.0f".format(pc(0.90))} max ${"%.0f".format(nearest.last())} m")
        for (t in listOf(30.0, 50.0, 100.0, 200.0, 400.0)) {
            println("ENDS   ends with a neighbour within ${t.toInt()} m: ${nearest.count { it <= t }} of ${nearest.size}")
        }
    }
}
