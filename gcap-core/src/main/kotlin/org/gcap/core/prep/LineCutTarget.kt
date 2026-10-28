package org.gcap.core.prep

import kotlin.math.sqrt

/**
 * Choosing which line a tap on the map meant, and where along it.
 *
 * Cutting is the one edit that removes a road, so it has to act on the line the user
 * actually touched rather than on whatever happens to be nearby. The search therefore
 * returns the nearest piece of geometry *within a radius the user can see on screen*, and
 * refuses rather than guessing when nothing is that close.
 */
object LineCutTarget {

    /**
     * A tap resolved to a line feature and a position along it.
     *
     * [fraction] runs 0..1 along the part's own length, so the caller does not need to
     * know the layer's coordinate system or how long any of its segments are.
     */
    data class Target(
        val recordIndex: Int,
        val partIndex: Int,
        val fraction: Double,
        /** Where the cut would land, in the layer's own coordinates. */
        val x: Double,
        val y: Double,
        /** How far the tap was from the line, in the layer's units. */
        val offsetM: Double,
        val partLengthM: Double,
    )

    /** Shortest cut gap the app will make, chosen to clear any realistic weld. */
    const val MIN_GAP_M = 10.0

    /** Longest cut, so a mistaken drag cannot delete a kilometre of road. */
    const val MAX_GAP_M = 100.0

    /**
     * The line nearest to ([x], [y]) within [maxOffsetM], or null if nothing is close.
     *
     * [toleranceM] is the healing tolerance this layer is expected to be used at. A cut
     * whose gap is not comfortably wider than that would be closed again the next time
     * somebody heals the layer, so the result is marked and the caller is expected to
     * refuse - [BlockReason.TOO_CLOSE_TO_HEAL_TOLERANCE].
     */
    fun find(
        lines: List<LineHealing.Line>,
        x: Double,
        y: Double,
        maxOffsetM: Double,
        toleranceM: Double = LineHealing.DEFAULT_TOLERANCE_M,
        gapM: Double = 20.0,
    ): Result = runCatching {
        var best: Target? = null
        for ((r, line) in lines.withIndex()) {
            for ((p, part) in line.parts.withIndex()) {
                if (part.size < 2) continue
                for (i in 0 until part.size - 1) {
                    val a = part[i]
                    val b = part[i + 1]
                    val dx = b[0] - a[0]
                    val dy = b[1] - a[1]
                    val len2 = dx * dx + dy * dy
                    if (len2 <= 0.0) continue
                    var t = ((x - a[0]) * dx + (y - a[1]) * dy) / len2
                    if (t < 0.0) t = 0.0
                    if (t > 1.0) t = 1.0
                    val px = a[0] + t * dx
                    val py = a[1] + t * dy
                    val off = sqrt((x - px) * (x - px) + (y - py) * (y - py))
                    if (off > maxOffsetM) continue
                    if (best != null && off >= best.offsetM) continue
                    // Fraction along the whole part, so the cutter can be told one number.
                    // Measured in one pass with explicit sums: an earlier version added the
                    // trailing segments with range arithmetic that silently contributed
                    // nothing, which put every cut at twice its true distance along the line.
                    var total = 0.0
                    for (k in 0 until part.size - 1) total += dist(part[k], part[k + 1])
                    if (total <= 0.0) continue
                    var before = 0.0
                    for (k in 0 until i) before += dist(part[k], part[k + 1])
                    val segLen = sqrt(len2)
                    val fraction = (before + segLen * t) / total
                    // No stub guard here. An earlier version refused a tap within 10 m of
                    // the part's own ends, which on a real survey layer rejects
                    // everything: `access` is thousands of 15 m features that the map draws
                    // as one continuous road, so no tap ever found a cuttable line. Whether
                    // the gap actually fits is decided by [fits] and explained by the caller.
                    if (!fits(part, px, py, gapM)) continue
                    // The cut itself must sit at least MIN_GAP_M in from each end of the part,
                    // measured along it. A tap at a feature's first vertex gives fraction 0,
                    // which the cutter refuses, and that refusal failed the whole save on device.
                    val along = fraction * total
                    if (along < MIN_GAP_M || total - along < MIN_GAP_M) continue
                    best = Target(
                        recordIndex = r,
                        partIndex = p,
                        fraction = fraction,
                        x = px,
                        y = py,
                        offsetM = off,
                        partLengthM = total,
                    )
                }
            }
        }
        val target = best ?: return@runCatching Result.NothingClose
        val block = when {
            gapM < MIN_GAP_M -> BlockReason.GAP_TOO_SMALL
            gapM > MAX_GAP_M -> BlockReason.GAP_TOO_LARGE
            gapM <= toleranceM -> BlockReason.TOO_CLOSE_TO_HEAL_TOLERANCE
            else -> null
        }
        if (block != null) Result.Refused(target, block)
        else Result.Found(target)
    }.getOrElse { Result.NothingClose }

