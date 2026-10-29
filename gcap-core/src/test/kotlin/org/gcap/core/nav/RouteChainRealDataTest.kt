package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the route chain over the real flight path and air calls.
 * Skipped when the data is absent.
 */
class RouteChainRealDataTest {

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

    private fun bytes(name: String): ByteArray? = File(dir, name).takeIf { it.exists() }?.readBytes()

    private fun bounds(file: String): DoubleArray {
        val h = ShpFormat.shpHeader(bytes(file)!!)!!
        return doubleArrayOf(h.xmin, h.ymin, h.xmax, h.ymax)
    }

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

    /** Air calls with their CALL_ID, the A and B ones the chain runs over. */
    private fun readAbCalls(): List<AirCall> {
        val dbf = ShpFormat.readDbfRows(airCallsDbf(dir)!!.readBytes())
        val out = ArrayList<AirCall>()
        for (row in dbf) {
            if (row.deleted) continue
            val v = row.values
            val name = v.getOrNull(1)?.trim().orEmpty()
            if (name.isEmpty() || name.last().isDigit()) continue      // bare calls dropped
            val northing = v.getOrNull(4)?.trim()?.toDoubleOrNull() ?: continue
            val easting = v.getOrNull(5)?.trim()?.toDoubleOrNull() ?: continue
            out.add(AirCall(name, easting, northing))
        }
        return out
    }

    @Test
    fun `chains the simplified route lines`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")

        val raw = readLines("flight_path.shp")
        val calls = readAbCalls()
        val prep = FlightRouteLines.build(raw, calls)
        println("PREP ${prep.summary()}")
        println("PREP A/B pairs=${FlightRouteLines.pairsOf(calls).size} " +
            "rejected=${prep.rejected.size} nearest=${prep.rejected.minOfOrNull { it.lengthM }?.let { "%.0f m".format(it) }}")

        for (pref in listOf(EndPreference.NEAREST, EndPreference.CONTINUE_LINE)) {
            val chain = RouteChainBuilder(
                prep.all, calls, weldMaxM = 200.0, preference = pref,
            ).build()
            val reached = calls.size - chain.unreached.size
            println("PREP $pref -> reached=$reached/${calls.size} features=${chain.featuresInOrder.size} " +
                "fallbacks=${chain.fallbackCount}")
            chain.stopReason?.let { println("PREP   stop: $it") }
        }

        // Was the triple the walk stopped at among the rejected pairs? If so the missing
        // connector, not the weld distance, is what stopped it.
        for (n in listOf("005", "006", "007")) {
            val rej = prep.rejected.filter { it.from.name.startsWith(n) }
            println("PREP triple $n rejected=${rej.size}" +
                (if (rej.isNotEmpty()) " A->B ${"%.0f".format(rej[0].lengthM)} m" else ""))
        }

