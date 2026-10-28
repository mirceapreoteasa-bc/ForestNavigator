package org.forestnav.app.map

/**
 * Which editor the app is in, and which ones the user is allowed to start.
 *
 * This exists so the rule is stated once and tested, rather than spread across a click
 * handler and held together by reading it.
 *
 * The invariant that matters: **starting point editing must never lead to cutting a
 * road.** Cutting removes a stretch of a layer in place, with no undo, so it must not be
 * reachable from a button a user presses while walking the map. Starting a cut therefore
 * needs Settings, a layer and a gap; only starting and stopping are shared with point
 * editing, because those carry no risk on their own.
 *
 * The rule is refused, never silently overridden. If point editing is open and the user
 * asks to cut, saying so is better than cutting the points layer by accident.
 */
object EditMode {

    sealed interface Decision {
        /** Allowed. */
        data object Go : Decision

        /** Refused, with the words to show the user. */
        data class No(val why: String) : Decision
    }

    /**
     * May point editing start now?
     *
     * Refused twice over. Obviously if points are already being edited. Also whenever a
     * line session exists - which outlives its armed state on purpose, because cancelling
     * a cut leaves the staged cuts in place and letting the point editor take over at that
     * moment is how a half-finished cutting job gets forgotten.
     */
    fun canStartPoints(pointSessionOpen: Boolean, lineSessionExists: Boolean): Decision = when {
        pointSessionOpen -> Decision.No("Already editing")
        lineSessionExists -> Decision.No("Stop line editing first (Stop edit)")
        else -> Decision.Go
    }

    /**
     * May a line cutting session start now?
     *
     * Refused while points are being edited, because the two write different layers and a
     * tap cannot mean both. Refused while a cut session is open, so a second arm cannot
     * replace one holding staged cuts.
     */
    fun canStartCutting(pointSessionOpen: Boolean, lineSessionExists: Boolean): Decision = when {
        pointSessionOpen ->
            Decision.No("Stop point editing first (Stop edit)")
        lineSessionExists ->
            Decision.No("Already cutting - save or stop that first")
        else -> Decision.Go
    }

    /** May a map tap be treated as a cut? */
    fun tapCutsLine(cutArmed: Boolean): Boolean = cutArmed

    /**
     * The banner text for the current state.
     *
     * Says how many cuts are unsaved whenever there are any, so a staged cut cannot be
     * forgotten between staging it and pressing Save.
     */
    fun lineBanner(cutArmed: Boolean, pending: Int): String? {
        if (!cutArmed) return null
        return if (pending > 0) {
            "CUT MODE - $pending staged, not yet saved"
        } else {
            "CUT MODE - tap the line to break it"
        }
    }

    /**
     * What `Stop edit` should do about staged cuts.
     *
     * The three-way choice is the point: saving writes real gaps into a real layer, and
     * discarding must leave it byte-identical, so the user has to be the one to say which.
     */
    enum class StopAction { SAVE, DISCARD, KEEP_CUTTING, NOT_CUTTING }

    fun stopAction(pendingCuts: Int): StopAction = when {
        pendingCuts > 0 -> StopAction.SAVE
        else -> StopAction.NOT_CUTTING
    }
}