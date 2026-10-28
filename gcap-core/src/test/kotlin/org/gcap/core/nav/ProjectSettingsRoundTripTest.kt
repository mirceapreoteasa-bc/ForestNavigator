package org.gcap.core.nav

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Settings values the user can change must survive a save into the project file and
 * be read back unchanged. They were held in memory, so a change looked like it had been
 * lost the moment the app was closed.
 */
class ProjectSettingsRoundTripTest {

    @Test
    fun `the four editable numbers survive a write and read`() {
        val original = JSONObject(
            """{"layers":[{"name":"access","visible":true}]}""",
        )
        val stored = org.gcap.core.nav.ProjectSettingsMirror(
            trackIntervalSec = 7,
            trackMinDistanceM = 12.0,
            saveEditsEverySec = 25,
            defaultScaleDenominator = 25000,
        )
        val text = ProjectSettingsMirror.writeInto(original.toString(), stored)

        val back = ProjectSettingsMirror.parse(text)
        assertEquals(7, back.trackIntervalSec)
        assertEquals(12.0, back.trackMinDistanceM)
        assertEquals(25, back.saveEditsEverySec)
        assertEquals(25000, back.defaultScaleDenominator)
    }

    @Test
    fun `the layer list is not damaged by writing settings`() {
        val original = JSONObject("""{"layers":[{"name":"access","visible":false}]}""")
        val text = org.gcap.core.nav.ProjectSettingsMirror(trackIntervalSec = 3)
            .let { ProjectSettingsMirror.writeInto(original.toString(), it) }
        val layers = JSONObject(text).getJSONArray("layers")
        assertEquals(1, layers.length())
        assertEquals("access", layers.getJSONObject(0).getString("name"))
        assertEquals(false, layers.getJSONObject(0).getBoolean("visible"))
    }

    @Test
    fun `an absent value reads as null rather than zero`() {
        val back = ProjectSettingsMirror.parse("""{"layers":[]}""")
        assertNull(back.trackIntervalSec)
        assertNull(back.saveEditsEverySec)
        assertNull(back.defaultScaleDenominator)
        assertNull(back.trackMinDistanceM)
    }

    @Test
    fun `a zero is a real value and is kept`() {
        val text = ProjectSettingsMirror.writeInto(
            """{"layers":[]}""", ProjectSettingsMirror(trackIntervalSec = 0),
        )
        assertEquals(0, ProjectSettingsMirror.parse(text).trackIntervalSec)
    }
}
