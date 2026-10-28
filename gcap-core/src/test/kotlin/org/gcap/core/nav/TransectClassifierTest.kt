package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransectClassifierTest {

    /** A straight east-running feature of [len] m starting at the origin. */
    private fun seg(len: Double, y: Double = 0.0) = doubleArrayOf(0.0, y, len, y)

    private fun callsAB(number: Int, len: Double) = listOf(
        AirCall("$number", len / 2.0, 0.0),
        AirCall("${number}A", 5.0, 0.0),
        AirCall("${number}B", len - 5.0, 0.0),
    )

    @Test
    fun `a bracketed straight feature is a transect`() {
        val c = TransectClassifier(callsAB(1, 180.0))
        assertTrue(c.isTransect(seg(180.0)))
    }

    @Test
    fun `without the bare call it is not a transect`() {
        val c = TransectClassifier(listOf(AirCall("1A", 5.0, 0.0), AirCall("1B", 175.0, 0.0)))
        assertFalse(c.isTransect(seg(180.0)))
    }

    @Test
    fun `a bare call away from the middle disqualifies it`() {
        val c = TransectClassifier(
            listOf(AirCall("1", 40.0, 0.0), AirCall("1A", 5.0, 0.0), AirCall("1B", 175.0, 0.0)),
        )
        assertFalse(c.isTransect(seg(180.0)), "the bare call must be near the middle")
    }

    @Test
    fun `a feature with no calls on its ends is not a transect`() {
        val c = TransectClassifier(listOf(AirCall("1", 90.0, 0.0)))
        assertFalse(c.isTransect(seg(180.0)))
    }

    @Test
    fun `a much longer feature is not a transect even with calls on it`() {
        val c = TransectClassifier(
            listOf(AirCall("1", 400.0, 0.0), AirCall("1A", 10.0, 0.0), AirCall("1B", 790.0, 0.0)),
        )
        assertFalse(c.isTransect(seg(800.0)), "beyond the length band it is a transit, not a transect")
    }

    @Test
    fun `a very short feature is not a transect`() {
        val c = TransectClassifier(callsAB(1, 30.0))
        assertFalse(c.isTransect(seg(30.0)), "connectors are not transects")
    }

    @Test
    fun `each call number is claimed by one feature`() {
        val feats = listOf(seg(180.0), seg(180.0, y = 500.0), seg(180.0, y = 900.0))
        val calls = listOf(
            AirCall("1", 90.0, 0.0), AirCall("1A", 5.0, 0.0), AirCall("1B", 175.0, 0.0),
            AirCall("2", 90.0, 500.0), AirCall("2A", 5.0, 500.0), AirCall("2B", 175.0, 500.0),
            AirCall("3", 90.0, 900.0), AirCall("3A", 5.0, 900.0), AirCall("3B", 175.0, 900.0),
        )
        val c = TransectClassifier(calls)
        assertEquals(3, feats.count { c.isTransect(it) })
        assertEquals(listOf(1, 2, 3), c.transectNumbers(feats).sorted())
    }
}
