package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Builds the real flight network from FOR_NAV and reports what the weld produced.
 * Skipped when the data is absent.
 */
class FlightNetworkRealDataTest {

    private val dir = File(System.getenv("FORNAV_DATA") ?: "/home/mircea/ForestNavigator/FOR_NAV")

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

    private fun bytes(name: String): ByteArray? = File(dir, name).takeIf { it.exists() }?.readBytes()

    private fun bounds(file: String): DoubleArray {
        val h = ShpFormat.shpHeader(bytes(file)!!)!!
        return doubleArrayOf(h.xmin, h.ymin, h.xmax, h.ymax)
    }

    /** Polyline features as flat e,n arrays, one per part. */
    private fun readLines(file: String): List<DoubleArray> {
        val b = bounds(file)
        val out = ArrayList<DoubleArray>()
        for (s in ShpFormat.readShp(bytes(file)!!, b[0], b[1], b[2], b[3])) {
            for (part in s.parts) {
                val flat = DoubleArray(part.size * 2)
                part.forEachIndexed { i, p -> flat[i * 2] = p[0]; flat[i * 2 + 1] = p[1] }
                if (flat.size >= 4) out.add(flat)
            }
        }
        return out
    }

    /** Point features as (e, n). */
    private fun readPoints(file: String): List<DoubleArray> {
        val b = bounds(file)
        return ShpFormat.readShp(bytes(file)!!, b[0], b[1], b[2], b[3])
            .mapNotNull { s -> s.parts.firstOrNull()?.firstOrNull()?.let { doubleArrayOf(it[0], it[1]) } }
    }

    /**
     * Air calls from `Air_Calls_YR_4`, which carries a real `CALL_ID` and named
     * coordinate fields.
     *
     * Field order there is OBJECTID, CALL_ID, Elevation, Z_meters, Y_Coor, X_Coor.
     * The older `air_calls.dbf` has no name field at all - its `F001A` is a cycling
     * label - so `CALL_ID` is the only column that carries the flying order.
     */
    private fun readCalls(): List<AirCall> {
        val dbf = ShpFormat.readDbfRows(airCallsDbf(dir)!!.readBytes())
        val out = ArrayList<AirCall>()
        for (row in dbf) {
            if (row.deleted) continue
            val v = row.values
            val name = v.getOrNull(1)?.trim().orEmpty()
            val northing = v.getOrNull(4)?.trim()?.toDoubleOrNull() ?: continue
            val easting = v.getOrNull(5)?.trim()?.toDoubleOrNull() ?: continue
            if (name.isEmpty()) continue
            out.add(AirCall(name, easting, northing))
        }
        return out
    }

    /** Point features as (e, n). */
    private fun readCallPoints(): List<DoubleArray> = readPoints(SurveyLayers.airCalls(dir)!!.name)

    @Test
    fun `builds the real flight network at 30 m`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")

        val lines = readLines("flight_path.shp")
        val calls = readCalls()
        println("FLIGHTREAL path features=${lines.size} calls=${calls.size} " +
            "(from Air_Calls_YR_4, CALL_ID)")

        // the call-name convention must hold on the real layer, and must be exactly the
        // order that plain text sorting would get wrong
        val names = calls.map { it.name }
        assertEquals(names.size, names.distinct().size, "CALL_ID must be unique")
        val byRule = calls.sortedWith(AirCallOrder).map { it.name }
        assertTrue(byRule != names.sorted(), "guard: text order must differ from the rule")

        // the canonical sequence: 001A, 001, 001B, 002A, ... 291A, 291, 291B
        val canonical = (1..291).flatMap { i ->
            listOf("%03dA".format(i), "%03d".format(i), "%03dB".format(i))
        }
        assertEquals(canonical, byRule, "the rule must reproduce the canonical order")
        assertEquals("001A", byRule.first())
        assertEquals("291B", byRule.last())

        // 291 numbers x 3 (A, bare, B), each name unique
        assertEquals(291, calls.count { it.suffix == "A" })
        assertEquals(291, calls.count { it.suffix.isEmpty() })
        assertEquals(291, calls.count { it.suffix == "B" })
        assertEquals(291, calls.map { it.order }.distinct().size)

        // File order is *not* reliable: one record, 217B, sits far from its triple,
        // between 195B and 218A. Sorting by the rule puts it back, which is precisely
        // why the order is derived rather than read off the file.
        // File order is not the flying order. It runs 001-195, then 217B, then
        // 218-291, then 196-217, so the three calls of number 217 are split apart with
        // 217B stranded 66 records early. Deriving the order rather than reading it off
        // the file is what puts them back together.
        assertTrue(names != byRule, "file order must not be trusted here")
        val posOf = names.withIndex().associate { (i, n) -> n to i }
        val triple217 = listOf("217A", "217", "217B").map { posOf.getValue(it) }.sorted()
        assertTrue(triple217[1] - triple217[0] != 1 || triple217[2] - triple217[1] != 1,
            "the 217 triple should be split in file order")
        assertTrue(byRule.indexOf("217B") - byRule.indexOf("217") == 1,
            "sorting must restore the A, bare, B order for 217")
        println("FLIGHTREAL 217 at file positions ${triple217.joinToString(",")}, " +
            "sorted to ${byRule.indexOf("217A")},${byRule.indexOf("217")},${byRule.indexOf("217B")}")

