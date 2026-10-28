package org.gcap.core.nav

/** Where one air call sits relative to a formed line. */
data class CallFoot(
    val call: AirCall,
    val lineIndex: Int,
    /** Distance along the line to the perpendicular foot. */
    val alongM: Double,
    /** Distance from the call to its foot. Zero would mean it lies on the line. */
    val offsetM: Double,
)

/**
 * Checks a set of formed lines against the air-call sequence by projecting each call onto
 * the nearest line with a perpendicular.
 *
 * The calls are not expected to lie on the line. A flown transect and the digitised line
 * that represents it differ by the survey error, so the A and B calls sit in the vicinity
 * rather than exactly on it, and the perpendicular foot is what matters.
 *
 * A correctly built line should show the calls in flying order along it: `001A`, `001B`,
 * `002A`, `002B` and so on, advancing monotonically in one direction. That makes this a
 * real test of the join distance rather than a visual check - if the calls come out in the
 * right order along a line, the line was assembled the right way round.
 */
class FlightLineSequence(
    private val lines: List<DoubleArray>,
    calls: List<AirCall>,
    /** A call further than this from every line is treated as not on any of them. */
    private val maxOffsetM: Double = 200.0,
) {
    /** Calls in flying order, A and B only. */
    val route: List<AirCall> = calls.sortedWith(AirCallOrder).filter { it.suffix.isNotEmpty() }

    /** Foot of every call that lands on some line. */
    val feet: List<CallFoot>

    init {
        val out = ArrayList<CallFoot>()
        for (c in route) {
            var bestLine = -1
            var bestOffset = Double.MAX_VALUE
            for (li in lines.indices) {
                val line = lines[li]
                var i = 0
                while (i + 3 < line.size) {
                    val d = Geodesy.pointSegmentDistance(
                        c.e, c.n, line[i], line[i + 1], line[i + 2], line[i + 3],
                    )[0]
                    if (d < bestOffset) { bestOffset = d; bestLine = li }
                    i += 2
                }
            }
            if (bestLine >= 0 && bestOffset <= maxOffsetM) {
                out.add(CallFoot(c, bestLine, alongOn(bestLine, c), bestOffset))
            }
        }
        feet = out
    }

    /** Precise arclength of the perpendicular foot of [c] on line [li]. */
    private fun alongOn(li: Int, c: AirCall): Double {
        val line = lines[li]
        var along = 0.0
        var best = 0.0
        var bestD = Double.MAX_VALUE
        var i = 0
        while (i + 3 < line.size) {
            val r = Geodesy.pointSegmentDistance(c.e, c.n, line[i], line[i + 1], line[i + 2], line[i + 3])
            val segLen = Geodesy.distanceProjectedM(line[i], line[i + 1], line[i + 2], line[i + 3])
            if (r[0] < bestD) { bestD = r[0]; best = along + segLen * r[1] }
            along += segLen
            i += 2
        }
        return best
    }

    /** How well one line's calls run in flying order along it. */
    data class LineCheck(
        val lineIndex: Int,
        val callCount: Int,
        /** True when the calls advance along the line in flying order. */
        val forward: Boolean,
        /** Adjacent call pairs that advance, as a fraction of all adjacent pairs. */
        val agreement: Double,
        val worstOffsetM: Double,
        /** First call of this line in flying order, and where it sits along the line. */
        val firstCall: AirCall? = null,
        val firstAlongM: Double? = null,
        /** Last call of this line in flying order, and where it sits along the line. */
        val lastCall: AirCall? = null,
        val lastAlongM: Double? = null,
    ) {
        fun describe(): String =
            "$callCount calls, order ${(agreement * 100).toInt()}% forward=$forward, " +
                "worst ${"%.0f".format(worstOffsetM)} m off"
    }

    /**
     * Order each line's calls along it and report how well that matches flying order.
     *
     * A line has no inherent direction, so both are tried and the better kept: a chain that
     * was assembled from the wrong end still has all the right calls on it, just running
     * backwards.
     */
    fun check(): List<LineCheck> {
        val byLine = feet.groupBy { it.lineIndex }
        val out = ArrayList<LineCheck>()
        for ((li, group) in byLine) {
            if (group.size < 2) {
                val only = group.firstOrNull()
                out.add(
                    LineCheck(
                        li, group.size, true, 1.0,
                        group.maxOfOrNull { it.offsetM } ?: 0.0,
                        only?.call, only?.alongM, only?.call, only?.alongM,
                    ),
                )
                continue
            }
            // group is already in flying order because `feet` follows `route`
            var forwardSteps = 0
            var backSteps = 0
            for (i in 0 until group.size - 1) {
                val d = group[i + 1].alongM - group[i].alongM
                if (d > 0) forwardSteps++ else backSteps++
            }
            val pairs = group.size - 1
            val forward = forwardSteps >= backSteps
            val agreement = maxOf(forwardSteps, backSteps).toDouble() / pairs
            out.add(
                LineCheck(
                    li, group.size, forward, agreement,
                    group.maxOf { it.offsetM },
                    group.first().call, group.first().alongM,
                    group.last().call, group.last().alongM,
                ),
            )
        }
        return out.sortedByDescending { it.callCount }
    }

    /** The calls lying on one line, in flying order. */
    fun callsOn(lineIndex: Int): List<CallFoot> = feet.filter { it.lineIndex == lineIndex }

    /**
     * The portion of one line between two arclengths, endpoints included.
     *
     * Arclengths are as [CallFoot.alongM] measures them, from the line's first
     * vertex. Either order is accepted: the calls of a plan may advance against
     * the line's own direction, and the slice then comes out reversed so it
     * still reads in the direction of travel.
     *
     * Consecutive duplicate vertices are dropped, which the welded and blended
     * lines contain at every join; keeping them would only add zero-length
     * segments to the drawn plan.
     */
    fun slice(lineIndex: Int, fromM: Double, toM: Double): DoubleArray {
        val line = lines[lineIndex]
        val forward = fromM <= toM
        val lo = minOf(fromM, toM)
        val hi = maxOf(fromM, toM)
        val pts = ArrayList<Double>()
        var along = 0.0
        var i = 0
        while (i + 3 < line.size) {
            val segLen = Geodesy.distanceProjectedM(
                line[i], line[i + 1], line[i + 2], line[i + 3],
            )
            if (segLen > 1e-9) {
                val s0 = along
                val s1 = along + segLen
                if (s1 >= lo && s0 <= hi) {
                    if (s0 < lo) {
                        val t = (lo - s0) / segLen
                        pts.add(line[i] + (line[i + 2] - line[i]) * t)
                        pts.add(line[i + 1] + (line[i + 3] - line[i + 1]) * t)
                    } else {
                        pts.add(line[i]); pts.add(line[i + 1])
                    }
                    if (s1 > hi) {
                        val t = (hi - s0) / segLen
                        pts.add(line[i] + (line[i + 2] - line[i]) * t)
                        pts.add(line[i + 1] + (line[i + 3] - line[i + 1]) * t)
                        break
                    } else {
                        pts.add(line[i + 2]); pts.add(line[i + 3])
                    }
                } else if (s0 > hi) {
                    break
                }
                along = s1
            }
            i += 2
        }
        // welded and blended lines carry the shared vertex at every join
        val dedup = ArrayList<Double>()
        var j = 0
        while (j + 1 < pts.size) {
            if (dedup.size < 2 ||
                Geodesy.distanceProjectedM(
                    dedup[dedup.size - 2], dedup[dedup.size - 1],
                    pts[j], pts[j + 1],
                ) > 1e-9
            ) {
                dedup.add(pts[j]); dedup.add(pts[j + 1])
            }
            j += 2
        }
        if (!forward) {
            var a = 0
            var bPt = dedup.size - 2
            while (a < bPt) {
                var t = dedup[a]; dedup[a] = dedup[bPt]; dedup[bPt] = t
                t = dedup[a + 1]; dedup[a + 1] = dedup[bPt + 1]; dedup[bPt + 1] = t
                a += 2; bPt -= 2
            }
        }
        return dedup.toDoubleArray()
    }

    fun summary(): String {
        val checks = check()
        val matched = feet.size
        val onLine = feet.count { it.offsetM <= 50.0 }
        val good = checks.count { it.callCount >= 2 && it.agreement >= 0.9 }
        return buildString {
            append("$matched of ${route.size} calls projected onto ${lines.size} lines")
            append("; $onLine within 50 m of a line")
            append("; ${checks.size} lines carry calls, $good of them in flying order")
        }
    }
}