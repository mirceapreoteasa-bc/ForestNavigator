package org.forestnav.app.map

import org.gcap.core.PointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `MobWriter` once fell back to the first `.prj` it found *anywhere in the folder* when
 * a layer had none of its own. In a folder like FOR_NAV - degrees point layers sitting
 * beside UTM project layers - that would hand `points.shp` somebody else's projection and
 * scatter every point ~5,600 km away. This folder is arranged so that the projected layer
 * sorts first, which is the case that would trigger it.
 */
class PrjContaminationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun folder() = FileMapDir(tmp.newFolder("FOR_NAV"))

    @Test
    fun writingToADegreesLayerNeverAdoptsAnotherLayersProjection() {
        val dir = folder()
        // projected layer, sorts before "points"
        val one = listOf(doubleArrayOf(612_345.0, 4_567_890.0))
        dir.overwriteFile("access.shp", org.gcap.core.ShpFormat.buildPointShp(one))
        dir.overwriteFile("access.shx", org.gcap.core.ShpFormat.buildPointShx(one))
        dir.overwriteFile("access.dbf", org.gcap.core.ShpFormat.buildDbf(ByteArray(0), listOf("A")))
        dir.overwriteFile("access.prj", PRJ_UTM.toByteArray())

        // the degrees layer the collector writes
        MobWriter.write(dir, "points", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)
        val before = String(dir.readFile("points.prj")!!)
        assertTrue("points.prj should be the degrees one", before.contains("Degree"))

        MobWriter.write(dir, "points", "NAME", "B", 612_355.0, 4_567_900.0, null, Utm.ASSUMED_ZONE)
        val after = String(dir.readFile("points.prj")!!)

        assertEquals("points.prj must be left exactly as it was", before, after)
        val c = PointStore.coords(dir.readFile("points.shp")!!)
        assertEquals(2, c.size)
        assertEquals(
            "the new point must be degrees",
            0.0, c[1][0] - Utm.utmToLatLon(612_355.0, 4_567_900.0, Utm.ASSUMED_ZONE).second, 1e-6,
        )
    }

    @Test
    fun aProjectedMOBLayerIsNotOverwrittenByTheFolderDegreesPrj() {
        val dir = folder()
        val one = listOf(doubleArrayOf(612_345.0, 4_567_890.0))
        dir.overwriteFile("zgv_mp.shp", org.gcap.core.ShpFormat.buildPointShp(one))
        dir.overwriteFile("zgv_mp.shx", org.gcap.core.ShpFormat.buildPointShx(one))
        dir.overwriteFile("zgv_mp.dbf", org.gcap.core.ShpFormat.buildDbf(ByteArray(0), listOf("A")))
        dir.overwriteFile("zgv_mp.prj", PRJ_UTM.toByteArray())
        // a degrees layer that sorts before it
        MobWriter.write(dir, "points", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)

        MobWriter.write(dir, "zgv_mp", "NAME", "B", 612_355.0, 4_567_900.0, null, Utm.ASSUMED_ZONE)

        assertTrue(
            "the projected layer must keep its own .prj",
            String(dir.readFile("zgv_mp.prj")!!).contains("Meter"),
        )
        val c = PointStore.coords(dir.readFile("zgv_mp.shp")!!)
        assertEquals(0.0, c[1][0] - 612_355.0, 1e-6)
    }

    private companion object {
        val PRJ_UTM =
            "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
                "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"]," +
                "PARAMETER[\"Central_Meridian\",-123.0],UNIT[\"Meter\",1.0]]"
    }
}