        val fn = FlightNetwork(lines, calls)
        val rep = fn.report!!
        println("FLIGHTREAL weld=${NetworkBuilder.FLIGHT_PATH_WELD_M.toInt()} m")

        println("FLIGHTREAL weld=30 m -> nodes=${rep.nodeCount} edges=" +
            "${fn.graph!!.edges.size} regions=${rep.regionCount}")
        println("FLIGHTREAL path length=${"%.1f".format(fn.pathLengthM() / 1000.0)} km")
        println("FLIGHTREAL unplaced=${fn.unplacedCalls.size} " +
            "worst call offset=${"%.1f".format(fn.placements.filter { it.onPath }.maxOfOrNull { it.offsetM } ?: -1.0)} m " +
            "worst node displacement=${"%.1f".format(rep.nodeDisplacementM)} m")

        // The calls sit within ~11 m of the raw, unwelded track, but the welded graph
        // cannot match that: a node welded across a gap has to sit somewhere between the
        // two ends, and each edge is then a chord between welded nodes. So drift grows
        // with the tolerance. This bound is what stops weld *chaining* - without the
        // centroid gate a "150 m" weld chained into 300 m spans and drifted far past it.
        val worst = fn.placements.filter { it.onPath }.maxOf { it.offsetM }
        val ceiling = NetworkBuilder.FLIGHT_PATH_WELD_M + 50.0
        assertTrue(worst <= ceiling,
            "calls drifted ${"%.1f".format(worst)} m, beyond the ${"%.0f".format(ceiling)} m ceiling")
        assertTrue(rep.nodeDisplacementM <= NetworkBuilder.FLIGHT_PATH_WELD_M,
            "node displacement ${"%.1f".format(rep.nodeDisplacementM)} m exceeds the weld tolerance")

        assertTrue(rep.nodeCount > 0, "nothing welded")
        assertTrue(fn.placements.count { it.onPath } > 250,
            "expected nearly all calls on the path, got ${fn.placements.count { it.onPath }}")

        // name order is 1..291 and must survive
        val orders = fn.orderedCalls.map { it.order }
        assertTrue(orders == orders.sorted(), "calls are not in numeric order")

        // How the weld distance affects connectivity, for information only. The
        // sequence below does not depend on it - this just records the trade-off.
        for (w in doubleArrayOf(30.0, 150.0, 300.0)) {
            val f = FlightNetwork(lines, calls, weldM = w)
            println("FLIGHTREAL   weld=${w.toInt()} m -> regions=${f.report!!.regionCount} " +
                "unplaced=${f.unplacedCalls.size}")
        }

        // report the flights
        println("FLIGHTREAL flights=${fn.runs.size}")
        fn.runs.forEach { r ->
            println("FLIGHTREAL   ${r.first.name}..${r.last.name} calls=${r.calls.size} " +
                "startGap=${"%.1f".format(r.startGapM / 1000.0)} km " +
                "newFlight=${r.brokeAfterPrevious}")
        }

        // the resume behaviour the user asked about
        val a = fn.orderedCalls.first { it.name == "101A" }
        println("FLIGHTREAL resume 101A -> ${fn.nextAfter(a)?.name}")
        assertEquals("101", fn.nextAfter(a)?.name, "101A leads to 101")
        assertEquals("101B", fn.nextAfter(fn.orderedCalls.first { it.name == "101" })?.name,
            "101 leads to 101B, so A then bare then B is the working order")
        assertEquals("102A", fn.nextAfter(fn.orderedCalls.first { it.name == "101B" })?.name)

        // how many within-triple legs can actually be drawn at this weld distance
        val pts = readCallPoints()
        val ordered = fn.orderedCalls
        val byName = ordered.zip(pts).toMap()
        var shortLeg = 0
        var drawn = 0
        for (i in 0 until ordered.size - 1) {
            val s = ordered[i].suffix
            if (s != "A" && s.isNotEmpty()) continue          // only within a triple
            val a2 = ordered[i]; val b2 = ordered[i + 1]
            val d = Geodesy.distanceProjectedM(a2.e, a2.n, b2.e, b2.n)
            drawn++
            if (d > NetworkBuilder.FLIGHT_PATH_WELD_M) shortLeg++
        }
        println("FLIGHTREAL within-triple legs=$drawn beyond the ${NetworkBuilder.FLIGHT_PATH_WELD_M.toInt()} m weld=$shortLeg")

        // resume behaviour on the real data
        val first = fn.orderedCalls.first()
        val next = fn.nextAfter(first)
        println("FLIGHTREAL resume: after ${first.name} the app offers ${next?.name}")
        assertTrue(next != null, "first call should lead somewhere")

        val lastOfFirstRun = fn.runs[0].last
        assertTrue(fn.nextAfter(lastOfFirstRun) == null,
            "the last call of a run must not lead into another flight")
    }
}
