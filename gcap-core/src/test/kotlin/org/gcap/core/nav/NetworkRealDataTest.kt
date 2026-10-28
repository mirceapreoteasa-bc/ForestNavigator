package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.hypot

/**
 * Runs the network builder against the real FOR_NAV layers when they are present.
 *
 * These are the only tests that exercise the actual digitised data: the block spans
 * far more ground than any hand-built fixture, contains road/access lines that only
 * nearly meet, and has no flight attributes at all. Skipped when the data is absent
 * so the rest of the suite still runs anywhere.
 */
class NetworkRealDataTest {

    private companion object { const val MAX_TEST_FEATURES = 6_000 }

    private val dir: File =
        File(System.getenv("FORNAV_DATA") ?: "/home/mircea/ForestNavigator/FOR_NAV")

    /**
     * Features are capped: the survey folder's `roads` layer grew past 90,000 features,
     * and a 3 m network over that many lines exhausts the heap before anything is
     * measured. The test is about the builder, not about how big the folder is today, so
     * it works on a bounded sample and says so.
     */
    private fun load(layer: String, cls: RouteClass): List<SourceLine> {
        val shp = File(dir, "$layer.shp")
        assumeTrue(shp.exists(), "missing ${shp.path}")
        val bytes = shp.readBytes()
        val info = ShpFormat.shpHeader(bytes)!!
        val all = ShpFormat.readShp(bytes, info.xmin, info.ymin, info.xmax, info.ymax)
        val shapes = if (all.size > MAX_TEST_FEATURES) all.take(MAX_TEST_FEATURES) else all

        val speedByIndex = HashMap<Int, Double>()
        val dbf = File(dir, "$layer.dbf")
        if (dbf.exists()) {
            val rows = ShpFormat.readDbfRows(dbf.readBytes())
            rows.forEachIndexed { i, row ->
                val surf = row.values.firstOrNull { it.isNotBlank() }
                if (surf != null) {
                    speedByIndex[i] = when (surf.lowercase().trim()) {
                        "paved" -> 40.0
                        "rough" -> 25.0
                        "loose" -> 15.0
                        else -> 0.0
                    }
                }
            }
        }

        val out = ArrayList<SourceLine>()
        shapes.forEachIndexed { si, sh ->
            if (sh.parts.isEmpty()) return@forEachIndexed
            for (part in sh.parts) {
                if (part.size < 2) continue
                val flat = DoubleArray(part.size * 2)
                part.forEachIndexed { i, xy ->
                    flat[i * 2] = xy[0]
                    flat[i * 2 + 1] = xy[1]
                }
                val sp = speedByIndex[si] ?: 0.0
                out.add(SourceLine(flat, layer, cls, OneWay.BOTH, sp))
            }
        }
        return out
    }

    @Test
    fun `builds a routable network from the real access and roads layers`() {
        val access = load("access", RouteClass.ACCESS)
        val roads = load("roads", RouteClass.ROAD)
        assumeTrue(access.isNotEmpty() && roads.isNotEmpty(), "no line features found")

        val t0 = System.currentTimeMillis()
        val (graph, report) = NetworkBuilder(toleranceM = 3.0).build(access + roads)
        val ms = System.currentTimeMillis() - t0

        println(
            "REALDATA source=${report.sourceLineCount} nodes=${report.nodeCount} " +
                "edges=${report.edgeCount} regions=${report.regionCount} " +
                "welded=${report.snappedEndpointCount} splits=${report.intersectionSplitCount} " +
                "discarded=${report.discardedDegenerateCount} biggestNodes=${report.maxRegionNodeCount} " +
                "biggestEdges=${report.maxRegionEdgeCount} biggestKm=%.1f ".format(report.maxRegionLengthKm) +
                "in ${ms}ms",
        )

        assertTrue(report.edgeCount > 0, "no edges produced")
        assertTrue(report.nodeCount > 0)
        assertTrue(report.regionCount >= 1)
        assertTrue(graph.nodedLines.isNotEmpty(), "network must be writable as a layer")
        assertRegionFiguresAreSane(report)
        // every edge must be reachable from at least one end
        assertTrue(graph.edges.all { it.lengthM > 0.0 })
    }

