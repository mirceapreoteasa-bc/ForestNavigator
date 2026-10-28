package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlightPlanTest {

    private fun c(vararg s: Triple<String, Double, Double>) = s.map { AirCall(it.first, it.second, it.third) }

    @Test
    fun `the plan alternates transect and transit`() {
        val plan = FlightPlan.build(
            c(
                Triple("001A", 0.0, 0.0),
                Triple("001B", 150.0, 0.0),
                Triple("002A", 2000.0, 0.0),
                Triple("002B", 2150.0, 0.0),
            ),
        )
        assertEquals(
            listOf(PlanLeg.TRANSECT, PlanLeg.TRANSIT, PlanLeg.TRANSECT),
            plan.segments.map { it.leg },
        )
        assertEquals(2, plan.tripleCount)
        assertEquals(1, plan.transits.size)
    }

    @Test
    fun `bare calls are not legs of the plan`() {
        val plan = FlightPlan.build(
            c(
                Triple("001A", 0.0, 0.0),
                Triple("001", 75.0, 0.0),
                Triple("001B", 150.0, 0.0),
            ),
        )
        assertEquals(1, plan.segments.size)
        assertEquals("001A", plan.segments[0].from.name)
        assertEquals("001B", plan.segments[0].to.name)
    }

    @Test
    fun `transits only ever run from a B call to the next A call`() {
        val plan = FlightPlan.build(
            c(
                Triple("001A", 0.0, 0.0),
                Triple("001B", 100.0, 0.0),
                Triple("002A", 500.0, 0.0),
            ),
        )
        assertEquals(
            listOf("001A->001B", "001B->002A"),
            plan.segments.map { "${it.from.name}->${it.to.name}" },
        )
        assertEquals(PlanLeg.TRANSIT, plan.segments[1].leg)
    }

    @Test
    fun `a leg is never dropped for being hard to connect`() {
        // 002B to 003A is a long hop; the plan must still contain it
        val plan = FlightPlan.build(
            c(
                Triple("001A", 0.0, 0.0),
                Triple("001B", 150.0, 0.0),
                Triple("002A", 2000.0, 0.0),
                Triple("002B", 2150.0, 0.0),
                Triple("003A", 9000.0, 0.0),
                Triple("003B", 9150.0, 0.0),
            ),
        )
        val names = plan.segments.map { "${it.from.name}->${it.to.name}" }
        assertTrue("002B->003A" in names, "the hard transit must still be in the plan: $names")
        assertEquals(5, plan.segments.size)
    }

    @Test
    fun `legs are numbered from one so a gap can be pointed at`() {
        val plan = FlightPlan.build(
            c(
                Triple("001A", 0.0, 0.0),
                Triple("001B", 150.0, 0.0),
                Triple("002A", 2000.0, 0.0),
                Triple("002B", 2150.0, 0.0),
            ),
        )
        assertEquals(1, plan.transects[0].from.order)
        assertEquals(2, plan.transects[1].from.order)
    }

    @Test
    fun `with no flight path every leg is flagged as having no geometry`() {
        val plan = FlightPlan.build(
            c(Triple("001A", 0.0, 0.0), Triple("001B", 150.0, 0.0)),
        )
        assertFalse(plan.segments[0].followsPath)
        assertEquals(0, plan.transitsWithoutPath, "this fixture has no transit to count")
        assertEquals(1, plan.segments.count { !it.followsPath })
        assertTrue(plan.segments[0].describe().contains("no path geometry"))
    }

    @Test
    fun `a leg alongside the flight path is flagged as following it`() {
        val path = listOf(doubleArrayOf(0.0, 0.0, 150.0, 0.0))
        val plan = FlightPlan.build(
            c(Triple("001A", 0.0, 0.0), Triple("001B", 150.0, 0.0)),
            flightPath = path,
        )
        assertTrue(plan.segments[0].followsPath)
        assertTrue(plan.segments[0].worstGapM < 1.0)
        assertTrue(plan.segments[0].describe().contains("along path"))
    }

    @Test
    fun `a leg the path does not reach is measured, not assumed`() {
        // the transect runs off to one side, 400 m from the path
        val path = listOf(doubleArrayOf(0.0, 400.0, 150.0, 400.0))
        val plan = FlightPlan.build(
            c(Triple("001A", 0.0, 0.0), Triple("001B", 150.0, 0.0)),
            flightPath = path,
        )
        assertFalse(plan.segments[0].followsPath)
        assertTrue(plan.segments[0].worstGapM > 380.0, "gap was ${plan.segments[0].worstGapM}")
    }

    @Test
    fun `the worst leg can be pointed at`() {
        // both transects sit on the path; the transit between them goes where it is not
        val path = listOf(
            doubleArrayOf(0.0, 0.0, 150.0, 0.0),            // transect 001
            doubleArrayOf(5000.0, 4000.0, 5150.0, 4000.0),    // transect 002, well away
        )
        val plan = FlightPlan.build(
            c(
                Triple("001A", 0.0, 0.0),
                Triple("001B", 150.0, 0.0),
                Triple("002A", 5000.0, 4000.0),
                Triple("002B", 5150.0, 4000.0),
            ),
            flightPath = path,
        )
        assertTrue(plan.transects.all { it.followsPath }, "both transects are on the path")
        val worst = plan.worstLeg()!!
        assertEquals(PlanLeg.TRANSIT, worst.leg)
        assertEquals("001B", worst.from.name)
        assertTrue(worst.worstGapM > 1000.0, "gap was ${worst.worstGapM}")
        assertTrue(plan.summary().contains("001B->002A"), plan.summary())
    }

    @Test
    fun `a triple can be looked up by index for the next point in route`() {
        val plan = FlightPlan.build(
            c(
                Triple("001A", 0.0, 0.0),
                Triple("001B", 150.0, 0.0),
                Triple("002A", 2000.0, 0.0),
                Triple("002B", 2150.0, 0.0),
            ),
        )
        assertEquals("001A", plan.transectAt(0)?.from?.name)
        assertEquals("002A", plan.transectAt(1)?.from?.name)
        assertEquals(null, plan.transectAt(5))
    }

    @Test
    fun `summary states the counts a crew needs`() {
        val plan = FlightPlan.build(
            c(
                Triple("001A", 0.0, 0.0),
                Triple("001B", 150.0, 0.0),
                Triple("002A", 9000.0, 0.0),
                Triple("002B", 9150.0, 0.0),
            ),
        )
        val s = plan.summary()
        assertTrue(s.contains("2 transects"), s)
        assertTrue(s.contains("1 transits"), s)
    }

    @Test
    fun `an empty layer gives an empty plan rather than failing`() {
        val plan = FlightPlan.build(emptyList())
        assertEquals(0, plan.segments.size)
        assertEquals(0, plan.tripleCount)
        assertTrue(plan.worstLeg() == null)
    }

    @Test
    fun `shuffled calls are still planned in flying order`() {
        val plan = FlightPlan.build(
            c(
                Triple("002B", 2150.0, 0.0),
                Triple("001A", 0.0, 0.0),
                Triple("002A", 2000.0, 0.0),
                Triple("001B", 150.0, 0.0),
            ),
        )
        assertEquals(
            listOf("001A->001B", "001B->002A", "002A->002B"),
            plan.segments.map { "${it.from.name}->${it.to.name}" },
        )
    }

    @Test
    fun `gap measurement samples along the leg not just its ends`() {
        // path sits at the two ends but not in the middle
        val path = listOf(
            doubleArrayOf(0.0, 0.0, 10.0, 0.0),
            doubleArrayOf(140.0, 0.0, 150.0, 0.0),
        )
        val gap = FlightPlan.worstGapToPath(
            AirCall("001A", 0.0, 0.0), AirCall("001B", 150.0, 0.0), path, samples = 11,
        )
        assertTrue(gap > 50.0, "the middle of the leg has no path, gap was $gap")
    }

    @Test
    fun `transect limit is a class boundary not a hard cut`() {
        // a 300 m A->B leg is still a transect; the boundary is well above the real maximum
        assertTrue(300 < FlightPlan.TRANSECT_MAX_M)
        assertTrue(FlightPlan.TRANSECT_MAX_M < 1000.0)
    }
}