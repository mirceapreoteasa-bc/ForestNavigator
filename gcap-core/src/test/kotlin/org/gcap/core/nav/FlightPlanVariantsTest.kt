package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Builds ten variants of the flight plan so one can be chosen on evidence rather than
 * taste.
 *
 * The criterion is accuracy: use as many of the long `flight_path` features as possible,
 * and end up with one continuous line visiting the calls in order. Both are measured here
 * and printed, so the variants can be compared side by side.
 */
class FlightPlanVariantsTest {

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

    private fun bytes(n: String) = File(dir, n).readBytes()

    private fun lines(): List<DoubleArray> {
        val h = ShpFormat.shpHeader(bytes("flight_path.shp"))!!
        val out = ArrayList<DoubleArray>()
        for (s in ShpFormat.readShp(bytes("flight_path.shp"), h.xmin, h.ymin, h.xmax, h.ymax)) {
            for (part in s.parts) {
                val f = DoubleArray(part.size * 2)
                part.forEachIndexed { i, p -> f[i * 2] = p[0]; f[i * 2 + 1] = p[1] }
                if (f.size >= 4) out.add(f)
            }
        }
        return out
    }

    private fun calls(): List<AirCall> {
        val out = ArrayList<AirCall>()
        for (row in ShpFormat.readDbfRows(bytes("Air_Calls_YR_4.dbf"))) {
            if (row.deleted) continue
            val v = row.values
            val nm = v.getOrNull(1)?.trim().orEmpty()
            if (nm.isEmpty() || nm.last().isDigit()) continue
            val n = v.getOrNull(4)?.trim()?.toDoubleOrNull() ?: continue
            val e = v.getOrNull(5)?.trim()?.toDoubleOrNull() ?: continue
            out.add(AirCall(nm, e, n))
        }
        return out
    }

    /** One combination of the choices that change the result. */
    private data class Variant(
        val n: Int,
        val weldM: Double,
        val minFeatureM: Double,
    ) {
        val label get() = "weld ${weldM.toInt()} m, keep features >= ${minFeatureM.toInt()} m"
    }

    /** How far a loose end may be from the next call in order, as the crew specified. */
    private val NEXT_CALL_REACH_M = 275.0

    private fun variants() = listOf(
        // snap must exceed the distance from a call to the nearest path feature, which is a
        // median of 70 m: at snap 50 most calls cannot attach at all and tracing collapses.
        // Every variant now uses the rule walk, never shortest-path routing. The grid spans
        // the weld distance and how short a feature may be before it is discarded.
        Variant(1, 150.0, 200.0),
        Variant(2, 200.0, 200.0),
        Variant(3, 250.0, 200.0),
        Variant(4, 300.0, 200.0),
        Variant(5, 350.0, 200.0),
        Variant(6, 400.0, 200.0),
        Variant(7, 250.0, 100.0),
        Variant(8, 350.0, 100.0),
        Variant(9, 400.0, 100.0),
        Variant(10, 350.0, 50.0),
    )

    @Test
    fun `measures whether the reuse surcharge increases feature coverage`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val raw = lines()
        val cs = calls()
        val longFeats = raw.filter { FlightRouteLines.lengthM(it) >= 200.0 }
        val longLen = longFeats.sumOf { FlightRouteLines.lengthM(it) }

