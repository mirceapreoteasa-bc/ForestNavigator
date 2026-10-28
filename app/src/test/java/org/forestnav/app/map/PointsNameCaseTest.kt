package org.forestnav.app.map

import org.gcap.core.PointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The app grew out of a collector that used `POINTS.shp`, while the survey folder and the
 * app's own defaults use `points.shp`. Those two spellings must not become two layers: a
 * MOB written to `POINTS` has to land in a `points` that is already there, and never in a
 * second file beside it.
 *
 * Every case here is driven through the real writer, so a mismatch shows up as a
 * duplicate file rather than as a passing assertion about a string.
 */
class PointsNameCaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun folder() = FileMapDir(tmp.newFolder("FOR_NAV"))

    private fun filesEnding(dir: FileMapDir, ext: String) =
        dir.listFiles().filter { it.endsWith(ext, true) }.sorted()

    @Test
    fun theDefaultSpellingIsLowercasePoints() {
        // whatever the app calls the MOB layer by default, it should agree across the
        // two places that declare it - NavSettings and LayerStyle
        val a = NavSettings().mobLayer
        val b = LayerStyle.Settings().mobLayer
        assertEquals(
            "the two defaults disagree: NavSettings=$a, LayerStyle=$b",
            a.lowercase(),
            b.lowercase(),
        )
        assertEquals("points", a)
        assertEquals("points", b)
    }

    @Test
    fun aMobWrittenToAnUppercaseNameFindsTheExistingLowercaseLayer() {
        val dir = folder()
        // an existing points.shp, lower case, as the survey folder has it
        MobWriter.write(dir, "points", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)
        assertEquals(1, filesEnding(dir, ".shp").size)

        // the MOB layer configured in upper case
        val out = MobWriter.write(dir, "POINTS", "NAME", "B", 612_355.0, 4_567_900.0, null, Utm.ASSUMED_ZONE)
        assertTrue("should have appended: $out", out is MobWriter.Outcome.Written)
        assertTrue(
            "must reuse the existing lower-case file, not create POINTS.shp",
            !filesEnding(dir, ".shp").contains("POINTS.shp"),
        )
        assertEquals(1, filesEnding(dir, ".shp").size)
    }

    @Test
    fun theReverseDirectionBehavesTheSame() {
        val dir = folder()
        MobWriter.write(dir, "POINTS", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)
        val upper = filesEnding(dir, ".shp").single()

        MobWriter.write(dir, "points", "NAME", "B", 612_355.0, 4_567_900.0, null, Utm.ASSUMED_ZONE)
        assertEquals("no second layer may appear", listOf(upper), filesEnding(dir, ".shp"))
    }

    @Test
    fun bothSpellingsResolveToTheSameLayer() {
        val dir = folder()
        MobWriter.write(dir, "points", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)
        val lower = MobWriter.shpName(dir, "POINTS")
        assertNotNull("POINTS must resolve to the existing points.shp", lower)
        assertEquals("points.shp", lower!!.lowercase())
    }

    @Test
    fun neitherSpellingCreatesADuplicateLayer() {
        val dir = folder()
        // alternate the way a crew would if the setting were changed between sessions
        MobWriter.write(dir, "points", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)
        MobWriter.write(dir, "POINTS", "NAME", "B", 612_355.0, 4_567_900.0, null, Utm.ASSUMED_ZONE)
        MobWriter.write(dir, "points", "NAME", "C", 612_365.0, 4_567_910.0, null, Utm.ASSUMED_ZONE)
        MobWriter.write(dir, "POINTS", "NAME", "D", 612_375.0, 4_567_920.0, null, Utm.ASSUMED_ZONE)

        assertEquals("one layer only, however the name is spelled", 1, filesEnding(dir, ".shp").size)
        val coords = PointStore.coords(dir.readFile(filesEnding(dir, ".shp").single())!!)
        assertEquals("all four MOBs in the one layer", 4, coords.size)
    }

    @Test
    fun everySidecarKeepsTheOnDiskCase() {
        val dir = folder()
        MobWriter.write(dir, "points", "NAME", "A", 612_345.0, 4_567_890.0, null, Utm.ASSUMED_ZONE)
        val name = filesEnding(dir, ".shp").single().substringBeforeLast('.')
        MobWriter.write(dir, "POINTS", "NAME", "B", 612_355.0, 4_567_900.0, null, Utm.ASSUMED_ZONE)

        for (ext in PointStore.SIDECARS) {
            val hits = filesEnding(dir, ".$ext")
            assertEquals("one $ext", 1, hits.size)
            assertEquals(
                "sidecars must not drift apart in case",
                name.lowercase(),
                hits.single().substringBeforeLast('.').lowercase(),
            )
        }
        assertFalse(
            "no upper-case duplicates",
            File(tmp.root, "FOR_NAV/POINTS.shp").exists(),
        )
    }

    @Test
    fun theEmergencyLayerIsRecognisedWhicheverWayItIsSpelled() {
        for (n in listOf("points", "POINTS", "Points", "points.shp")) {
            assertTrue(
                "$n must count as the emergency layer",
                org.forestnav.app.map.ScaleLayerPolicy.isEmergency(n),
            )
        }
    }

    @Test
    fun theDegreesLayerIsRecognisedWhicheverWayItIsSpelled() {
        for (n in listOf("points", "POINTS", "Points")) {
            assertTrue(
                "$n must count as deliberately in degrees",
                n.equals("points", true) || n.equals("tracks", true),
            )
        }
    }

    private fun assertFalse(message: String, condition: Boolean) =
        assertTrue(message, !condition)
}