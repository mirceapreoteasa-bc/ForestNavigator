package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class FlightPathRepairRealDataTest {

    private val dir = File(System.getenv("FORNAV_DATA") ?: "/home/mircea/ForestNavigator/FOR_NAV")

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
    fun `repairs the real flight path`() {
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val raw = lines()
        println("REPAIR source features=${raw.size}")

        // All features are kept. Measured: keeping only the long ones leaves 29 joins,
        // while keeping the short pieces as well gives 583 at the same 30 m weld - the
        // short features are what the long lines actually connect through.
        for (weld in listOf(30.0, 50.0, 100.0)) {
            val probe = FlightPathRepair(raw, weldM = weld, minFeatureM = 0.0)
            println("REPAIR weld=${weld.toInt()} m: ${probe.summary()}")
        }

        val r = FlightPathRepair(raw, weldM = 30.0, minFeatureM = 0.0)
        println("REPAIR ${r.summary()}")
        println("REPAIR ambiguous joins=${r.ambiguous.size}")

        val byPieces = r.repaired.groupingBy { it.featureIds.size }.eachCount()
        println("REPAIR lines by piece count: ${byPieces.toSortedMap()}")
        val longest = r.repaired.sortedByDescending { it.lengthM() }.take(8)
        longest.forEach {
            println("REPAIR   ${"%.1f".format(it.lengthM() / 1000.0)} km from ${it.featureIds.size} pieces, ${it.vertexCount} vertices${if (it.closed) " (ring)" else ""}")
        }
        val joined = r.repaired.filter { it.featureIds.size > 1 }
        println("REPAIR joined lines=${joined.size}, pieces in them=${joined.sumOf { it.featureIds.size }}")

        // export so it can be inspected in QGIS before any plan is built on it
        val out = File("/home/mircea/ForestNavigator/flight_plan_export")
        out.mkdirs()
        val prj = File(dir, "flight_path.prj").let { if (it.exists()) it.readText() else "" }
        // a feature is a list of points, each a flat e,n pair
        val parts = r.repaired.map { line ->
            (0 until line.vertexCount).map { doubleArrayOf(line.points[it * 2], line.points[it * 2 + 1]) }
        }
        val rows = r.repaired.map {
            listOf(it.featureIds.size.toString(), it.joinCount.toString(), it.vertexCount.toString(),
                if (it.closed) "Y" else "N", "%.1f".format(it.lengthM()))
        }
        File(out, "flight_path_repaired.shp").writeBytes(ShpFormat.buildPolylineShp(parts))
        File(out, "flight_path_repaired.shx").writeBytes(ShpFormat.buildPolylineShx(parts))
        File(out, "flight_path_repaired.dbf").writeBytes(ShpFormat.buildDbfFields(
            listOf("PIECES" to 7, "JOINS" to 6, "VERTS" to 7, "RING" to 5, "LEN_M" to 12), rows,
        ))
        if (prj.isNotBlank()) File(out, "flight_path_repaired.prj").writeText(prj)
        println("REPAIR wrote flight_path_repaired.shp (${r.repaired.size} lines)")

        val back = ShpFormat.shpHeader(File(out, "flight_path_repaired.shp").readBytes())!!
        assertEquals(r.repaired.size, back.recordCount)
        val allIds = r.repaired.flatMap { it.featureIds }
        assertEquals(allIds.size, allIds.distinct().size, "a feature was welded into two lines")
        assertEquals(r.kept.size, allIds.size, "every kept feature should appear exactly once")
    }
}
