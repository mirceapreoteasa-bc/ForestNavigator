package org.forestnav.app.map

import org.gcap.core.PointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MOB and Add point write to layers in different coordinate systems on purpose -
 * `points` in degrees for point-collector interop, `xgv_mp` in project metres. Neither
 * may take the other's format, whatever the user does in between.
 *
 * These are the alternating cases, run against real files rather than reasoning about
 * them, because the failure is silent: a layer written in the wrong units still opens
 * cleanly and still shows a marker, just in the wrong hemisphere.
 */
class CrsDriftTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun folder() = FileMapDir(tmp.newFolder("FOR_NAV"))

    private fun firstCoord(dir: FileMapDir, stem: String): DoubleArray {
        val all = PointStore.coords(dir.readFile("$stem.shp")!!)
        assertTrue("$stem should hold a point", all.isNotEmpty())
        return all[0]
    }

    @Test
    fun mobThenAddPointThenMobKeepsEachLayerInItsOwnSystem() {
        val dir = folder()
        val e = 612_345.0
        val n = 4_567_890.0

        // a degrees MOB layer, and a projected edit layer
        MobWriter.write(dir, "points", "NAME", "A", e, n, null, Utm.ASSUMED_ZONE)
        for (f in PointStore.newLayer("GCALL_ID", PRJ_UTM).entries) {
            dir.overwriteFile("XGV_MP.${f.key.substringAfterLast('.')}", f.value)
        }

        // alternating, as a crew would
        MobWriter.write(dir, "points", "NAME", "B", e + 10, n + 10, null, Utm.ASSUMED_ZONE)
        val session = EditSession("XGV_MP", "GCALL_ID", emptyList())
        session.add(e + 20, n + 20, "P1")
        session.commit(dir, null, Utm.ASSUMED_ZONE)
        MobWriter.write(dir, "points", "NAME", "C", e + 30, n + 30, null, Utm.ASSUMED_ZONE)

        val p = firstCoord(dir, "points")
        assertEquals(
            "the MOB layer must still be in degrees after an edit-session write",
            0.0, p[0] - expectedLon(e, n), 1e-6,
        )
        assertEquals(
            "the MOB layer must still be in degrees after an edit-session write",
            0.0, p[1] - expectedLat(e, n), 1e-6,
        )
    }

    @Test
    fun aDegreesLayerThatHasLostItsPrjIsNotSilentlyWrittenInMetres() {
        val dir = folder()
        val e = 612_345.0
        val n = 4_567_890.0
        MobWriter.write(dir, "points", "NAME", "A", e, n, null, Utm.ASSUMED_ZONE)

        // the .prj goes missing - renamed, or lost in a copy
        File(tmp.root, "FOR_NAV/points.prj").delete()

        val out = MobWriter.write(dir, "points", "NAME", "B", e + 10, n + 10, null, Utm.ASSUMED_ZONE)
        assertTrue(
            "writing into a layer whose CRS cannot be read must be refused, not guessed: $out",
            out is MobWriter.Outcome.Failed,
        )
    }

    @Test
    fun theRefusalLeavesTheExistingPointUntouched() {
        val dir = folder()
        MobWriter.write(dir, "points", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)
        File(tmp.root, "FOR_NAV/points.prj").delete()
        MobWriter.write(dir, "points", "NAME", "B", 612_355.0, 4_567_900.0, null, Utm.ASSUMED_ZONE)

        val coords = PointStore.coords(dir.readFile("points.shp")!!)
        assertEquals("nothing should have been added", 1, coords.size)
        assertEquals(0.0, coords[0][0] - expectedLon(612_345.0, 4_567_890.0), 1e-6)
    }

    @Test
    fun aProjectedLayerKeepsMetresAcrossRepeatedMobWrites() {
        val dir = folder()
        // a MOB layer that is projected, not degrees - unusual but legal
        val one = listOf(doubleArrayOf(612_345.0, 4_567_890.0))
        dir.overwriteFile("points.shp", org.gcap.core.ShpFormat.buildPointShp(one))
        dir.overwriteFile("points.shx", org.gcap.core.ShpFormat.buildPointShx(one))
        dir.overwriteFile("points.dbf", org.gcap.core.ShpFormat.buildDbf(ByteArray(0), listOf("A")))
        dir.overwriteFile("points.prj", PRJ_UTM.toByteArray())

        MobWriter.write(dir, "points", "NAME", "B", 612_355.0, 4_567_900.0, null, Utm.ASSUMED_ZONE)
        val c = PointStore.coords(dir.readFile("points.shp")!!)
        assertEquals(2, c.size)
        // the second point must be metres, matching the layer it was written into
        assertEquals(0.0, c[1][0] - 612_355.0, 1e-6)
        assertEquals(0.0, c[1][1] - 4_567_900.0, 1e-6)
    }

    private fun expectedLon(e: Double, n: Double): Double = Utm.utmToLatLon(e, n, Utm.ASSUMED_ZONE).second
    private fun expectedLat(e: Double, n: Double): Double = Utm.utmToLatLon(e, n, Utm.ASSUMED_ZONE).first

    private companion object {
        val PRJ_UTM =
            "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
                "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"]," +
                "PARAMETER[\"Central_Meridian\",-123.0],UNIT[\"Meter\",1.0]]"
    }
}

/** The same guarantee on the Add point side, which writes to a different layer. */
class AddPointCrsDriftTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun folder() = FileMapDir(tmp.newFolder("FOR_NAV"))

    @Test
    fun addPointRefusesALayerThatHasLostItsPrj() {
        val dir = folder()
        // a degrees layer from the point collector
        MobWriter.write(dir, "points", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)
        java.io.File(tmp.root, "FOR_NAV/points.prj").delete()

        val session = EditSession("points", "NAME", listOf("A"))
        session.add(612_355.0, 4_567_900.0, "B")
        val r = session.commit(dir, null, Utm.ASSUMED_ZONE)

        assertTrue(
            "must refuse rather than write metres into an unknown layer: $r",
            r is EditSession.Commit.Failed,
        )
    }

    @Test
    fun addPointStillWorksOnAProjectedLayer() {
        val dir = folder()
        val one = listOf(doubleArrayOf(612_345.0, 4_567_890.0))
        dir.overwriteFile("xgv_mp.shp", org.gcap.core.ShpFormat.buildPointShp(one))
        dir.overwriteFile("xgv_mp.shx", org.gcap.core.ShpFormat.buildPointShx(one))
        dir.overwriteFile("xgv_mp.dbf", org.gcap.core.ShpFormat.buildDbf(ByteArray(0), listOf("A")))
        dir.overwriteFile("xgv_mp.prj", PRJ_UTM.toByteArray())

        val session = EditSession("xgv_mp", "GCALL_ID", listOf("A"))
        session.add(612_355.0, 4_567_900.0, "B")
        val r = session.commit(dir, null, Utm.ASSUMED_ZONE)
        assertTrue("a projected layer must still accept points: $r", r is EditSession.Commit.Written)

        val c = org.gcap.core.PointStore.coords(dir.readFile("xgv_mp.shp")!!)
        assertEquals(2, c.size)
        assertEquals(0.0, c[1][0] - 612_355.0, 1e-6)
    }

    private companion object {
        val PRJ_UTM =
            "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
                "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"]," +
                "PARAMETER[\"Central_Meridian\",-123.0],UNIT[\"Meter\",1.0]]"
    }
}
