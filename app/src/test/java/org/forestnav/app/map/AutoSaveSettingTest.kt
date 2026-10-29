package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A8 says "leave it blank ... if blank no autosave for edits", while the app default is
 * 40 seconds. Those are different states and must not collapse into one.
 */
class AutoSaveSettingTest {

    private val p = File(System.getProperty("user.home"), "projects/ForestNavigator")

    @Test
    fun noSpreadsheetMeansTheAppDefault() {
        assertEquals(40, NavSettings().saveEditsEverySec)
        assertEquals(40, SettingsResolver(null, 0, null, 0).resolve().saveEditsEverySec)
    }

    @Test
    fun theRealSheetsPlaceholderLeavesTheAppDefaultInForce() {
        val f = File(p, "settings_1.ods")
        if (!f.exists()) return
        val sheet = OdsTable.parse(f.readBytes())
        val a8 = sheet?.cell("A8")?.trim().orEmpty()
        assertTrue("A8 should still hold the sheet's placeholder prose", a8.isNotEmpty())
        assertEquals("a placeholder is not a decision, so the default stands",
            40, SettingsResolver(sheet, 1, null, 0).resolve().saveEditsEverySec)
    }

    @Test
    fun theOtherDefaultsAreUnaffected() {
        val r = SettingsResolver(null, 0, null, 0).resolve()
        assertEquals(10, r.trackIntervalSec)
        assertEquals(5.0, r.trackMinDistanceM, 0.001)
        assertEquals("tracks", r.trackLayer)
    }
}
