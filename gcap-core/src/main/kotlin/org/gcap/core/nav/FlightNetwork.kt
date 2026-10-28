package org.gcap.core.nav

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * An air call: a named point the crew has to reach.
 *
 * [order] is the number in the name and [suffixRank] ranks the letter after it. Both
 * are needed because the convention is
 *
 * ```
 * 101A, 101, 101B, 102A, 102, 102B, 103A, 103, 103B, ...
 * ```
 *
 * The three calls sharing 101 differ only by their suffix, and they are **not** in
 * text order: sorting as strings puts the bare `101` before `101A`. Nor is it a simple
 * alphabetical rank, because the bare name sits between A and B. So the suffix gets an
 * explicit order - A first, then no suffix, then B.
 */
data class AirCall(val name: String, val e: Double, val n: Double) {
    /** The number in the name; [Int.MAX_VALUE] when there is none. */
    val order: Int = numericPartOf(name)

    /** The letters following the number, e.g. `"A"` in `101A`, `""` in `101`. */
    val suffix: String = suffixOf(name)

    /** Where this suffix falls: A, then bare, then B, then anything else. */
    val suffixRank: Int = rankOf(suffix)

    companion object {
        private val DIGITS = Regex("\\d+")

        /**
         * The first run of digits in [name] as a number.
         *
         * `002A` -> 2, `air_call 101` -> 101, `217A` -> 217. Names with no digits sort
         * after numbered ones.
         */
        fun numericPartOf(name: String): Int =
            DIGITS.find(name)?.value?.toIntOrNull() ?: Int.MAX_VALUE

        /** Whatever follows the first run of digits, upper-cased. */
        fun suffixOf(name: String): String {
            val m = DIGITS.find(name) ?: return name.uppercase()
            return name.substring(m.range.last + 1).uppercase()
        }

        /**
         * Rank a suffix. A first, then no suffix at all, then B.
         *
         * Anything else sorts after those in alphabetical order, so an unexpected
         * suffix still gives a stable, explainable sequence instead of scrambling it.
         */
        fun rankOf(suffix: String): Int = when (suffix.uppercase()) {
            "A" -> 0
            "" -> 1
            "B" -> 2
            else -> 3 + (suffix.uppercase().firstOrNull()?.code ?: 0)
        }
    }
}

/** Sorts air calls into flying order: number, then suffix (A, bare, B), then name. */
object AirCallOrder : Comparator<AirCall> {
    override fun compare(a: AirCall, b: AirCall): Int {
        val byNumber = a.order.compareTo(b.order)
        if (byNumber != 0) return byNumber
        val bySuffix = a.suffixRank.compareTo(b.suffixRank)
        if (bySuffix != 0) return bySuffix
        return a.name.compareTo(b.name)
    }
}

/**
 * One flight's worth of calls, in the order the crew works them.
 *
 * Split on a **large** positional gap between consecutive calls, never on the welded
 * graph. The graph is only a few tens of metres accurate in places and fragments
 * readily, so using it to decide the sequence would invent breaks that are not there.
 * Measured on the real layer, the gaps between consecutive calls are bimodal: about
 * 2-6 km as the aircraft repositions to start another line, then occasional jumps of
 * 42 km and 58 km between genuinely separate flights.
 */
data class FlightRun(
    val calls: List<AirCall>,
    val startGapM: Double,
) {
    /** Whether this run began after a break big enough to be a different flight. */
    val brokeAfterPrevious: Boolean get() = startGapM > FlightNetwork.FLIGHT_BREAK_M

    val first: AirCall get() = calls.first()
    val last: AirCall get() = calls.last()

    /** The call to fly to after [call], or null at the end of this run. */
    fun nextAfter(call: AirCall): AirCall? {
        val i = calls.indexOfFirst { it.name == call.name }
        if (i < 0) return null
        return calls.getOrNull(i + 1)
    }

    fun indexOf(call: AirCall): Int = calls.indexOfFirst { it.name == call.name }
}

/** Where a call sits on the welded flight path. */
data class CallPlacement(val call: AirCall, val region: Int, val offsetM: Double, val onPath: Boolean)

/**
 * Flight path welded into flyable routes, with the air calls sequenced along it.
 *
 * Two things happen, and they are deliberately separate:
 *
 * 1. The flight path is welded into continuous routes at
 *    [NetworkBuilder.FLIGHT_PATH_WELD_M], so hundreds of short segments become the
 *    handful of lines a crew actually flies. Connected components of that graph are
 *    the routes.
 * 2. The air calls are ordered by their own number and then grouped onto those routes.
 *
 * A change of component starts a new run. That is the only thing that splits the work,
 * and deliberately so: consecutive calls routinely sit 5-9 km apart as the aircraft
 * returns to begin another line, and splitting on distance would tear a single flight
 * into pieces. Only a genuine disconnection separates flights, and the welded graph
 * knows the difference.
 */
