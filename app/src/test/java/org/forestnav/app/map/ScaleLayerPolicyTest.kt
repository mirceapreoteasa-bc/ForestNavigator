package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleLayerPolicyTest {

    @Test
    fun onlyTheSkeletonLoadsWhenFullyZoomedOut() {
        val at400k = ScaleLayerPolicy.layersAt(400_000)!!
        assertTrue(at400k.contains("access"))
        assertTrue(at400k.contains("ground_call"))
        assertTrue(at400k.contains("xgv_mp"))
        assertEquals(3, at400k.size)
    }

    @Test
    fun theLadderAddsDetailAsYouZoomIn() {
        assertFalse(ScaleLayerPolicy.loads("roads", 400_000))
        assertTrue(ScaleLayerPolicy.loads("roads", 200_000))
        assertFalse(ScaleLayerPolicy.loads("flight_path", 200_000))
        assertTrue(ScaleLayerPolicy.loads("flight_path", 100_000))
        assertTrue("close in, everything loads", ScaleLayerPolicy.loads("prov_parks", 50_000))
    }

    @Test
    fun boundariesAreInclusive() {
        assertEquals(ScaleLayerPolicy.WIDEST, ScaleLayerPolicy.layersAt(400_000))
        assertEquals(ScaleLayerPolicy.WIDEST, ScaleLayerPolicy.layersAt(500_000))
        assertEquals(ScaleLayerPolicy.MID, ScaleLayerPolicy.layersAt(200_000))
        assertEquals(ScaleLayerPolicy.NEAR, ScaleLayerPolicy.layersAt(100_000))
        assertEquals(null, ScaleLayerPolicy.layersAt(99_999))
    }

    @Test
    fun theScaleOnScreenIsTheOneThatMatters() {
        // the blank map was at 1:400000, where only three layers load
        assertEquals(3, ScaleLayerPolicy.layersAt(400_000)!!.size)
    }

    @Test
    fun matchingIgnoresCaseAndTheExtension() {
        assertTrue(ScaleLayerPolicy.loads("Ground_Call.shp", 400_000))
        assertTrue(ScaleLayerPolicy.loads("ground_call", 400_000))
        assertTrue(ScaleLayerPolicy.loads("XGV_MP.shp", 400_000))
    }

    @Test
    fun everySurveyLayerIsCoveredByTheRungs() {
        val survey = listOf(
            "air_calls", "access", "Air_Calls_YR_4", "delineation", "fire", "flight_path",
            "grid", "ground_call", "lakes", "landings", "no_work", "points", "prov_parks",
            "results", "rivers", "roads", "tracks", "transect", "xgv_mp")
        // Every one of them is loaded at some scale. Checked across the range rather
        // than at one denominator, because a layer may be on the widening ladder or
        // in the close-in band, and a single-scale check would wrongly report the
        // banded layers as unreachable.
        val scales = listOf(500, 1_000, 5_000, 10_000, 50_000, 100_000, 200_000, 400_000)
        for (l in survey) {
            assertTrue(
                "$l never loads at any scale",
                scales.any { ScaleLayerPolicy.loads(l, it) },
            )
        }
        // and the rungs only ever name layers that exist in that survey
        val named = ScaleLayerPolicy.NEAR.map { it.lowercase() }
        for (l in named) assertTrue("$l is not in the survey folder", l in survey.map { it.lowercase() })
    }

    @Test
    fun droppedLayersAreReportedForTheUser() {
        val present = listOf("access.shp", "xgv_mp.shp", "prov_parks.shp", "fire.shp")
        val dropped = ScaleLayerPolicy.dropped(present, 400_000)
        assertEquals(2, dropped.size)
        assertTrue(dropped.contains("prov_parks.shp"))
        assertTrue(dropped.contains("fire.shp"))
    }

    @Test
    fun nothingIsReportedWhenNothingIsHeldBack() {
        assertTrue(ScaleLayerPolicy.dropped(listOf("access.shp", "roads.shp"), 50_000).isEmpty())
    }
}

/**
 * A MOB is an emergency record and must be on the map whatever the zoom.
 */
class EmergencyLayerTest {

    @Test
    fun theMobLayerIsLoadedAtEveryScale() {
        for (den in listOf(1_000_000, 400_000, 200_000, 100_000, 10_000)) {
            assertTrue("points must load at 1:$den",
                ScaleLayerPolicy.loadsOrEmergency("points.shp", den))
        }
    }

    @Test
    fun itIsRecognisedHoweverItIsSpelled() {
        assertTrue(ScaleLayerPolicy.isEmergency("points"))
        assertTrue(ScaleLayerPolicy.isEmergency("POINTS.shp"))
        assertTrue(ScaleLayerPolicy.isEmergency("Points"))
    }

