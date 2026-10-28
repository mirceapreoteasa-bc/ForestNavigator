package org.gcap.core.nav

/**
 * Builds the flight plan by walking the flight path under the crew's rules, rather than by
 * shortest-path routing.
 *
 * The rules, as given:
 *
 * 1. A loose end may connect **only to the next call in order**, never to whichever point
 *    happens to be closest.
 * 2. **A line end may be used once.** A connected end is spent.
 * 3. A feature attached at one end presents a **loose end** at its other, which then looks
 *    for the next call in order.
 * 4. Only if the next call is further than [nextCallReachM] does the loose end take the
 *    **closest unconnected end** of any other feature, and that is always a real feature
 *    **end** - never a point partway along a line, so no line is ever joined in the middle.
 *
 * [nextCallReachM] is deliberately separate from [weldM]. Reaching the next call in order
 * is the point of the walk, so it gets its own, larger tolerance; [weldM] only governs the
 * fallback hop to a neighbouring line end.
 *
 * This matters because shortest-path routing produces something that looks similar and is
 * not: A\* answers "what is the shortest way from call N to call N+1", and will cross to
 * any nearby feature because crossing is shorter than following the line. That silently
 * discards flown lines and connects calls the crew never intended to connect. The walk
 * below can only connect a call to the one after it.
 */
