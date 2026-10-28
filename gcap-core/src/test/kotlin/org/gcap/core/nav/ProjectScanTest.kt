package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The survey folder is mixed: UTM metres for most layers, WGS84 degrees for the point
 * collector app's files, and the odd layer with no .prj at all. This decides the project's
 * working zone once, and reports only what the crew is actually relying on.
 */
class ProjectScanTest {

    private val utm10 = "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
        "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"]," +
        "PARAMETER[\"Central_Meridian\",-123.0],UNIT[\"Meter\",1.0]]"
    private val utm11 = utm10.replace("Zone_10N", "Zone_11N").replace("-123.0", "-117.0")
    private val degrees = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\"," +
        "SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],UNIT[\"Degree\",0.0174532925199433]]"

    private fun probes(vararg p: ProjectScan.LayerProbe) = p.toList()

    // ---- deciding the working zone ------------------------------------------

    @Test
    fun theZoneUsedByMostLayersWins() {
        val r = ProjectScan.scan(
            probes(
                ProjectScan.LayerProbe("ground_call", utm10),
                ProjectScan.LayerProbe("xgv_mp", utm10),
                ProjectScan.LayerProbe("roads", utm10),
                ProjectScan.LayerProbe("stray", utm11),
            ),
            enabled = setOf("ground_call", "xgv_mp", "roads", "stray"),
        )
        assertEquals(10, r.workingZone)
    }

    @Test
    fun aLoneLayerFromAnotherZoneCannotDragTheProject() {
        val r = ProjectScan.scan(
            probes(
                ProjectScan.LayerProbe("a", utm10),
                ProjectScan.LayerProbe("b", utm10),
                ProjectScan.LayerProbe("c", utm10),
                ProjectScan.LayerProbe("odd", utm11),
            ),
            enabled = setOf("a", "b", "c", "odd"),
        )
        assertEquals(10, r.workingZone)
        assertTrue(r.findings.any { it.stem == "odd" && it.kind == ProjectScan.Kind.ZONE_MISMATCH })
    }

    @Test
    fun aProjectWithNoUsablePrjFallsBackToTheAssumedZone() {
        val r = ProjectScan.scan(probes(ProjectScan.LayerProbe("x", null)), enabled = setOf("x"))
        assertEquals(ProjectScan.UtmZoneFallback, r.workingZone)
    }

    // ---- switched-off layers are not our problem today ----------------------

    @Test
    fun layersThatAreOffAreSkipped() {
        val r = ProjectScan.scan(
            probes(
                ProjectScan.LayerProbe("ground_call", utm10),
                ProjectScan.LayerProbe("broken", null),
            ),
            enabled = setOf("ground_call"),
        )
        assertEquals(listOf("ground_call"), r.scanned)
        assertEquals(listOf("broken"), r.skipped)
        assertTrue(r.clean, "a broken layer that is off must not be reported")
    }

    @Test
    fun aLayerAbsentFromTheLayerSheetIsNotScanned() {
        val r = ProjectScan.scan(
            probes(ProjectScan.LayerProbe("ground_call", utm10, inLayerSettings = false)),
            enabled = setOf("ground_call"),
        )
        assertTrue(r.scanned.isEmpty())
        assertTrue(r.clean)
    }

    @Test
    fun switchedOffMatchingIgnoresCase() {
        val r = ProjectScan.scan(
            probes(ProjectScan.LayerProbe("Ground_Call", utm10)),
            enabled = setOf("ground_call"),
        )
        assertEquals(listOf("Ground_Call"), r.scanned)
    }

    // ---- what gets reported -------------------------------------------------

    @Test
    fun aSwitchedOnLayerWithNoPrjIsReported() {
        val r = ProjectScan.scan(
            probes(
                ProjectScan.LayerProbe("ground_call", utm10),
                ProjectScan.LayerProbe("mystery", null),
            ),
            enabled = setOf("ground_call", "mystery"),
        )
        val f = r.findings.single()
        assertEquals("mystery", f.stem)
        assertEquals(ProjectScan.Kind.NO_CRS, f.kind)
        assertTrue(f.message().contains("no .prj"))
    }

    @Test
    fun aSwitchedOnLayerWithAnUnreadablePrjIsReported() {
        val r = ProjectScan.scan(
            probes(
                ProjectScan.LayerProbe("ground_call", utm10),
                ProjectScan.LayerProbe("weird", "GARBAGE"),
            ),
            enabled = setOf("ground_call", "weird"),
        )
        assertTrue(r.findings.any { it.stem == "weird" })
        assertTrue(r.messages().any { it.contains("weird") })
    }

    @Test
    fun degreesInAProjectedFolderIsANoteNotAWarning() {
        // points.shp is deliberately in degrees; that is not a fault
        val r = ProjectScan.scan(
            probes(
                ProjectScan.LayerProbe("ground_call", utm10),
                ProjectScan.LayerProbe("points", degrees),
            ),
            enabled = setOf("ground_call", "points"),
        )
        val f = r.findings.single()
        assertEquals(ProjectScan.Kind.GEOGRAPHIC_IN_PROJECTED, f.kind)
        assertEquals("note", f.severity())
        assertEquals(10, r.workingZone)
    }

    @Test
    fun aConsistentProjectIsSilent() {
        val r = ProjectScan.scan(
            probes(
                ProjectScan.LayerProbe("ground_call", utm10),
                ProjectScan.LayerProbe("xgv_mp", utm10),
            ),
            enabled = setOf("ground_call", "xgv_mp"),
        )
        assertTrue(r.clean)
        assertTrue(r.messages().isEmpty())
    }

    // ---- the real survey folder ---------------------------------------------

    @Test
    fun classifiesTheRealPrjTexts() {
        assertEquals(ProjectScan.CrsKind.UTM, ProjectScan.classifyCrs(utm10).kind)
        assertEquals(10, ProjectScan.classifyCrs(utm10).zone)
        assertEquals(11, ProjectScan.classifyCrs(utm11).zone)
        assertEquals(ProjectScan.CrsKind.GEOGRAPHIC, ProjectScan.classifyCrs(degrees).kind)
        assertEquals(ProjectScan.CrsKind.NONE, ProjectScan.classifyCrs(null).kind)
    }

    @Test
    fun aProjectedPrjIsNotMistakenForGeographic() {
        // UTM WKT embeds GEOGCS and UNIT["Degree"], which a naive check calls geographic
        assertFalse(ProjectScan.classifyCrs(utm10).kind == ProjectScan.CrsKind.GEOGRAPHIC)
    }
}
/**
 * A folder that deliberately keeps layers in degrees must not be interrupted every time
 * the map is opened - that is a choice already made, not news.
 */
