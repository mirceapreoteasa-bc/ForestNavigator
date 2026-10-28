package org.gcap.core.nav

import java.util.PriorityQueue
import kotlin.math.hypot

/** What a route minimises. */
enum class RouteMetric {
    /** Fastest arrival, using each edge class speed. */
    TIME,

    /** Shortest ground distance regardless of speed. */
    DISTANCE,
}

/** Where a request snapped onto the network. */
data class SnapResult(
    val edge: Int,
    val t: Double,
    val distanceM: Double,
    val pointE: Double,
    val pointN: Double,
)

/** A single continuous path along the network. */
data class Path(
    val nodes: IntArray,
    val edges: IntArray,
    val seconds: Double,
    val metres: Double,
    /** World position of the first snap point, when the route did not start on a node. */
    val startPointE: Double = Double.NaN,
    val startPointN: Double = Double.NaN,
    val endPointE: Double = Double.NaN,
    val endPointN: Double = Double.NaN,
) {
    val legCount: Int get() = edges.size
}

/** Result of routing between two world positions. */
sealed class RouteResult {
    data class Ok(
        val path: Path,
        val snapStart: SnapResult,
        val snapGoal: SnapResult,
        val startRegion: Int,
        val goalRegion: Int,
    ) : RouteResult()

    /** No route exists; [reason] is safe to show to the user. */
    data class Failed(val reason: String, val startRegion: Int = -1, val goalRegion: Int = -1) : RouteResult()
}

/** One leg of a multi-waypoint route, in the order the waypoints were given. */
data class RouteLeg(
    val legIndex: Int,
    val fromLabel: String,
    val toLabel: String,
    val result: RouteResult,
)

/** Result of routing through an ordered list of waypoints. */
sealed class MultiRouteResult {
    data class Ok(
        val legs: List<RouteLeg>,
        val totalSeconds: Double,
        val totalMetres: Double,
    ) : MultiRouteResult()

    data class FailedAtLeg(val leg: RouteLeg) : MultiRouteResult()
}

/**
 * Shortest-path routing over a [NetworkGraph].
 *
 * Requests are made in world coordinates, not node indices: a vehicle starts
 * somewhere in the middle of a road, not at a junction. Each end is snapped to
 * the closest point on any edge and the partial edge is costed, so a route does
 * not have to walk back to the nearest node first.
 *
 * A straight-line heuristic scaled by the fastest speed in the network is used, so
 * this is A* in practice; it degrades to Dijkstra when the heuristic is useless.
 */
class Router(val graph: NetworkGraph) {

    private val fastestMps: Double = graph.edges.maxOfOrNull { it.metresPerSecond } ?: 1.0

    fun nearestPoint(
        e: Double,
        n: Double,
        region: Int = -1,
        maxDistanceM: Double = 50.0,
    ): SnapResult? {
        val hit = graph.nearestOnNetwork(e, n, region, maxDistanceM) ?: return null
        val ed = graph.edges[hit.edge]
        val ax = graph.nodeE[ed.fromNode]; val ay = graph.nodeN[ed.fromNode]
        val bx = graph.nodeE[ed.toNode]; val by = graph.nodeN[ed.toNode]
        return SnapResult(
            hit.edge, hit.tAlongEdge, hit.distanceM,
            ax + (bx - ax) * hit.tAlongEdge, ay + (by - ay) * hit.tAlongEdge,
        )
    }

