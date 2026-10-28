package org.gcap.core.nav

import kotlin.math.abs

/** One end of a line feature. */
data class LineEnd(val feature: Int, val atStart: Boolean) {
    override fun toString(): String = "${if (atStart) "start" else "end"} of #$feature"
}

/** What a step of the chain attached. */
enum class StepKind {
    /** A route point from the air-call sequence. */
    ROUTE_POINT,

    /** The loose end of a line feature, taken from an unused feature end. */
    LINE_END,
}

/**
 * One step of the built route.
 *
 * [viaFallback] marks a step taken because the next route point was out of range and the
 * chain had to keep going on line ends alone, which is the case worth reviewing after a
 * flight.
 */
data class ChainStep(
    val kind: StepKind,
    val featureIndex: Int = -1,
    val endAtStart: Boolean = false,
    val routePoint: AirCall? = null,
    val distanceM: Double = 0.0,
    val viaFallback: Boolean = false,
) {
    fun describe(): String = when (kind) {
        StepKind.ROUTE_POINT -> "route point ${routePoint?.name} (${"%.0f".format(distanceM)} m)"
        StepKind.LINE_END -> "line #$featureIndex ${if (endAtStart) "start" else "end"} (${"%.0f".format(distanceM)} m)"
    }
}

/**
 * The route built by walking the flight path, and how it went.
 *
 * [featuresInOrder] is the chain of line features to fly, in order. [unreached] lists the
 * route points never reached, so a chain that quietly stopped early is visible rather than
 * looking like a complete route.
 */
data class RouteChain(
    val steps: List<ChainStep>,
    val featuresInOrder: List<Int>,
    val unreached: List<AirCall>,
    val stopReason: String?,
) {
    val reachedEverything: Boolean get() = unreached.isEmpty()
    val fallbackCount: Int get() = steps.count { it.viaFallback }

    fun summary(): String = buildString {
        append("${featuresInOrder.size} line features in order")
        append(", ${steps.size} steps")
        if (fallbackCount > 0) append(", $fallbackCount by fallback")
        if (unreached.isNotEmpty()) append(", ${unreached.size} route points unreached")
        stopReason?.let { append("; stopped: $it") }
    }
}

/**
 * Builds a flyable route by walking the flight path from the first air call.
 *
 * The line features are short and only loosely joined, so rather than weld everything
 * within a tolerance - which merges lines that merely pass near each other - the route is
 * built as a **chain**, one step at a time, taking exactly one connection per step.
 *
 * The walk keeps one *loose end*: the far end of the feature just attached, which is not
 * yet connected to anything. From there it prefers to reach the **next route point** in
 * the air-call sequence, and only falls back to another unconnected line end when that
 * point is out of range. Both moves are capped at [weldMaxM].
 *
 * **Each feature end may be used once.** A line end that has been welded into the route is
 * spent, which is what stops two stretches of the walk from joining at the same junction
 * and quietly building a loop.
 *
 * Bare-numbered calls are not part of this chain: they sit in the middle of a segment
 * rather than at a junction, so the chain runs over the A and B calls only. Use
 * [FlightNetwork.abRoute] to get that sequence.
 *
 * This is deliberately greedy and local. It reports where it had to fall back and where it
 * stopped instead of claiming the route is complete, because a route that quietly misses
 * air calls is the dangerous failure here.
 */
/** How to choose between several line ends that are all within the weld distance. */
enum class EndPreference {
    /**
     * Take the closest unused end, which is the rule as first described.
     *
     * Measured on the real layer this walks into a tangle of short stubs: at `001B` the
     * nearest end is 2.6 m away on a feature only 35 m long, while the flight line the
     * crew actually flies is 180 m away and 1589 m long. Nearest-first therefore stalls
     * after a handful of features.
     */
    NEAREST,

    /**
     * Take the end that best carries the flight on.
     *
     * Two situations, because the right answer differs:
     *
     * - **At a loose end**, prefer the candidate whose feature leaves that end pointing most
     *   nearly the way the aircraft is already travelling.
     * - **At a route point**, with no heading to work from, prefer the candidate whose
     *   *far* end leads onward toward the next call in the sequence. This is what "connect
     *   `001A` through the short segment to `001B`" actually asks for: nearest-first picks
     *   whatever stub happens to lie within range, not the segment that goes where the
     *   flight is going.
     *
     * Distance breaks ties in both cases.
     */
    CONTINUE_LINE,
}