    /** Why a tap was not acted on, or why the proposed cut is not safe to make. */
    enum class BlockReason {
        /** Nothing within reach of the tap. */
        NOTHING_CLOSE,

        /** Below [MIN_GAP_M]: healing or a later edit could easily close it. */
        GAP_TOO_SMALL,

        /** Above [MAX_GAP_M]: too much road disappears. */
        GAP_TOO_LARGE,

        /**
         * The gap is no wider than the healing tolerance, so healing this layer again
         * would weld the road straight back together and the cut would silently stop
         * working.
         */
        TOO_CLOSE_TO_HEAL_TOLERANCE,
    }

    sealed interface Result {
        data class Found(val target: Target) : Result
        data class Refused(val target: Target, val reason: BlockReason) : Result
        data object NothingClose : Result

        val targetOrNull: Target?
            get() = when (this) {
                is Found -> target
                is Refused -> target
                NothingClose -> null
            }
    }

    /**
     * True when a gap of [gapM] can actually be cut at this point on this part.
     *
     * Needs [MIN_GAP_M] of line either side of the cut as well, or the two halves are stubs
     * too short to be worth keeping.
     */
    fun fits(part: List<DoubleArray>, x: Double, y: Double, gapM: Double): Boolean {
        val need = gapM + 2 * MIN_GAP_M
        return partLength(part) >= need
    }

    /**
     * Why a tap found no cuttable line, in words the user can act on.
     *
     * "No line here" alone sent people to re-tap, or to heal a layer that did not need it.
     * This names the nearest line and, when it is too short for the gap, says how long it
     * would have to be.
     */
    fun explainNoCut(
        lines: List<LineHealing.Line>,
        x: Double,
        y: Double,
        maxOffsetM: Double,
        gapM: Double,
    ): String {
        var nearest = Double.MAX_VALUE
        var nearestPartLength = 0.0
        for (line in lines) for (part in line.parts) {
            if (part.size < 2) continue
            for (i in 0 until part.size - 1) {
                val d = pointToSegment(x, y, part[i], part[i + 1])
                if (d < nearest) {
                    nearest = d
                    nearestPartLength = partLength(part)
                }
            }
        }
        val need = lengthNeededFor(gapM)
        return when {
            nearest > maxOffsetM ->
                "No line within ${"%.0f".format(maxOffsetM)} m of the tap. Tap closer to the line."
            nearestPartLength < need ->
                "The line here is only ${"%.0f".format(nearestPartLength)} m long in one piece. " +
                    "A ${"%.0f".format(gapM)} m gap needs at least ${"%.0f".format(need)} m, " +
                    "10 m either side of the cut. Heal the layer so its pieces join up, then cut."
            else ->
                "This point is within ${"%.0f".format(MIN_GAP_M)} m of the end of its line piece, " +
                    "so a cut here would leave a stub too short to keep. Tap further along the line."
        }
    }

    private fun pointToSegment(px: Double, py: Double, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else (((px - a[0]) * dx + (py - a[1]) * dy) / len2).coerceIn(0.0, 1.0)
        return sqrt((px - (a[0] + t * dx)) * (px - (a[0] + t * dx)) + (py - (a[1] + t * dy)) * (py - (a[1] + t * dy)))
    }

    /** How much line is needed to cut here, for the message when it is too short. */
    fun lengthNeededFor(gapM: Double): Double = gapM + 2 * MIN_GAP_M

    /** True when the part is too short to take the gap; used to explain the refusal. */
    fun tooShortFor(part: List<DoubleArray>, gapM: Double): Boolean =
        partLength(part) < lengthNeededFor(gapM)

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun partLength(part: List<DoubleArray>): Double {
        var t = 0.0
        for (i in 0 until part.size - 1) t += dist(part[i], part[i + 1])
        return t
    }

}