class FlightNetwork(
    flightPath: List<DoubleArray>,
    calls: List<AirCall>,
    weldM: Double = NetworkBuilder.FLIGHT_PATH_WELD_M,
    maxSnapM: Double = 150.0,
) {
    private val sources: List<SourceLine> = flightPath.map { pts ->
        SourceLine(pts, FLIGHT_PATH, RouteClass.AIR)
    }

    private val snapM: Double = maxSnapM

    /** How far a call may sit from the flight path and still count as on it. */
    val maxSnapM: Double get() = snapM

    /** The welded flight path, null only when there is no path at all. */
    val graph: NetworkGraph? =
        if (sources.isEmpty()) null else NetworkBuilder.forFlightPath(weldM).build(sources).first

    /** What the weld did, for the Verify-style report. */
    val report: BuildReport? =
        if (sources.isEmpty()) null else NetworkBuilder.forFlightPath(weldM).build(sources).second

    /** Calls in flying order. */
    val orderedCalls: List<AirCall> = calls.sortedWith(AirCallOrder)

    /** Each call in flying order, with where it sits on the path. */
    val placements: List<CallPlacement> = placeCalls()

    /** The flights, in order. */
    val runs: List<FlightRun> = groupRuns()

    /**
     * The air-call sequence the ROUTE chain is built over: the A and B calls only.
     *
     * Bare-numbered calls are excluded because they sit in the middle of a flight-line
     * segment rather than at a junction, so the chain passes over them instead of
     * stopping. That is the sequence the walk in [RouteChainBuilder] consumes.
     */
    val abRoute: List<AirCall> get() = orderedCalls.filter { it.suffix.isNotEmpty() }

    /** Calls too far from the flight path to be worked from it. */
    val unplacedCalls: List<AirCall> get() = placements.filter { !it.onPath }.map { it.call }

    private fun placeCalls(): List<CallPlacement> {
        val g = graph ?: return orderedCalls.map { CallPlacement(it, -1, Double.POSITIVE_INFINITY, false) }
        return orderedCalls.map { c ->
            val hit = g.nearestOnNetwork(c.e, c.n, maxDistanceM = snapM)
            if (hit == null) CallPlacement(c, -1, Double.POSITIVE_INFINITY, false)
            else CallPlacement(c, g.regionOfEdge[hit.edge], hit.distanceM, true)
        }
    }

    /**
     * Group the ordered calls into flights, breaking on a gap large enough to mean a
     * different flight.
     *
     * Every call takes part, whether or not it sits on the flight path: the crew still
     * has to reach it, so dropping the unplaced ones from the sequence would silently
     * skip work.
     */
    private fun groupRuns(): List<FlightRun> {
        val out = ArrayList<FlightRun>()
        var current = ArrayList<AirCall>()
        var startGap = 0.0            // gap that opened the run being built
        for (c in orderedCalls) {
            if (current.isNotEmpty()) {
                val gap = Geodesy.distanceProjectedM(current.last().e, current.last().n, c.e, c.n)
                if (gap > FLIGHT_BREAK_M) {
                    out += FlightRun(current, startGap)
                    current = ArrayList()
                    startGap = gap     // this gap is what opened the *next* run
                }
            }
            current.add(c)
        }
        if (current.isNotEmpty()) out += FlightRun(current, startGap)
        return out
    }

    val isEmpty: Boolean get() = graph == null || orderedCalls.isEmpty()

    /** Total welded flight-path length, in metres. */
    fun pathLengthM(): Double =
        graph?.let { g ->
            var sum = 0.0
            for (e in g.edges) sum += Geodesy.distanceProjectedM(g.nodeE[e.fromNode], g.nodeN[e.fromNode], g.nodeE[e.toNode], g.nodeN[e.toNode])
            sum
        } ?: 0.0

    /** The run containing [call], or null when it is not on the path. */
    fun runOf(call: AirCall): FlightRun? = runs.firstOrNull { it.indexOf(call) >= 0 }

    /**
     * What to fly to after reaching [call]: the next call, by name order.
     *
     * This is the resume case. If the crew stops after `101` and starts again they are
     * told `102`. If `101` ended a flight there is nothing next and the app says so
     * rather than inventing a target.
     */
    fun nextAfter(call: AirCall): AirCall? = runOf(call)?.nextAfter(call)

    /** How many calls lie after [call] in the same run. */
    fun callsRemainingAfter(call: AirCall): Int {
        val run = runOf(call) ?: return 0
        val i = run.indexOf(call)
        return if (i < 0) 0 else run.calls.size - i - 1
    }

    companion object {
        const val FLIGHT_PATH = "flight_path"

        /**
         * Gap between two calls that means a different flight, not a repositioning leg.
         *
         * Chosen from the real layer, where consecutive calls sit 2-6 km apart while
         * the aircraft repositions, and the jumps between actual flights are 42.7 km
         * and 57.7 km. 20 km sits in the empty gap between the two populations, so it
         * does not depend on a knife-edge threshold.
         */
        const val FLIGHT_BREAK_M = 20_000.0

        /**
         * How far a call may sit from the flight path and still count as on it.
         *
         * Measured on the real layer: every one of the 291 calls lies within 10 m, so
         * 150 m is generous. It exists to absorb survey drift, not to rescue a call
         * from the wrong flight.
         */
        const val CALL_SNAP_M = 150.0
    }
}
