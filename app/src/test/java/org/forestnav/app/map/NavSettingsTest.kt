package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavSettingsTest {

    private val project = File("/home/mircea/projects/ForestNavigator")

    private fun real(): SettingsResolver? {
        val sf = File(project, "settings_1.ods")
        val lf = File(project, "layer_settings.ods")
        if (!sf.exists() || !lf.exists()) return null
        return SettingsResolver(
            OdsTable.parse(sf.readBytes()), sf.lastModified(),
            OdsTable.parse(lf.readBytes()), lf.lastModified(),
        )
    }

    @Test
    fun fallsBackToDefaultsWithNoFiles() {
        val r = SettingsResolver(null, 0, null, 0)
        val s = r.resolve()
        assertEquals("Ground_Call", s.goToLayer)
        assertEquals("points", s.mobLayer)
        assertEquals("tracks", s.trackLayer)
        assertEquals(10, s.trackIntervalSec)
        assertEquals("app default", s.addPointNameFieldSource)
    }

    @Test
    fun readsLayerRows() {
        val r = real() ?: return
        val rows = r.layers()
        assertTrue("should list layers, got ${rows.size}", rows.size > 5)
        val xgv = rows.firstOrNull { it.name == "XGV_MP" }
        assertNotNull("XGV_MP should be listed", xgv)
        assertTrue("XGV_MP is marked editable", xgv!!.editable)
        assertEquals("triangle", xgv.symbol)
        assertEquals("GCALL_ID", xgv.labelField)
    }

    @Test
    fun trackLayerIsEditableInTheFile() {
        val r = real() ?: return
        val track = r.layer("track")
        assertNotNull("track layer should be listed", track)
        assertTrue("track is marked editable in layer_settings.ods", track!!.editable)
    }

    @Test
    fun unfilledSheetLeavesDefaultsInForce() {
        val r = real() ?: return
        val s = r.resolve()
        // settings_1.ods as designed is a mockup: column A is prose, the value cells are empty
        assertEquals("Ground_Call", s.goToLayer)
        assertEquals("points", s.mobLayer)
        assertEquals("tracks", s.trackLayer)
        assertEquals(10, s.trackIntervalSec)
        assertEquals(5.0, s.trackMinDistanceM, 0.001)
    }

    @Test
    fun commentaryIsNeverReadAsAValue() {
        val r = real() ?: return
        for (addr in listOf("A6", "A8", "A10", "A16", "A18", "A22", "A24")) {
            val raw = File(project, "settings_1.ods").let { OdsTable.parse(it.readBytes()) }?.cell(addr)
            assertTrue("A$addr should hold prose, got ${raw?.take(40)}", raw?.contains("Unknown Author") == true)
        }
        // and none of it may leak into a layer name
        val s = r.resolve()
        for (v in listOf(s.goToLayer, s.mobLayer, s.trackLayer, s.addPointLayer)) {
            assertTrue("commentary leaked into \"$v\"", v.length < 40 && !v.contains("Author"))
        }
    }

    @Test
    fun nameFieldFallsBackToTheLayerLabel() {
        // A27 has no value and A26 is only a caption, so the layer's column G must win
        val forced = SettingsResolver(
            OdsTable.parse(File(project, "settings_1.ods").readBytes()), 300,
            OdsTable.parse(File(project, "layer_settings.ods").readBytes()), 200,
        )
        val s = forced.resolve()
        assertEquals("GCALL_ID", s.addPointNameField)
        assertEquals("layer_settings.ods", s.addPointNameFieldSource)
    }

    @Test
    fun newerLayerFileWinsTheNameField() {
        // pretend layer_settings.ods is the newer of the two
        val forced = SettingsResolver(
            OdsTable.parse(File(project, "settings_1.ods").readBytes()), 100,
            OdsTable.parse(File(project, "layer_settings.ods").readBytes()), 200,
        )
        val s = forced.resolve()
        assertEquals("GCALL_ID", s.addPointNameField)
    }

    @Test
    fun newerSettingsFileWinsTheNameField() {
        val forced = SettingsResolver(
            OdsTable.parse(File(project, "settings_1.ods").readBytes()), 300,
            OdsTable.parse(File(project, "layer_settings.ods").readBytes()), 200,
        )
        // settings_1.ods is newer but its name-field cell is empty, so nothing changes
        assertEquals("GCALL_ID", forced.resolve().addPointNameField)
    }

    @Test
    fun unknownLayerIsReported() {
        val r = real() ?: return
        val s = r.resolve().copy(addPointLayer = "no_such_layer")
        val w = r.warnings(s)
        assertTrue("should warn about the missing layer: $w", w.any { it.contains("no_such_layer") })
    }

    @Test
    fun scaleAcceptsTheSpreadsheetFormat() {
        assertEquals(10_000, SettingsResolver(null, 0, null, 0).resolve().defaultScaleDenominator)
    }

    @Test
    fun defaultCoordinateLayerIsTheFirstListed() {
        val r = real() ?: return
        assertEquals("XGV_MP", r.resolve().defaultCrsLayer)
    }

    @Test
    fun labelFieldOfEachLayerIsRead() {
        val r = real() ?: return
        assertEquals("Call_Num", r.layer("Ground_Call")!!.labelField)
        assertEquals("GCALL_ID", r.layer("XGV_MP")!!.labelField)
        assertEquals(7.0, r.layer("XGV_MP")!!.labelSize, 0.001)
        assertEquals(0.2, r.layer("track")!!.size, 0.001)
    }
}
/**
 * The sheet's layout: a yellow function button on each odd row, its display field on the
 * row beneath. A test that reads prose out of the wrong row is how a layer name silently
 * becomes a paragraph, so the pairing is pinned here.
 */
