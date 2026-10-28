package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Would Start map have caught it?" - asked about a layer that is switched ON in
 * layer settings. ProjectScan is the only thing standing between a broken layer and the
 * map drawing it, so each way a .prj can fail to be there is checked on its own.
 */
class MissingPrjScanTest {

    private val prjUtm =
        "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
            "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"]," +
            "PARAMETER[\"Central_Meridian\",-123.0],UNIT[\"Meter\",1.0]]"
    private val prjDeg =
        "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\"," +
            "6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925]]"

    private fun probes(vararg p: Triple<String, String?, Boolean>) =
        p.map { ProjectScan.LayerProbe(it.first, it.second, it.third) }

    @Test
    fun aSwitchedOnLayerWithNoPrjIsReportedAsAWarning() {
        val r = ProjectScan.scan(
            probes(Triple("points", null, true)),
            setOf("points"),
        )
        assertEquals(1, r.warnings().size, "warning count")
        assertTrue(r.warnings()[0].contains("points"), "names the layer")
        assertTrue(r.warnings()[0].contains("no .prj"), "says what is wrong")
    }

    @Test
    fun aSwitchedOnLayerWithAnUnreadablePrjIsAlsoAWarning() {
        // .prj present but not text we can interpret - the other failure mode
        val r = ProjectScan.scan(
            probes(Triple("points", "binary junk", true)),
            setOf("points"),
        )
        assertEquals(1, r.warnings().size, "warning count")
        assertTrue(r.warnings()[0].contains("does not recognise"), "unreadable prj")
    }

    @Test
    fun aSwitchedOffLayerIsNotExaminedAndStaysQuiet() {
        val r = ProjectScan.scan(
            probes(Triple("points", null, true)),
            setOf("roads"), // points is not enabled
        )
        assertTrue(r.warnings().isEmpty(), "an off layer should not raise a dialog")
        assertEquals(listOf("points"), r.skipped, "skipped list")
    }

    @Test
    fun aLayerAbsentFromLayerSettingsIsNotExamined() {
        val r = ProjectScan.scan(
            probes(Triple("stray", null, false)),
            setOf("stray"),
        )
        assertTrue(
            r.warnings().isEmpty(),
            "not in layer settings means we do not claim to know its state",
        )
    }

    @Test
    fun aMissingPrjDoesNotDisturbTheWorkingZone() {
        // the degrees layer that lost its prj must not drag the project zone with it
        val r = ProjectScan.scan(
            probes(
                Triple("roads", prjUtm, true),
                Triple("access", prjUtm, true),
                Triple("points", null, true),
                Triple("tracks", prjDeg, true),
            ),
            setOf("roads", "access", "points", "tracks"),
        )
        assertEquals(10, r.workingZone, "the projected majority decides the zone")
        assertEquals(1, r.warnings().size, "warning count")
    }

    @Test
    fun theWarningNamesTheActionableFix() {
        val r = ProjectScan.scan(probes(Triple("points", null, true)), setOf("points"))
        val m = r.warnings()[0]
        assertTrue(m.contains("turn the layer off"), "must say what to do: $m")
    }

    @Test
    fun everyWarningNamesItsOwnLayerWhenThereAreSeveral() {
        val r = ProjectScan.scan(
            probes(
                Triple("points", null, true),
                Triple("xgv_mp", null, true),
            ),
            setOf("points", "xgv_mp"),
        )
        assertEquals(2, r.warnings().size, "warning count")
        assertTrue(r.messages().any { it.startsWith("points") })
        assertTrue(r.messages().any { it.startsWith("xgv_mp") })
    }
}