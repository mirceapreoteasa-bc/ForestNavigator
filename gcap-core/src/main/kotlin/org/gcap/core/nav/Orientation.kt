
package org.gcap.core.nav

import kotlin.math.abs

/** How the map is oriented, chosen by the user. */
enum class OrientationMode {
    /** Track the direction of travel. Degrades to hold, then north, when stationary. */
    COURSE_UP,

    /** Keep the active waypoint straight ahead. */
    WAYPOINT_UP,

    /** North up always. */
    NORTH_UP,

    /**
     * Keep the straight-line bearing to the navigation target ahead.
     *
     * Distinct from [WAYPOINT_UP]: this follows whatever the Navigate screen is
     * currently aiming at, so it points at the next leg of a route plan, not at the
     * final waypoint. On a map with no network built, that is the target line.
     */
    DIRECT_LINE_UP,

    /**
     * Follow the magnetic compass rather than the course over ground.
     *
     * Course needs movement to show a heading at all, so a crew standing still before
     * setting off sees nothing. The compass reads a heading while stationary, which is
     * what a person holding a phone flat in their hand is doing.
     */
    COMPASS_UP,
}

/**
 * Turns the user's orientation preference plus the current GPS situation into the
 * bearing that should point up the screen.
 *
 * Stationary behaviour inside [OrientationMode.COURSE_UP] is the user's rule:
 * hold the last known heading for 30 s, then fall back to north up after 120 s.
 * The hold exists because GPS drift alone will otherwise spin the map while
 * someone is standing still at a waypoint.
 *
 * Bearing changes are rate limited rather than snapped, so a single bad fix cannot
 * make the whole map lurch.
 */
class OrientationController(
    var mode: OrientationMode = OrientationMode.COURSE_UP,
    private val holdSeconds: Double = 30.0,
    private val northAfterSeconds: Double = 120.0,
    private val maxDegPerSecond: Double = 90.0,
) {
    private var lastApplied: Double = Double.NaN
    private var lastTimeMs: Long = Long.MIN_VALUE

    /** Bearing currently on screen, for drawing the north arrow. */
    var appliedBearing: Double = 0.0
        private set

    /** Why the map is oriented this way, for the on-screen reason indicator. */
    var reason: OrientationReason = OrientationReason.NORTH
        private set

    fun reset() {
        lastApplied = Double.NaN
        lastTimeMs = Long.MIN_VALUE
        appliedBearing = 0.0
        reason = OrientationReason.NORTH
    }

    /**
     * @param nowMs monotonic clock, only compared against [stillForMs].
     * @param heading current course estimate, may be [HeadingState.INVALID].
     * @param waypointBearing bearing to the active waypoint, or null if none.
     * @param stillForMs how long the receiver has been effectively stationary.
     * @param compassBearing magnetic heading, or null when no compass is available.
     */
    fun bearingFor(
        nowMs: Long,
        heading: HeadingState,
        waypointBearing: Double? = null,
        stillForMs: Long = 0L,
        compassBearing: Double? = null,
    ): Double {
        val target: Double
        val why: OrientationReason

        when (mode) {
            OrientationMode.NORTH_UP -> {
                target = 0.0; why = OrientationReason.NORTH
            }

            OrientationMode.WAYPOINT_UP -> {
                if (waypointBearing != null && !waypointBearing.isNaN()) {
                    target = waypointBearing; why = OrientationReason.WAYPOINT
                } else {
                    target = 0.0; why = OrientationReason.NORTH
                }
            }

            OrientationMode.DIRECT_LINE_UP -> {
                if (waypointBearing != null && !waypointBearing.isNaN()) {
                    target = waypointBearing; why = OrientationReason.WAYPOINT
                } else {
                    target = 0.0; why = OrientationReason.NORTH
                }
            }

            OrientationMode.COMPASS_UP -> {
                if (compassBearing != null && !compassBearing.isNaN()) {
                    target = compassBearing; why = OrientationReason.COMPASS
                } else if (heading.valid && !heading.bearingDeg.isNaN()) {
                    // no compass reading yet; course is better than nothing
                    target = heading.bearingDeg; why = OrientationReason.COURSE
                } else {
                    target = 0.0; why = OrientationReason.NORTH
                }
            }

            OrientationMode.COURSE_UP -> when {
                heading.valid && heading.moving && !heading.bearingDeg.isNaN() -> {
                    target = heading.bearingDeg; why = OrientationReason.COURSE
                }

                stillForMs >= northAfterSeconds * 1000.0 -> {
                    target = 0.0; why = OrientationReason.NORTH
                }

                stillForMs >= holdSeconds * 1000.0 -> {
                    // nothing trustworthy to hold and we have no course: go north
                    target = 0.0; why = OrientationReason.NORTH
                }

                else -> {
                    // hold the last heading actually applied to the screen
                    target = if (lastApplied.isNaN()) 0.0 else lastApplied
                    why = OrientationReason.HOLDING
                }
            }
        }

        val slewed = slew(target, nowMs)
        appliedBearing = slewed
        reason = why
        return slewed
    }

    /**
     * Limit how fast the on-screen bearing may change, taking the short way round
     * the compass so 359 -> 1 does not sweep backwards through 180.
     */
    private fun slew(target: Double, nowMs: Long): Double {
        if (lastApplied.isNaN() || lastTimeMs == Long.MIN_VALUE || nowMs <= lastTimeMs) {
            lastApplied = target
            lastTimeMs = nowMs
            return target
        }
        val dt = (nowMs - lastTimeMs) / 1000.0
        val maxStep = maxDegPerSecond * dt
        var delta = MapTransform.normaliseBearing(target - lastApplied + 180.0) - 180.0
        if (abs(delta) > maxStep) delta = if (delta > 0) maxStep else -maxStep
        lastApplied = MapTransform.normaliseBearing(lastApplied + delta)
        lastTimeMs = nowMs
        return lastApplied
    }
}

/** Why the map is oriented the way it is, shown to the user. */
enum class OrientationReason {
    /** Following the direction of travel. */
    COURSE,

    /** Following the magnetic compass. */
    COMPASS,

    /** Stationary, still holding the previous heading. */
    HOLDING,

    /** Keeping the active waypoint ahead. */
    WAYPOINT,

    /** North up. */
    NORTH,
}