    /** Dijkstra/A* between two nodes. Returns null when unreachable. */
    /**
     * Shortest path, with an optional extra cost on individual edges.
     *
     * [edgeSurcharge] is added to the traversal cost of an edge, in metres of equivalent
     * distance. It exists so a caller can discourage re-using ground it has already
     * covered: a survey route should follow the lines the aircraft flew rather than take
     * the shortest way across them, and a surcharge on edges already used is the cheapest
     * way to say so without needing to know the intended sequence in advance.
     *
     * The heuristic stays admissible because surcharges only make edges dearer.
     */
    fun shortestPath(
        fromNode: Int,
        toNode: Int,
        metric: RouteMetric = RouteMetric.TIME,
        edgeSurcharge: ((edge: Int) -> Double)? = null,
    ): Path? {
        if (fromNode == toNode) return Path(intArrayOf(fromNode), IntArray(0), 0.0, 0.0)
        if (fromNode < 0 || toNode < 0 || fromNode >= graph.nodeCount || toNode >= graph.nodeCount) return null

        val g = DoubleArray(graph.nodeCount) { Double.POSITIVE_INFINITY }
        val prevNode = IntArray(graph.nodeCount) { -1 }
        val prevEdge = IntArray(graph.nodeCount) { -1 }
        g[fromNode] = 0.0

        val h = DoubleArray(graph.nodeCount) { Double.POSITIVE_INFINITY }
        val hx = graph.nodeE[toNode]
        val hy = graph.nodeN[toNode]
        for (i in 0 until graph.nodeCount) {
            h[i] = when (metric) {
                RouteMetric.DISTANCE -> hypot(graph.nodeE[i] - hx, graph.nodeN[i] - hy)
                RouteMetric.TIME -> hypot(graph.nodeE[i] - hx, graph.nodeN[i] - hy) / fastestMps
            }
        }

        data class Q(val f: Double, val node: Int)
        val open = PriorityQueue<Q>(compareBy { it.f })
        open.add(Q(h[fromNode], fromNode))
        val closed = BooleanArray(graph.nodeCount)

        while (open.isNotEmpty()) {
            val cur = open.poll() ?: break
            val u = cur.node
            if (closed[u]) continue
            closed[u] = true
            if (u == toNode) break
            for (arc in graph.adjacency[u]) {
                if (closed[arc.toNode]) continue
                val cost = when (metric) {
                    RouteMetric.TIME -> arc.seconds
                    RouteMetric.DISTANCE -> arc.lengthM
                } + (edgeSurcharge?.invoke(arc.edge) ?: 0.0)
                val tentative = g[u] + cost
                if (tentative < g[arc.toNode]) {
                    g[arc.toNode] = tentative
                    prevNode[arc.toNode] = u
                    prevEdge[arc.toNode] = arc.edge
                    open.add(Q(tentative + h[arc.toNode], arc.toNode))
                }
            }
        }
        if (g[toNode].isInfinite()) return null
        return rebuild(prevNode, prevEdge, fromNode, toNode, g[toNode])
    }

    private fun rebuild(
        prevNode: IntArray,
        prevEdge: IntArray,
        fromNode: Int,
        toNode: Int,
        cost: Double,
    ): Path {
        val nodes = ArrayDeque<Int>()
        val edges = ArrayDeque<Int>()
        var cur = toNode
        while (cur != fromNode && cur >= 0) {
            nodes.addFirst(cur)
            edges.addFirst(prevEdge[cur])
            cur = prevNode[cur]
        }
        nodes.addFirst(fromNode)
        val nodeArr = nodes.toIntArray()
        val edgeArr = edges.toIntArray()
        var metres = 0.0
        for (ei in edgeArr) if (ei >= 0) metres += graph.edges[ei].lengthM
        return Path(nodeArr, edgeArr, cost, metres)
    }

