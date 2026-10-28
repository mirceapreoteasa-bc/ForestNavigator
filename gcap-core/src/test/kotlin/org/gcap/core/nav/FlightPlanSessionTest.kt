package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlightPlanSessionTest {

    /** Two triples laid out in a line, transect then transit then transect. */
    private fun plan(): FlightPlan = FlightPlan.build(
        listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001B", 150.0, 0.0),
            AirCall("002A", 2000.0, 0.0),
            AirCall("002B", 2150.0, 0.0),
        ),
    )

    @Test
    fun `starts idle`() {
        val s = FlightPlanSession(plan())
        assertEquals(PlanPhase.IDLE, s.phase)
        assertEquals(null, s.target)
    }

    @Test
    fun `choosing a call starts a direct line approach`() {
        val s = FlightPlanSession(plan())
        val p = s.startAt("002A")
        assertEquals(PlanPhase.APPROACHING, p.phase)
        assertTrue(p.directLine, "the approach is a direct line, not a plan leg")
        assertEquals("002A", p.targetName)
        assertEquals("002A", s.startCall?.name)
    }

    @Test
    fun `an unknown call is refused rather than starting from the beginning`() {
        val s = FlightPlanSession(plan())
        val p = s.startAt("999Z")
        assertEquals(PlanPhase.IDLE, p.phase)
        assertTrue(p.message.contains("999Z"), p.message)
        assertEquals(null, s.target)
    }

    @Test
    fun `the approach keeps the same target until it is reached`() {
        val s = FlightPlanSession(plan())
        s.startAt("002A")
        val far = s.update(0.0, 0.0)
        assertEquals("002A", far.targetName)
        val closer = s.update(1900.0, 0.0)
        assertEquals("002A", closer.targetName, "still short of it")
        assertEquals(PlanPhase.APPROACHING, closer.phase)
    }

    @Test
    fun `reaching the chosen call hands over to the plan`() {
        val s = FlightPlanSession(plan())
        s.startAt("002A")
        val p = s.update(2000.0, 0.0)
        assertEquals(PlanPhase.ON_PLAN, p.phase)
        assertTrue(!p.directLine)
        // 002A is the start of a transect, so the next target is 002B
        assertEquals("002B", p.targetName)
    }

    @Test
    fun `the plan then runs to the end`() {
        val s = FlightPlanSession(plan())
        s.startAt("002A")
        assertEquals("002B", s.update(2000.0, 0.0).targetName)
        val done = s.update(2150.0, 0.0)
        assertEquals(PlanPhase.COMPLETE, done.phase)
        assertEquals(null, done.targetName)
        assertEquals(0, done.callsRemaining)
    }

    @Test
    fun `starting from the first call runs the whole plan`() {
        val s = FlightPlanSession(plan())
        s.startAt("001A")
        assertEquals("001A", s.update(-60.0, 0.0).targetName, "still 60 m short, so approaching")
        assertEquals("001B", s.update(0.0, 0.0).targetName)
        assertEquals("002A", s.update(150.0, 0.0).targetName)
        assertEquals("002B", s.update(2000.0, 0.0).targetName)
        assertEquals(PlanPhase.COMPLETE, s.update(2150.0, 0.0).phase)
    }

    @Test
    fun `choosing the far end of a leg picks up that leg`() {
        // start at 001B, which is the *end* of the first leg
        val s = FlightPlanSession(plan())
        s.startAt("001B")
        val p = s.update(150.0, 0.0)
        assertEquals(PlanPhase.ON_PLAN, p.phase)
        assertEquals("002A", p.targetName, "continue with the leg out of 001B")
    }

    @Test
    fun `a leg is only advanced once within tolerance`() {
        val s = FlightPlanSession(plan(), reachToleranceM = 50.0)
        s.startAt("001A")
        s.update(0.0, 0.0)
        assertEquals("001B", s.target?.name)
        // 60 m short of a 50 m tolerance: still the same target
        s.update(150.0, 60.0)
        assertEquals("001B", s.target?.name)
        // within 50 m: advance
        s.update(150.0, 40.0)
        assertEquals("002A", s.target?.name)
    }

    @Test
    fun `tolerance is respected in both directions`() {
        val s = FlightPlanSession(plan(), reachToleranceM = 50.0)
        s.startAt("001A")
        s.update(0.0, 0.0)
        s.update(150.0, 60.0)
        assertEquals("001B", s.target?.name, "60 m is outside a 50 m tolerance")
        s.update(150.0, 49.0)
        assertEquals("002A", s.target?.name, "49 m is inside it")
    }

    @Test
    fun `distance to the target is reported`() {
        val s = FlightPlanSession(plan())
        s.startAt("002A")
        val p = s.progressAt(2000.0, 300.0)
        assertEquals(300.0, p.distanceM, 0.01)
    }

    @Test
    fun `update reports the distance to the current target`() {
        val s = FlightPlanSession(plan())
        s.startAt("002A")
        // 300 m short while still approaching
        assertEquals(300.0, s.update(2000.0, 300.0).distanceM, 0.01)
    }

    @Test
    fun `update reports a distance after handing over to the plan`() {
        val s = FlightPlanSession(plan())
        s.startAt("002A")
        val onPlan = s.update(2000.0, 0.0)
        assertEquals(PlanPhase.ON_PLAN, onPlan.phase)
        assertTrue(onPlan.distanceM.isFinite(), "distance was ${onPlan.distanceM}")
        assertEquals(150.0, onPlan.distanceM, 0.01, "002B is 150 m from 002A")
    }

    @Test
    fun `the distance is never NaN once there is a target`() {
        val s = FlightPlanSession(plan())
        s.startAt("001A")
        var p = s.update(-500.0, 0.0)
        assertTrue(p.distanceM.isFinite(), "approaching: ${p.distanceM}")
        p = s.update(0.0, 0.0)
        assertTrue(p.distanceM.isFinite(), "on plan: ${p.distanceM}")
        p = s.update(150.0, 0.0)
        assertTrue(p.distanceM.isFinite(), "on plan: ${p.distanceM}")
    }

    @Test
    fun `calls remaining counts the legs left`() {
        val s = FlightPlanSession(plan())
        val p = s.startAt("001A")
        assertEquals(3, p.callsRemaining, "001B, 002A, 002B")
        // entering the plan at 001A still leaves all three to reach
        assertEquals(3, s.update(0.0, 0.0).callsRemaining)
        // reaching 001B leaves two
        assertEquals(2, s.update(150.0, 0.0).callsRemaining)
    }

    @Test
    fun `stopping returns to idle and forgets the start`() {
        val s = FlightPlanSession(plan())
        s.startAt("001A")
        val p = s.stop()
        assertEquals(PlanPhase.IDLE, p.phase)
        assertEquals(null, s.startCall)
        assertEquals(null, s.target)
    }

    @Test
    fun `updating while idle does nothing harmful`() {
        val s = FlightPlanSession(plan())
        val p = s.update(10.0, 10.0)
        assertEquals(PlanPhase.IDLE, p.phase)
        assertEquals(null, p.targetName)
    }

    @Test
    fun `starting again from a different call restarts the approach`() {
        val s = FlightPlanSession(plan())
        s.startAt("001A")
        s.update(0.0, 0.0)
        val again = s.startAt("002B")
        assertEquals(PlanPhase.APPROACHING, again.phase)
        assertEquals("002B", again.targetName)
    }

    @Test
    fun `the messages say which kind of leg is next`() {
        val s = FlightPlanSession(plan())
        s.startAt("001A")
        val onTransect = s.update(0.0, 0.0)
        assertTrue(onTransect.message.contains("transect"), onTransect.message)
        val onTransit = s.update(150.0, 0.0)
        assertTrue(onTransit.message.contains("transit"), onTransit.message)
    }

    @Test
    fun `a call name is matched regardless of case`() {
        val s = FlightPlanSession(plan())
        assertEquals(PlanPhase.APPROACHING, s.startAt("002a").phase)
    }
}