class FlightPlanWalk(
    private val flightPath: List<DoubleArray>,
    /** Air calls in flying order, A and B only. */
    calls: List<AirCall>,
    private val weldM: Double,
    /**
     * How far a loose end may be from the **next call in order** and still count as
     * reaching it. The crew set this at 275 m.
     */
    private val nextCallReachM: Double = 275.0,
    private val reachToleranceM: Double = 50.0,
    /** Drop path features shorter than this before walking. */
    private val minFeatureM: Double = 200.0,
) {
    /** The surviving features, each a polyline. Index into this list is the feature id. */
    val features: List<DoubleArray> = flightPath.filter {
        it.size >= 4 && FlightRouteLines.lengthM(it) >= minFeatureM
    }

    val route: List<AirCall> = calls.sortedWith(AirCallOrder).filter { it.suffix.isNotEmpty() }

    /** Which feature ends have been spent. Key `feature * 2 + (0 start, 1 end)`. */
    private val used = HashSet<Int>()

    /** Endpoints of features, flattened for quick lookup. */
    private val startE: DoubleArray
    private val startN: DoubleArray
    private val endE: DoubleArray
    private val endN: DoubleArray

    init {
        val n = features.size
        startE = DoubleArray(n); startN = DoubleArray(n)
        endE = DoubleArray(n); endN = DoubleArray(n)
        for (i in 0 until n) {
            startE[i] = features[i][0]; startN[i] = features[i][1]
            endE[i] = features[i][features[i].size - 2]
            endN[i] = features[i][features[i].size - 1]
        }
    }

    private fun key(f: Int, atStart: Boolean) = f * 2 + (if (atStart) 0 else 1)
    private fun free(f: Int, atStart: Boolean) = key(f, atStart) !in used
    private fun spend(f: Int, atStart: Boolean) { used.add(key(f, atStart)) }

    private fun distanceTo(f: Int, atStart: Boolean, e: Double, n: Double): Double =
        Geodesy.distanceProjectedM(
            e, n,
            if (atStart) startE[f] else endE[f],
            if (atStart) startN[f] else endN[f],
        )

    /**
     * One leg of the plan: the line walked between two consecutive calls.
     *
     * [geometry] is the flown line when the walk reached the call, and null when it could
     * not, in which case the caller should draw a straight line and say so.
     */
    data class WalkedLeg(
        val from: AirCall,
        val to: AirCall,
        val featureIds: List<Int>,
        val viaFallback: Int,
    ) {
        val reached: Boolean get() = featureIds.isNotEmpty() || viaFallback >= 0
    }

    /** Walk the whole plan, one leg per consecutive pair of calls. */
    fun walk(): List<WalkedLeg> {
        val out = ArrayList<WalkedLeg>()
        var looseF = -1
        var looseStart = false
        var haveLoose = false

        for (i in 0 until route.size - 1) {
            val from = route[i]
            val to = route[i + 1]
            val legIds = ArrayList<Int>()
            var fallbacks = -1

            // step 1: are we already at a loose end from the previous leg?
            if (!haveLoose) {
                val c = nearestFreeEnd(from.e, from.n, -1, false)
                if (c != null) {
                    spend(c.first, c.second)
                    looseF = c.first
                    looseStart = !c.second          // the far end becomes loose
                    legIds.add(looseF)
                    haveLoose = true
                } else {
                    // nothing free near this call: the leg cannot be walked
                    out += WalkedLeg(from, to, legIds, -1)
                    continue
                }
            }

            // step 2 and 3: from the loose end, the next call in order is the only target
            var guard = 0
            while (guard++ < features.size + 4) {
                // the only permitted target is the next call in order, at its own tolerance
                val dNext = Geodesy.distanceProjectedM(
                    if (looseStart) startE[looseF] else endE[looseF],
                    if (looseStart) startN[looseF] else endN[looseF],
                    to.e, to.n,
                )
                if (dNext <= nextCallReachM) {
                    // reached: the loose end is spent joining the call
                    spend(looseF, looseStart)
                    haveLoose = false
                    out += WalkedLeg(from, to, legIds, fallbacks)
                    break
                }
                // step 4: the next call is out of reach, so take the closest unconnected
                // end of another line. `features` holds only features at least
                // `minFeatureM` long, and only their two ends are ever considered, so this
                // can never join a line in the middle.
                val c = nearestFreeEnd(
                    if (looseStart) startE[looseF] else endE[looseF],
                    if (looseStart) startN[looseF] else endN[looseF],
                    looseF, looseStart,
                )
                if (c == null) {
                    // nowhere left to go: the leg could not be walked
                    out += WalkedLeg(from, to, legIds, -1)
                    haveLoose = false
                    break
                }
                if (fallbacks < 0) fallbacks = legIds.size
                spend(c.first, c.second)
                spend(looseF, looseStart)          // the end we came from is now spent
                looseF = c.first
                looseStart = !c.second
                legIds.add(looseF)
            }
            if (guard >= features.size + 4) {
                out += WalkedLeg(from, to, legIds, -1)
                haveLoose = false
            }
        }
        return out
    }

    /**
     * Closest unused feature end to (e, n), or null when there is none in range.
     *
     * [exclude] is the loose end being walked from, so a feature is never re-entered
     * through the end the walk arrived at.
     */
    private fun nearestFreeEnd(
        e: Double,
        n: Double,
        excludeF: Int,
        excludeStart: Boolean,
    ): Pair<Int, Boolean>? {
        var bestF = -1
        var bestStart = false
        var bestD = weldM
        for (f in features.indices) {
            for (atStart in booleanArrayOf(true, false)) {
                if (!free(f, atStart)) continue
                if (f == excludeF && atStart == excludeStart) continue
                val d = distanceTo(f, atStart, e, n)
                if (d <= bestD) { bestD = d; bestF = f; bestStart = atStart }
            }
        }
        return if (bestF < 0) null else Pair(bestF, bestStart)
    }

    /**
     * Features short enough to be excluded, and so never a fallback target.
     *
     * Reported so the plan can say how many lines were discarded as stubs rather than
     * leaving them silently absent.
     */
    val discardedFeatures: Int = flightPath.count { it.size >= 4 && FlightRouteLines.lengthM(it) < minFeatureM }

    /** Vertices of the walked chain, in order, or null when the walk failed. */
    fun geometryOf(leg: WalkedLeg): DoubleArray? {
        if (leg.featureIds.isEmpty()) return null
        val pts = ArrayList<DoubleArray>()
        pts.add(doubleArrayOf(leg.from.e, leg.from.n))
        for (f in leg.featureIds) {
            val line = features[f]
            var i = 0
            while (i + 3 < line.size) {
                pts.add(doubleArrayOf(line[i], line[i + 1]))
                i += 2
            }
            pts.add(doubleArrayOf(line[line.size - 2], line[line.size - 1]))
        }
        pts.add(doubleArrayOf(leg.to.e, leg.to.n))
        val out = DoubleArray(pts.size * 2)
        pts.forEachIndexed { i, p -> out[i * 2] = p[0]; out[i * 2 + 1] = p[1] }
        return out
    }
}