    /** Route between two world positions, snapping each end onto the network. */
    fun route(
        startE: Double,
        startN: Double,
        goalE: Double,
        goalN: Double,
        metric: RouteMetric = RouteMetric.TIME,
        region: Int = -1,
        maxSnapM: Double = 50.0,
        edgeSurcharge: ((edge: Int) -> Double)? = null,
    ): RouteResult {
        val s = nearestPoint(startE, startN, region, maxSnapM)
            ?: return RouteResult.Failed("No network within ${maxSnapM.toInt()} m of the start")
        val g = nearestPoint(goalE, goalN, region, maxSnapM)
            ?: return RouteResult.Failed("No network within ${maxSnapM.toInt()} m of the destination")

        val sRegion = graph.regionOfEdge[s.edge]
        val gRegion = graph.regionOfEdge[g.edge]

        // pick the pair of edge ends that minimises start-stub + core + end-stub
        var bestCore: Path? = null
        var bestTotal = Double.POSITIVE_INFINITY
        var bestSE = -1
        var bestGE = -1
        for (sEnd in listOf(graph.edges[s.edge].fromNode, graph.edges[s.edge].toNode)) {
            if (!stubAllowedFrom(s, sEnd)) continue
            for (gEnd in listOf(graph.edges[g.edge].fromNode, graph.edges[g.edge].toNode)) {
                if (!stubAllowedTo(g, gEnd)) continue
                val core = shortestPath(sEnd, gEnd, metric, edgeSurcharge) ?: continue
                val total = partialCost(s, sEnd, metric) + core.total(metric) + partialCost(g, gEnd, metric)
                if (total < bestTotal) {
                    bestTotal = total; bestCore = core; bestSE = sEnd; bestGE = gEnd
                }
            }
        }
        val core = bestCore ?: return RouteResult.Failed(
            "The start and destination are not connected by any road or track",
            sRegion, gRegion,
        )
        // fold the stub lengths into the reported totals and record the stub points
        val stubM = distanceAlong(s, bestSE) + distanceAlong(g, bestGE)
        val stubSec = stubSeconds(s, bestSE, metric) + stubSeconds(g, bestGE, metric)
        val chosen = core.copy(
            seconds = core.seconds + stubSec,
            metres = core.metres + stubM,
            startPointE = s.pointE, startPointN = s.pointN,
            endPointE = g.pointE, endPointN = g.pointN,
        )
        return RouteResult.Ok(chosen, s, g, sRegion, gRegion)
    }

    private fun Path.total(metric: RouteMetric): Double =
        if (metric == RouteMetric.TIME) seconds else metres

    private fun distanceAlong(snap: SnapResult, endNode: Int): Double {
        val ed = graph.edges[snap.edge]
        val frac = if (endNode == ed.fromNode) snap.t else (1.0 - snap.t)
        return ed.lengthM * frac
    }

    private fun stubSeconds(snap: SnapResult, endNode: Int, metric: RouteMetric): Double =
        if (metric == RouteMetric.TIME) distanceAlong(snap, endNode) / graph.edges[snap.edge].metresPerSecond else 0.0

    /**
     * One-way checks for the stub at the *start* of a route: the vehicle sits on the
     * snap point and travels along the edge to [endNode].
     */
    private fun stubAllowedFrom(snap: SnapResult, endNode: Int): Boolean {
        val ed = graph.edges[snap.edge]
        if (ed.oneway == OneWay.BOTH) return true
        if (distanceAlong(snap, endNode) < 1e-9) return true   // no travel along the edge
        val forward = endNode == ed.toNode
        return if (ed.oneway == OneWay.FORWARD) forward else !forward
    }

    /**
     * One-way check for the stub at the *destination*: the vehicle enters the edge at
     * [entryNode] and travels to the snap point, which runs the opposite way round to
     * the start stub.
     */
    private fun stubAllowedTo(snap: SnapResult, entryNode: Int): Boolean {
        val ed = graph.edges[snap.edge]
        if (ed.oneway == OneWay.BOTH) return true
        if (distanceAlong(snap, entryNode) < 1e-9) return true
        val forward = entryNode == ed.fromNode
        return if (ed.oneway == OneWay.FORWARD) forward else !forward
    }

    /** Cost from the snapped point to one end of the edge it lies on. */
    private fun partialCost(snap: SnapResult, endNode: Int, metric: RouteMetric): Double {
        val ed = graph.edges[snap.edge]
        val frac = if (endNode == ed.fromNode) snap.t else (1.0 - snap.t)
        val d = ed.lengthM * frac
        return if (metric == RouteMetric.TIME) d / ed.metresPerSecond else d
    }

