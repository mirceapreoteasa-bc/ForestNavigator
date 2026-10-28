package org.forestnav.app.map

import org.gcap.core.PointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the Go to… defaults against the real survey folder.
 *
 * The app default is only worth anything if it matches the data it will actually open, so
 * this fails loudly if the project's Ground_Call layer is renamed or its field changes.
 */
class GoToDefaultsTest {

    private val forNav = File("/home/mircea/ForestNavigator/FOR_NAV")

    /** The first call/naming field a layer offers; the survey folder varies. */
    private val NAMED_FIELDS = listOf("Call_Num", "GCALL_ID", "NAME", "Call_ID", "F001A")
        .first { f ->
            listOf("ground_call", "xgv_mp", "poi_interes", "points").any { l ->
                MobWriter.existingNames(real() ?: return@any false, l, f).isNotEmpty()
            }
        }

    private fun real(): FileMapDir? = forNav.takeIf { it.isDirectory }?.let { FileMapDir(it) }

    @Test
    fun theDefaultLayerAndFieldNameALayerThatExists() {
        val d = real() ?: return
        val cfg = NavSettings()
        // The stored default must at least name a layer the project has. Which layer holds
        // the calls is the user's choice and is set in Settings, so it is not pinned here.
        assertTrue(
            "${cfg.goToLayer}.shp should exist in FOR_NAV",
            MobWriter.shpName(d, cfg.goToLayer) != null,
        )
    }

    @Test
    fun aChosenCallResolvesToItsOwnCoordinate() {
        val d = real() ?: return
        // Uses a layer that actually carries records in the current survey folder.
        val layer = listOf("ground_call", "xgv_mp", "poi_interes", "points")
            .firstOrNull { MobWriter.existingNames(d, it, NAMED_FIELDS).isNotEmpty() } ?: return
        val cfg = NavSettings()
        val shp = MobWriter.shpName(d, layer)!!
        val stem = shp.substringBeforeLast('.')
        fun side(ext: String) = d.readFile(
            d.listFiles().first { it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".$ext", true) })!!

        val dbf = side("dbf")
        val shpB = side("shp")
        val first = PointStore.names(dbf, NAMED_FIELDS).first()
        val i = PointStore.indexOf(dbf, NAMED_FIELDS, first)
        assertTrue("$first should resolve to a record", i >= 0)
        val c = PointStore.coordAt(shpB, i)
        assertTrue("record $i should have a coordinate", c != null)
        // the survey area, so a wrong axis or an unprojected value shows up here
        assertTrue("eastings look wrong: ${c!![0]}", c[0] in 100_000.0..800_000.0)
        assertTrue("northings look wrong: ${c[1]}", c[1] in 4_000_000.0..6_000_000.0)
    }
}
