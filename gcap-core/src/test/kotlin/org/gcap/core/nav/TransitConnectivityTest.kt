package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Does the recorded flight path actually connect one triple's B call to the next A call?
 *
 * The user is right that straight lines between calls discard the flown line, and that
 * the flown line follows smooth ascents and descents around ridges. This checks whether
 * the recorded geometry can carry the route at all.
 */
class TransitConnectivityTest {

    private val dir = File(System.getenv("FORNAV_DATA") ?: (System.getProperty("user.home") + "/ForestNavigator/FOR_NAV"))

    /**
     * These tests measure the real flight survey, which needs Air_Calls_YR_4. A folder
     * with only the much smaller air_calls has nothing to measure, so the test says so
     * instead of asserting against empty results.
     */
    private fun requireAirCalls() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            SurveyLayers.airCallsDbf(dir) != null, "Air_Calls_YR_4 is not in the survey folder",
        )
    }

    /** The real Air_Calls_YR_4 sidecars; these tests skip when the folder has none. */
    private fun airCallsDbf(d: File): File? =
        SurveyLayers.airCallsDbf(d)

    private fun bounds(f: String): DoubleArray {
        val h = ShpFormat.shpHeader(File(dir, f).readBytes())!!
        return doubleArrayOf(h.xmin, h.ymin, h.xmax, h.ymax)
    }

    private fun lines(): List<DoubleArray> {
        val b = bounds("flight_path.shp")
        val out = ArrayList<DoubleArray>()
        for (s in ShpFormat.readShp(File(dir, "flight_path.shp").readBytes(), b[0], b[1], b[2], b[3])) {
            for (part in s.parts) {
                val flat = DoubleArray(part.size * 2)
                part.forEachIndexed { i, p -> flat[i * 2] = p[0]; flat[i * 2 + 1] = p[1] }
                if (flat.size >= 4) out.add(flat)
            }
        }
        return out
    }

    private fun calls(): Map<String, AirCall> {
        val out = LinkedHashMap<String, AirCall>()
        for (row in ShpFormat.readDbfRows(airCallsDbf(dir)!!.readBytes())) {
            if (row.deleted) continue
            val v = row.values
            val nm = v.getOrNull(1)?.trim().orEmpty()
            if (nm.isEmpty() || nm.last().isDigit()) continue
            val n = v.getOrNull(4)?.trim()?.toDoubleOrNull() ?: continue
            val e = v.getOrNull(5)?.trim()?.toDoubleOrNull() ?: continue
            out[nm] = AirCall(nm, e, n)
        }
        return out
    }

    @Test
    fun `traces the plan through the recorded path`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val ls = lines()
        val cs = calls()
        val ordered = cs.values.sortedWith(AirCallOrder)

        for (weld in listOf(150.0, 250.0, 500.0)) {
            val plan = FlightPlan.buildTraced(ordered, ls, weldM = weld)
            val traced = plan.segments.count { it.traced }
            val straight = plan.segments.size - traced
            val transects = plan.transects
            val transits = plan.transits
            val vt = transects.filter { it.traced }.map { it.vertexCount }
            val vi = transits.filter { it.traced }.map { it.vertexCount }
            println("TRACE weld=${weld.toInt()} m: traced $traced of ${plan.segments.size} " +
                "(transects ${transects.count { it.traced }}/${transects.size}, " +
                "transits ${transits.count { it.traced }}/${transits.size}) straight=$straight")
            if (vt.isNotEmpty()) println("TRACE   transect vertices: min ${vt.min()} max ${vt.max()}")
            if (vi.isNotEmpty()) println("TRACE   transit vertices:  min ${vi.min()} max ${vi.max()}")
            if (weld == 500.0) {
                val straightTransits = transits.filter { !it.traced }.map { "${it.from.name}->${it.to.name}" }
                println("TRACE   transits still straight (first 12): ${straightTransits.take(12).joinToString(", ")}")
            }
        }
    }

    @Test
    fun `reports how many transits the recorded path connects`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val ls = lines()
        val cs = calls()
        val ordered = cs.values.sortedWith(AirCallOrder)
        val transits = ArrayList<Pair<AirCall, AirCall>>()
        for (i in 0 until ordered.size - 1) {
            val a = ordered[i]
            val b = ordered[i + 1]
            if (a.suffix == "B" && b.suffix == "A" && b.order == a.order + 1) transits += a to b
        }
        println("CONN transits=${transits.size} flight_path features=${ls.size}")

        for (weld in listOf(150.0, 250.0, 500.0)) {
            val g = NetworkBuilder.forFlightPath(weld).build(
                ls.map { SourceLine(it, "flight_path", RouteClass.AIR) },
            ).first
            var connected = 0
            var bothOn = 0
            val byRegion = HashMap<Int, Int>()
            for ((a, b) in transits) {
                val ra = g.nearestOnNetwork(a.e, a.n, maxDistanceM = 200.0)
                val rb = g.nearestOnNetwork(b.e, b.n, maxDistanceM = 200.0)
                if (ra != null && rb != null) {
                    bothOn++
                    val regionA = g.regionOfEdge[ra.edge]
                    val regionB = g.regionOfEdge[rb.edge]
                    byRegion.merge(regionA, 1, Int::plus)
                    if (regionA == regionB) connected++
                }
            }
            val nodeDisp = NetworkBuilder.forFlightPath(weld).build(
                ls.map { SourceLine(it, "flight_path", RouteClass.AIR) },
            ).second.nodeDisplacementM
            println("CONN weld=${weld.toInt()} m: regions=${g.regionCount} " +
                "both ends on the path=$bothOn/${transits.size} SAME REGION=$connected " +
                "nodeDisplacement=${"%.0f".format(nodeDisp)} m")
        }

        // the case the user singled out
        val a = cs["234B"]!!
        val b = cs["235A"]!!
        println("CONN 234B at (${a.e}, ${a.n})  235A at (${b.e}, ${b.n})  straight ${"%.0f".format(Geodesy.distanceProjectedM(a.e, a.n, b.e, b.n))} m")
        val g30 = NetworkBuilder.forFlightPath(30.0).build(
            ls.map { SourceLine(it, "flight_path", RouteClass.AIR) },
        ).first
        for (c in listOf(a, b)) {
            val hit = g30.nearestOnNetwork(c.e, c.n, maxDistanceM = 600.0)
            println("CONN ${c.name}: " + if (hit == null) "no path within 600 m" else
                "${"%.0f".format(hit.distanceM)} m from edge ${hit.edge}, region ${g30.regionOfEdge[hit.edge]}")
        }
    }
}
