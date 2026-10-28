package org.forestnav.app.map

import org.gcap.core.PointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NameFieldAndCrsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val utm = "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
        "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"],UNIT[\"Meter\",1.0]]"
    private val degrees = "GEOGCS[\"GCS_WGS_1984\",UNIT[\"Degree\",0.0174532925199433]]"

    private fun dir(): FileMapDir = FileMapDir(tmp.newFolder("FOR_NAV"))

    // ---- a projected .prj is not a geographic one --------------------------

    @Test
    fun projectedCrsIsNotMistakenForGeographic() {
        assertTrue(LayerCrs.isGeographic(degrees))
        assertTrue("a PROJCS embeds GEOGCS and UNIT[\"Degree\"]",
            !LayerCrs.isGeographic(utm))
    }

    @Test
    fun aMissingPrjIsNotAssumedGeographic() {
        assertTrue(!LayerCrs.isGeographic(null))
        assertTrue(!LayerCrs.isGeographic(""))
    }

    @Test
    fun aProjectedLayerKeepsItsMetres() {
        val c = LayerCrs.toLayerCoords(500_000.0, 4_500_000.0, Utm.ASSUMED_ZONE, utm)
        assertEquals(500_000.0, c[0], 1e-6)
        assertEquals(4_500_000.0, c[1], 1e-6)
    }

    @Test
    fun aGeographicLayerReceivesLonLatDegrees() {
        val c = LayerCrs.toLayerCoords(500_000.0, 4_500_000.0, Utm.ASSUMED_ZONE, degrees)
        // shapefiles store X first, so longitude leads
        assertTrue("lon ${c[0]} out of range", c[0] in -180.0..180.0)
        assertTrue("lat ${c[1]} out of range", c[1] in -90.0..90.0)
        assertTrue("lon should be near zone 10, was ${c[0]}", c[0] in -126.0..-120.0)
        // and it must round-trip back to the same metres
        val (e2, n2) = Utm.wgs84ToUtmZone(c[1], c[0], Utm.ASSUMED_ZONE)
        assertEquals(500_000.0, e2, 1.0)
        assertEquals(4_500_000.0, n2, 1.0)
    }

    // ---- MOB into a degrees layer ------------------------------------------

    @Test
    fun mobIntoADegreesLayerIsStoredInDegrees() {
        val d = dir()
        File(tmp.root, "FOR_NAV").mkdirs()
        File(tmp.root, "FOR_NAV/points.prj").writeText(degrees)
        seedLayer(tmp.root, "points")

        val out = MobWriter.write(d, "points", "NAME", "071045", 500_000.0, 4_500_000.0,
            null, Utm.ASSUMED_ZONE)
        assertTrue(out is MobWriter.Outcome.Written)
        val c = PointStore.coords(d.readFile("points.shp")!!).last()
        // Zone 10N easting 500000 is exactly the central meridian, -123.00
        assertTrue("stored as degrees, got $c", Math.abs(c[0]) < 180 && Math.abs(c[1]) < 90)
        assertEquals("degrees must not be written as metres", -123.0, c[0], 0.01)
        assertTrue("northing should be about 40.6", c[1] in 40.0..41.0)
    }

    @Test
    fun theDegreesPrjIsNotRewritten() {
        val d = dir()
        File(tmp.root, "FOR_NAV").mkdirs()
        File(tmp.root, "FOR_NAV/points.prj").writeText(degrees)
        seedLayer(tmp.root, "points")
        MobWriter.write(d, "points", "NAME", "071045", 500_000.0, 4_500_000.0, utm, Utm.ASSUMED_ZONE)
        assertEquals("the file must stay in degrees so the collector app agrees",
            degrees, File(tmp.root, "FOR_NAV/points.prj").readText())
    }

    @Test
    fun mobIntoAProjectedLayerStaysInMetres() {
        val d = dir()
        File(tmp.root, "FOR_NAV").mkdirs()
        File(tmp.root, "FOR_NAV/points.prj").writeText(utm)
        seedLayer(tmp.root, "points")
        MobWriter.write(d, "points", "NAME", "071045", 500_000.0, 4_500_000.0)
        assertEquals(500_000.0, PointStore.coords(d.readFile("points.shp")!!).last()[0], 1e-6)
    }

    // ---- per-layer name fields ---------------------------------------------

    @Test
    fun eachLayerKeepsItsOwnNameField() {
        val cfg = NavSettings()
        assertEquals("points.shp holds NAME", "NAME", cfg.nameFieldFor("points"))
        assertEquals("POINTS too", "NAME", cfg.nameFieldFor("POINTS"))
        assertEquals("xgv_mp.shp holds GCALL_ID", "GCALL_ID", cfg.nameFieldFor("xgv_mp"))
        assertEquals("ground_call.shp holds Call_Num", "Call_Num", cfg.nameFieldFor("ground_call"))
    }

    @Test
    fun anExplicitSettingBeatsTheKnownDefault() {
        val cfg = NavSettings(nameFields = mapOf("points" to "MY_NAME"))
        assertEquals("MY_NAME", cfg.nameFieldFor("points"))
    }

    @Test
    fun anUnknownLayerFallsBackToTheEditField() {
        assertEquals("GCALL_ID", NavSettings().nameFieldFor("brand_new_layer"))
    }

    @Test
    fun mobUsesTheNameFieldOfTheLayerItWritesTo() {
        val d = dir()
        File(tmp.root, "FOR_NAV").mkdirs()
        File(tmp.root, "FOR_NAV/points.prj").writeText(degrees)
        seedLayer(tmp.root, "points")
        MobWriter.write(d, "points", NavSettings().nameFieldFor("points"), "071045",
            500_000.0, 4_500_000.0, null, Utm.ASSUMED_ZONE)
        assertEquals(listOf("071045"), MobWriter.existingNames(d, "points", "NAME"))
        assertEquals("it must not land in GCALL_ID",
            emptyList<String>(), MobWriter.existingNames(d, "points", "GCALL_ID"))
    }
    /** A valid one-point layer on disk, so the code under test sees an existing layer. */
    private fun seedLayer(root: File, stem: String) {
        val d = File(root, "FOR_NAV"); d.mkdirs()
        val files = org.gcap.core.PointStore.append(
            org.gcap.core.PointStore.newLayer("SEED", "PROJCS[\"SEED\"]"), "SEED", "S1", 1.0, 2.0)
        // no .prj: each test sets its own coordinate system
        for ((ext, bytes) in files) if (ext != "prj") File(d, "$stem.$ext").writeBytes(bytes)
    }

}
