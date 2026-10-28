package org.gcap.core.verify

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayerVerifyTest {

    private val utm10 = """PROJCS["NAD_1983_UTM_Zone_10N"]"""
    private val utm11 = """PROJCS["NAD_1983_UTM_Zone_11N"]"""
    private val wgs84 = """GEOGCS["GCS_WGS_1984"]"""

    private val classifier = CrsClassifier { prj ->
        val t = prj.orEmpty().uppercase().filter { it.isLetterOrDigit() }
        when {
            t.isEmpty() -> CrsVerdict("assumed UTM", 10)
            t.contains("UTMZONE11N") -> CrsVerdict("UTM", 11)
            t.contains("UTMZONE10N") -> CrsVerdict("UTM", 10)
            t.startsWith("GEOGCS") || t.contains("GCSWGS1984") ->
                CrsVerdict("WGS84", 0, recognised = true, geographic = true)
            else -> CrsVerdict("?", 0, recognised = false)
        }
    }

    private fun polylineShp(xmin: Double, ymin: Double, xmax: Double, ymax: Double): ByteArray =
        ShpFormat.buildPolylineShp(listOf(listOf(doubleArrayOf(xmin, ymin), doubleArrayOf(xmax, ymax))))

    private fun good(stem: String, prj: String, shp: ByteArray): Map<String, ByteArray> = mapOf(
        "$stem.shp" to shp,
        "$stem.shx" to ByteArray(112),
        "$stem.dbf" to ByteArray(96),
        "$stem.prj" to prj.toByteArray(),
    )

    @Test
    fun `a complete layer is OK`() {
        val r = LayerVerifier(
            LayerSource.of(good("roads", utm10, polylineShp(0.0, 0.0, 1000.0, 1000.0))),
            classifier,
        ).verify()
        assertEquals(1, r.layers.size)
        assertEquals(LayerStatus.OK, r.layers[0].status, r.lines.toString())
        assertEquals("line", r.layers[0].kind)
        assertTrue(r.lines[0].contains("roads.shp [line, UTM 10N"), r.lines[0])
        assertTrue(r.summary.contains("1 OK"), r.summary)
    }

    @Test
    fun `missing sidecars are reported one by one`() {
        val src = LayerSource.of(mapOf("access.shp" to polylineShp(0.0, 0.0, 100.0, 100.0)))
        val r = LayerVerifier(src, classifier).verify()
        val p = r.layers[0].problems
        assertTrue(p.contains("no .shx"), p.toString())
        assertTrue(p.contains("no .dbf (no labels or attributes)"), p.toString())
        assertTrue(p.contains("no .prj"), p.toString())
        assertEquals(LayerStatus.WARN, r.layers[0].status)
    }

    @Test
    fun `an unrecognised CRS skips the layer`() {
        val m = good("weird", """PROJCS["Something_Else"]""", polylineShp(0.0, 0.0, 100.0, 100.0))
        val r = LayerVerifier(LayerSource.of(m), classifier).verify()
        assertEquals(LayerStatus.SKIP, r.layers[0].status)
        assertTrue(r.layers[0].problems.any { it.contains("unrecognised") })
    }

    @Test
    fun `a geographic layer is labelled and carries no zone`() {
        val m = good("points", wgs84, ShpFormat.buildPointShp(listOf(doubleArrayOf(-123.0, 49.0))))
        val r = LayerVerifier(LayerSource.of(m), classifier).verify()
        assertEquals(0, r.layers[0].zone)
        assertTrue(r.layers[0].crsNote.startsWith("WGS84"), r.layers[0].crsNote)
    }

    @Test
    fun `mixed zones are called out`() {
        val m = good("a", utm10, polylineShp(0.0, 0.0, 1000.0, 1000.0)) +
            good("b", utm11, polylineShp(0.0, 0.0, 1000.0, 1000.0))
        val r = LayerVerifier(LayerSource.of(m), classifier).verify()
        assertTrue(r.crsNote.contains("layer(s)"), r.crsNote)
    }

    @Test
    fun `a tiny outlier layer is flagged`() {
        val m = good("big", utm10, polylineShp(0.0, 0.0, 100000.0, 100000.0)) +
            good("stray", utm10, polylineShp(50000.0, 50000.0, 50010.0, 50010.0))
        val r = LayerVerifier(LayerSource.of(m), classifier).verify()
        assertTrue(r.extentNote.contains("outlier"), r.extentNote)
        assertTrue(r.extentNote.contains("stray"), r.extentNote)
    }

    @Test
    fun `matching layers report no outliers`() {
        val m = good("a", utm10, polylineShp(0.0, 0.0, 100000.0, 100000.0)) +
            good("b", utm10, polylineShp(1000.0, 1000.0, 99000.0, 99000.0))
        val r = LayerVerifier(LayerSource.of(m), classifier).verify()
        assertTrue(!r.extentNote.contains("outlier"), r.extentNote)
    }

    @Test
    fun `an empty folder is reported rather than thrown`() {
        val r = LayerVerifier(LayerSource.of(emptyMap()), classifier).verify()
        assertTrue(r.summary.contains("No .shp files"), r.summary)
    }

    @Test
    fun `an empty layer is warned about`() {
        val m = good("empty", utm10, ShpFormat.buildPolylineShp(emptyList()))
        val r = LayerVerifier(LayerSource.of(m), classifier).verify()
        assertTrue(r.layers[0].problems.contains("no features"), r.layers[0].problems.toString())
    }

    @Test
    fun `project rows are noted`() {
        val src = LayerSource.of(good("roads", utm10, polylineShp(0.0, 0.0, 1000.0, 1000.0)))
        val r = LayerVerifier(src, classifier, projectRows = setOf("roads")).verify()
        assertTrue(r.layers[0].crsNote.contains("in project"), r.layers[0].crsNote)
    }
}
