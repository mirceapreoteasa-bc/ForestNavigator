package org.gcap.core.nav

/** One rejected A/B pair, and why. */
data class RejectedPair(val from: AirCall, val to: AirCall, val lengthM: Double)

/**
 * The line features a route is built from.
 *
 * The raw `flight_path` is mostly debris: measured on the real layer, **582 of its 1195
 * features are 50 m or shorter**, and there are exactly 582 air-call legs inside the
 * flight-line triples, so those short features are the A->bare and bare->B stubs. They
 * are what a nearest-end walk kept tripping over.
 *
 * Rather than navigate around them, they are replaced:
 *
 * 1. Features shorter than [minFeatureM] are **dropped**. What remains is the flight line
 *    proper - 421 features over 200 m on the real layer.
 * 2. One **connector** is synthesised for each A/B pair, and kept only if it is within
 *    [maxConnectorM]. This is the "new line between `001A` and `001B`" step, and it makes
 *    the trip through the triple explicit instead of leaving it to be guessed at from
 *    stubs.
 *
 * The two thresholds are separate on purpose. The first removes debris; the second is a
 * statement about the flight itself - a triple whose A and B calls are further apart than
 * the limit is one where a straight connector would not represent what was flown, so it
 * is reported rather than drawn.
 */
class FlightRouteLines private constructor(
    /** The surviving long features, in file order. */
    val longFeatures: List<DoubleArray>,
    /** One connector per accepted A/B pair, in flying order. */
    val connectors: List<DoubleArray>,
    /** A/B pairs rejected for being too far apart to connect. */
    val rejected: List<RejectedPair>,
    val droppedShortFeatures: Int,
) {
    /** Everything the chain may walk, long features and connectors together. */
    val all: List<DoubleArray> get() = longFeatures + connectors

    val connectorCount: Int get() = connectors.size

    fun summary(): String = buildString {
        append("${longFeatures.size} long features")
        append(", $droppedShortFeatures short features dropped")
        append(", $connectorCount A/B connectors")
        if (rejected.isNotEmpty()) append(", ${rejected.size} pairs rejected as too far apart")
    }

    companion object {
        /** Discard features shorter than this; the flight lines proper survive. */
        const val MIN_FEATURE_M = 200.0

        /**
         * Synthesised A/B connectors longer than this are reported instead of drawn.
         *
         * Raised from 200 m to 350 m after the walk stopped dead at `006A`: that triple's
         * A and B calls are 223 m apart, so a 200 m cap refused the connector and left the
         * chain with nothing to attach at the triple. Measured over all 291 pairs the
         * longest is 328 m, so 350 m accepts every one and nothing is reported.
         *
         * This is a different rule from [MIN_FEATURE_M], which is about discarding debris.
         */
        const val MAX_CONNECTOR_M = 350.0

        /** True ground length of a polyline, summed over its segments. */
        fun lengthM(line: DoubleArray): Double {
            var sum = 0.0
            var i = 0
            while (i + 3 < line.size) {
                sum += Geodesy.distanceProjectedM(line[i], line[i + 1], line[i + 2], line[i + 3])
                i += 2
            }
            return sum
        }

        /**
         * Split the air-call sequence into consecutive A/B pairs.
         *
         * Pairs are formed from the A call to the next call, so a triple missing its bare
         * call still pairs up, and a triple missing its B call does not pair with whatever
         * comes next.
         */
        fun pairsOf(calls: List<AirCall>): List<Pair<AirCall, AirCall>> {
            // Bare-numbered calls sit mid-segment and are not chain nodes, so they are
            // dropped before pairing; otherwise `001A` would be followed by the bare
            // `001` and never reach its own `001B`.
            val ab = calls.sortedWith(AirCallOrder).filter { it.suffix.isNotEmpty() }
            val out = ArrayList<Pair<AirCall, AirCall>>()
            for (i in 0 until ab.size - 1) {
                val a = ab[i]
                val b = ab[i + 1]
                if (a.suffix == "A" && b.suffix == "B" && b.order == a.order) out += a to b
            }
            return out
        }

        fun build(
            lines: List<DoubleArray>,
            calls: List<AirCall>,
            minFeatureM: Double = MIN_FEATURE_M,
            maxConnectorM: Double = MAX_CONNECTOR_M,
        ): FlightRouteLines {
            val kept = ArrayList<DoubleArray>()
            var dropped = 0
            for (l in lines) {
                if (l.size < 4) { dropped++; continue }
                if (lengthM(l) >= minFeatureM) kept.add(l) else dropped++
            }

            val connectors = ArrayList<DoubleArray>()
            val rejected = ArrayList<RejectedPair>()
            for ((a, b) in pairsOf(calls)) {
                val d = Geodesy.distanceProjectedM(a.e, a.n, b.e, b.n)
                if (d <= maxConnectorM) connectors.add(doubleArrayOf(a.e, a.n, b.e, b.n))
                else rejected.add(RejectedPair(a, b, d))
            }

            return FlightRouteLines(kept, connectors, rejected, dropped)
        }
    }
}