        println("SURCH long features=${longFeats.size} total ${"%.0f".format(longLen / 1000.0)} km")
        println("SURCH weld snap surch  traced  straight  usedLong  cover%  lengthKm")
        for (weld in listOf(250.0, 400.0, 500.0)) {
            for (sur in listOf(0.0, -1.0)) {
                val plan = if (sur < 0) {
                    FlightPlan.buildByRule(cs, longFeats, weldM = weld)
                } else {
                    FlightPlan.buildTraced(
                        cs, longFeats, weldM = weld, maxSnapM = 200.0, reuseSurchargeM = sur,
                    )
                }
                val pts = ArrayList<DoubleArray>()
                for (seg in plan.segments) {
                    for (i in 0 until seg.vertexCount) {
                        pts.add(doubleArrayOf(seg.geometry[i * 2], seg.geometry[i * 2 + 1]))
                    }
                }
                var used = 0
                var usedLen = 0.0
                for (f in longFeats) {
                    var touch = false
                    var k = 0
                    while (k + 3 < f.size && !touch) {
                        if (minDistToPlan(f[k], f[k + 1], pts) <= 30.0) touch = true
                        k += 2
                    }
                    if (touch) { used++; usedLen += FlightRouteLines.lengthM(f) }
                }
                var drawn = 0.0
                for (i in 0 until plan.segments.size - 1) {
                    val a = plan.segments[i].to
                    val b = plan.segments[i + 1].from
                    drawn += Geodesy.distanceProjectedM(a.e, a.n, b.e, b.n)
                }
                println(
                    String.format(
                        "SURCH %4.0f %4.0f %6s  %6d  %7d  %8d  %5.1f%%  %8.0f",
                        weld, 200.0, if (sur < 0) "rule" else "a*",
                        plan.segments.count { it.traced }, plan.segments.size - plan.segments.count { it.traced },
                        used, 100.0 * usedLen / longLen, drawn / 1000.0,
                    ),
                )
            }
        }
    }

    @Test
    fun `diagnoses which long features the plan does not reach`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val raw = lines()
        val cs = calls()
        val longFeats = raw.filter { FlightRouteLines.lengthM(it) >= 200.0 }
        val plan = FlightPlan.buildTraced(cs, longFeats, weldM = 250.0, maxSnapM = 200.0)

        val pts = ArrayList<DoubleArray>()
        for (seg in plan.segments) {
            for (i in 0 until seg.vertexCount) {
                pts.add(doubleArrayOf(seg.geometry[i * 2], seg.geometry[i * 2 + 1]))
            }
        }
        val covered = ArrayList<DoubleArray>()
        val missed = ArrayList<DoubleArray>()
        for (f in longFeats) {
            var touch = false
            var k = 0
            while (k + 3 < f.size && !touch) {
                if (minDistToPlan(f[k], f[k + 1], pts) <= 30.0) touch = true
                k += 2
            }
            (if (touch) covered else missed).add(f)
        }
        println("DIAG long=${longFeats.size} covered=${covered.size} missed=${missed.size}")

        fun bounds(fs: List<DoubleArray>): DoubleArray {
            var x0=Double.MAX_VALUE;var y0=Double.MAX_VALUE
            var x1=-Double.MAX_VALUE;var y1=-Double.MAX_VALUE
            for (f in fs) for (i in 0 until f.size/2) {
                x0=minOf(x0,f[i*2]);x1=maxOf(x1,f[i*2])
                y0=minOf(y0,f[i*2+1]);y1=maxOf(y1,f[i*2+1])
            }
            return doubleArrayOf(x0,y0,x1,y1)
        }
        val cb=bounds(covered); val mb=bounds(missed)
        println("DIAG covered extent e ${cb[0].toInt()}..${cb[2].toInt()}  n ${cb[1].toInt()}..${cb[3].toInt()}")
        println("DIAG missed  extent e ${mb[0].toInt()}..${mb[2].toInt()}  n ${mb[1].toInt()}..${mb[3].toInt()}")

        // are the missed ones close to covered ones (parallel duplicates) or isolated?
        val near = ArrayList<Double>()
        for (f in missed) {
            var best = Double.MAX_VALUE
            for (g in covered) {
                for (i in 0 until f.size/2 step 4) {
                    val d = minDistToPlan(f[i*2], f[i*2+1], listOf(doubleArrayOf(g[0],g[1]), doubleArrayOf(g[g.size-2],g[g.size-1])))
                    if (d < best) best = d
                }
            }
            near.add(best)
        }
        near.sort()
        println("DIAG distance from a missed feature to the nearest covered one:")
        println("DIAG   min %.0f  p25 %.0f  p50 %.0f  p90 %.0f m".format(
            near.first(), near[near.size/4], near[near.size/2], near[(near.size*9)/10]))
        for (t in listOf(100.0, 300.0, 638.0)) {
            println("DIAG   missed features within ${t.toInt()} m of a covered one: ${near.count { it <= t }} of ${near.size}")
        }
    }

    @Test
    fun `builds ten flight plan variants for comparison`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")

        val raw = lines()
        val cs = calls()
        val prj = FlightPlanWriter.prjTextOf(dir, "Air_Calls_YR_4.shp")
        val outDir = File(System.getenv("FORNAV_EXPORT") ?: "/home/mircea/ForestNavigator/flight_plan_export")
        outDir.mkdirs()

        // the long features the plan is meant to use
        val longFeats = raw.filter { FlightRouteLines.lengthM(it) >= 200.0 }
        val longLengthM = longFeats.sumOf { FlightRouteLines.lengthM(it) }

        println("VAR long features=${longFeats.size} of ${raw.size}, total ${"%.0f".format(longLengthM / 1000.0)} km")
        println("VAR #   weld  minF  traced  straight  transT  transI  transT_st  transI_st  usedLong  cover%")

        for (v in variants()) {
            val source = raw
            val plan = FlightPlan.buildByRule(
                cs, source, weldM = v.weldM, nextCallReachM = NEXT_CALL_REACH_M,
                minFeatureM = v.minFeatureM,
            )
            val w = FlightPlanWriter(plan, cs)
            val name = "flight_plan${v.n}"
            w.writeLegs(outDir, name, prj)
            w.writeContinuous(outDir, "${name}_line", prj)

            val traced = plan.segments.count { it.traced }
            val tT = plan.transects.count { it.traced }
            val tI = plan.transits.count { it.traced }

            // how many of the long features does the drawn plan actually touch?
            val planPts = ArrayList<DoubleArray>()
            for (seg in plan.segments) {
                for (i in 0 until seg.vertexCount) {
                    planPts.add(doubleArrayOf(seg.geometry[i * 2], seg.geometry[i * 2 + 1]))
                }
            }
            var used = 0
            var usedLen = 0.0
            for (f in longFeats) {
                var touches = false
                var k = 0
                while (k + 3 < f.size && !touches) {
                    if (minDistToPlan(f[k], f[k + 1], planPts) <= 30.0) touches = true
                    k += 2
                }
                if (touches) { used++; usedLen += FlightRouteLines.lengthM(f) }
            }
            println(
                String.format(
                    "VAR %-3d %5.0f %5.0f  %6d  %7d  %6d  %6d  %9d  %9d  %8d  %5.1f%%",
                    v.n, v.weldM, v.minFeatureM,
                    traced, plan.segments.size - traced,
                    tT, tI,
                    plan.transects.size - tT, plan.transits.size - tI,
                    used, 100.0 * usedLen / longLengthM,
                ),
            )
        }

        // the calls are the same for every variant, so write them once
        val base = FlightPlan.buildByRule(cs, raw, weldM = 350.0, nextCallReachM = NEXT_CALL_REACH_M)
        FlightPlanWriter(base, cs).writeCalls(outDir, "flight_plan_calls", prj)

        // every variant must be readable, or QGIS shows an empty layer
        for (v in variants()) {
            val info = ShpFormat.shpHeader(File(outDir, "flight_plan${v.n}.shp").readBytes())!!
            val lineInfo = ShpFormat.shpHeader(File(outDir, "flight_plan${v.n}_line.shp").readBytes())!!
            println("VAR written flight_plan${v.n}.shp records=${info.recordCount}  " +
                "flight_plan${v.n}_line.shp records=${lineInfo.recordCount}")
        }
    }

    /** Distance from one point to a polyline given as a list of points. */
    private fun minDistToPlan(px: Double, py: Double, plan: List<DoubleArray>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until plan.size - 1) {
            val d = Geodesy.pointSegmentDistance(
                px, py, plan[i][0], plan[i][1], plan[i + 1][0], plan[i + 1][1],
            )[0]
            if (d < best) best = d
            if (best == 0.0) return 0.0
        }
        return if (plan.size == 1) hypot(px - plan[0][0], py - plan[0][1]) else best
    }
}