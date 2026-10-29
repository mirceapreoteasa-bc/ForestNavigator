package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Works out what a transect looks like in the real data, rather than assuming it.
 *
 * A transect is the short flown segment a call pair brackets: a bare-named call sits at its
 * middle and the A and B calls sit near its two ends. This test measures how well that
 * description actually separates transects from the rest of the path.
 */
class TransectSurveyTest {

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
        for (row in ShpFormat.readDbfRows(airCallsDbf(dir)!!.readBytes())) {
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
    fun surveyWhatTheDataCallsATransect() {
        requireAirCalls()
        assumeTrue(File(dir, "flight_path.shp").exists(), "FOR_NAV data not present")
        val fs = lines()
        val cs = calls()
        println("TRS features=${fs.size} calls=${cs.size}")

        val lens = fs.map { FlightRouteLines.lengthM(it) }.sorted()
        fun pct(p: Double) = lens[((lens.size - 1) * p).toInt().coerceIn(0, lens.size - 1)]
        println("TRS feature length m: min=${"%.0f".format(lens.first())} " +
            "p10=${"%.0f".format(pct(0.10))} p25=${"%.0f".format(pct(0.25))} " +
            "median=${"%.0f".format(pct(0.5))} p75=${"%.0f".format(pct(0.75))} " +
            "p90=${"%.0f".format(pct(0.90))} max=${"%.0f".format(lens.last())}")

        // quieter length picture: just the bands that matter
        val bands = listOf(0 to 50, 50 to 100, 100 to 150, 150 to 200, 200 to 500, 500 to 100000)
        for ((lo, hi) in bands) {
            println("TRS features ${lo}..${hi} m: ${fs.count { FlightRouteLines.lengthM(it) in lo.toDouble()..hi.toDouble() }}")
        }

        // how far apart are the A and B calls of the same number? that is the transect
        val byNumber = cs.filter { it.suffix == "A" }.associateBy { it.order }
        val bByNumber = cs.filter { it.suffix == "B" }.associateBy { it.order }
        val ab = byNumber.keys.filter { bByNumber.containsKey(it) }.sorted()
        val abDists = ab.map { n ->
            Geodesy.distanceProjectedM(byNumber[n]!!.e, byNumber[n]!!.n, bByNumber[n]!!.e, bByNumber[n]!!.n)
        }.sorted()
        println("TRS A-B pairs=${abDists.size}")
        if (abDists.isNotEmpty()) {
            fun q(f: Double) = abDists[((abDists.size - 1) * f).toInt()]
            println("TRS A-B distance m: min=${"%.0f".format(abDists.first())} p10=${"%.0f".format(q(0.1))} " +
                "median=${"%.0f".format(q(0.5))} p90=${"%.0f".format(q(0.9))} max=${"%.0f".format(abDists.last())}")
        }

        // and where is the bare call relative to that A-B pair?
        val bareByNumber = cs.filter { it.suffix.isEmpty() }.associateBy { it.order }
        println("TRS bare calls=${bareByNumber.size}, numbers with a full A/_/B triple=" +
            ab.count { bareByNumber.containsKey(it) })
        val offMid = ab.filter { bareByNumber.containsKey(it) }.map { n ->
            val a = byNumber[n]!!; val bb = bByNumber[n]!!; val m = bareByNumber[n]!!
            val mx = (a.e + bb.e) / 2.0; val my = (a.n + bb.n) / 2.0
            Geodesy.distanceProjectedM(m.e, m.n, mx, my)
        }.sorted()
        if (offMid.isNotEmpty()) {
            println("TRS bare call offset from A-B midpoint m: median=${"%.0f".format(offMid[offMid.size / 2])} " +
                "p90=${"%.0f".format(offMid[(offMid.size * 0.9).toInt()])} max=${"%.0f".format(offMid.last())}")
        }

        // sweep the length band to see which setting accounts for all 291 triples
        for (band in listOf(220.0, 300.0, 400.0, 600.0)) {
            for (endTol in listOf(45.0, 60.0)) {
                val t = TransectClassifier(cs, endToleranceM = endTol, maxLengthM = band)
                val flags = fs.map { t.isTransect(it) }
                val nums = t.transectNumbers(fs)
                println("TRS band<=$band endTol=$endTol: features=${flags.count { it }} distinctNumbers=${nums.distinct().size}")
            }
        }

        // and the chosen setting
        val t = TransectClassifier(cs)
        val flags = fs.map { t.isTransect(it) }
        val yes = flags.count { it }
        println("TRS classified as transect: $yes of ${fs.size}")
        val tLens = fs.filterIndexed { i, _ -> flags[i] }.map { FlightRouteLines.lengthM(it) }.sorted()
        if (tLens.isNotEmpty()) {
            println("TRS transect length m: min=${"%.0f".format(tLens.first())} " +
                "median=${"%.0f".format(tLens[tLens.size / 2])} max=${"%.0f".format(tLens.last())}")
        }
        val oLens = fs.filterIndexed { i, _ -> !flags[i] }.map { FlightRouteLines.lengthM(it) }.sorted()
        if (oLens.isNotEmpty()) {
            println("TRS other length m: min=${"%.0f".format(oLens.first())} " +
                "median=${"%.0f".format(oLens[oLens.size / 2])} max=${"%.0f".format(oLens.last())}")
        }
        println("TRS call suffixes: A=${cs.count { it.suffix == "A" }} " +
            "bare=${cs.count { it.suffix.isEmpty() }} B=${cs.count { it.suffix == "B" }}")

        // do A, bare and B of one number fall on a single feature?
        val single = ab.count { n ->
            val probes = listOf(byNumber[n]!!, bareByNumber[n]!!, bByNumber[n]!!)
            fs.count { f ->
                probes.all { p ->
                    var k = 0
                    var ok = false
                    while (k + 3 < f.size) {
                        if (Geodesy.pointSegmentDistance(p.e, p.n, f[k], f[k + 1], f[k + 2], f[k + 3])[0] <= 25.0) ok = true
                        k += 2
                    }
                    ok
                }
            } >= 1
        }
        println("TRS A/_/B triples all lying on one feature within 25 m: $single of ${ab.size}")
    }
}