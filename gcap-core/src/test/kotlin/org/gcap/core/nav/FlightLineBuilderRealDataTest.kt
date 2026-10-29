package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class FlightLineBuilderRealDataTest {

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

    private fun calls(): List<AirCall> {
        val out = ArrayList<AirCall>()
        for (row in ShpFormat.readDbfRows((SurveyLayers.airCallsDbf(dir) ?: return emptyList()).readBytes())) {
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

    @Test
    fun `builds continuous lines from the real flight path`() {
        requireAirCalls()
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val raw = lines()
        println("FLB source features=${raw.size}")

        val cs = calls()
        println("FLB A/B calls=${cs.size}")

        for (maxJoin in listOf(1000.0)) {
            val b = FlightLineBuilder(raw, minLineM = 50.0, maxJoinM = maxJoin)
            b.build()
            // one healing round: same procedure again over the lines just formed
            val healPass = b.heal(rounds = 1, maxJoinM = maxJoin)
            val healed = b.healedLines!!
            println("FLH   heal(1): ${healPass.describe()}")
            println("FLH   after healing: ${healed.size} lines, vertices ${healed.sumOf { it.size / 2 }}")

            val res = b.resultLines()
            val total = res.sumOf { it.size / 2 }
            val inVerts = b.lines.sumOf { it.size / 2 }
            val sizes = res.sortedByDescending { it.size }
            println("FLB maxJoin=${maxJoin.toInt()} m: ${b.summary()}")
            println("FLB   result lines=" + res.size + "  vertices " + total + " of " + inVerts +
                "  longest=${"%.0f".format((sizes.firstOrNull()?.size ?: 0) / 2.0)} vertices")
            println("FLB   top 6 by segments: ${sizes.take(6).map { it.size / 2 }}")

            // step 8: project the calls and see whether they run in flying order
            val seq = FlightLineSequence(healed, cs)
            val checks = seq.check()
            val matched = seq.feet.size
            val onLine = seq.feet.count { it.offsetM <= 50.0 }
            val ordered = checks.count { it.callCount >= 2 && it.agreement >= 0.9 }
            val covered = checks.filter { it.callCount >= 2 }
            val avgAgree = if (covered.isEmpty()) 0.0 else covered.sumOf { it.agreement } / covered.size
            println("FLB   sequence: ${seq.summary()}")
            println("FLB   matched=$matched/${cs.size} within50m=$onLine " +
                "linesWithCalls=${checks.size} inOrder=$ordered avgAgreement=${"%.2f".format(avgAgree)}")
            val worst = checks.maxByOrNull { it.worstOffsetM }
            println("FLB   busiest line: ${worst?.describe() ?: "-"}")

            // export the two most useful settings so they can be checked in QGIS
            if (maxJoin == 1000.0) {
                val out = File(System.getProperty("user.home"), "ForestNavigator/flight_plan_export")
                out.mkdirs()
                val prj = File(dir, "flight_path.prj").let { if (it.exists()) it.readText() else "" }
                fun writeShapes(nm: String, ls: List<DoubleArray>, cs: List<FlightLineSequence.LineCheck>) {
                val parts = ls.map { ln ->
                    (0 until ln.size / 2).map { doubleArrayOf(ln[it * 2], ln[it * 2 + 1]) }
                }
                File(out, "$nm.shp").writeBytes(ShpFormat.buildPolylineShp(parts))
                File(out, "$nm.shx").writeBytes(ShpFormat.buildPolylineShx(parts))
                File(out, "$nm.dbf").writeBytes(ShpFormat.buildDbfFields(
                    listOf("VERTS" to 7, "CALLS" to 6, "AGREE" to 7, "MAXOFF" to 8),
                    cs.map { c ->
                        listOf(
                            (c.lineIndex.toString()),
                            c.callCount.toString(),
                            "%.2f".format(c.agreement),
                            "%.0f".format(c.worstOffsetM),
                        )
                    }.let { r ->
                        // one row per formed line, padding lines that carry no calls
                        val byIdx = r.associateBy { it[0] }
                        (0 until ls.size).map { i ->
                            byIdx[i.toString()] ?: listOf(i.toString(), "0", "", "")
                        }
                    },
                ))
                if (prj.isNotBlank()) File(out, "$nm.prj").writeText(prj)
                println("FLB   wrote $nm.shp with ${ls.size} lines")
                }

                // keep the round-1 result alongside the healed one so both can be compared
                writeShapes("flight_lines_${maxJoin.toInt()}", res, FlightLineSequence(res, cs).check())
                writeShapes("flight_lines_${maxJoin.toInt()}_healed", healed, seq.check())

                // how do the lines sit relative to each other in flying order?
                val ordered = checks.filter { it.callCount > 0 }.sortedBy { it.firstCall?.order ?: 0 }
                println("FLS   lines carrying calls, in call order: ${ordered.size}")
                for (c in ordered) {
                    println("FLS   line${c.lineIndex}: ${c.callCount} calls ${c.firstCall?.name} .. ${c.lastCall?.name}" +
                        " spanning ${"%.0f".format(c.firstAlongM ?: 0.0)}m..${"%.0f".format(c.lastAlongM ?: 0.0)}m")
                }
                // Is there flown geometry in the two gaps that the <50 m filter removed?
                val raw = lines()
                fun lenOf(f: DoubleArray): Double {
                    var t = 0.0
                    var k = 0
                    while (k + 3 < f.size) { t += Geodesy.distanceProjectedM(f[k], f[k + 1], f[k + 2], f[k + 3]); k += 2 }
                    return t
                }
                println("FLG   raw features=${raw.size}")
                for (i in 0 until ordered.size - 1) {
                    val a = ordered[i]
                    val b = ordered[i + 1]
                    val endF = seq.callsOn(a.lineIndex).lastOrNull()
                    val startF = seq.callsOn(b.lineIndex).firstOrNull()
                    val la = res[a.lineIndex]
                    val lb = res[b.lineIndex]
                    if (endF != null && startF != null) {
                        val gap = Geodesy.distanceProjectedM(endF.call.e, endF.call.n, startF.call.e, startF.call.n)
                        println("FLS   transition ${endF.call.name} -> ${startF.call.name}: straight gap ${"%.0f".format(gap)} m")
                    }
                    // do the formed lines already run into each other at their own ends?
                    val ea = doubleArrayOf(la[la.size - 2], la[la.size - 1])
                    val sa = doubleArrayOf(la[0], la[1])
                    val eb = doubleArrayOf(lb[lb.size - 2], lb[lb.size - 1])
                    val sb = doubleArrayOf(lb[0], lb[1])
                    println("FLS   line${a.lineIndex} end -> line${b.lineIndex} start: ${"%.0f".format(Geodesy.distanceProjectedM(ea[0], ea[1], sb[0], sb[1]))} m")
                    println("FLS   line${b.lineIndex} end -> line${a.lineIndex} start: ${"%.0f".format(Geodesy.distanceProjectedM(eb[0], eb[1], sa[0], sa[1]))} m")
                    val ends = minOf(
                        Geodesy.distanceProjectedM(ea[0], ea[1], sb[0], sb[1]),
                        Geodesy.distanceProjectedM(ea[0], ea[1], eb[0], eb[1]),
                    )
                    println("FLS   closest approach between the two lines ends: ${"%.0f".format(ends)} m")

                    // look for discarded short features sitting in the gap
                    val p = doubleArrayOf(ea[0], ea[1])
                    val q = doubleArrayOf(sb[0], sb[1])
                    var near = 0
                    var nearLen = 0.0
                    var nearest = Double.MAX_VALUE
                    var kept = 0
                    for (f in raw) {
                        var k = 0
                        var touches = false
                        var dmin = Double.MAX_VALUE
                        while (k + 3 < f.size) {
                            val d = Geodesy.pointSegmentDistance(p[0], p[1], f[k], f[k + 1], f[k + 2], f[k + 3])[0]
                            if (d < dmin) dmin = d
                            val d2 = Geodesy.pointSegmentDistance(q[0], q[1], f[k], f[k + 1], f[k + 2], f[k + 3])[0]
                            if (d2 < dmin) dmin = d2
                            if (d < 250.0 || d2 < 250.0) touches = true
                            k += 2
                        }
                        if (touches) {
                            val len = lenOf(f)
                            if (len < 50.0) { near++; nearLen += len } else kept++
                            if (dmin < nearest) nearest = dmin
                        }
                    }
                    println("FLG   gap line${a.lineIndex}->line${b.lineIndex}: $near discarded sub-50 m features nearby " +
                        "totalling ${"%.0f".format(nearLen)} m; $kept kept features also nearby; " +
                        "closest raw feature ${"%.0f".format(nearest)} m from the gap ends")
                }
            }
        }
    }
}
