package org.gcap.core.nav

/** What a leg of the flight plan is for. */
enum class PlanLeg {
    /**
     * The short run inside one triple, `xxxA` to `xxxB`, roughly 100 m.
     *
     * This is an **observation transect**, not a transit: the helicopter flies it slowly
     * and often circles it two or three times to gather what it needs. The very short
     * features around each end mark where the transect begins and ends.
     */
    TRANSECT,

    /**
     * The run from one triple's B call to the next triple's A call, a median of 2.4 km.
     *
     * This is repositioning, and `flight_path` does **not** record most of it, so a plan
     * leg of this kind is often drawn as a plain straight line and flagged as having no
     * path geometry.
     */
    TRANSIT,
}

/**
 * One leg of the flight plan.
 *
 * [worstGapM] is the largest distance from the straight leg to the nearest flight-path
 * geometry, sampled along its length. It is the honest measure of whether the leg can be
 * drawn along the recorded path or has to be shown as a plain line, and a large value means
 * the path does not go that way.
 */
data class PlanSegment(
    val from: AirCall,
    val to: AirCall,
    val leg: PlanLeg,
    val distanceM: Double,
    val worstGapM: Double,
    /** True when some flight-path geometry runs alongside the whole leg. */
    val followsPath: Boolean,
    /**
     * The line to draw, as flat `e,n` pairs.
     *
     * This is the **flown** line where the recorded flight path can carry it, not a chord
     * between the two calls. It matters: the recorded lines follow smooth ascents and
     * descents that avoid high peaks and ridges, so a straight line between the calls
     * would cut across ground the pilot deliberately avoided.
     */
    val geometry: DoubleArray = doubleArrayOf(from.e, from.n, to.e, to.n),
    /** True when [geometry] came from the recorded path rather than falling back. */
    val traced: Boolean = false,
) {
    /** Vertices of [geometry]. */
    val vertexCount: Int get() = geometry.size / 2

    fun describe(): String {
        val kind = if (followsPath) "along path" else "no path geometry"
        return "${from.name}->${to.name} ${leg} ${"%.0f".format(distanceM)} m ($kind)"
    }
}

/**
 * The flight plan: every triple's transect, in flying order, and the transits between them.
 *
 * This is the thing worth showing on the map. The crew flies the transects and circles
 * them, which is disorienting, so having the plan drawn - the next point in route
 * highlighted - is what keeps a pilot and passengers oriented.
 *
 * The plan is built from the **air calls alone**, so it is complete even where the flight
 * path has no geometry: a transit with nothing alongside it is still drawn, and flagged,
 * rather than dropped. Legs are never omitted for being hard to connect, because omitting
 * one would quietly hide a leg of the day's work.
 */