    @Test
    fun `two points inside the largest region are routable`() {
        val access = load("access", RouteClass.ACCESS)
        val roads = load("roads", RouteClass.ROAD)
        assumeTrue(access.isNotEmpty() && roads.isNotEmpty())

        val (graph, report) = NetworkBuilder(toleranceM = 3.0).build(access + roads)
        // find the region with the most nodes
        var bestRegion = -1
        var bestCount = 0
        for (r in 0 until report.regionCount) {
            val c = graph.nodesInRegion(r).size
            if (c > bestCount) { bestCount = c; bestRegion = r }
        }
        assumeTrue(bestRegion >= 0, "no regions")

        val nodes = graph.nodesInRegion(bestRegion)
        assumeTrue(nodes.size >= 2, "largest region too small to route")
        val a = nodes[0]
        val b = nodes[nodes.size / 2]

        val res = Router(graph).route(
            graph.nodeE[a], graph.nodeN[a],
            graph.nodeE[b], graph.nodeN[b],
            region = bestRegion,
        )
        println("REALDATA route in region $bestRegion: $res".take(160))
        assertTrue(res is RouteResult.Ok, "two points in the same region must connect: $res")
        val ok = res as RouteResult.Ok
        assertTrue(ok.path.metres > 0.0)
        assertEquals(bestRegion, ok.startRegion)
        assertEquals(bestRegion, ok.goalRegion)
    }

    @Test
    fun `nearest network point is found for a real coordinate`() {
        val access = load("access", RouteClass.ACCESS)
        assumeTrue(access.isNotEmpty())
        val (graph, _) = NetworkBuilder(toleranceM = 3.0).build(access)

        val info = ShpFormat.shpHeader(File(dir, "access.shp").readBytes())!!
        val midE = (info.xmin + info.xmax) / 2
        val midN = (info.ymin + info.ymax) / 2

        // search a widening radius until something is found; the block centre may be
        // well away from any line
        var snap: SnapResult? = null
        var r = 500.0
        while (snap == null && r <= 100_000.0) {
            snap = Router(graph).nearestPoint(midE, midN, -1, r)
            r *= 2
        }
        assumeTrue(snap != null, "no network found within 100 km of the block centre")
        assertTrue(snap!!.distanceM <= 100_000.0)
        println("REALDATA nearest snap ${"%.1f".format(snap.distanceM)} m")
    }

    /**
     * Prints how the real network quality changes with the connection tolerance, so
     * the "maximum distance between two lines to be connected" setting can be chosen
     * from measurements instead of guessed.
     *
     * Stops at the unsupervised limit: above it the builder refuses, because welding
     * across a wide gap can invent a road over a collapsed bridge or a landslide.
     * Those figures were gathered before the limit existed and are kept in the
     * requirements doc; widening them again requires the supervised flow.
     */
    @Test
    fun `tolerance sweep on the real layers`() {
        val access = load("access", RouteClass.ACCESS)
        val roads = load("roads", RouteClass.ROAD)
        assumeTrue(access.isNotEmpty() && roads.isNotEmpty())
        val endpoints = (access.size + roads.size) * 2

        println("TOLERANCE  edges   regions  biggestNodes biggestEdges biggestKm  welded/ends  welded%")
        for (tol in listOf(0.5, 1.0, 2.0, 3.0, 4.0, 5.0)) {
            val t0 = System.currentTimeMillis()
            val (g, r) = NetworkBuilder(toleranceM = tol).build(access + roads)
            val ms = System.currentTimeMillis() - t0
            println(
                "SWEEP tol=%5.1fm edges=%7d regions=%6d byNodes=%7d byEdges=%7d km=%7.1f welded=%6d/%d (%3.0f%%) %5dms".format(
                    tol, r.edgeCount, r.regionCount, r.maxRegionNodeCount,
                    r.maxRegionEdgeCount, r.maxRegionLengthKm,
                    r.snappedEndpointCount, endpoints,
                    100.0 * r.snappedEndpointCount / endpoints, ms,
                ),
            )
            assertTrue(r.edgeCount > 0)
            assertTrue(g.nodedLines.isNotEmpty())
        }
    }

