package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixGuardTest {

    @Test
    fun firstFixIsAccepted() {
        val g = FixGuard()
        assertTrue(g.accept(1000L))
        assertTrue(g.hasFix)
    }

    @Test
    fun newerFixesAreAcceptedInOrder() {
        val g = FixGuard()
        assertTrue(g.accept(1000L))
        assertTrue(g.accept(2000L))
        assertTrue(g.accept(3000L))
        assertEquals(0, g.staleRejected)
    }

    @Test
    fun aFixOlderThanTheNewestIsRefused() {
        val g = FixGuard()
        assertTrue(g.accept(5000L))
        assertFalse("an older fix must not take over", g.accept(2000L))
        assertEquals(1, g.staleRejected)
    }

    @Test
    fun aFixStampedTheSameIsRefusedAsAReplay() {
        val g = FixGuard()
        assertTrue(g.accept(1000L))
        assertFalse("identical timestamps are a replay", g.accept(1000L))
    }

    @Test
    fun theObservedFailureIsPrevented() {
        // a fresh fix arrives, then a cached one from before it arrives late
        val g = FixGuard()
        assertTrue("fresh fix accepted", g.accept(9000L))
        assertFalse("the late cached fix must not win", g.accept(2000L))
        assertTrue("the next genuinely new fix is still fine", g.accept(9500L))
    }

    @Test
    fun aBackwardsJumpDoesNotSpoilLaterFixes() {
        val g = FixGuard()
        g.accept(1000L)
        g.accept(500L)
        assertTrue(g.accept(1500L))
    }

    @Test
    fun resetAllowsAFreshStart() {
        val g = FixGuard()
        g.accept(10000L)
        assertFalse(g.accept(1000L))
        g.reset()
        assertTrue("after a reset an old timestamp is acceptable again", g.accept(1000L))
        assertEquals(0, g.staleRejected)
    }

    @Test
    fun aGuardThatHasSeenNothingRefusesNothing() {
        val g = FixGuard()
        assertFalse(g.hasFix)
        assertTrue(g.accept(Long.MIN_VALUE + 1))
    }
}