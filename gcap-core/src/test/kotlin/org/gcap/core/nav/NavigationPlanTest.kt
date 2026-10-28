package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The point of this: Navigate must never silently pick a destination the crew has
 * forgotten. One real symptom was a "Navigating on ACCESS" with no idea where to, which
 * would have led someone to a waypoint chosen the day before.
 */
class NavigationPlanTest {

    private class Clock(var t: Long = 1_000_000L) { fun plus(sec: Long) { t += sec * 1000L } }
    private fun plan(c: Clock) = NavigationPlan(freshSeconds = 120, now = { c.t })

    private fun dest(
        c: Clock, label: String = "P1", origin: NavigationPlan.Destination.Origin =
            NavigationPlan.Destination.Origin.WAYPOINT,
    ) = NavigationPlan.Destination(label, 500_000.0, 5_000_000.0, c.t, origin)

    // ---- a fresh choice is honoured ----------------------------------------

    @Test
    fun aWaypointJustChosenIsTheDefaultDestination() {
        val c = Clock(); val p = plan(c)
        p.setDestination("waypoint 3", 1.0, 2.0, NavigationPlan.Destination.Origin.WAYPOINT)
        val r = p.resolve()
        assertTrue(r is NavigationPlan.Resolution.Ready)
        assertEquals("waypoint 3", (r as NavigationPlan.Resolution.Ready).destination.label)
    }

    @Test
    fun aGoToChoiceIsAlsoTheDefaultDestination() {
        val c = Clock(); val p = plan(c)
        p.setDestination("call 12", 1.0, 2.0, NavigationPlan.Destination.Origin.GO_TO)
        assertTrue(p.resolve() is NavigationPlan.Resolution.Ready)
    }

    @Test
    fun aHeldMapPointIsTheDefaultDestination() {
        val c = Clock(); val p = plan(c)
        p.setDestination("here", 1.0, 2.0, NavigationPlan.Destination.Origin.LONG_PRESS)
        assertTrue(p.resolve() is NavigationPlan.Resolution.Ready)
    }

    // ---- staleness ----------------------------------------------------------

    @Test
    fun justInsideTheWindowIsStillUsed() {
        val c = Clock(); val p = plan(c)
        p.setDestination("P1", 1.0, 2.0, NavigationPlan.Destination.Origin.WAYPOINT)
        c.plus(119)
        assertTrue(p.resolve() is NavigationPlan.Resolution.Ready)
    }

    @Test
    fun justOutsideTheWindowIsRefusedRatherThanReused() {
        val c = Clock(); val p = plan(c)
        p.setDestination("P1", 1.0, 2.0, NavigationPlan.Destination.Origin.WAYPOINT)
        c.plus(121)
        val r = p.resolve()
        assertTrue(r is NavigationPlan.Resolution.NeedsDestination)
        assertEquals(121L, (r as NavigationPlan.Resolution.NeedsDestination).staleAgeSec)
    }

    @Test
    fun aWaypointFromYesterdayIsNeverSilentlyReused() {
        val c = Clock(); val p = plan(c)
        p.setDestination("yesterdays call", 1.0, 2.0, NavigationPlan.Destination.Origin.WAYPOINT)
        c.plus(86_400)
        assertTrue(p.resolve() is NavigationPlan.Resolution.NeedsDestination)
    }

    @Test
    fun pressingNavigateAgainCannotReviveAStaleChoice() {
        val c = Clock(); val p = plan(c)
        p.setDestination("P1", 1.0, 2.0, NavigationPlan.Destination.Origin.WAYPOINT)
        c.plus(600)
        repeat(5) { assertTrue(p.resolve() is NavigationPlan.Resolution.NeedsDestination) }
        // age is measured from when it was chosen, not from the last look
        assertEquals(600L, p.ageSeconds())
    }

    @Test
    fun ageIsCountedFromWhenItWasChosen() {
        val c = Clock(); val p = plan(c)
        p.setDestination("P1", 1.0, 2.0, NavigationPlan.Destination.Origin.WAYPOINT)
        c.plus(30)
        assertEquals(30L, p.ageSeconds())
    }

    // ---- nothing chosen -----------------------------------------------------

    @Test
    fun withNoDestinationAtAllTheAppMustAsk() {
        val c = Clock(); val p = plan(c)
        val r = p.resolve()
        assertTrue(r is NavigationPlan.Resolution.NeedsDestination)
        assertEquals(null, (r as NavigationPlan.Resolution.NeedsDestination).staleAgeSec)
    }

    // ---- a new choice replaces an old route --------------------------------

    @Test
    fun choosingOneDestinationReplacesTheRoute() {
        val c = Clock(); val p = plan(c)
        p.setRoute(listOf(dest(c, "A"), dest(c, "B"), dest(c, "C")))
        assertTrue(p.isRoute)
        p.setDestination("P9", 1.0, 2.0, NavigationPlan.Destination.Origin.WAYPOINT)
        assertFalse(p.isRoute)
        assertTrue(p.route.isEmpty())
    }

    // ---- the three axes are independent ------------------------------------

    @Test
    fun groundOrAirIsIndependentOfDirectOrNetwork() {
        val p = NavigationPlan()
        p.useMedium(NavigationPlan.Medium.AIR)
        p.useGuidance(NavigationPlan.Guidance.DIRECT_LINE)
        assertEquals(NavigationPlan.Medium.AIR, p.medium)
        assertEquals(NavigationPlan.Guidance.DIRECT_LINE, p.guidance)
        p.useMedium(NavigationPlan.Medium.GROUND)
        assertEquals(NavigationPlan.Guidance.DIRECT_LINE, p.guidance)
    }

    @Test
    fun theDefaultsAreGroundAndNetwork() {
        val p = NavigationPlan()
        assertEquals(NavigationPlan.Medium.GROUND, p.medium)
        assertEquals(NavigationPlan.Guidance.NETWORK, p.guidance)
    }
}