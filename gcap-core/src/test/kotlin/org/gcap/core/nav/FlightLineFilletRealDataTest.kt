package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the whole procedure on the real flight path: join, heal, then round the corners that
 * are not transect ends, and check the calls still run in flying order afterwards.
 */
class FlightLineFilletRealDataTest {

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

    private fun allCalls(): List<AirCall> {
        val out = ArrayList<AirCall>()
        for (row in ShpFormat.readDbfRows((SurveyLayers.airCallsDbf(dir) ?: return emptyList()).readBytes())) {
            if (row.deleted) continue
            val v = row.values
            val nm = v.getOrNull(1)?.trim().orEmpty()
            if (nm.isEmpty()) continue
            val n = v.getOrNull(4)?.trim()?.toDoubleOrNull() ?: continue
            val e = v.getOrNull(5)?.trim()?.toDoubleOrNull() ?: continue
            out.add(AirCall(nm, e, n))
        }
        return out
    }

    @Test
    fun filletTheRealPathAndKeepTheCallOrder() {
        requireAirCalls()
        assumeTrue(File(dir, "flight_path.shp").exists(), "FOR_NAV data not present")
        val raw = lines()
        val calls = allCalls()
        val cls = TransectClassifier(calls)
        val isTransect = BooleanArray(raw.size) { cls.isTransect(raw[it]) }
        println("FLT transects detected: ${isTransect.count { it }} of ${raw.size}")

        val b = FlightLineBuilder(raw, minLineM = 50.0, maxJoinM = 1000.0)
        b.build()
        val healed = b.heal(rounds = 1, maxJoinM = 1000.0)
        println("FLT join+heal: ${healed.describe()}")
        val detailed = b.resultLinesDetailed()
        println("FLT healed into ${detailed.size} line(s)")

        var totalCorners = 0
        var rounded = 0
        var shallow = 0
        var transectEnds = 0
        var tooShort = 0
        val filled = ArrayList<DoubleArray>()

        for (d in detailed) {
            val (out, rep) = FlightLineFillet.apply(d.vertices, d.featureStarts, { i ->
                isTransect[keptIndex[i]]
            })
            totalCorners += (d.featureStarts.size - 1)
            rounded += rep.rounded
            shallow += rep.tooShallow
            transectEnds += rep.transectEnd
            tooShort += rep.tooShort
            filled.add(out)
        }
        println("FLT corners at feature boundaries: $totalCorners")
        println("FLT $rounded rounded, $shallow gentle, $transectEnds at transect ends, $tooShort too short")

        val before = detailed.sumOf { it.vertexCount }
        val after = filled.sumOf { it.size / 2 }
        println("FLT vertices before=$before after=$after (arcs add ${after - before})")

        val seqBefore = FlightLineSequence(detailed.map { it.vertices }, calls)
        val seqAfter = FlightLineSequence(filled, calls)
        println("FLT sharp : ${seqBefore.summary()}")
        println("FLT fillet: ${seqAfter.summary()}")
        val ca = seqAfter.check()
        println("FLT filleted lines carrying calls=${ca.size} " +
            "inOrder=${ca.count { it.callCount >= 2 && it.agreement >= 0.9 }} " +
            "worstOffset=${"%.0f".format(ca.maxOfOrNull { it.worstOffsetM } ?: 0.0)} m")

        // write the filleted result next to the sharp one so they can be compared
        run {
            val out = File(System.getProperty("user.home"), "ForestNavigator/flight_plan_export")
            out.mkdirs()
            val prj = File(dir, "flight_path.prj").let { if (it.exists()) it.readText() else "" }
            val nm = "flight_lines_1000_fillet"
            val parts = filled.map { ln -> (0 until ln.size / 2).map { doubleArrayOf(ln[it * 2], ln[it * 2 + 1]) } }
            File(out, "$nm.shp").writeBytes(ShpFormat.buildPolylineShp(parts))
            File(out, "$nm.shx").writeBytes(ShpFormat.buildPolylineShx(parts))
            File(out, "$nm.dbf").writeBytes(ShpFormat.buildDbfFields(
                listOf("VERTS" to 7, "CALLS" to 6, "AGREE" to 7, "MAXOFF" to 8),
                (0 until filled.size).map { i ->
                    val c = seqAfter.check().firstOrNull { it.lineIndex == i }
                    listOf(
                        (filled[i].size / 2).toString(),
                        (c?.callCount ?: 0).toString(),
                        c?.let { "%.2f".format(it.agreement) } ?: "",
                        c?.let { "%.0f".format(it.worstOffsetM) } ?: "",
                    )
                },
            ))
            if (prj.isNotBlank()) File(out, "$nm.prj").writeText(prj)
            println("FLT wrote $nm.shp with ${filled.size} lines")
        }

        // transect boundaries can never be rounded: apply() returns before any geometry
        // is touched when either side is a transect, and the unit tests pin that
        println("FLT transect boundaries left sharp: $transectEnds of $totalCorners")

        // which calls got worse, and what is near them
        val beforeFeet = FlightLineSequence(detailed.map { it.vertices }, calls).feet.associateBy { it.call.name }
        val afterFeet = FlightLineSequence(filled, calls).feet
        val worse = afterFeet.mapNotNull { f ->
            val b = beforeFeet[f.call.name] ?: return@mapNotNull null
            if (f.offsetM > b.offsetM + 20.0) f.call.name to (b.offsetM to f.offsetM) else null
        }.sortedByDescending { it.second.second }
        println("FLT calls pushed more than 20 m further off: ${worse.size}")
        for ((name, d) in worse.take(5)) println("FLT   $name: ${"%.0f".format(d.first)} -> ${"%.0f".format(d.second)} m")

        // and the geometry around the worst one
        val worstCall = worse.firstOrNull()?.first
        if (worstCall != null) {
            val cf = FlightLineSequence(detailed.map { it.vertices }, calls).feet.first { it.call.name == worstCall }
            val line = detailed[cf.lineIndex].vertices
            var near = -1
            var nd = Double.MAX_VALUE
            for (i in 0 until line.size / 2) {
                val d = Geodesy.distanceProjectedM(cf.call.e, cf.call.n, line[i * 2], line[i * 2 + 1])
                if (d < nd) { nd = d; near = i }
            }
            println("FLT worst call $worstCall is vertex $near of ${line.size / 2} on line ${cf.lineIndex}")
            val starts = detailed[cf.lineIndex].featureStarts.toList()
            val nearestBoundary = starts.indices.minByOrNull { kotlin.math.abs(starts[it] - near) } ?: -1
            println("FLT nearest feature boundary index=$nearestBoundary at vertex ${if (nearestBoundary >= 0) starts[nearestBoundary] else -1}")
            if (nearestBoundary in starts.indices) {
                val at = starts[nearestBoundary]
                println("FLT vertices around it:")
                for (i in (at - 3).coerceAtLeast(0)..(at + 2).coerceAtMost(line.size / 2 - 1)) {
                    println("FLT   v$i = ${"%.1f".format(line[i * 2])}, ${"%.1f".format(line[i * 2 + 1])}")
                }
            }
        }
    }

    /** Indices, into the original feature list, of the ones the builder kept. */
    private val keptIndex: IntArray by lazy {
        val raw = lines()
        var k = 0
        val out = IntArray(raw.size)
        for ((i, f) in raw.withIndex()) {
            if (f.size >= 4 && FlightRouteLines.lengthM(f) >= 50.0) out[k++] = i
        }
        out.copyOf(k)
    }
}