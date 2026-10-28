package org.forestnav.app.map

import org.forestnav.app.map.FileMapDir
import org.gcap.core.PointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MobWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): FileMapDir = FileMapDir(tmp.newFolder("FOR_NAV"))

    @Test
    fun missingLayerIsCreatedWithAllFourSidecars() {
        val d = dir()
        val out = MobWriter.write(d, "POINTS", "GCALL_ID", "071045", 500_000.0, 4_500_000.0)
        assertTrue(out is MobWriter.Outcome.Written)
        assertTrue((out as MobWriter.Outcome.Written).created)
        for (ext in PointStore.SIDECARS) {
            assertNotNull("$ext should exist", File(tmp.root, "FOR_NAV/POINTS.$ext"))
        }
    }

    @Test
    fun theWrittenPointIsWhereItWasAskedFor() {
        val d = dir()
        MobWriter.write(d, "POINTS", "GCALL_ID", "071045", 612_345.0, 4_567_890.0,
            null, Utm.ASSUMED_ZONE)
        // a layer created here is degrees, so it must come back as the same place in degrees
        val c = PointStore.coords(d.readFile("POINTS.shp")!!)
        assertEquals(1, c.size)
        val (lat, lon) = Utm.utmToLatLon(612_345.0, 4_567_890.0, Utm.ASSUMED_ZONE)
        assertEquals(lon, c[0][0], 1e-6)
        assertEquals(lat, c[0][1], 1e-6)
    }

    @Test
    fun secondMobKeepsTheFirst() {
        val d = dir()
        MobWriter.write(d, "POINTS", "GCALL_ID", "070815", 1.0, 2.0)
        MobWriter.write(d, "POINTS", "GCALL_ID", "070816", 3.0, 4.0)
        val names = MobWriter.existingNames(d, "POINTS", "GCALL_ID")
        assertEquals(listOf("070815", "070816"), names)
        assertEquals(2, PointStore.coords(d.readFile("POINTS.shp")!!).size)
    }

    @Test
    fun aDuplicateNameIsRefusedRatherThanSilentlyAdded() {
        val d = dir()
        MobWriter.write(d, "POINTS", "GCALL_ID", "070815", 1.0, 2.0)
        val out = MobWriter.write(d, "POINTS", "GCALL_ID", "070815", 9.0, 9.0)
        assertTrue(out is MobWriter.Outcome.Failed)
        assertEquals(1, PointStore.coords(d.readFile("POINTS.shp")!!).size)
    }

    @Test
    fun anEmptyNameIsRefused() {
        assertTrue(MobWriter.write(dir(), "POINTS", "GCALL_ID", "  ", 1.0, 2.0) is MobWriter.Outcome.Failed)
    }

    @Test
    fun existingPointsLayerIsNotReplaced() {
        val d = dir()
        // a layer that already holds real points, written by something else
        val seed = PointStore.append(PointStore.newLayer("GCALL_ID", "SEED-CRS"),
            "GCALL_ID", "9999", 7.0, 8.0)
        for ((ext, bytes) in seed) File(tmp.root, "FOR_NAV/POINTS.$ext").writeBytes(bytes)

        val out = MobWriter.write(d, "POINTS", "GCALL_ID", "071045", 1.0, 2.0)
        assertTrue(out is MobWriter.Outcome.Written)
        assertTrue(!(out as MobWriter.Outcome.Written).created)
        assertEquals(listOf("9999", "071045"), MobWriter.existingNames(d, "POINTS", "GCALL_ID"))
    }

    @Test
    fun aNewLayerDoesNotBorrowAnUnrelatedLayersCrs() {
        val d = dir()
        // an existing projected layer tells a new MOB layer which coordinates it is in
        File(tmp.root, "FOR_NAV").mkdirs()
        File(tmp.root, "FOR_NAV/XGV_MP.prj").writeText("PROJECTED-CRS-FROM-SOURCE")
        MobWriter.write(d, "POINTS", "GCALL_ID", "071045", 1.0, 2.0)
        // deliberately NOT the source CRS: a fresh MOB layer is degrees so it can be
        // exchanged with the point collector app
        assertTrue("a new layer must not borrow another layer's CRS",
            File(tmp.root, "FOR_NAV/POINTS.prj").readText().contains("Degree"))
    }

    @Test
    fun aLayerNamedWithItsExtensionStillWorks() {
        val d = dir()
        MobWriter.write(d, "POINTS.shp", "GCALL_ID", "071045", 1.0, 2.0)
        assertNotNull(File(tmp.root, "FOR_NAV/POINTS.shp"))
        assertEquals("POINTS", MobWriter.shpName(d, "POINTS")!!.substringBeforeLast('.'))
    }

    @Test
    fun namesOfAnAbsentLayerAreEmpty() {
        val d = dir()
        assertEquals(emptyList<String>(), MobWriter.existingNames(d, "NOPE", "GCALL_ID"))
        assertNull(MobWriter.shpName(d, "NOPE"))
    }
}

/**
 * A MOB layer created from nothing is created in degrees, like the point collector app's.
 */
class CreatedMobLayerCrsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): FileMapDir = FileMapDir(tmp.newFolder("FOR_NAV"))

    @Test
    fun aNewMobLayerIsCreatedInDegreesNotMetres() {
        val d = dir()
        File(tmp.root, "FOR_NAV").mkdirs()
        // another projected layer exists, which is what a naive fallback would copy
        File(tmp.root, "FOR_NAV/xgv_mp.prj").writeText("PROJCS[\"PROJECTED\"]")
        seedLayer(tmp.root, "xgv_mp")

        assertTrue(MobWriter.write(d, "points", "NAME", "071045", 500_000.0, 4_500_000.0)
            is MobWriter.Outcome.Written)

        val prj = File(tmp.root, "FOR_NAV/points.prj").readText()
        assertTrue("a new MOB layer must be degrees, got: $prj",
            prj.contains("Degree") && !prj.contains("PROJCS["))
        val c = PointStore.coords(d.readFile("points.shp")!!).last()
        assertTrue("and must hold degrees, got $c", Math.abs(c[0]) < 180 && Math.abs(c[1]) < 90)
    }

    @Test
    fun anExistingLayerKeepsItsOwnSystem() {
        val d = dir()
        File(tmp.root, "FOR_NAV").mkdirs()
        File(tmp.root, "FOR_NAV/points.prj").writeText("PROJCS[\"UTM 10N\",UNIT[\"Meter\",1.0]]")
        seedLayer(tmp.root, "points")
        MobWriter.write(d, "points", "NAME", "071045", 500_000.0, 4_500_000.0)
        assertEquals(500_000.0, PointStore.coords(d.readFile("points.shp")!!).last()[0], 1e-6)
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
