package org.gcap.core.nav

/**
 * Turns a routed [Path] into geometry the map can draw, and into guidance for the user.
 *
 * Kept apart from the router so it can be tested without a map: the failure that matters
 * here is a route line that is subtly the wrong shape, which looks like a map bug rather
 * than a routing one and is very hard to spot on a phone.
 */
object RouteGeometry {

    /**
     * The route as flat `e,n,e,n...` in the working zone.
     *
     * Starts at the point where the crew joined the network and ends where it leaves for
     * the destination, so the line drawn is the line actually travelled, not the line
     * between junctions. Where the path is empty - already at the destination - the two
     * ends are joined by a single straight segment.
     */
    fun polyline(graph: NetworkGraph, path: Path): DoubleArray {
        val out = ArrayList<Double>()
        fun add(e: Double, n: Double) {
            if (out.size >= 2 && out[out.size - 2] == e && out[out.size - 1] == n) return
            out.add(e); out.add(n)
        }

        val startE = if (path.startPointE.isNaN()) {
            path.nodes.firstOrNull()?.let { graph.nodeE[it] }
        } else path.startPointE
        val startN = if (path.startPointN.isNaN()) {
            path.nodes.firstOrNull()?.let { graph.nodeN[it] }
        } else path.startPointN
        if (startE != null && startN != null) add(startE, startN)

        for (i in path.nodes.indices) {
            val nd = path.nodes[i]
            if (nd < 0 || nd >= graph.nodeCount) continue
            add(graph.nodeE[nd], graph.nodeN[nd])
        }

        if (!path.endPointE.isNaN()) add(path.endPointE, path.endPointN)
        return out.toDoubleArray()
    }

    /**
     * The next node to steer at, from [fromNode], walking [path] forwards.
     *
     * Returns -1 when the path has run out. A crew on the ground needs the *next* junction,
     * not the whole route, and a leg that ends here has no next node.
     */
    fun nextNodeAfter(path: Path, fromNode: Int): Int {
        val i = path.nodes.indexOf(fromNode)
        if (i < 0) return -1
        return path.nodes.getOrNull(i + 1) ?: -1
    }

    /**
     * Metres still to drive from (e, n) to the end of [points].
     *
     * Measured forward from the point *nearest* the position, not from the start of the
     * line. Walking the whole polyline instead would add the ground already covered, so
     * the figure would grow as the vehicle drove rather than shrink.
     *
     * Everything here is plain arithmetic on the numbers given, so it silently produces
     * a huge, plausible figure if the route is in UTM metres and the position is in
     * degrees - which is exactly the mixed-CRS case, and read about 5475 km for a trip
     * of 7 km. A result past [MAX_PLAUSIBLE_M] is refused rather than shown, because a
     * wrong number that looks like a distance is worse than no number.
     */
    fun remainingM(points: DoubleArray, e: Double, n: Double): Double {
        if (points.size < 2) return 0.0
        // nearest vertex on the line, and its index
        var bestIdx = 0
        var bestD = Double.MAX_VALUE
        var i = 0
        while (i + 1 < points.size) {
            val d = kotlin.math.hypot(points[i] - e, points[i + 1] - n)
            if (d < bestD) { bestD = d; bestIdx = i }
            i += 2
        }
        // The position is nowhere near the line it is supposed to be driving. Either the
        // two are in different coordinate systems, or the fix has not been resolved yet.
        if (bestD > MAX_PLAUSIBLE_M) return Double.NaN
        var total = 0.0
        var px = e; var py = n
        var k = bestIdx
        while (k + 1 < points.size) {
            val x = points[k]; val y = points[k + 1]
            total += kotlin.math.hypot(x - px, y - py)
            px = x; py = y
            k += 2
        }
        return if (total > MAX_PLAUSIBLE_M) Double.NaN else total
    }

    /** Compass bearing in degrees from (e1, n1) to (e2, n2), north-clockwise. */
    fun bearing(e1: Double, n1: Double, e2: Double, n2: Double): Double {
        val dE = e2 - e1
        val dN = n2 - n1
        if (dE == 0.0 && dN == 0.0) return 0.0
        var deg = Math.toDegrees(kotlin.math.atan2(dE, dN))
        if (deg < 0) deg += 360.0
        return deg
    }

    /** Compass bearing to the next node, or -1 when there is none. */
    fun bearingToNextNode(graph: NetworkGraph, path: Path, fromNode: Int): Double {
        val nxt = nextNodeAfter(path, fromNode)
        if (nxt < 0 || nxt >= graph.nodeCount) return -1.0
        return bearing(graph.nodeE[fromNode], graph.nodeN[fromNode], graph.nodeE[nxt], graph.nodeN[nxt])
    }

    /**
     * Furthest a road trip can plausibly be, in metres. Well past any single day's
     * drive, so it never fires on a real route; only on a coordinate-system mix-up.
     */
    const val MAX_PLAUSIBLE_M = 2_000_000.0
}