class ScanInterruptTest {

    private val utm10 = "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
        "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"],UNIT[\"Meter\",1.0]]"
    private val degrees = "GEOGCS[\"GCS_WGS_1984\",UNIT[\"Degree\",0.0174532925199433]]"

    @Test
    fun degreesAloneRaiseNoInterruption() {
        val r = ProjectScan.scan(
            listOf(
                ProjectScan.LayerProbe("ground_call", utm10),
                ProjectScan.LayerProbe("points", degrees),
                ProjectScan.LayerProbe("tracks", degrees),
            ),
            setOf("ground_call", "points", "tracks"),
        )
        assertTrue(r.warnings().isEmpty(), "a deliberate degrees layer must not nag")
        assertTrue(r.messages().isNotEmpty(), "but it is still recorded")
    }

    @Test
    fun aMissingPrjDoesRaiseAnInterruption() {
        val r = ProjectScan.scan(
            listOf(
                ProjectScan.LayerProbe("ground_call", utm10),
                ProjectScan.LayerProbe("mystery", null),
            ),
            setOf("ground_call", "mystery"),
        )
        assertEquals(1, r.warnings().size)
        assertTrue(r.warnings().single().contains("mystery"))
    }

    @Test
    fun aZoneMismatchIsAlsoNotWorthInterruptingFor() {
        val utm11 = utm10.replace("Zone_10N", "Zone_11N").replace("-123", "-117")
        val r = ProjectScan.scan(
            listOf(
                ProjectScan.LayerProbe("a", utm10),
                ProjectScan.LayerProbe("odd", utm11),
            ),
            setOf("a", "odd"),
        )
        assertTrue(r.warnings().isEmpty())
        assertTrue(r.messages().any { it.contains("odd") })
    }
}

/**
 * `enabled` is honoured strictly: a layer the user has switched off is not the app's
 * problem. This matters because a caller with no layer_settings.ods passes the whole
 * folder as enabled, and a caller with one passes only the visible rows.
 */
class ScanEnabledHonouringTest {

    private val utm10 = "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\"," +
        "UNIT[\"Degree\",0.017]],PROJECTION[\"Transverse_Mercator\"],UNIT[\"Meter\",1.0]]"

    @Test
    fun aLayerSwitchedOffIsNeverExamined() {
        val r = ProjectScan.scan(
            listOf(ProjectScan.LayerProbe("good", utm10), ProjectScan.LayerProbe("off", null)),
            setOf("good"),
        )
        assertTrue(r.clean)
        assertTrue(r.messages().none { it.contains("off") })
    }

    @Test
    fun aLayerNotInTheSettingsAtAllIsIgnored() {
        val r = ProjectScan.scan(
            listOf(ProjectScan.LayerProbe("good", utm10), ProjectScan.LayerProbe("stray", null, inLayerSettings = false)),
            setOf("good"),
        )
        assertTrue(r.clean)
    }

    @Test
    fun aProjectedProjectSaysSo() {
        val r = ProjectScan.scan(listOf(ProjectScan.LayerProbe("access", utm10)), setOf("access"))
        assertTrue(r.projectProjected, "a UTM layer means the zone came from the data")
    }

    @Test
    fun anAllDegreesProjectReportsThatItsZoneIsOnlyAssumed() {
        // The scan has no coordinates to work from, so its zone here is the fallback and
        // tells the app nothing. It must say so, or the app converts every layer through
        // a zone guessed from nothing.
        val deg = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",UNIT[\"Degree\",0.0174532925199433]]"
        val r = ProjectScan.scan(
            listOf(ProjectScan.LayerProbe("calls", deg), ProjectScan.LayerProbe("path", deg)),
            setOf("calls", "path"),
        )
        assertFalse(r.projectProjected, "nothing is projected, so the zone is a guess")
    }
}
