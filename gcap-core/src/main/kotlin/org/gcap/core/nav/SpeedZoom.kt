package org.gcap.core.nav

/**
 * Speed-adaptive map scale.
 *
 * Forest work spans walking pace to helicopter transit, and a scale suited to one is
 * useless for the other: at helicopter speed the road ahead leaves the screen almost
 * immediately, while at walking pace a block overview hides the very feature you
 * stopped to look at. The bands below are the user's operating ranges.
 *
 * Two refinements over a plain lookup:
 *
 *  - **Hysteresis.** Speed wanders around a boundary, and a naive lookup makes the map
 *    jump between two scales several times a second while parked at a threshold. A
 *    change only takes effect once the speed has stayed beyond the boundary by
 *    [hysteresisKph] *and* has done so for [dwellMs].
 *  - **Damping.** Scale changes are applied a little at a time, because snapping
 *    straight from 1:2,000 to 1:50,000 is disorienting at speed.
 */
class SpeedZoom(
    private val dwellMs: Long = 1500L,
    private val hysteresisKph: Double = 8.0,
    private val maxStepsPerUpdate: Int = 1,
) {
    /** Scale the app should currently display, in nominal denominator form. */
    var currentScale: Int = DEFAULT_SCALE
        private set

    /** Ground extent each band represents under the canvas convention, for reference. */
    private fun targetFor(speedKph: Double): Int = when {
        speedKph >= 200.0 -> 50000
        speedKph >= 100.0 -> 20000
        speedKph >= 70.0 -> 15000
        speedKph >= 50.0 -> 10000
        speedKph >= 40.0 -> 7500
        speedKph >= 30.0 -> 5000
        speedKph >= 15.0 -> 3000
        speedKph >= 5.0 -> 2000
        else -> 1000
    }

    private var candidateScale = DEFAULT_SCALE
    private var candidateSinceMs = 0L

    /** Forget any pending change and start again from [scale]. */
    fun reset(scale: Int = DEFAULT_SCALE) {
        currentScale = scale
        candidateScale = scale
        candidateSinceMs = 0L
    }

    /**
     * Feed the current speed.
     *
     * @return the scale to display now, which moves at most [maxStepsPerUpdate] rungs
     *   toward the target band so the change is not a lurch.
     */
    fun update(speedKph: Double, nowMs: Long): Int {
        val s = if (speedKph.isFinite()) speedKph else 0.0
        val target = targetFor(s)
        if (target == currentScale) {
            candidateScale = target
            candidateSinceMs = nowMs
            return currentScale
        }
        if (target != candidateScale) {
            // first sighting of this band: start its dwell clock
            candidateScale = target
            candidateSinceMs = nowMs
            return currentScale
        }
        if (nowMs - candidateSinceMs < dwellMs) return currentScale
        if (!clearlyPastBoundary(s, currentScale, target)) return currentScale

        val stepped = stepTowards(currentScale, target, maxStepsPerUpdate)
        currentScale = stepped
        if (stepped == target) candidateSinceMs = nowMs
        return currentScale
    }

    /**
     * Confirm the speed has genuinely crossed from [current]'s band into [target]'s,
     * rather than hovering on the shared boundary where receiver noise lives.
     *
     * The shared boundary is the lower edge of whichever band is coarser, and the
     * speed must clear it by [hysteresisKph] in the direction of travel.
     */
    private fun clearlyPastBoundary(speedKph: Double, current: Int, target: Int): Boolean {
        // a larger denominator is coarser, and coarser belongs to the faster band
        val coarser = maxOf(current, target)
        val boundary = lowerBoundOf(coarser)
        return if (target < current) speedKph <= boundary - hysteresisKph   // finer: slowing
        else speedKph >= boundary + hysteresisKph                           // coarser: speeding
    }

    private fun lowerBoundOf(scale: Int): Double = when (scale) {
        50000 -> 200.0
        20000 -> 100.0
        15000 -> 70.0
        10000 -> 50.0
        7500 -> 40.0
        5000 -> 30.0
        3000 -> 15.0
        2000 -> 5.0
        else -> 0.0
    }

    /** Move along the scale ladder toward [target], at most [maxSteps] rungs. */
    private fun stepTowards(from: Int, to: Int, maxSteps: Int): Int {
        val i = LADDER.indexOf(from)
        val j = LADDER.indexOf(to)
        if (i < 0 || j < 0) return to
        if (i == j) return to
        return LADDER[(i + if (j > i) maxSteps else -maxSteps).coerceIn(0, LADDER.size - 1)]
    }

    companion object {
        /** 1:20,000 - helicopter transit scale, and what the app opens at. */
        const val DEFAULT_SCALE = 20000

        /** Ordered coarse-to-fine, used for damped stepping. */
        val LADDER = intArrayOf(50000, 20000, 15000, 10000, 7500, 5000, 3000, 2000, 1000)

        /** The full speed-to-scale table, for display in settings. */
        val TABLE: Array<Triple<String, Int, Double>> = arrayOf(
            Triple("over 200", 50000, 200.0),
            Triple("199 - 100", 20000, 100.0),
            Triple("99 - 70", 15000, 70.0),
            Triple("69 - 50", 10000, 50.0),
            Triple("49 - 40", 7500, 40.0),
            Triple("39 - 30", 5000, 30.0),
            Triple("29 - 15", 3000, 15.0),
            Triple("14 - 5", 2000, 5.0),
            Triple("under 5", 1000, 0.0),
        )
    }
}
