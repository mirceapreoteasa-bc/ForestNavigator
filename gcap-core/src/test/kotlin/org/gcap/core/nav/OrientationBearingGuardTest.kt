package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrientationBearingGuardTest {
    /**
     * A missing or broken heading must not reach the transform. Kotlin's % keeps NaN,
     * so an unguarded bearing turns every screen coordinate into NaN and the map draws
     * nothing at all.
     */
    @Test
    fun normaliseBearingTurnsNaNIntoNorth() {
        assertEquals(0.0, MapTransform.normaliseBearing(Double.NaN), 1e-9)
        assertTrue(MapTransform.normaliseBearing(Double.NaN).isFinite())
    }

    @Test
    fun normaliseBearingKeepsNormalValues() {
        assertEquals(359.0, MapTransform.normaliseBearing(-1.0), 1e-9)
        assertEquals(10.0, MapTransform.normaliseBearing(370.0), 1e-9)
        assertEquals(0.0, MapTransform.normaliseBearing(0.0), 1e-9)
    }

    /** A HeadingState claiming to be valid and moving but carrying NaN must not be used. */
    @Test
    fun courseUpFallsBackToNorthOnNaNHeading() {
        val c = OrientationController(OrientationMode.COURSE_UP)
        val hs = HeadingState(
            bearingDeg = Double.NaN, speedMps = 4.0, moving = true, valid = true,
        )
        val b = c.bearingFor(0L, hs, null, stillForMs = 0L)
        assertTrue(b.isFinite())
        assertEquals(0.0, b, 1e-9)
    }
}
