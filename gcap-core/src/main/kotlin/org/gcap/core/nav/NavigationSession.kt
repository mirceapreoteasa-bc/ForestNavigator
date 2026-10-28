package org.gcap.core.nav

/** How the crew is being led to the target. */
enum class NavKind {
    /** Follow a network: a road, an access track, a flight path or a call sequence. */
    NETWORK,

    /** Straight to the target, ignoring any network. */
    DIRECT_LINE,
}

/**
 * Everything the app needs to draw the navigation line and the status line.
 *
 * [message] is written for the crew rather than the developer: it says what happened and
 * what to do next, so the buttons can be dumb and the wording lives in one place.
 */
data class NavState(
    val active: Boolean,
    val kind: NavKind,
    /** Label of the network in use, e.g. `ACCESS`, or null before starting. */
    val network: String?,
    /** Current target: a waypoint name or an air call id. */
    val target: String?,
    val message: String,
    /**
     * Set when navigation stops, so the caller knows to put the map back on the GPS
     * position with course-up orientation, as the main page specifies.
     */
    val recentreOnGps: Boolean = false,
)

/**
 * The navigation the crew has asked for.
 *
 * `Navigate` starts on the default network for the mode, `Stop_Navigating` ends it,
 * `Direct_Line` switches to flying straight at the target, and the three network buttons
 * choose what is being followed. Choosing a network before starting is remembered, so
 * tapping ROAD then Navigate follows ROAD.
 *
 * Kept free of Android types so the rules can be tested directly; the Activity only
 * translates the result into map drawing.
 */
class NavigationSession(initialMode: NavMode = NavMode.GROUND) {

    var mode: NavMode = initialMode
        private set

    private var chosenNetworkId: String? = null

    var state: NavState = NavState(
        active = false,
        kind = NavKind.NETWORK,
        network = null,
        target = null,
        message = idleMessage(),
    )
        private set

    private fun idleMessage(): String =
        if (mode == NavMode.AIR) "Ready. Navigate follows the Flight Path." else "Ready. Navigate starts ACCESS."

    /** Labels of the three network buttons in the current mode. */
    fun networkLabels(): List<String> =
        MainGrid.networkIds.mapNotNull { MainGrid.byId(it)?.label(mode) }

    /** The network Navigate will use right now. */
    fun activeNetworkId(): String = chosenNetworkId ?: MainGrid.defaultNetworkId(mode)

    fun activeNetworkLabel(): String? = MainGrid.byId(activeNetworkId())?.label(mode)

    /**
     * `Navigate`.
     *
     * With no argument the target already chosen with `Waypoint` is kept, so choosing a
     * waypoint and then pressing Navigate heads for it. Starting twice is refused rather
     * than silently restarting, which would lose the crew's place.
     */
    fun start(target: String? = null): NavState {
        if (state.active) {
            state = state.copy(message = "Already navigating on ${state.network}.")
            return state
        }
        val net = activeNetworkLabel() ?: "the default network"
        val goal = target ?: state.target
        state = NavState(
            active = true,
            kind = NavKind.NETWORK,
            network = net,
            target = goal,
            message = if (goal == null) {
                "Navigating on $net."
            } else {
                "Navigating on $net towards $goal."
            },
        )
        return state
    }

    /** `Stop_Navigating`. Leaves the map to be re-centred on the GPS position. */
    fun stop(): NavState {
        if (!state.active) {
            state = state.copy(message = "Not navigating.")
            return state
        }
        state = NavState(
            active = false,
            kind = NavKind.NETWORK,
            network = null,
            target = null,
            message = "Navigation stopped. Map centred on your position.",
            recentreOnGps = true,
        )
        return state
    }

    /**
     * `Direct_Line`.
     *
     * Only meaningful while navigating: switching before starting would leave nothing
     * to switch to, so it is refused with an explanation instead of setting a flag that
     * appears to do nothing.
     */
    fun directLine(): NavState {
        if (!state.active) {
            state = state.copy(message = "Start navigating first.")
            return state
        }
        state = state.copy(
            kind = NavKind.DIRECT_LINE,
            message = buildString {
                append("Direct line to ")
                append(state.target ?: "the target")
                append('.')
            },
        )
        return state
    }

    /** Back to following the network, having been on a direct line. */
    fun followNetwork(): NavState {
        if (!state.active) {
            state = state.copy(message = "Start navigating first.")
            return state
        }
        state = state.copy(
            kind = NavKind.NETWORK,
            message = "Following ${state.network}.",
        )
        return state
    }

    /**
     * One of the three network buttons.
     *
     * Works whether or not navigation is running: before starting it chooses the network
     * Navigate will use, while running it switches to that network.
     */
    fun chooseNetwork(buttonId: String): NavState {
        val button = MainGrid.byId(buttonId)
        if (button == null || button.id !in MainGrid.networkIds) {
            state = state.copy(message = "That is not a network button.")
            return state
        }
        chosenNetworkId = buttonId
        val label = button.label(mode)
        state = if (state.active) {
            state.copy(network = label, kind = NavKind.NETWORK, message = "Following $label.")
        } else {
            state.copy(message = "$label selected. Navigate will follow it.")
        }
        return state
    }

    /** `Waypoint`: set the target from the waypoints layer. */
    fun setTarget(label: String): NavState {
        if (label.isBlank()) return state
        state = state.copy(
            target = label,
            message = if (state.active) {
                "Heading for $label."
            } else {
                "$label selected. Navigate will head for it."
            },
        )
        return state
    }

    /** Move the target on, for instance after the crew reaches it. */
    fun advanceTo(label: String?): NavState {
        if (!state.active) {
            state = state.copy(message = "Not navigating.")
            return state
        }
        if (label == null) {
            state = state.copy(target = null, message = "Last call reached.")
            return state
        }
        state = state.copy(target = label, message = "Next: $label.")
        return state
    }

    /**
     * Change mode. Navigation is stopped first, because a ROUTE and a road network are
     * not interchangeable and carrying one into the other would be misleading.
     */
    fun setMode(newMode: NavMode): NavState {
        if (newMode == mode) return state
        val wasActive = state.active
        mode = newMode
        chosenNetworkId = null
        state = NavState(
            active = false,
            kind = NavKind.NETWORK,
            network = null,
            target = null,
            message = if (wasActive) {
                "Navigation stopped: ${if (newMode == NavMode.AIR) "on_air" else "on_ground"} selected."
            } else {
                if (newMode == NavMode.AIR) "On air. Navigate follows the Flight Path." else "On ground. Navigate starts ACCESS."
            },
            recentreOnGps = wasActive,
        )
        return state
    }
}