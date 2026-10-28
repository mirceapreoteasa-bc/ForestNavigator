package org.gcap.core.nav

/** Where the crew is in the flight plan. */
enum class PlanPhase {
    /** Nothing chosen yet. */
    IDLE,

    /**
     * Flying straight at the chosen first air call.
     *
     * The crew may start anywhere - perhaps they were dropped somewhere else, or the
     * morning's first transect is not the first in the file - so the app goes directly to
     * that call rather than pretending the route began at `001A`.
     */
    APPROACHING,

    /** On the plan, working through it leg by leg. */
    ON_PLAN,

    /** The last leg has been reached. */
    COMPLETE,
}

/** What to fly to next, and how far it is. */
data class PlanProgress(
    val phase: PlanPhase,
    val targetName: String?,
    val targetE: Double,
    val targetN: Double,
    val distanceM: Double,
    /** True while flying straight at the start call rather than following the plan. */
    val directLine: Boolean,
    /** Air calls still to reach, counting the one being flown to. */
    val callsRemaining: Int,
    val message: String,
)

/**
 * Flying a flight plan: straight to a chosen first call, then along the plan.
 *
 * The approach is a **direct line** rather than part of the plan, because the crew picked
 * that call precisely so the app would go straight there. Once within
 * [reachToleranceM] of it, the plan takes over from that call onward.
 *
 * A leg is only advanced once the aircraft is actually within tolerance of its end point.
 * That matters more than it looks: the plan is followed by a helicopter that drifts, and
 * advancing on "the last one I passed" would skip calls on a long leg.
 */
