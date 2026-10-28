package org.gcap.core.nav

/**
 * What the app is being asked to navigate, and to where.
 *
 * The problem this exists to solve: pressing Navigate with nothing chosen used to announce
 * "Navigating on ACCESS" and then pick a destination the crew had forgotten about, from
 * a Waypoint or a Go to... chosen minutes or days earlier. The rule here is that a
 * destination is used only while it is *current*, and otherwise the app asks.
 *
 * Three independent axes, deliberately separate, because they change at different times:
 * ground or air, direct or network, one destination or a sequence of them.
 */
class NavigationPlan(
    /** How long a chosen destination stays current. Beyond this it must be chosen again. */
    private val freshSeconds: Long = DEFAULT_FRESH_SECONDS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    enum class Medium { GROUND, AIR }
    enum class Guidance { DIRECT_LINE, NETWORK }

    /** One place to go, and when it was chosen. */
    data class Destination(
        val label: String,
        val e: Double,
        val n: Double,
        val atMs: Long,
        val origin: Origin,
    ) {
        enum class Origin {
            /** Held on the map, then Navigate. */
            LONG_PRESS,

            /** Chosen with the Waypoint button. */
            WAYPOINT,

            /** Centred with Go to..., then Navigate. */
            GO_TO,

            /** Part of a route read from route_plan.shp. */
            ROUTE,
        }
    }

    var medium: Medium = Medium.GROUND
    var guidance: Guidance = Guidance.NETWORK

    /** The single destination currently in hand, or null. */
    var destination: Destination? = null
        private set

    /**
     * An ordered sequence of destinations, when the crew asked for a route.
     *
     * A route is deliberate rather than incidental, so its stops do not go stale the way a
     * stray tap does. It is cleared only when something else becomes the destination.
     */
    var route: List<Destination> = emptyList()
        private set

    /** True when the crew asked for a sequence rather than one place. */
    val isRoute: Boolean get() = route.size > 1

    fun useMedium(m: Medium) { medium = m }

    fun useGuidance(g: Guidance) { guidance = g }

    /** Set the one destination, replacing any route. */
    fun setDestination(
        label: String,
        e: Double,
        n: Double,
        origin: Destination.Origin,
        atMs: Long = now(),
    ) {
        destination = Destination(label, e, n, atMs, origin)
        route = emptyList()
    }

    /**
     * Load an ordered sequence. Points must already be in the order they are to be
     * visited - a route is a plan, not a bag of choices.
     */
    fun setRoute(stops: List<Destination>) {
        route = stops
        destination = stops.lastOrNull()
    }

    fun clearRoute() {
        route = emptyList()
    }

    /** What happens when Navigate is pressed. */
    sealed interface Resolution {
        /** Something current to go to. */
        data class Ready(val destination: Destination, val route: List<Destination>) : Resolution

        /**
         * Nothing current. The old destination is reported so the app can say how stale it
         * is rather than quietly reusing it.
         */
        data class NeedsDestination(
            val staleAgeSec: Long?,
            val hasSavedRoute: Boolean,
        ) : Resolution
    }

    /**
     * Decide what Navigate should do, given no fresh choice.
     *
     * A route is used when one is loaded; otherwise a destination is honoured only while it
     * is within [freshSeconds]. Age is measured from when it was *chosen*, not from when it
     * was last looked at, so repeatedly pressing Navigate cannot revive a stale one.
     */
    fun resolve(): Resolution {
        if (isRoute) return Resolution.Ready(destination!!, route)
        val d = destination ?: return Resolution.NeedsDestination(null, false)
        val ageSec = (now() - d.atMs) / 1000
        if (ageSec > freshSeconds) {
            return Resolution.NeedsDestination(ageSec, false)
        }
        return Resolution.Ready(d, listOf(d))
    }

    /** Age in seconds of the current destination, or null when there is none. */
    fun ageSeconds(): Long? = destination?.let { (now() - it.atMs) / 1000 }

    companion object {
        /** Two minutes: long enough to walk from the map to the vehicle, short enough
         *  that yesterday's waypoint is never mistaken for today's intent. */
        const val DEFAULT_FRESH_SECONDS = 120L
    }
}