    @Test
    fun theLadderStillGovernsEverythingElse() {
        assertFalse("roads is not an emergency layer", ScaleLayerPolicy.isEmergency("roads"))
        assertFalse(ScaleLayerPolicy.loadsOrEmergency("roads.shp", 400_000))
        assertTrue(ScaleLayerPolicy.loadsOrEmergency("roads.shp", 200_000))
    }

    @Test
    fun anEmergencyLayerIsNotInAnyRung() {
        for (rung in listOf(ScaleLayerPolicy.WIDEST, ScaleLayerPolicy.MID, ScaleLayerPolicy.NEAR)) {
            assertFalse("points should sit outside the ladder", rung.any { it == "points" })
        }
    }
}

/**
 * A folder carrying orthoimagery must say so rather than load looking empty: the
 * natural reading of a blank map is that the project is wrong, not the format.
 */
class RasterReportingTest {

    private val policy = ScaleLayerPolicy

    @Test
    fun commonRasterExtensionsAreRecognised() {
        for (f in listOf("ortho.tif", "ortho.TIF", "mosaic.vrt", "a.img", "b.jp2", "c.ecw", "d.sid")) {
            assertTrue("$f should be detected as raster", policy.isUnsupportedRaster(f))
        }
    }

    @Test
    fun vectorAndSidecarFilesAreNotMistakenForRasters() {
        for (f in listOf("access.shp", "access.dbf", "access.prj", "settings_1.ods", "notes.txt")) {
            assertFalse("$f is not a raster", policy.isUnsupportedRaster(f))
        }
    }

    @Test
    fun rastersAreReportedOnceByNameWithoutExtension() {
        val present = listOf("ortho.tif", "ortho.tif.aux.xml", "ortho.vrt", "access.shp", "elev.img")
        val rasters = policy.rastersPresent(present)
        assertEquals(2, rasters.size)
        assertTrue(rasters.containsAll(listOf("ortho", "elev")))
    }

    @Test
    fun aFolderWithNoRastersReportsNone() {
        assertTrue(policy.rastersPresent(listOf("access.shp", "roads.shp", "points.shp")).isEmpty())
    }

    @Test
    fun theMobLayerIsEmergencyAndAlwaysLoads() {
        assertTrue(policy.isEmergency("points.shp"))
        assertTrue("MOB must load even when zoomed out", policy.loadsOrEmergency("points.shp", 400_000))
        assertFalse(policy.isEmergency("access.shp"))
    }
}

/**
 * Block delineation is the heaviest layer in the folder and is only legible with the
 * boundary in view, so it is shown in a band rather than by the usual zoom ladder.
 */
class DelineationBandTest {

    @Test
    fun shownAcrossTheWorkingScales() {
        for (d in listOf(500, 1_000, 2_500, 5_000, 10_000)) {
            assertTrue("should show at 1:$d", ScaleLayerPolicy.loads("delineation", d))
        }
    }

    @Test
    fun hiddenWhenZoomedOutPastTheBand() {
        for (d in listOf(20_000, 50_000, 100_000, 200_000, 400_000, 1_000_000)) {
            assertFalse("should not show at 1:$d", ScaleLayerPolicy.loads("delineation", d))
        }
    }

    @Test
    fun hiddenWhenZoomedInBeyondTheBand() {
        for (d in listOf(499, 250, 100, 50)) {
            assertFalse("should not show at 1:$d", ScaleLayerPolicy.loads("delineation", d))
        }
    }

    @Test
    fun matchesWithOrWithoutExtensionAndAnyCase() {
        assertTrue(ScaleLayerPolicy.loads("Delineation.shp", 5_000))
        assertTrue(ScaleLayerPolicy.loads("DELINEATION", 10_000))
    }

    @Test
    fun theBandDoesNotAffectOtherLayers() {
        assertTrue("access is on the wide ladder", ScaleLayerPolicy.loads("access", 400_000))
        assertFalse("roads joins at 1:200,000", ScaleLayerPolicy.loads("roads", 400_000))
        assertTrue(ScaleLayerPolicy.loads("roads", 200_000))
    }

    @Test
    fun droppedReportsItWhenHeldBack() {
        // 1:200,000 is a rung where the ladder genuinely holds layers back, and the
        // band holds back delineation too.
        val dropped = ScaleLayerPolicy.dropped(listOf("delineation.shp", "access.shp"), 200_000)
        assertTrue("should be dropped when zoomed out", dropped.contains("delineation.shp"))
        assertFalse("access is always in", dropped.contains("access.shp"))
    }
}
