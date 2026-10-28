package org.gcap.core.nav

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Measures what a network build costs on the real survey layers, so the answer to "will
 * this crash a phone?" comes from the data rather than from an estimate. Prints its
 * measurements; asserts only that every layer either builds or fails cleanly.
 */
class NetworkSizeProbeTest {

    @Test
    fun `report build cost per real layer`() {
        val dir = File("/home/mircea/ForestNavigator/FOR_NAV")
        assumeTrue(dir.isDirectory, "survey folder not present")
        for (name in listOf("access", "trails", "roads")) {
            val shp = File(dir, "$name.shp")
            if (!shp.isFile) { println("PROBE $name missing"); continue }
            val bytes = shp.readBytes()
            val h = org.gcap.core.ShpFormat.shpHeader(bytes)!!
            val shapes = org.gcap.core.ShpFormat.readShp(bytes, h.xmin, h.ymin, h.xmax, h.ymax)
            // SourceLine takes one flat e,n,e,n array and a route class
            val lines = ArrayList<SourceLine>()
            for (sh in shapes) {
                for (part in sh.parts) {
                    if (part.size < 2) continue
                    val flat = DoubleArray(part.size * 2)
                    part.forEachIndexed { i, xy -> flat[i * 2] = xy[0]; flat[i * 2 + 1] = xy[1] }
                    lines.add(SourceLine(flat, name, RouteClass.ROAD))
                }
            }
            println("PROBE $name features=${lines.size} mb=${bytes.size / 1048576}")
            for (tol in listOf(3.0, 10.0, 30.0)) {
                val used0 = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                val t0 = System.currentTimeMillis()
                try {
                    val (_, r) = NetworkBuilder(toleranceM = tol).build(lines)
                    println(
                        "PROBE   $name tol=${tol.toInt()}ms=${System.currentTimeMillis() - t0} " +
                            "nodes=${r.nodeCount} edges=${r.edgeCount} regions=${r.regionCount}",
                    )
                } catch (e: OutOfMemoryError) {
                    println(
                        "PROBE   $name tol=${tol.toInt()} OUT_OF_MEMORY after " +
                            "${System.currentTimeMillis() - t0}ms heapWas=${used0 / 1048576}MB",
                    )
                    break
                } catch (e: Exception) {
                    println("PROBE   $name tol=${tol.toInt()} failed ${e.javaClass.simpleName}: ${e.message}")
                    break
                }
            }
        }
    }
}