    /**
     * Route through waypoints in the order given, one leg at a time.
     *
     * Order is never reordered: for helicopter navigation the sequence comes from the
     * air-call numbers and must be flown as given. Failure reports the offending leg
     * rather than silently dropping it.
     */
    fun routeThrough(
        startE: Double,
        startN: Double,
        waypoints: List<Pair<Double, Double>>,
        labels: List<String> = emptyList(),
        metric: RouteMetric = RouteMetric.TIME,
        maxSnapM: Double = 50.0,
    ): MultiRouteResult {
        val legs = ArrayList<RouteLeg>()
        var totalSeconds = 0.0
        var totalMetres = 0.0
        var curE = startE
        var curN = startN
        for ((i, wp) in waypoints.withIndex()) {
            val label = labels.getOrNull(i) ?: "WP ${i + 1}"
            val fromLabel = if (i == 0) "Start" else (labels.getOrNull(i - 1) ?: "WP $i")
            val res = route(curE, curN, wp.first, wp.second, metric, -1, maxSnapM)
            val leg = RouteLeg(i, fromLabel, label, res)
            if (res is RouteResult.Failed) return MultiRouteResult.FailedAtLeg(leg)
            legs.add(leg)
            totalSeconds += (res as RouteResult.Ok).path.seconds
            totalMetres += res.path.metres
            curE = wp.first; curN = wp.second
        }
        return MultiRouteResult.Ok(legs, totalSeconds, totalMetres)
    }

    /**
     * How far the current position has drifted from the route being followed, and
     * where along the route the nearest point lies. Used to warn before re-routing.
     */
    fun progressAlong(
        path: Path,
        e: Double,
        n: Double,
        withinRadiusM: Double = 50.0,
    ): RouteProgress? {
        val legs = buildLegs(path)
        var bestD = Double.POSITIVE_INFINITY
        var bestLeg = -1
        var bestT = 0.0
        for ((i, leg) in legs.withIndex()) {
            val r = Geodesy.pointSegmentDistance(e, n, leg[0], leg[1], leg[2], leg[3])
            if (r[0] < bestD) { bestD = r[0]; bestLeg = i; bestT = r[1] }
        }
        if (bestLeg < 0 || bestD > withinRadiusM) return null

        var remainingM = 0.0
        for (i in bestLeg until legs.size) {
            remainingM += hypot(legs[i][2] - legs[i][0], legs[i][3] - legs[i][1])
        }
        val legLen = hypot(legs[bestLeg][2] - legs[bestLeg][0], legs[bestLeg][3] - legs[bestLeg][1])
        return RouteProgress(bestLeg, bestT, bestD, remainingM - legLen * (1 - bestT))
    }

    /**
     * The path as concrete segments, including the leading and trailing snap stubs
     * which are not graph edges. Without these, a route that begins part way along a
     * road reads as "off route" for its first hundred metres.
     */
    private fun buildLegs(path: Path): List<DoubleArray> {
        val legs = ArrayList<DoubleArray>()
        val nodes = path.nodes
        if (nodes.isEmpty()) return legs
        if (!path.startPointE.isNaN()) {
            legs.add(doubleArrayOf(path.startPointE, path.startPointN, graph.nodeE[nodes[0]], graph.nodeN[nodes[0]]))
        }
        for (ei in path.edges) {
            if (ei < 0) continue
            val ed = graph.edges[ei]
            legs.add(
                doubleArrayOf(
                    graph.nodeE[ed.fromNode], graph.nodeN[ed.fromNode],
                    graph.nodeE[ed.toNode], graph.nodeN[ed.toNode],
                ),
            )
        }
        if (!path.endPointE.isNaN()) {
            val last = nodes[nodes.size - 1]
            legs.add(doubleArrayOf(graph.nodeE[last], graph.nodeN[last], path.endPointE, path.endPointN))
        }
        return legs
    }
}

data class RouteProgress(
    val legIndex: Int,
    val tAlongLeg: Double,
    val offRouteM: Double,
    val remainingM: Double,
)
