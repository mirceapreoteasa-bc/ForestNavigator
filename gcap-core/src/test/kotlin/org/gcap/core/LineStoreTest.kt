package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

class LineStoreTest {

    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int): Calendar =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, 0); set(Calendar.MILLISECOND, 0)
        }

    private fun path(vararg e: Double) = e.map { doubleArrayOf(it, 4_500_000.0 + it) }

    /** The project's actual .prj text: a PROJCS that embeds a GEOGCS with UNIT["Degree"]. */
    private val utm =
        "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
            "DATUM[\"D_North_American_1983\",SPHEROID[\"GRS_1980\",6378137.0,298.257222101]]," +
            "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]," +
            "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"Central_Meridian\",-123.0]," +
            "UNIT[\"Meter\",1.0]]"
    private val degrees =
        "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\"," +
            "6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"

    @Test
    fun aTrackNameCarriesTheDateAndTime() {
        assertEquals("2026-10-07 1432", LineStore.defaultName(cal(2026, 10, 7, 14, 32)))
        assertEquals("2026-10-07", LineStore.defaultDate(cal(2026, 10, 7, 14, 32)))
    }

    @Test
    fun aProjectedCrsIsNotMistakenForGeographicOnes() {
        // the embedded GEOGCS block carries UNIT["Degree"], which a substring test reads
        // as geographic; that mistake once made a wrong layer look correct
        assertFalse(LineStore.isDegrees(utm))
        assertTrue(LineStore.isDegrees(degrees))
    }

    @Test
    fun aDegreesFileAgainstAProjectedProjectIsFlagged() {
        val wrong = mapOf("prj" to degrees.toByteArray())
        assertTrue(LineStore.crsIsWrong(wrong, utm))
    }

    @Test
    fun aMatchingOrAbsentFileIsNotFlagged() {
        assertFalse(LineStore.crsIsWrong(mapOf("prj" to utm.toByteArray()), utm))
        assertFalse(LineStore.crsIsWrong(null, utm))
        // no project CRS to compare against, so nothing to correct
        assertFalse(LineStore.crsIsWrong(mapOf("prj" to degrees.toByteArray()), null))
    }

    @Test
    fun theDeclaredCrsIsNeverRewritten() {
        // tracks.prj ships declaring degrees and stays that way: reprojecting is a
        // desktop QGIS job, not something to do silently on a phone
        val template = mapOf("prj" to degrees.toByteArray())
        val out = LineStore.append(template, path(500_000.0, 500_100.0), "2026-10-07 1432", "2026-10-07", utm)
        assertEquals(degrees, String(out["prj"]!!))
    }

    @Test
    fun aLayerWithNoPrjGetsOneRatherThanNone() {
        val out = LineStore.append(emptyMap(), path(1.0, 2.0), "T", "D", utm)
        assertTrue(out["prj"]!!.isNotEmpty())
    }

    @Test
    fun aProjectedFileAlsoKeepsItsCrs() {
        val template = mapOf("prj" to utm.toByteArray())
        val out = LineStore.append(template, path(1.0, 2.0), "n", "d", degrees)
        assertEquals(utm, String(out["prj"]!!))
    }

    @Test
    fun nameAndDateAreAddedToTheBareTemplate() {
        val out = LineStore.append(emptyMap(), path(1.0, 2.0, 3.0), "2026-10-07 1432", "2026-10-07", utm)
        val fields = PointStore.existingFields(out["dbf"]!!).map { it.first }
        assertTrue(fields.any { it == "NAME" }, "NAME should be added")
        assertTrue(fields.any { it == "DATE" }, "DATE should be added")
        assertEquals(listOf("2026-10-07 1432"), PointStore.names(out["dbf"]!!, "NAME"))
    }

    @Test
    fun existingArcGisFieldsSurviveTheUpgrade() {
        // the template arrived with OBJECTID and SHAPE_Leng only
        val template = mapOf(
            "shp" to ShpFormat.buildPolylineShp(emptyList()),
            "dbf" to ShpFormat.buildDbfFields(
                listOf("OBJECTID" to 10, "SHAPE_Leng" to 19), emptyList()),
        )
        val out = LineStore.append(template, path(1.0, 2.0), "T1", "2026-10-07", utm)
        // field names are written upper-case, as ArcGIS does, so compare case-insensitively
        val fields = PointStore.existingFields(out["dbf"]!!).map { it.first.uppercase() }
        assertTrue(fields.contains("OBJECTID"), "OBJECTID must not be dropped")
        assertTrue(fields.contains("SHAPE_LENG"), "SHAPE_LENG must not be dropped")
        assertTrue(fields.contains("NAME"))
    }

    @Test
    fun aSecondTrackKeepsTheFirst() {
        var l = LineStore.append(emptyMap(), path(1.0, 2.0), "T1", "2026-10-07", utm)
        l = LineStore.append(l, path(3.0, 4.0), "T2", "2026-10-07", utm)
        assertEquals(2, LineStore.count(l))
        assertEquals(listOf("T1", "T2"), LineStore.names(l))
        assertEquals(2, LineStore.existingParts(l).size)
    }

    @Test
    fun recordedVerticesAreKeptInOrder() {
        val out = LineStore.append(emptyMap(), path(10.0, 20.0, 30.0), "T", "D", utm)
        val part = LineStore.existingParts(out).single()
        assertEquals(3, part.size)
        assertEquals(10.0, part[0][0], 1e-9)
        assertEquals(30.0, part[2][0], 1e-9)
    }

    @Test
    fun aSingleFixIsRefusedRatherThanWritingADegenerateLine() {
        var threw = false
        try { LineStore.append(emptyMap(), path(1.0), "T", "D", utm) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw, "one fix is not a track")
    }

    @Test
    fun brokenFixesAreDroppedBeforeWriting() {
        val coords = listOf(
            doubleArrayOf(1.0, 2.0), doubleArrayOf(Double.NaN, 3.0), doubleArrayOf(4.0, 5.0))
        val out = LineStore.append(emptyMap(), coords, "T", "D", utm)
        assertEquals(2, LineStore.existingParts(out).single().size)
    }

    @Test
    fun everySidecarIsWritten() {
        val out = LineStore.append(emptyMap(), path(1.0, 2.0), "T", "D", utm)
        for (ext in LineStore.SIDECARS) assertTrue(out.containsKey(ext), "$ext missing")
    }

    @Test
    fun dbfAndShpRecordCountsAgree() {
        var l = LineStore.append(emptyMap(), path(1.0, 2.0), "T1", "D", utm)
        l = LineStore.append(l, path(3.0, 4.0), "T2", "D", utm)
        assertEquals(LineStore.count(l).toLong(), ShpFormat.dbfHeader(l["dbf"]!!)!!.numRecords.toLong())
    }
}
