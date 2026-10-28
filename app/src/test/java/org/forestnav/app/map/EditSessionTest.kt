package org.forestnav.app.map

import org.gcap.core.PointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): FileMapDir = FileMapDir(tmp.newFolder("FOR_NAV"))

    @Test
    fun aFreshSessionHasNothingToSave() {
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        assertFalse(s.dirty)
        assertEquals(0, s.count)
    }

    @Test
    fun anAddedPointMakesTheSessionDirty() {
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        assertTrue(s.add(500_000.0, 4_500_000.0, "P1"))
        assertTrue(s.dirty)
        assertEquals(1, s.count)
    }

    @Test
    fun aBlankNameIsRefusedWithAReason() {
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        assertFalse(s.add(1.0, 2.0, "   "))
        assertTrue(s.lastError!!.contains("name"))
        assertFalse(s.dirty)
    }

    @Test
    fun aNameAlreadyInTheFileIsRefused() {
        val s = EditSession("XGV_MP", "GCALL_ID", listOf("P1"))
        assertFalse(s.add(1.0, 2.0, "P1"))
        assertTrue(s.lastError!!.contains("already"))
        assertFalse(s.dirty)
    }

    @Test
    fun theSameNameCannotBeAddedTwiceInOneSession() {
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        assertTrue(s.add(1.0, 2.0, "P1"))
        assertFalse(s.add(3.0, 4.0, "P1"))
        assertEquals(1, s.count)
    }

    @Test
    fun namesDifferingOnlyInCaseAreStillDuplicates() {
        val s = EditSession("XGV_MP", "GCALL_ID", listOf("p1"))
        assertFalse(s.add(1.0, 2.0, "P1"))
    }

    @Test
    fun stagedNamesComeBackInOrder() {
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        s.add(1.0, 2.0, "A")
        s.add(3.0, 4.0, "B")
        s.add(5.0, 6.0, "C")
        assertEquals(listOf("A", "B", "C"), s.stagedNames())
    }

    @Test
    fun discardingLeavesNoTrace() {
        val d = dir()
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        s.add(1.0, 2.0, "P1")
        s.discard()
        assertFalse(s.dirty)
        assertFalse(File(tmp.root, "FOR_NAV/XGV_MP.shp").exists())
    }

    @Test
    fun committingCreatesTheLayerWithEveryStagedPoint() {
        val d = dir()
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        s.add(500_000.0, 4_500_000.0, "A")
        s.add(500_100.0, 4_500_000.0, "B")
        s.add(500_200.0, 4_500_000.0, "C")

        val c = s.commit(d, "UTM-TEXT")
        assertTrue(c is EditSession.Commit.Written)
        assertEquals(3, (c as EditSession.Commit.Written).points)
        assertTrue(c.created)
        assertEquals(listOf("A", "B", "C"), PointStore.names(d.readFile("XGV_MP.dbf")!!, "GCALL_ID"))
        assertEquals(3, PointStore.coords(d.readFile("XGV_MP.shp")!!).size)
    }

    @Test
    fun coordinatesSurviveInOrder() {
        val d = dir()
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        s.add(10.0, 20.0, "A")
        s.add(30.0, 40.0, "B")
        s.commit(d)
        val pts = PointStore.coords(d.readFile("XGV_MP.shp")!!)
        assertEquals(2, pts.size)
        assertEquals(10.0, pts[0][0], 1e-9)
        assertEquals(30.0, pts[1][0], 1e-9)
    }

    @Test
    fun pointsAlreadyInTheFileAreKept() {
        val d = dir()
        File(tmp.root, "FOR_NAV").mkdirs()
        val seed = PointStore.append(PointStore.newLayer("GCALL_ID", "SEED"),
            "GCALL_ID", "OLD", 7.0, 8.0)
        for ((ext, bytes) in seed) File(tmp.root, "FOR_NAV/XGV_MP.$ext").writeBytes(bytes)

        val s = EditSession("XGV_MP", "GCALL_ID", listOf("OLD"))
        s.add(9.0, 10.0, "NEW")
        s.commit(d)
        assertEquals(listOf("OLD", "NEW"), PointStore.names(d.readFile("XGV_MP.dbf")!!, "GCALL_ID"))
    }

    @Test
    fun aSavedSessionIsNoLongerDirty() {
        val d = dir()
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        s.add(1.0, 2.0, "A")
        s.commit(d)
        assertFalse(s.dirty)
        assertEquals(0, s.count)
    }

    @Test
    fun committingNothingDoesNothing() {
        val d = dir()
        assertTrue(EditSession("XGV_MP", "GCALL_ID", emptyList()).commit(d) is EditSession.Commit.Nothing)
        assertFalse(File(tmp.root, "FOR_NAV/XGV_MP.shp").exists())
    }

    @Test
    fun theCrsIsWrittenWhenTheLayerIsCreated() {
        val d = dir()
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        s.add(1.0, 2.0, "A")
        s.commit(d, "PROJCS-TEST")
        assertEquals("PROJCS-TEST", File(tmp.root, "FOR_NAV/XGV_MP.prj").readText())
    }

    @Test
    fun anExistingLayerKeepsItsOwnCrs() {
        val d = dir()
        File(tmp.root, "FOR_NAV").mkdirs()
        File(tmp.root, "FOR_NAV/XGV_MP.prj").writeText("ORIGINAL-CRS")
        val seed = PointStore.append(PointStore.newLayer("GCALL_ID", "ORIGINAL-CRS"),
            "GCALL_ID", "OLD", 1.0, 1.0)
        for ((ext, bytes) in seed) File(tmp.root, "FOR_NAV/XGV_MP.$ext").writeBytes(bytes)

        val s = EditSession("XGV_MP", "GCALL_ID", listOf("OLD"))
        s.add(2.0, 2.0, "NEW")
        s.commit(d, "IGNORED-SUGGESTION")
        assertEquals("ORIGINAL-CRS", File(tmp.root, "FOR_NAV/XGV_MP.prj").readText())
    }

    @Test
    fun dbfAndShpCountsStayInStep() {
        val d = dir()
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        for (i in 1..4) s.add(i * 10.0, i * 20.0, "P$i")
        s.commit(d)
        val shp = org.gcap.core.ShpFormat.shpHeader(d.readFile("XGV_MP.shp")!!)!!
        val dbf = org.gcap.core.ShpFormat.dbfHeader(d.readFile("XGV_MP.dbf")!!)!!
        assertEquals(shp.recordCount.toLong(), dbf.numRecords.toLong())
        assertEquals(4, shp.recordCount)
    }
}