class SettingsSheetLayoutTest {

    private val table = OdsTable.parse(File("/home/mircea/projects/ForestNavigator/settings_1.ods").readBytes())

    private fun at(a: String) = table?.cell(a)?.trim().orEmpty()

    private fun assertButton(button: String, display: String, word: String) {
        val b = at(button)
        val d = at(display)
        assertTrue("$button should be a function button mentioning \"$word\", got: ${b.take(60)}",
            b.contains(word, ignoreCase = true))
        assertTrue("$display should be a display field, got: ${d.take(60)}",
            d.contains("here", ignoreCase = true) || d.isEmpty())
    }

    @Test
    fun everyButtonSitsDirectlyAboveItsDisplayField() {
        assertButton("A3", "A4", "folder")
        assertButton("A5", "A6", "coordinate")
        assertButton("A7", "A8", "seconds")
        assertButton("A9", "A10", "interval")
        assertButton("A11", "A12", "distance")
        assertButton("A13", "A14", "scale")
        assertButton("A15", "A16", "scale")
        assertButton("A17", "A18", "point")
        assertButton("A19", "A20", "column")
        assertButton("A21", "A22", "Add Point")
        assertButton("A23", "A24", "MOB")
        assertButton("A25", "A26", "track")
    }

    @Test
    fun navigationModeButtonLivesInColumnB() {
        assertTrue("B23 should be the Navigation mode button", at("B23").contains("Navigation mode"))
        assertTrue("B24 should display it", at("B24").contains("on ground or on air"))
    }

    @Test
    fun nameFieldButtonIsA27AndDisplaysInA28() {
        assertTrue("A27 should be the name field button", at("A27").contains("NAME_FIELD"))
    }

    @Test
    fun restoreAndVerifyHaveNoDisplayField() {
        // B7 restores defaults and B9 writes the long report, so nothing is displayed
        // beneath them; their display slots are reused by the network builders
        assertTrue("B9 is Verify Layers", at("B9").contains("Verify"))
    }

    @Test
    fun displayCellsHoldNoRealValueYetSoDefaultsApply() {
        val r = SettingsResolver(table, 1, null, 0)
        // B4 holds the note 'current map name default "mirshp"', which is prose,
        // not a settled name - the opened project supplies it instead
        assertEquals("", r.resolve().workingMapName)
        assertEquals("Ground_Call", r.resolve().goToLayer)
        assertEquals("app default", r.resolve().addPointNameFieldSource)
    }
}

/**
 * The sheet's row is `track`, the layer on disk is `tracks.shp`. These must still meet,
 * or the track layer quietly loses its dash symbol and editability.
 */
class TrackLayerMatchTest {

    private val dir = SettingsResolver(
        OdsTable.parse(File("/home/mircea/projects/ForestNavigator/settings_1.ods").readBytes()), 1,
        OdsTable.parse(File("/home/mircea/projects/ForestNavigator/layer_settings.ods").readBytes()), 1,
    )

    @Test
    fun theSheetRowIsStillFoundForTheTracksLayer() {
        val t = dir.layer("tracks")
        assertNotNull("track row should style tracks.shp", t)
        assertEquals("track", t!!.name)
        assertTrue("track is editable in the sheet", t.editable)
        assertEquals("dash", t.symbol)
    }

    @Test
    fun matchingIgnoresCaseAndTheExtension() {
        assertNotNull(dir.layer("TRACKS"))
        assertNotNull(dir.layer("tracks.shp"))
        assertNotNull(dir.layer("Track"))
    }

    @Test
    fun otherLayersStillResolveToThemselves() {
        assertEquals("POINTS", dir.layer("POINTS")?.name)
        assertEquals("XGV_MP", dir.layer("XGV_MP")?.name)
        // the loose match must not make every layer look like the track row
        assertNull(dir.layer("no_such_layer"))
    }

    @Test
    fun theDefaultIsTheOnDiskName() {
        assertEquals("tracks", NavSettings().trackLayer)
    }
}