class FlightPlan private constructor(
    val segments: List<PlanSegment>,
    /** How far from a leg the path geometry may lie and still count as following it. */
    val pathToleranceM: Double,
) {
    val transects: List<PlanSegment> get() = segments.filter { it.leg == PlanLeg.TRANSECT }
    val transits: List<PlanSegment> get() = segments.filter { it.leg == PlanLeg.TRANSIT }

    val tripleCount: Int get() = transects.size

    /** Transits with no flight-path geometry alongside: the path does not record them. */
    val transitsWithoutPath: Int get() = transits.count { !it.followsPath }

    /** The single leg that follows the path least well, for reporting. */
    fun worstLeg(): PlanSegment? = segments.maxByOrNull { it.worstGapM }

    fun summary(): String = buildString {
        append("$tripleCount transects")
        append(", ${transits.size} transits")
        append(", $transitsWithoutPath transits with no path geometry")
        worstLeg()?.let { append("; worst gap ${"%.0f".format(it.worstGapM)} m at ${it.from.name}->${it.to.name}") }
    }

    /** The transect the crew is on or heading for, given how far they have got. */
    fun transectAt(index: Int): PlanSegment? = transects.getOrNull(index)

    companion object {
        /**
         * A transect longer than this is not an observation run.
         *
         * The real layer's `A -> B` legs are a median of 186 m with a maximum of 328 m, so
         * this cannot be a tight cut-off; it separates a transect from the multi-kilometre
         * transit that follows it.
         */
        const val TRANSECT_MAX_M = 400.0

        /**
         * Build the plan from the air calls, optionally measuring each leg against the
         * flight path.
         */
        /**
         * Build the plan, tracing the recorded flight path for each leg where possible.
         *
         * The path is welded at [weldM] so the legs join up, and each leg is then routed
         * through that graph so the drawn line is the one actually flown. A leg that cannot
         * be routed falls back to a straight line and is marked [PlanSegment.traced] false,
         * because silently drawing a chord across a ridge would be worse than admitting the
         * gap.
         *
         * 500 m is defensible because the closest any two flight lines lie is 638 m, so a
         * weld that wide cannot merge two of them. Above roughly 600 m it can.
         */
        fun buildTraced(
            calls: List<AirCall>,
            flightPath: List<DoubleArray>,
            weldM: Double = 500.0,
            maxSnapM: Double = 200.0,
            pathToleranceM: Double = 100.0,
            /**
             * Equivalent metres charged for an edge an earlier leg already used.
             *
             * Plain shortest-path routing **takes shortcuts**: it will cross straight over
             * survey lines the aircraft actually flew, because crossing is shorter than
             * following them. That quietly drops features from the plan - measured, the
             * plain version covered 55-57% of the long features.
             *
             * Charging edges already covered makes the router prefer ground it has not used
             * yet, so the drawn plan follows the flown lines instead of skipping them. Zero
             * restores plain shortest path.
             */
            reuseSurchargeM: Double = 0.0,
        ): FlightPlan {
            val flat = build(calls, flightPath, pathToleranceM)
            if (flightPath.isEmpty()) return flat

            val graph = NetworkBuilder.forFlightPath(weldM).build(
                flightPath.map { SourceLine(it, FLIGHT_PATH_LAYER, RouteClass.AIR) },
            ).first
            val router = Router(graph)

            // edges already covered by an earlier leg, so the router can be told to
            // prefer ground it has not used yet
            val usedEdges = HashSet<Int>()

            val traced = flat.segments.map { seg ->
                val surcharge = if (reuseSurchargeM > 0.0) {
                    { edge: Int -> if (edge in usedEdges) reuseSurchargeM else 0.0 }
                } else {
                    null
                }
                when (val r = router.route(
                    seg.from.e, seg.from.n, seg.to.e, seg.to.n,
                    RouteMetric.DISTANCE, maxSnapM = maxSnapM,
                    edgeSurcharge = surcharge,
                )) {
                    is RouteResult.Ok -> {
                        val pts = ArrayList<DoubleArray>()
                        for (node in r.path.nodes) {
                            pts.add(doubleArrayOf(graph.nodeE[node], graph.nodeN[node]))
                        }
                        // keep the exact call positions at each end, so the drawn line starts
                        // and ends on the calls rather than on nearby path vertices
                        if (pts.isEmpty()) {
                            seg
                        } else {
                            val chain = ArrayList<DoubleArray>()
                            chain.add(doubleArrayOf(seg.from.e, seg.from.n))
                            chain.addAll(pts)
                            chain.add(doubleArrayOf(seg.to.e, seg.to.n))
                            for (edge in r.path.edges) usedEdges.add(edge)
                            seg.copy(geometry = flatten(chain), traced = true)
                        }
                    }
                    else -> seg
                }
            }
            return FlightPlan(traced, flat.pathToleranceM)
        }

        /** Flatten a run of points into one `e,n,e,n,...` array. */
        private fun flatten(points: List<DoubleArray>): DoubleArray {
            val out = DoubleArray(points.size * 2)
            points.forEachIndexed { i, p -> out[i * 2] = p[0]; out[i * 2 + 1] = p[1] }
            return out
        }

        const val FLIGHT_PATH_LAYER = "flight_path"

        /**
         * Build the plan by **walking** the flight path under the crew's rules, rather than
         * by shortest-path routing.
         *
         * Use this in preference to [buildTraced]. Routing answers "what is the shortest
         * way from call N to call N+1" and will cross to any nearby feature because that is
         * shorter than following the line, so flown features get dropped and calls end up
         * joined in ways the crew did not intend. The walk instead may only connect a call to
         * the one after it, and spends each line end once.
         */
        fun buildByRule(
            calls: List<AirCall>,
            flightPath: List<DoubleArray>,
            weldM: Double = 250.0,
            nextCallReachM: Double = 275.0,
            reachToleranceM: Double = 50.0,
            minFeatureM: Double = 200.0,
            pathToleranceM: Double = 100.0,
        ): FlightPlan {
            val flat = build(calls, flightPath, pathToleranceM)
            if (flightPath.isEmpty()) return flat
            val walk = FlightPlanWalk(
                flightPath, calls, weldM, nextCallReachM, reachToleranceM, minFeatureM,
            )
            val legs = walk.walk()

            val traced = flat.segments.mapIndexed { i, seg ->
                val leg = legs.getOrNull(i)
                val geom = leg?.let { walk.geometryOf(it) }
                if (geom == null) seg else seg.copy(geometry = geom, traced = true)
            }
            return FlightPlan(traced, flat.pathToleranceM)
        }

        /**
         * Build the plan from the **flown lines**: the recorded flight path assembled into
         * continuous lines and each leg drawn as the portion of that line between its two
         * calls.
         *
         * Use this in preference to [buildTraced] and [buildByRule]. Both of those route or
         * walk from one call to the next, which answers a question the survey never asked:
         * the flight path already *is* the line that was flown, so the plan's job is to cut
         * it at the calls, not to find a path between them. Measured on the real survey,
         * routing dropped features and walking invented shortcuts, while this carries the
         * whole recorded line through: every leg is a slice of it, transect ends untouched
         * and transit links bent into curves that leave and arrive along the transect's own
         * bearing.
         *
         * Legs whose calls do not both land on the same continuous line fall back to a
         * straight line and are flagged, exactly as [build] flags them - never omitted,
         * because omitting one would quietly hide a leg of the day's work.
         */
        fun buildFromFlownLines(
            calls: List<AirCall>,
            flightPath: List<DoubleArray>,
            minLineM: Double = 50.0,
            maxJoinM: Double = 1000.0,
            pathToleranceM: Double = 200.0,
        ): FlightPlan {
            if (flightPath.isEmpty()) return build(calls)
            val res = FlightLinePipeline.run(flightPath, calls, minLineM, maxJoinM)
            val seq = FlightLineSequence(res.lines, calls, maxOffsetM = pathToleranceM)

            val ab = seq.route
            val footOf = seq.feet.associateBy { it.call.name }
            val segs = ArrayList<PlanSegment>()
            for (i in 0 until ab.size - 1) {
                val a = ab[i]
                val b = ab[i + 1]
                val withinTriple = a.order == b.order
                if (!withinTriple && a.suffix != "B") continue      // only B -> next A
                if (withinTriple && !(a.suffix == "A" && b.suffix == "B")) continue
                val leg = if (withinTriple) PlanLeg.TRANSECT else PlanLeg.TRANSIT
                val fa = footOf[a.name]
                val fb = footOf[b.name]
                if (fa != null && fb != null && fa.lineIndex == fb.lineIndex) {
                    val geom = seq.slice(fa.lineIndex, fa.alongM, fb.alongM)
                    segs += PlanSegment(
                        from = a, to = b, leg = leg,
                        // the flown distance, not the chord: this is what was recorded
                        distanceM = FlightRouteLines.lengthM(geom),
                        // the leg's ends are this far off the flown line; the geometry
                        // between them *is* the path, so this is the whole gap
                        worstGapM = maxOf(fa.offsetM, fb.offsetM),
                        followsPath = true,
                        geometry = geom,
                        traced = true,
                    )
                } else {
                    val d = Geodesy.distanceProjectedM(a.e, a.n, b.e, b.n)
                    val gap = worstGapToPath(a, b, flightPath)
                    segs += PlanSegment(
                        from = a, to = b, leg = leg, distanceM = d,
                        worstGapM = gap,
                        followsPath = gap <= pathToleranceM,
                    )
                }
            }
            return FlightPlan(segs, pathToleranceM)
        }

        fun build(
            calls: List<AirCall>,
            flightPath: List<DoubleArray> = emptyList(),
            pathToleranceM: Double = 100.0,
            samplesPerLeg: Int = 11,
        ): FlightPlan {
            val ab = calls.sortedWith(AirCallOrder).filter { it.suffix.isNotEmpty() }
            val segs = ArrayList<PlanSegment>()
            for (i in 0 until ab.size - 1) {
                val a = ab[i]
                val b = ab[i + 1]
                val withinTriple = a.order == b.order
                if (!withinTriple && a.suffix != "B") continue      // only B -> next A
                if (withinTriple && !(a.suffix == "A" && b.suffix == "B")) continue
                val d = Geodesy.distanceProjectedM(a.e, a.n, b.e, b.n)
                val leg = if (withinTriple) PlanLeg.TRANSECT else PlanLeg.TRANSIT
                val gap = worstGapToPath(a, b, flightPath, samplesPerLeg)
                segs += PlanSegment(
                    from = a, to = b, leg = leg, distanceM = d,
                    worstGapM = gap, followsPath = flightPath.isNotEmpty() && gap <= pathToleranceM,
                )
            }
            return FlightPlan(segs, pathToleranceM)
        }

        /**
         * Largest distance from the straight `a`-`b` line to the nearest flight-path
         * geometry, sampled along its length.
         *
         * Zero means the path runs along the leg; a large value means there is nothing
         * there at all. Returns [Double.MAX_VALUE] when there is no path to compare with.
         */
        fun worstGapToPath(
            a: AirCall,
            b: AirCall,
            flightPath: List<DoubleArray>,
            samples: Int = 11,
        ): Double {
            if (flightPath.isEmpty()) return Double.MAX_VALUE
            var worst = 0.0
            for (i in 0 until samples) {
                val t = if (samples == 1) 0.0 else 0.05 + 0.90 * i / (samples - 1)
                val pe = a.e + (b.e - a.e) * t
                val pn = a.n + (b.n - a.n) * t
                var best = Double.POSITIVE_INFINITY
                for (line in flightPath) {
                    var k = 0
                    while (k + 3 < line.size) {
                        val d = Geodesy.pointSegmentDistance(
                            pe, pn, line[k], line[k + 1], line[k + 2], line[k + 3],
                        )[0]
                        if (d < best) best = d
                        k += 2
                    }
                }
                if (best > worst) worst = best
            }
            return worst
        }
    }
}