package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The same case hazard as [PointsNameCaseTest], on the Add point side. The survey
 * folder has `xgv_mp.shp`; the app's defaults and the collector spell it `XGV_MP`.
 * Add point must land in the file that is already there.
 */
class AddPointNameCaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun folder() = FileMapDir(tmp.newFolder("FOR_NAV"))

    private fun shps(dir: FileMapDir) = dir.listFiles().filter { it.endsWith(".shp", true) }.sorted()

    private fun seedXgv(dir: FileMapDir, name: String) {
        val one = listOf(doubleArrayOf(612_345.0, 4_567_890.0))
        dir.overwriteFile("$name.shp", org.gcap.core.ShpFormat.buildPointShp(one))
        dir.overwriteFile("$name.shx", org.gcap.core.ShpFormat.buildPointShx(one))
        dir.overwriteFile("$name.dbf", org.gcap.core.ShpFormat.buildDbf(ByteArray(0), listOf("A")))
        dir.overwriteFile("$name.prj", PRJ_UTM.toByteArray())
    }

    @Test
    fun addPointToTheOtherSpellingDoesNotCreateASecondLayer() {
        val dir = folder()
        seedXgv(dir, "xgv_mp")

        val s = EditSession("XGV_MP", "GCALL_ID", listOf("A"))
        s.add(612_355.0, 4_567_900.0, "B")
        val r = s.commit(dir, null, Utm.ASSUMED_ZONE)

        assertTrue("commit should succeed: $r", r is EditSession.Commit.Written)
        assertEquals("one layer only", listOf("xgv_mp.shp"), shps(dir))
    }

    @Test
    fun theReverseDirectionAlsoResolvesToOneLayer() {
        val dir = folder()
        seedXgv(dir, "XGV_MP")

        val s = EditSession("xgv_mp", "GCALL_ID", listOf("A"))
        s.add(612_355.0, 4_567_900.0, "B")
        s.commit(dir, null, Utm.ASSUMED_ZONE)

        assertEquals("one layer only", listOf("XGV_MP.shp"), shps(dir))
    }

    @Test
    fun alternatingSpellingsNeverSplitTheLayer() {
        val dir = folder()
        seedXgv(dir, "xgv_mp")
        listOf("XGV_MP", "xgv_mp", "Xgv_Mp").forEachIndexed { i, name ->
            val s = EditSession(name, "GCALL_ID", listOf("A"))
            s.add(612_355.0 + i, 4_567_900.0 + i, "P$i")
            s.commit(dir, null, Utm.ASSUMED_ZONE)
        }
        assertEquals("still one layer", listOf("xgv_mp.shp"), shps(dir))
        val c = org.gcap.core.PointStore.coords(dir.readFile("xgv_mp.shp")!!)
        assertEquals("every point accounted for", 4, c.size)
    }

    private companion object {
        val PRJ_UTM =
            "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
                "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"]," +
                "PARAMETER[\"Central_Meridian\",-123.0],UNIT[\"Meter\",1.0]]"
    }
}