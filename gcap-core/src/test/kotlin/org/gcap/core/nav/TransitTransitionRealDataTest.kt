package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Joins the real flight path into one line, then bends the links the healing pass created so
 * the curve leaves and arrives along the bearing of the transect it touches.
 */
class TransitTransitionRealDataTest {

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
    fun bendTheHealingLinksIntoTransitions() {
        requireAirCalls()
        assumeTrue(File(dir, "flight_path.shp").exists(), "FOR_NAV data not present")
        val raw = lines()
        val calls = allCalls()

        val b = FlightLineBuilder(raw, minLineM = 50.0, maxJoinM = 1000.0)
        b.build()
        val heal = b.heal(rounds = 1, maxJoinM = 1000.0)
        println("TTR join+heal: ${heal.describe()}")

        val hb = b.lastHealBuilder!!
        val detailed = hb.resultLinesDetailed()
        println("TTR healed into ${detailed.size} line(s), ${detailed.sumOf { it.vertexCount }} vertices")

        val before = hb.resultLines()
        val seqBefore = FlightLineSequence(before, calls)
        println("TTR straight link: ${seqBefore.summary()}")

        // each internal feature boundary of the healed line is a link the healing joined
        val bent = ArrayList<DoubleArray>()
        val bows = ArrayList<Triple<Double, Double, Int>>()
        var links = 0
        for (d in detailed) {
            val v = d.vertices
            if (d.featureStarts.size < 2) { bent.add(v); continue }
            val out = ArrayList<Double>()
            var prev = 0
            for (bi in 1 until d.featureStarts.size) {
                val at = d.featureStarts[bi]
                val p0 = at - 1
                val p1 = at
                val gap = Geodesy.distanceProjectedM(v[p0 * 2], v[p0 * 2 + 1], v[p1 * 2], v[p1 * 2 + 1])
                // only bend where there is a real link and a leg either side to read
                if (gap > 1.0 && p0 - 1 >= prev && p1 + 1 < v.size / 2) {
                    val a = v.copyOfRange(p0 * 2, p0 * 2 + 2)
                    val cPt = v.copyOfRange(p1 * 2, p1 * 2 + 2)
                    val outDir = TransitTransition.direction(v, (p0 - 1) * 2, p0 * 2)
                    val inDir = TransitTransition.direction(v, p1 * 2, (p1 + 1) * 2)
                    val curve = TransitTransition.curve(a, outDir, cPt, inDir)
                    var i = prev
                    while (i < p0) { out.add(v[i * 2]); out.add(v[i * 2 + 1]); i++ }
                    var k = 0
                    while (k < curve.size) { out.add(curve[k]); out.add(curve[k + 1]); k += 2 }
                    // measure how far this curve strays from the straight link it replaces
                    var worst = 0.0
                    k = 0
                    while (k + 3 < curve.size) {
                        val dd = Geodesy.pointSegmentDistance(
                            curve[k], curve[k + 1], a[0], a[1], cPt[0], cPt[1],
                        )[0]
                        if (dd > worst) worst = dd
                        k += 2
                    }
                    bows.add(Triple(gap, worst, curve.size / 2))
                    links++
                    println(
                        "TTR link $links: gap ${"%.0f".format(gap)} m, turned " +
                            "${"%.0f".format(Math.toDegrees(kotlin.math.acos((outDir[0] * inDir[0] + outDir[1] * inDir[1]).coerceIn(-1.0, 1.0))))} deg, " +
                            "curve ${curve.size / 2} points",
                    )
                    prev = p1
                }
            }
            var i = prev
            while (i < v.size / 2) { out.add(v[i * 2]); out.add(v[i * 2 + 1]); i++ }
            bent.add(out.toDoubleArray())
        }

        println("TTR bent $links link(s); vertices ${before.sumOf { it.size / 2 }} -> ${bent.sumOf { it.size / 2 }}")

        val seqAfter = FlightLineSequence(bent, calls)
        println("TTR bent link  : ${seqAfter.summary()}")
        val ca = seqAfter.check()
        println("TTR lines with calls=${ca.size} inOrder=${ca.count { it.callCount >= 2 && it.agreement >= 0.9 }} " +
            "worstOffset=${"%.0f".format(ca.maxOfOrNull { it.worstOffsetM } ?: 0.0)} m")

        // how far each curve bows from the straight link it replaces
        for ((gap, worst, pts) in bows) {
            println("TTR link ${"%.0f".format(gap)} m bent through $pts points, worst bow ${"%.0f".format(worst)} m")
        }

        write(bent, seqAfter.check())
    }

    private fun write(lines: List<DoubleArray>, checks: List<FlightLineSequence.LineCheck>) {
        val out = File("/home/mircea/ForestNavigator/flight_plan_export")
        out.mkdirs()
        val prj = File(dir, "flight_path.prj").let { if (it.exists()) it.readText() else "" }
        val nm = "flight_lines_1000_transit"
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
        println("TTR wrote $nm.shp with ${lines.size} lines")
    }
}