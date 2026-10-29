package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The whole pipeline on the real survey data, as the app will run it.
 *
 * This is the integration test for [FlightLinePipeline] and
 * [FlightPlan.buildFromFlownLines]: one continuous line, every leg a slice of
 * it, the calls in flying order, and the exported shapefile checked by hand in
 * QGIS against the layer it came from.
 */
class FlightLinePipelineRealDataTest {

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

    /** All calls, bare names included: the transect classifier needs the bare ones. */
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
    fun oneContinuousLineWithEveryCallInFlyingOrder() {
        requireAirCalls()
        assumeTrue(File(dir, "flight_path.shp").exists(), "FOR_NAV data not present")
        val raw = lines()
        val calls = allCalls()

        val res = FlightLinePipeline.run(raw, calls)
        println("PIPE ${res.report.describe()}")
        println("PIPE result   : ${res.lines.size} line(s), ${res.lines.sumOf { it.size / 2 }} vertices")

        val seq = FlightLineSequence(res.lines, calls)
        println("PIPE sequence : ${seq.summary()}")
        val checks = seq.check()
        val ordered = checks.count { it.callCount >= 2 && it.agreement >= 0.9 }
        val worst = checks.maxOfOrNull { it.worstOffsetM } ?: 0.0
        println(
            "PIPE order    : $ordered of ${checks.size} line(s) with calls in flying order, " +
                "worst offset ${"%.0f".format(worst)} m",
        )

        assertEquals(1, res.lines.size, "the survey should allow exactly one continuous line")
        assertEquals(582, seq.feet.size, "every A/B call should land on the flown line")
        assertEquals(582, seq.feet.count { it.offsetM <= 50.0 })
        assertEquals(1, ordered, "the one line should have its calls in flying order")
        assertTrue(worst <= 50.0, "worst call offset was $worst m")

        write(res.lines, checks)

        // the plan the app will draw: every leg a slice of the flown line
        val plan = FlightPlan.buildFromFlownLines(calls, raw)
        val traced = plan.segments.count { it.traced }
        val transects = plan.segments.count { it.leg == PlanLeg.TRANSECT }
        val transits = plan.segments.count { it.leg == PlanLeg.TRANSIT }
        val untraced = plan.segments.filter { !it.traced }
        println("PIPE plan     : ${plan.summary()}")
        println("PIPE legs     : $traced traced of ${plan.segments.size} ($transects transects, $transits transits)")
        for (seg in untraced) println("PIPE   untraced: ${seg.describe()}")
        val worstLeg = plan.worstLeg()
        if (worstLeg != null) println("PIPE worst leg: ${worstLeg.describe()}, gap ${"%.0f".format(worstLeg.worstGapM)} m")

        assertEquals(291, transects, "one transect per call triple")
        assertEquals(290, transits, "one transit between each pair of triples")
        assertEquals(plan.segments.size, traced, "every leg should follow the flown line")
        assertTrue(plan.transitsWithoutPath == 0, "no transit should be left without path geometry")
        for (seg in plan.segments) {
            assertTrue(seg.vertexCount >= 2, "${seg.from.name}->${seg.to.name} has no geometry")
        }
    }

    private fun write(lines: List<DoubleArray>, checks: List<FlightLineSequence.LineCheck>) {
        val out = File(System.getProperty("user.home"), "ForestNavigator/flight_plan_export")
        out.mkdirs()
        val prj = File(dir, "flight_path.prj").let { if (it.exists()) it.readText() else "" }
        val nm = "flight_lines_1000_full"
        val parts = lines.map { ln -> (0 until ln.size / 2).map { doubleArrayOf(ln[it * 2], ln[it * 2 + 1]) } }
        File(out, "$nm.shp").writeBytes(ShpFormat.buildPolylineShp(parts))
        File(out, "$nm.shx").writeBytes(ShpFormat.buildPolylineShx(parts))
        File(out, "$nm.dbf").writeBytes(ShpFormat.buildDbfFields(
            listOf("VERTS" to 7, "CALLS" to 6, "AGREE" to 7, "MAXOFF" to 8),
            (0 until lines.size).map { i ->
                val c = checks.firstOrNull { it.lineIndex == i }
                listOf(
                    (lines[i].size / 2).toString(),
                    (c?.callCount ?: 0).toString(),
                    c?.let { "%.2f".format(it.agreement) } ?: "",
                    c?.let { "%.0f".format(it.worstOffsetM) } ?: "",
                )
            },
        ))
        if (prj.isNotBlank()) File(out, "$nm.prj").writeText(prj)
        println("PIPE wrote    : $nm.shp with ${lines.size} line(s)")
    }
}