class FlightPlanSession(
    val plan: FlightPlan,
    private val reachToleranceM: Double = 50.0,
) {
    var phase: PlanPhase = PlanPhase.IDLE
        private set

    /**
     * Index of the leg whose end point we are flying to.
     *
     * -1 before the plan is entered, so arriving at a call that *starts* a leg targets that
     * leg's far end rather than skipping past it.
     */
    private var legIndex = -1

    /** The call the crew chose to start from, kept for the readout. */
    var startCall: AirCall? = null
        private set

    /** The call currently being flown to. */
    var target: AirCall? = null
        private set

    /**
     * Start the plan, flying straight to [callName] first.
     *
     * Returns a progress of [PlanPhase.IDLE] with an explanatory message when the name is
     * not a call in the plan, rather than silently starting from the beginning.
     */
    fun startAt(callName: String): PlanProgress {
        val call = plan.segments.flatMap { listOf(it.from, it.to) }
            .firstOrNull { it.name.equals(callName, ignoreCase = true) }
        if (call == null) {
            return progress(
                PlanPhase.IDLE,
                "$callName is not in this flight plan.",
            )
        }
        startCall = call
        target = call
        phase = PlanPhase.APPROACHING
        legIndex = -1
        return progress(PlanPhase.APPROACHING, "Direct line to ${call.name}.")
    }

    /**
     * Move to the next target for the current position.
     *
     * Call this on every location fix. It reports the same target until that one is
     * reached, then steps on.
     */
    fun update(e: Double, n: Double): PlanProgress {
        if (phase == PlanPhase.IDLE || phase == PlanPhase.COMPLETE) {
            return progress(phase, if (phase == PlanPhase.IDLE) "Choose a call to start from." else "Plan complete.")
        }
        val t = target ?: return progress(PlanPhase.IDLE, "Choose a call to start from.")
        val d = Geodesy.distanceProjectedM(e, n, t.e, t.n)

        if (d > reachToleranceM) {
            val msg = when (phase) {
                PlanPhase.APPROACHING -> "Direct line to ${t.name}."
                else -> "On plan: ${t.name}."
            }
            // report the distance to the target the crew is actually flying to
            return progress(phase, msg).copy(distanceM = d)
        }

        // reached the target
        return when (phase) {
            PlanPhase.APPROACHING -> enterPlanAt(t, e, n)
            PlanPhase.ON_PLAN -> stepOn(e, n)
            else -> progress(phase, "")
        }
    }

    /**
     * Hand over from the direct-line approach to the plan at call [t].
     *
     * If [t] starts a leg, that leg's far end is the next thing to fly to. If it is the far
     * end of a leg - the crew picked `001B` rather than `001A` - the plan carries on with
     * the leg that leaves it.
     */
    private fun enterPlanAt(t: AirCall, e: Double, n: Double): PlanProgress {
        val starts = plan.segments.indexOfFirst { it.from.name == t.name }
        if (starts >= 0) {
            legIndex = starts
            phase = PlanPhase.ON_PLAN
            target = plan.segments[starts].to
            val kind = if (plan.segments[starts].leg == PlanLeg.TRANSIT) "transit" else "transect"
            return progress(
                PlanPhase.ON_PLAN,
                "On the plan from ${t.name}: $kind to ${target?.name}.",
            ).withDistance(e, n)
        }
        val ends = plan.segments.indexOfFirst { it.to.name == t.name }
        if (ends >= 0) {
            legIndex = ends          // stepOn will move to ends + 1
            return stepOn(e, n)
        }
        phase = PlanPhase.COMPLETE
        target = null
        return progress(PlanPhase.COMPLETE, "Plan complete: nothing follows ${t.name}.")
    }

    /** Move to the next leg, or finish if the plan has run out. */
    private fun stepOn(e: Double, n: Double): PlanProgress {
        val next = legIndex + 1
        val leg = plan.segments.getOrNull(next)
        if (leg == null) {
            phase = PlanPhase.COMPLETE
            target = null
            return progress(PlanPhase.COMPLETE, "Plan complete: ${startCall?.name} worked.")
        }
        legIndex = next
        phase = PlanPhase.ON_PLAN
        target = leg.to
        return reportLeg(e, n, null)
    }

    /** Report the current leg, naming whether it is a transect or a transit. */
    private fun reportLeg(e: Double, n: Double, prefix: String?): PlanProgress {
        val leg = plan.segments.getOrNull(legIndex)
        val via = if (leg?.leg == PlanLeg.TRANSIT) "transit" else "transect"
        return progress(PlanPhase.ON_PLAN, prefix ?: "On plan: $via to ${target?.name}.")
            .withDistance(e, n)
    }

    /** Fill in the distance to the current target, tolerating a position we do not have. */
    private fun PlanProgress.withDistance(e: Double, n: Double): PlanProgress {
        val t = target ?: return this
        if (!e.isFinite() || !n.isFinite()) return this
        return copy(distanceM = Geodesy.distanceProjectedM(e, n, t.e, t.n))
    }

    fun stop(): PlanProgress {
        phase = PlanPhase.IDLE
        legIndex = -1
        target = null
        startCall = null
        return progress(PlanPhase.IDLE, "Flight plan stopped.")
    }

    private var lastMessage: String = ""

    private fun progress(p: PlanPhase, message: String): PlanProgress {
        if (message.isNotEmpty()) lastMessage = message
        val t = target
        // every leg ends at one call, so the calls still to reach are the legs left
        val remaining = when (p) {
            PlanPhase.IDLE, PlanPhase.COMPLETE -> 0
            else -> (plan.segments.size - legIndex.coerceAtLeast(0)).coerceAtLeast(0)
        }
        return PlanProgress(
            phase = p,
            targetName = t?.name,
            targetE = t?.e ?: Double.NaN,
            targetN = t?.n ?: Double.NaN,
            distanceM = Double.NaN,
            directLine = p == PlanPhase.APPROACHING,
            callsRemaining = remaining,
            message = message,
        )
    }

    /** Progress with the distance to the current target filled in. */
    fun progressAt(e: Double, n: Double): PlanProgress {
        val base = progress(phase, currentMessage())
        val t = target ?: return base
        return base.copy(distanceM = Geodesy.distanceProjectedM(e, n, t.e, t.n))
    }

    private fun currentMessage(): String = lastMessage.ifEmpty {
        when (phase) {
            PlanPhase.IDLE -> "Choose a call to start from."
            PlanPhase.APPROACHING -> "Direct line to ${target?.name}."
            PlanPhase.ON_PLAN -> "On plan: ${target?.name}."
            PlanPhase.COMPLETE -> "Plan complete."
        }
    }
}