/**
 * An edit must be written in the CRS the layer declares, not the one the app works in.
 *
 * The app plans in working-zone metres. A layer kept in degrees - like the point
 * collector app's points.shp - has to receive degrees, or every point lands about
 * 100,000x too far away while the file still opens cleanly.
 */
class EditSessionCrsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val degrees = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\"," +
        "SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0]," +
        "UNIT[\"Degree\",0.0174532925199433]]"
    private val utm = "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
        "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"],UNIT[\"Meter\",1.0]]"

    private fun dir() = FileMapDir(tmp.newFolder("FOR_NAV"))

    private fun seed(layer: String, prj: String, e: Double, n: Double) {
        File(tmp.root, "FOR_NAV").mkdirs()
        val files = PointStore.append(
            PointStore.newLayer("NAME", prj), "NAME", "OLD", e, n, prj)
        for ((ext, bytes) in files) File(tmp.root, "FOR_NAV/$layer.$ext").writeBytes(bytes)
    }

    @Test
    fun editingADegreesLayerStoresDegrees() {
        val d = dir()
        seed("points", degrees, -125.0189, 50.7666)
        val s = EditSession("points", "NAME", listOf("OLD"))
        s.add(500_000.0, 5_625_908.0, "NEW")          // working-zone metres
        assertTrue(s.commit(d, null, Utm.ASSUMED_ZONE) is EditSession.Commit.Written)

        val c = PointStore.coords(d.readFile("points.shp")!!).last()
        assertTrue("must be degrees, got $c", Math.abs(c[0]) < 180 && Math.abs(c[1]) < 90)
        assertEquals("and near the same place", -123.0, c[0], 6.0)
    }

    @Test
    fun editingAProjectedLayerStoresMetres() {
        val d = dir()
        seed("calls", utm, 500_000.0, 5_625_908.0)
        val s = EditSession("calls", "NAME", listOf("OLD"))
        s.add(500_200.0, 5_626_008.0, "NEW")
        assertTrue(s.commit(d, null, Utm.ASSUMED_ZONE) is EditSession.Commit.Written)

        assertEquals(500_200.0, PointStore.coords(d.readFile("calls.shp")!!).last()[0], 1e-6)
    }

    @Test
    fun aNewLayerStillGetsTheSuppliedCrs() {
        val d = dir()
        val s = EditSession("fresh", "NAME", emptyList())
        s.add(500_000.0, 5_625_908.0, "P1")
        assertTrue(s.commit(d, utm, Utm.ASSUMED_ZONE) is EditSession.Commit.Written)
        assertEquals(utm, File(tmp.root, "FOR_NAV/fresh.prj").readText())
    }
}
