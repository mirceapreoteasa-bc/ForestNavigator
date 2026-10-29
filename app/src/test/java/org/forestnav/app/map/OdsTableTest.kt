package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OdsTableTest {

    private val project = File(System.getProperty("user.home"), "projects/ForestNavigator")

    private fun settings(): OdsTable? {
        val f = File(project, "settings_1.ods")
        if (!f.exists()) return null
        return OdsTable.parse(f.readBytes())
    }

    private fun layers(): OdsTable? {
        val f = File(project, "layer_settings.ods")
        if (!f.exists()) return null
        return OdsTable.parse(f.readBytes())
    }

    @Test
    fun readsTheRealSettingsFile() {
        val t = settings() ?: return
        assertTrue("settings_1.ods should parse", t.sheetName.isNotEmpty())
        // label rows are odd, their value sits on the next row
        val goToLabel = t.cell("A17")
        assertTrue(
            "A17 should be the Go to layer label, got: $goToLabel",
            goToLabel != null && goToLabel.contains("Go to", ignoreCase = true),
        )
    }

    @Test
    fun readsTheRealLayerSettingsFile() {
        val t = layers() ?: return
        val xgv = (1..40).firstOrNull { t.cell("A$it") == "XGV_MP" }
        assertTrue("XGV_MP row should exist", xgv != null)
        assertEquals("triangle", t.cell("D$xgv"))
        assertEquals("orange", t.cell("E$xgv"))
    }

    @Test
    fun addressLookupRejectsNonsense() {
        val t = settings() ?: return
        assertEquals(null, t.cell(""))
        assertEquals(null, t.cell("17"))
        assertEquals(null, t.cell("A"))
        assertEquals(null, t.cell("A0"))
    }

    @Test
    fun missingFileYieldsNullRatherThanThrowing() {
        assertEquals(null, OdsTable.parse(ByteArray(0)))
        assertEquals(null, OdsTable.parse("not a zip".toByteArray()))
    }
}