class RouteChainBuilder(
    private val lines: List<DoubleArray>,
    routePoints: List<AirCall>,
    /** Largest gap the walk will bridge, for either a route point or another line end. */
    val weldMaxM: Double = 200.0,
    private val preference: EndPreference = EndPreference.NEAREST,
) {
    /** Route points in flying order, bare-numbered calls removed. */
    val route: List<AirCall> = routePoints.sortedWith(AirCallOrder).filter { it.suffix.isNotEmpty() }

    /** Whether each feature end has been spent. Index is `feature * 2 + (0 start, 1 end)`. */
    private val used = BooleanArray(lines.size * 2)

    private fun idx(feature: Int, atStart: Boolean) = feature * 2 + (if (atStart) 0 else 1)

    private fun eOf(feature: Int, i: Int) = lines[feature][i]
    private fun nOf(feature: Int, i: Int) = lines[feature][i + 1]

    private fun endE(feature: Int, atStart: Boolean) =
        if (atStart) eOf(feature, 0) else eOf(feature, lastIndex(feature))

    /** Northing of the last vertex is `points[size - 1]`, not one past the vertex index. */
    private fun endN(feature: Int, atStart: Boolean) =
        if (atStart) nOf(feature, 0) else nOf(feature, lastIndex(feature))

    private fun lastIndex(feature: Int) = lines[feature].size - 2

    private fun spend(feature: Int, atStart: Boolean) {
        used[idx(feature, atStart)] = true
    }

    private fun isFree(feature: Int, atStart: Boolean) = !used[idx(feature, atStart)]

    fun build(): RouteChain {
        val steps = ArrayList<ChainStep>()
        val order = ArrayList<Int>()
        val reached = ArrayList<AirCall>()
        val unreached = ArrayList<AirCall>()

        if (lines.isEmpty() || route.isEmpty()) {
            return RouteChain(emptyList(), emptyList(), route, "no flight path or no air calls")
        }

        // Start at the first route point, then attach it to a line end.
        var nextRoute = 0
        var looseFeature = -1
        var looseAtStart = false
        var haveLooseEnd = false
        var stop: String? = null
        // Direction of travel on arriving at the loose end, so CONTINUE_LINE can tell
        // which candidate keeps the aircraft going the same way.
        var headingE = 0.0
        var headingN = 0.0
        var haveHeading = false

        while (true) {
            if (!haveLooseEnd) {
                // Every route point reached and nothing left to attach: the route is done.
                if (nextRoute >= route.size) break
                // Losing the loose end part-way through means the chain is broken.
                //
                // It is tempting to look for a line end near the *next route point* to
                // carry on, but that would let the walk teleport to a disconnected piece
                // of the flight path and report a route it never actually joined. The
                // fallback belongs at the loose end, so an exhausted chain stops here and
                // says where it ended.
                if (nextRoute > 0) {
                    stop = "chain broken after ${route[nextRoute - 1].name}: " +
                        "no unused line end within ${weldMaxM.toInt()} m to carry on to ${route[nextRoute].name}"
                    break
                }
                // At the first route point: find an unused line end to continue from,
                // preferring one that carries the flight onward to the next call.
                val rp = route[nextRoute]
                val cand = nearestFreeEnd(rp.e, rp.n, null, lookAheadTo = route.getOrNull(nextRoute + 1))
                if (cand == null) {
                    stop = "no unused line end within ${weldMaxM.toInt()} m of ${rp.name}"
                    break
                }
                steps += ChainStep(
                    kind = StepKind.ROUTE_POINT,
                    routePoint = rp,
                    distanceM = cand.second,
                )
                reached += rp
                spend(cand.first.feature, cand.first.atStart)
                looseFeature = cand.first.feature
                looseAtStart = !cand.first.atStart   // the far end becomes the loose end
                order += looseFeature
                haveLooseEnd = true
                nextRoute++
                headingE = endE(looseFeature, looseAtStart) - endE(looseFeature, !looseAtStart)
                headingN = endN(looseFeature, looseAtStart) - endN(looseFeature, !looseAtStart)
                haveHeading = true
                continue
            }

            // At a loose end. Prefer the next route point.
            if (nextRoute < route.size) {
                val rp = route[nextRoute]
                val d = Geodesy.distanceProjectedM(
                    endE(looseFeature, looseAtStart), endN(looseFeature, looseAtStart), rp.e, rp.n,
                )
                if (d <= weldMaxM) {
                    steps += ChainStep(StepKind.ROUTE_POINT, routePoint = rp, distanceM = d)
                    reached += rp
                    nextRoute++
                    // the loose end we reached the route point from is now spent
                    spend(looseFeature, looseAtStart)
                    // The route point sits mid-walk; look for a line end to carry on from.
                    val cand = nearestFreeEnd(
                        rp.e, rp.n, null, headingE, headingN, haveHeading,
                        lookAheadTo = route.getOrNull(nextRoute),
                    )
                    if (cand == null) {
                        haveLooseEnd = false   // wait at this route point for another try
                        continue
                    }
                    spend(cand.first.feature, cand.first.atStart)
                    steps += ChainStep(StepKind.LINE_END, featureIndex = cand.first.feature,
                        endAtStart = cand.first.atStart, distanceM = cand.second)
                    looseFeature = cand.first.feature
                    looseAtStart = !cand.first.atStart
                    order += looseFeature
                    headingE = endE(looseFeature, looseAtStart) - endE(looseFeature, !looseAtStart)
                    headingN = endN(looseFeature, looseAtStart) - endN(looseFeature, !looseAtStart)
                    haveHeading = true
                    continue
                }
            }

            // No route point in range: fall back to the closest unused line end.
            val lx = endE(looseFeature, looseAtStart)
            val ly = endN(looseFeature, looseAtStart)
            val cand = nearestFreeEnd(lx, ly, looseFeature to looseAtStart, headingE, headingN, haveHeading)
            if (cand == null) {
                val near = if (nextRoute < route.size) {
                    val rp = route[nextRoute]
                    val d = Geodesy.distanceProjectedM(lx, ly, rp.e, rp.n)
                    "next call ${rp.name} is ${"%.0f".format(d)} m away"
                } else {
                    "no further calls"
                }
                stop = "loose end of #$looseFeature: $near, and no unused line end within " +
                    "${weldMaxM.toInt()} m to fall back to"
                break
            }
            spend(cand.first.feature, cand.first.atStart)
            steps += ChainStep(
                StepKind.LINE_END, featureIndex = cand.first.feature,
                endAtStart = cand.first.atStart, distanceM = cand.second, viaFallback = true,
            )
            // release the loose end we came from, so it is free to be used elsewhere
            spend(looseFeature, looseAtStart)
            looseFeature = cand.first.feature
            looseAtStart = !cand.first.atStart
            order += looseFeature
            headingE = endE(looseFeature, looseAtStart) - endE(looseFeature, !looseAtStart)
            headingN = endN(looseFeature, looseAtStart) - endN(looseFeature, !looseAtStart)
            haveHeading = true
        }

        for (i in reached.size until route.size) unreached += route[i]
        // every route point up to nextRoute was reached
        return RouteChain(steps, order, unreached, stop)
    }

    /**
     * Closest unused line end to (e, n) **within [weldMaxM]**, or null when there is none.
     *
     * The distance limit is essential rather than incidental: without it the walk happily
     * attaches a feature arbitrarily far away, reports a route it never actually joined,
     * and never falls back.
     *
     * [exclude] is the loose end we are standing on, so a feature cannot be re-entered
     * through the end we arrived at.
     */
    private fun nearestFreeEnd(
        e: Double,
        n: Double,
        exclude: Pair<Int, Boolean>?,
        headingE: Double = 0.0,
        headingN: Double = 0.0,
        haveHeading: Boolean = false,
        lookAheadTo: AirCall? = null,
    ): Pair<LineEnd, Double>? {
        var best: LineEnd? = null
        var bestD = weldMaxM
        var bestScore = Double.POSITIVE_INFINITY
        val continueLine = preference == EndPreference.CONTINUE_LINE
        val useHeading = continueLine && haveHeading
        // at a route point there is no heading, so look at where the candidate leads instead
        val useLookAhead = continueLine && !haveHeading && lookAheadTo != null
        val hLen = hypot(headingE, headingN)

        for (f in lines.indices) {
            if (lines[f].size < 4) continue
            for (atStart in booleanArrayOf(true, false)) {
                if (!isFree(f, atStart)) continue
                if (exclude != null && exclude.first == f && exclude.second == atStart) continue
                val d = Geodesy.distanceProjectedM(e, n, endE(f, atStart), endN(f, atStart))
                if (d > weldMaxM) continue

                if (useLookAhead) {
                    val onward = Geodesy.distanceProjectedM(
                        endE(f, !atStart), endN(f, !atStart), lookAheadTo!!.e, lookAheadTo.n,
                    )
                    val score = onward / 1000.0 + d / weldMaxM
                    if (best == null || score < bestScore) {
                        bestScore = score
                        bestD = d
                        best = LineEnd(f, atStart)
                    }
                } else if (useHeading) {
                    // entering at this end means travelling towards the feature's far end
                    val travelE = endE(f, !atStart) - endE(f, atStart)
                    val travelN = endN(f, !atStart) - endN(f, atStart)
                    val tLen = hypot(travelE, travelN)
                    if (tLen <= 0.0) continue
                    val cos = (travelE * headingE + travelN * headingN) / (tLen * hLen)
                    // 0 straight ahead, 1 doubling back; distance only breaks ties
                    val score = (1.0 - cos) * 1000.0 + d / weldMaxM
                    if (best == null || score < bestScore) {
                        bestScore = score
                        bestD = d
                        best = LineEnd(f, atStart)
                    }
                } else if (d <= bestD) {
                    bestD = d
                    best = LineEnd(f, atStart)
                }
            }
        }
        return best?.let { it to bestD }
    }

    private fun hypot(a: Double, b: Double) = kotlin.math.sqrt(a * a + b * b)
}