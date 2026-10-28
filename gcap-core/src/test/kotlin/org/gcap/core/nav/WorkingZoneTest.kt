package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The order the project's working zone is decided in, and what each answer claims to be. */
class WorkingZoneTest {

    @Test
    fun theAccessLayerDecidesTheProject() {
        val d = WorkingZone.decide(accessZone = 10, declaredZone = 11, geographicZone = 0, assumedZone = 10)
        assertEquals(10, d.zone)
        assertEquals(WorkingZone.Source.ACCESS, d.source)
    }

    @Test
    fun theAccessLayerBeatsAStrayLayerThatWouldFlipTheMajority() {
        // One layer claiming zone 11 must not move a project whose backbone says 10.
        val d = WorkingZone.decide(accessZone = 10, declaredZone = 11, geographicZone = 0, assumedZone = 10)
        assertEquals(10, d.zone)
        assertEquals(WorkingZone.Source.ACCESS, d.source)
    }

    @Test
    fun withoutAccessTheProjectsOwnDeclarationIsUsed() {
        val d = WorkingZone.decide(accessZone = null, declaredZone = 10, geographicZone = 0, assumedZone = 10)
        assertEquals(10, d.zone)
        assertEquals(WorkingZone.Source.DECLARED, d.source)
    }

    @Test
    fun aDegreesOnlyProjectIsReadFromItsCoordinatesNotAssumed() {
        val d = WorkingZone.decide(accessZone = null, declaredZone = null, geographicZone = 10, assumedZone = 10)
        assertEquals(10, d.zone)
        assertEquals(WorkingZone.Source.FROM_DATA, d.source)
    }

    @Test
    fun aDataZoneIsUsedEvenWhenItDiffersFromTheAssumption() {
        // the case that used to go wrong: data in zone 33, guess of 10
        val d = WorkingZone.decide(accessZone = null, declaredZone = null, geographicZone = 33, assumedZone = 10)
        assertEquals(33, d.zone)
        assertEquals(WorkingZone.Source.FROM_DATA, d.source)
    }

    @Test
    fun anUnusableZoneIsNotTrusted() {
        for (bad in listOf(0, -1, 61)) {
            assertEquals(
                10,
                WorkingZone.decide(accessZone = bad, declaredZone = 10, geographicZone = 0, assumedZone = 10).zone,
                "zone $bad must not be used as a zone",
            )
        }
    }

    @Test
    fun withNothingToGoOnItSaysItIsAssumingRatherThanClaimingAMeasurement() {
        val d = WorkingZone.decide(accessZone = null, declaredZone = null, geographicZone = 0, assumedZone = 10)
        assertEquals(10, d.zone)
        assertEquals(WorkingZone.Source.ASSUMED, d.source)
        assertEquals(true, d.describe().contains("assumed"))
    }

    @Test
    fun theDescriptionSaysWhichAccessLayerTheNumberCameFrom() {
        val d = WorkingZone.decide(accessZone = 10, declaredZone = 10, geographicZone = 0, assumedZone = 10)
        assertEquals("UTM 10, taken from the access layer", d.describe())
    }
}