        // How far does the walk get as the two limits are relaxed?
        // The gap that stops the walk is 214 m, so the interesting range is just above
        // it. Going much wider derails the walk onto a 4990 m feature that belongs
        // elsewhere, which costs calls rather than gaining them.
        println("PREP narrow weld sweep at minFeature 200, connector 350:")
        for (weld in listOf(200.0, 210.0, 215.0, 220.0, 230.0)) {
            val p2 = FlightRouteLines.build(raw, calls, maxConnectorM = 350.0)
            val c2 = RouteChainBuilder(p2.all, calls, weldMaxM = weld,
                preference = EndPreference.CONTINUE_LINE).build()
            val stop = c2.stopReason?.substringAfter("loose end of")?.trim()?.take(46) ?: "reached the end"
            println(String.format("PREP   weld %3.0f m -> %3d of %d  features=%2d  fallbacks=%d  %s",
                weld, calls.size - c2.unreached.size, calls.size,
                c2.featuresInOrder.size, c2.fallbackCount, stop))
        }
    }

    @Test
    fun `exports the real flight plan for qgis`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")

        val raw = readLines("flight_path.shp")
        val calls = readAbCalls()
        // trace through the recorded flight path so the plan follows the line actually
        // flown rather than a chord between the calls
        val plan = FlightPlan.buildTraced(calls, raw, weldM = 500.0)

        val outDir = File(System.getenv("FORNAV_EXPORT") ?: (System.getProperty("user.home") + "/ForestNavigator/flight_plan_export"))
        outDir.mkdirs()
        val prj = FlightPlanWriter.prjTextOf(dir, "Air_Calls_YR_4.shp")
        val writer = FlightPlanWriter(plan, calls)
        writer.writeLegs(outDir, "flight_plan2", prj)
        writer.writeCalls(outDir, "flight_plan2_calls", prj)

        println("EXPORT ${writer.summary()}")
        println("EXPORT written to ${outDir.absolutePath}")
        outDir.listFiles()?.sortedBy { it.name }?.forEach {
            println("EXPORT   ${it.name}  ${it.length()} bytes")
        }

        // the export has to be readable, or QGIS shows an empty layer
        val legs = File(outDir, "flight_plan2.shp").readBytes()
        val info = org.gcap.core.ShpFormat.shpHeader(legs)!!
        println("EXPORT legs header: records=${info.recordCount} type=${info.shapeType}")
        assertEquals(plan.segments.size, info.recordCount)

        val callsShp = File(outDir, "flight_plan2_calls.shp").readBytes()
        val cInfo = org.gcap.core.ShpFormat.shpHeader(callsShp)!!
        println("EXPORT calls header: records=${cInfo.recordCount} type=${cInfo.shapeType}")
        assertEquals(calls.size, cInfo.recordCount)
        assertTrue(File(outDir, "flight_plan2.prj").exists(), "the export needs a .prj to line up")
    }

    @Test
    fun `builds the real flight plan`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")

        val raw = readLines("flight_path.shp")
        val calls = readAbCalls()
        val plan = FlightPlan.build(calls, flightPath = raw, pathToleranceM = 100.0)

        println("PLAN ${plan.summary()}")
        val tl = plan.transects.map { it.distanceM }.sorted()
        println("PLAN transect length: p50 ${"%.0f".format(tl[tl.size / 2])} m  max ${"%.0f".format(tl.last())} m")
        val tr = plan.transits.map { it.distanceM }.sorted()
        println("PLAN transit length:  p50 ${"%.0f".format(tr[tr.size / 2])} m  max ${"%.0f".format(tr.last())} m")

        val tf = plan.transects.count { it.followsPath }
        val trf = plan.transits.count { it.followsPath }
        println("PLAN transects following the path: $tf of ${plan.transects.size}")
        println("PLAN transits following the path:  $trf of ${plan.transits.size}")

        val gaps = plan.transits.map { it.worstGapM }.sorted()
        println("PLAN transit gap to path: p50 ${"%.0f".format(gaps[gaps.size / 2])} m  " +
            "p90 ${"%.0f".format(gaps[(gaps.size * 9) / 10])} m  max ${"%.0f".format(gaps.last())} m")
        val w = plan.worstLeg()!!
        println("PLAN worst leg: ${w.describe()}")

        // the plan must be complete whatever the path does
        assertEquals(291, plan.tripleCount)
        assertEquals(290, plan.transits.size)
        assertTrue(plan.transects.all { it.distanceM <= 400.0 }, "transects should be short")
        assertTrue(tf >= 280, "nearly every transect should sit on the path, got $tf")
    }

    @Test
    fun `chains the real flight path`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")

        val lines = readLines("flight_path.shp")
        val calls = readAbCalls()
        println("CHAINREAL features=${lines.size} A/B calls=${calls.size}")

        // Both strategies, so the choice is made on measurements rather than assertion.
        for (pref in listOf(EndPreference.NEAREST, EndPreference.CONTINUE_LINE)) {
            val c = RouteChainBuilder(lines, calls, weldMaxM = 200.0, preference = pref).build()
            println("CHAINREAL $pref -> ${c.summary()}")
            println("CHAINREAL   reached=${calls.size - c.unreached.size}/${calls.size} " +
                "features=${c.featuresInOrder.size} fallbacks=${c.fallbackCount}")
            c.stopReason?.let { println("CHAINREAL   stop: $it") }
        }

        val builder = RouteChainBuilder(lines, calls, weldMaxM = 200.0, preference = EndPreference.CONTINUE_LINE)
        println("CHAINREAL route points=${builder.route.size} first=${builder.route.first().name} " +
            "last=${builder.route.last().name}")

        val chain = builder.build()
        println("CHAINREAL ${chain.summary()}")
        println("CHAINREAL reached=${builder.route.size - chain.unreached.size}/${builder.route.size}")

        if (chain.unreached.isNotEmpty()) {
            val names = chain.unreached.map { it.name }
            println("CHAINREAL first unreached: ${names.take(6).joinToString(", ")}" +
                if (names.size > 6) " (+${names.size - 6} more)" else "")
        }
        chain.stopReason?.let { println("CHAINREAL stop: $it") }

        val reached = chain.steps.count { it.kind == StepKind.ROUTE_POINT }
        val gaps = chain.steps.filter { it.kind == StepKind.LINE_END }.map { it.distanceM }
        if (gaps.isNotEmpty()) {
            gaps.sorted()
            println("CHAINREAL line-end steps=${gaps.size} median=${"%.0f".format(gaps[gaps.size / 2])} m " +
                "max=${"%.0f".format(gaps.last())} m")
        }
        println("CHAINREAL route points reached=$reached features=${chain.featuresInOrder.size}")

        chain.steps.take(8).forEach { println("CHAINREAL   ${it.describe()}${if (it.viaFallback) "  [fallback]" else ""}") }

        // It must make real progress rather than stopping immediately, and it must never
        // reuse a feature or an end.
        assertTrue(chain.featuresInOrder.isNotEmpty(), "no features chained")
        assertTrue(
            chain.featuresInOrder.size == chain.featuresInOrder.distinct().size,
            "a feature was chained twice",
        )
        val spends = chain.steps.filter { it.kind == StepKind.LINE_END }
            .map { it.featureIndex * 2 + (if (it.endAtStart) 0 else 1) }
        assertTrue(spends.size == spends.distinct().size, "a line end was spent twice")
        assertTrue(reached >= 2, "expected several route points reached, got $reached")
    }

    private fun assertTrue(cond: Boolean, msg: String) =
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg)
}