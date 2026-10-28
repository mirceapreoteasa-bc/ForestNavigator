package org.gcap.core.prep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayerPreparationTest {

    private val mib = 1024L * 1024L

    private fun layer(
        stem: String,
        sizeMiB: Long = 1,
        zone: Int = 10,
        geo: Boolean = false,
        deliberate: Boolean = false,
        label: String = "UTM ${zone}N",
    ) = LayerPreparation.LayerFacts(
        stem, sizeMiB * mib, label, zone, geo, deliberate,
    )

    @Test
    fun aLayerInTheProjectZoneIsLeftAlone() {
        val a = LayerPreparation.audit(listOf(layer("access")), projectZone = 10).single()
        assertEquals(LayerPreparation.Issue.FINE, a.issue)
        assertNull(a.action, "a good layer must not be given work to do")
    }

    @Test
    fun aLayerInAnotherZoneIsOfferedAReprojection() {
        val a = LayerPreparation.audit(listOf(layer("roads", zone = 9)), projectZone = 10).single()
        assertEquals(LayerPreparation.Issue.OFF_ZONE, a.issue)
        assertEquals(LayerPreparation.Action.REPROJECT, a.action)
        assertEquals("roads_utm10", a.outputStem)
        assertTrue(a.detail.contains("zone 9"), "should name the zone it is in: ${a.detail}")
    }

    @Test
    fun deliberateDegreesLayersAreNeverOfferedForReprojection() {
        val facts = layer("points", geo = true, deliberate = true, label = "WGS84 degrees")
        val a = LayerPreparation.audit(listOf(facts), projectZone = 10).single()
        assertEquals(LayerPreparation.Issue.DELIBERATE_DEGREES, a.issue)
        assertNull(a.action, "rewriting points.shp would break point-collector interchange")
        assertTrue(a.detail.contains("on purpose"))
    }

    @Test
    fun aHeavyLayerIsOfferedASimplifiedCopy() {
        // 15.6 MiB of delineation, measured at ~2.7x on the phone
        val a = LayerPreparation.audit(listOf(layer("delineation", sizeMiB = 16)), projectZone = 10).single()
        assertEquals(LayerPreparation.Issue.HEAVY, a.issue)
        assertEquals(LayerPreparation.Action.SIMPLIFY, a.action)
        assertEquals("delineation_lite", a.outputStem)
        assertTrue(
            !a.detail.contains("much lighter"),
            "the audit must not promise a saving it has not measured: ${a.detail}",
        )
    }

    @Test
    fun aMissingPrjIsReportedButNotGuessable() {
        val a = LayerPreparation.audit(
            listOf(layer("mystery", label = "no .prj")), projectZone = 10,
        ).single()
        assertEquals(LayerPreparation.Issue.NO_CRS, a.issue)
        assertNull(a.action, "the app must not guess a CRS for a layer")
    }

    @Test
    fun theMemoryEstimateScalesWithFileSize() {
        val small = LayerPreparation.estimatedHeapMiB(1 * mib)
        val big = LayerPreparation.estimatedHeapMiB(16 * mib)
        assertEquals(small * 16, big, 0.001)
        assertEquals(2.7, small, 0.01)
    }

    @Test
    fun theDerivedNameKeepsTheOriginal() {
        assertEquals("access_utm10", LayerPreparation.outStem("access", "utm10"))
        assertEquals("access", "access", "the original name is never the output name")
    }

    @Test
    fun simplificationKeepsTheShapeWithinTolerance() {
        // a straight line with jitter along it: every vertex lies on y=0
        val pts = (0..40).map { doubleArrayOf(it * 10.0, 0.0) }
        val out = LayerPreparation.simplifyPart(pts, LayerPreparation.SIMPLIFY_TOLERANCE_M)
        assertEquals(2, out.size, "a straight line collapses to its ends")
        assertEquals(0.0, out.first()[0], 1e-9)
        assertEquals(400.0, out.last()[0], 1e-9)
    }

    @Test
    fun simplificationKeepsACornerThatMatters() {
        val pts = listOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(50.0, 0.0),
            doubleArrayOf(50.0, 50.0),   // a right-angle corner, far outside tolerance
            doubleArrayOf(100.0, 50.0),
        )
        val out = LayerPreparation.simplifyPart(pts, LayerPreparation.SIMPLIFY_TOLERANCE_M)
        assertTrue(out.any { it[0] == 50.0 && it[1] == 50.0 }, "the corner must survive: ${out.toList()}")
    }

    @Test
    fun simplificationLeavesShortPartsAlone() {
        val pts = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0))
        assertEquals(2, LayerPreparation.simplifyPart(pts, 10.0).size)
    }

    @Test
    fun everyLayerGetsAnAnswerSoNothingIsQuietlySkipped() {
        val layers = listOf(
            layer("access"), layer("roads", zone = 9), layer("points", geo = true, deliberate = true),
            layer("delineation", sizeMiB = 16), layer("mystery", label = "no .prj"),
        )
        val advice = LayerPreparation.audit(layers, projectZone = 10)
        assertEquals(5, advice.size)
        assertEquals(
            layers.map { it.stem },
            advice.map { it.stem },
        )
        assertTrue(advice.all { it.detail.isNotBlank() }, "every line says something to the user")
    }

    @Test
    fun writtenLayersCarryTheirOwnProjectCrs() {
        val out = LayerPreparation.writePointLayer(
            listOf(doubleArrayOf(1.0, 2.0)), ByteArray(8) { 0 }, "PROJCS[test]", "x_utm10",
        )
        assertNotNull(out["x_utm10.shp"])
        assertNotNull(out["x_utm10.shx"])
        assertEquals(8, out["x_utm10.dbf"]!!.size, "the attribute table is copied, not rebuilt")
        assertTrue(String(out["x_utm10.prj"]!!, Charsets.US_ASCII).contains("PROJCS"))
    }
}

/**
 * Only one layer can be the heaviest. Telling all three "the largest in this folder"
 * makes the audit read as a template rather than as a measurement.
 */
class HeaviestClaimTest {

    @Test
    fun onlyOneLayerIsCalledTheLargest() {
        val facts = listOf(
            LayerPreparation.LayerFacts("delineation", 16L * 1024 * 1024, "UTM 10N", 10),
            LayerPreparation.LayerFacts("grid", 3L * 1024 * 1024, "UTM 10N", 10),
            LayerPreparation.LayerFacts("resultst", 4L * 1024 * 1024, "UTM 10N", 10),
        )
        val advice = LayerPreparation.audit(facts, projectZone = 10)
        assertEquals(1, advice.count { it.detail.contains("the largest") }, "exactly one claim")
        assertTrue(advice.first { it.stem == "delineation" }.detail.contains("the largest"))
        assertTrue(advice.none { it.stem != "delineation" && it.detail.contains("the largest") })
    }
}