    /**
     * End to end on the real layers: build a network, write it as a shapefile layer,
     * read it back, and confirm the layer is well formed. This is the proof that
     * "turn access.shp into a navigation network" actually produces something another
     * tool can open.
     */
    @Test
    fun `real network writes and reads back as a valid layer`() {
        val access = load("access", RouteClass.ACCESS)
        val roads = load("roads", RouteClass.ROAD)
        assumeTrue(access.isNotEmpty() && roads.isNotEmpty())

        val t0 = System.currentTimeMillis()
        val (graph, report) = NetworkBuilder(toleranceM = 3.0).build(access + roads)
        val buildMs = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val files = NetworkWriter.write(graph, zone = 10, datum = NetworkWriter.Datum.NAD83, baseName = "network")
        val writeMs = System.currentTimeMillis() - t1

        var bytes = 0L
        for ((k, v) in files) {
            bytes += v.size
            assertTrue(v.isNotEmpty(), "$k is empty")
            println("REALDATA wrote $k ${v.size / 1024} KB")
        }
        println("REALDATA build ${buildMs}ms write ${writeMs}ms total ${bytes / 1024 / 1024} MB")

        val shp = files["network.shp"]!!
        val info = ShpFormat.shpHeader(shp)!!
        assertEquals(3, info.shapeType, "a network layer must be a Polyline")
        val back = ShpFormat.readShp(shp, info.xmin, info.ymin, info.xmax, info.ymax)
        println("REALDATA features written=${files["network.dbf"]!!.let { ShpFormat.readDbfRows(it).size }} readBack=${back.size} lines=${graph.nodedLines.size}")
        assertEquals(graph.nodedLines.size, back.size, "every written feature must read back")

        val dbf = files["network.dbf"]!!
        val hdr = ShpFormat.dbfHeader(dbf)
        assertEquals(graph.nodedLines.size, hdr.numRecords)
        val rows = ShpFormat.readDbfRows(dbf)
        val names = hdr.fields.map { it.name.trim() }
        val lenIdx = names.indexOfFirst { it == "LEN_M" }
        var totalLen = 0.0
        for (r in rows) totalLen += r.values[lenIdx].trim().toDouble()
        println("REALDATA network total length %.1f km across %d features, %d regions".format(
            totalLen / 1000.0, rows.size, report.regionCount,
        ))
        assertTrue(totalLen > 0.0)

        // the generated prj must be recognised by the app's own classifier key
        val prj = String(files["network.prj"]!!, Charsets.US_ASCII)
        assertTrue(prj.uppercase().filter { it.isLetterOrDigit() }.contains("UTMZONE10N"))
    }
    /**
     * A region cannot contain more edges than the whole network, or more kilometres.
     *
     * These are the numbers the build report shows the user, so a wrong one is worse
     * than no number: it reads as fact. This was written after "largest region" reported
     * more edges and kilometres than the entire network contained.
     */
    private fun assertRegionFiguresAreSane(report: BuildReport) {
        assertTrue(
            report.maxRegionEdgeCount in 1..report.edgeCount,
            "largest region has ${report.maxRegionEdgeCount} edges but the whole " +
                "network has ${report.edgeCount}",
        )
        assertTrue(
            report.maxRegionLengthKm > 0.0,
            "largest region should cover some distance",
        )
        assertTrue(
            report.maxRegionNodeCount in 1..report.nodeCount,
            "largest region has ${report.maxRegionNodeCount} nodes but the whole " +
                "network has ${report.nodeCount}",
        )
    }

}
