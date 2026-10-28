package org.gcap.core.nav

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Travel class of a network edge, with a default speed used unless overridden. */
enum class RouteClass(val defaultSpeedKph: Double) {
    ROAD(40.0),
    ACCESS(15.0),
    TRAIL(8.0),
    BIKE(12.0),
    AIR(120.0),
    ;
}

/** One-way restriction on an edge. */
object OneWay {
    const val BOTH = 0
    const val FORWARD = 1
    const val TO = 2
}

/** A noded, routable segment between two graph nodes. */
data class NetworkEdge(
    val id: Int,
    val fromNode: Int,
    val toNode: Int,
    val lengthM: Double,
    val cls: RouteClass,
    val speedKph: Double,
    val oneway: Int,
    val sourceLayer: String,
    /** Higher wins when several geometries are offered for the same corridor. */
    val confidence: Int = 0,
) {
    val metresPerSecond: Double get() = (speedKph / 3.6).coerceAtLeast(0.1)
    val travelSeconds: Double get() = lengthM / metresPerSecond

    fun allows(from: Int): Boolean = when (oneway) {
        OneWay.BOTH -> true
        OneWay.FORWARD -> from == fromNode
        OneWay.TO -> from == toNode
        else -> true
    }
}

/** An outgoing traversal of [edge] from some node to [toNode]. */
data class Arc(val edge: Int, val toNode: Int, val seconds: Double, val lengthM: Double)

/**
 * A routable network: nodes, noded edges, adjacency, and connected-component regions.
 *
 * Regions exist because a forestry block can hold several road systems that share
 * no junction; a 245 km project with an outlying block would otherwise be one graph
 * whose "nearest edge" lookups return something unreachable.
 */
class NetworkGraph(
    val nodeE: DoubleArray,
    val nodeN: DoubleArray,
    val edges: List<NetworkEdge>,
    val adjacency: Array<List<Arc>>,
    val regionOfEdge: IntArray,
    val regionCount: Int,
    /** Noded polylines ready to be written out as a network shapefile layer. */
    val nodedLines: List<NodedLine>,
) {
    val nodeCount: Int get() = nodeE.size

    fun regionOfNode(node: Int): Int {
        if (node < 0 || node >= adjacency.size) return -1
        for (a in adjacency[node]) if (a.edge < regionOfEdge.size) return regionOfEdge[a.edge]
        return -1
    }

    /** Nodes belonging to a region, for focusing a map or a route request. */
    fun nodesInRegion(region: Int): List<Int> =
        (0 until nodeCount).filter { regionOfNode(it) == region }

    /**
     * Closest point on the network to (e,n), restricted to [region] when >= 0.
     * Returns edge index, the point, and distance in metres.
     */
    fun nearestOnNetwork(
        e: Double,
        n: Double,
        region: Int = -1,
        maxDistanceM: Double = Double.POSITIVE_INFINITY,
    ): NearestHit? {
        var best: NearestHit? = null
        for (edge in edges.indices) {
            if (region >= 0 && regionOfEdge[edge] != region) continue
            val en = edges[edge]
            val r = Geodesy.pointSegmentDistance(e, n, nodeE[en.fromNode], nodeN[en.fromNode], nodeE[en.toNode], nodeN[en.toNode])
            if (r[0] > maxDistanceM) continue
            if (best == null || r[0] < best.distanceM) {
                best = NearestHit(edge, r[0], r[1])
            }
        }
        return best
    }

    /**
     * The point on [edge] at fraction [t] along it, as (e, n).
     *
     * This is where a waypoint is delivered when it lies close enough to the network:
     * driving to the nearest point on the line beats driving to a spot in the field.
     */
    fun pointAt(edge: Int, t: Double): DoubleArray? {
        if (edge < 0 || edge >= edges.size) return null
        val en = edges[edge]
        val k = t.coerceIn(0.0, 1.0)
        val e0 = nodeE[en.fromNode]; val n0 = nodeN[en.fromNode]
        val e1 = nodeE[en.toNode]; val n1 = nodeN[en.toNode]
        return doubleArrayOf(e0 + (e1 - e0) * k, n0 + (n1 - n0) * k)
    }

    /**
     * What to do with a waypoint that is some way off the network.
     *
     * Three cases, because they are three different situations and treating them alike is
     * how a survey app ends up quietly delivering someone to the wrong place.
     */
    fun snapWaypoint(
        e: Double,
        n: Double,
        limitM: Double = PROJECT_LIMIT_M,
    ): WaypointDecision {
        val hit = nearestOnNetwork(e, n)
            ?: return WaypointDecision.TooFar(Double.MAX_VALUE, e, n, e, n)
        val on = pointAt(hit.edge, hit.tAlongEdge)
            ?: return WaypointDecision.TooFar(Double.MAX_VALUE, e, n, e, n)
        return when {
            hit.distanceM <= SNAP_SILENT_M -> WaypointDecision.Direct(hit.distanceM, on[0], on[1])
            hit.distanceM <= limitM -> WaypointDecision.Snapped(hit.distanceM, on[0], on[1], hit.edge)
            else -> WaypointDecision.TooFar(hit.distanceM, e, n, on[0], on[1])
        }
    }

    companion object {
        /** Within this, the waypoint is on the line already: snap and say nothing. */
        const val SNAP_SILENT_M = 70.0

        /**
         * How far off the network a waypoint may be and still be projected onto it.
         *
         * Beyond this the user is asked, rather than being routed somewhere they did not
         * ask for. Settable, because 1.5 km suits a logging road and not a walking track.
         */
        const val PROJECT_LIMIT_M = 1500.0
    }
}

/** What navigation should do with a waypoint that is off the network. */
sealed interface WaypointDecision {
    /** Where to go, either way. */
    val targetE: Double
    val targetN: Double

    /** How far the waypoint sits from the nearest line. */
    val distanceM: Double

    /** Already on the line: project it and say nothing. */
    data class Direct(override val distanceM: Double, override val targetE: Double, override val targetN: Double) :
        WaypointDecision

    /** Far enough to be worth mentioning: project it and tell the user how far. */
    data class Snapped(
        override val distanceM: Double,
        override val targetE: Double,
        override val targetN: Double,
        val edge: Int,
    ) : WaypointDecision

    /**
     * Beyond the limit: ask the user.
     *
     * [projectionE]/[projectionN] is where the line is nearest, which is what Bypass
     * navigates to and what "Tap on screen" starts from.
     */
    data class TooFar(
        override val distanceM: Double,
        override val targetE: Double,
        override val targetN: Double,
        val projectionE: Double,
        val projectionN: Double,
    ) : WaypointDecision
}

data class NearestHit(val edge: Int, val distanceM: Double, val tAlongEdge: Double)

/** A source polyline handed to the builder, in the working projected CRS. */
data class SourceLine(
    val points: DoubleArray,          // flat e,n,e,n...
    val layer: String,
    val cls: RouteClass,
    val oneway: Int = OneWay.BOTH,
    val speedKph: Double = 0.0,       // 0 means use RouteClass default
    val confidence: Int = 0,
) {
    val vertexCount: Int get() = points.size / 2
    fun e(i: Int) = points[i * 2]
    fun n(i: Int) = points[i * 2 + 1]
}

/** Output polyline with attributes, for writing a network layer. */
data class NodedLine(
    val points: DoubleArray,
    val edgeIds: IntArray,
    val layer: String,
    val cls: RouteClass,
    val oneway: Int,
    val speedKph: Double,
    val confidence: Int,
)

/** Diagnostics so the network can be verified rather than trusted. */
data class BuildReport(
    val sourceLineCount: Int,
    val nodeCount: Int,
    val edgeCount: Int,
    val regionCount: Int,
    val snappedEndpointCount: Int,
    val intersectionSplitCount: Int,
    val discardedDegenerateCount: Int,
    val maxRegionNodeCount: Int,
    /**
     * Size of the largest connected region measured in edges and kilometres.
     *
     * Prefer these over [maxRegionNodeCount] when judging whether a tolerance helped:
     * welding merges nodes, so the node count of a region *falls* as the tolerance
     * grows even while the region covers more road. Node count is a misleading
     * progress measure here.
     */
    val maxRegionEdgeCount: Int,
    val maxRegionLengthKm: Double,
    /**
     * Largest distance a welded node was moved from the points it replaced, in metres.
     *
     * Welding across a gap has to put the joint somewhere between the two ends, so this
     * is the geometric price of the tolerance. It is near zero at the 5 m road setting
     * and rises with the flight-path tolerance; report it rather than let a wide weld
     * quietly move the track.
     */
    val nodeDisplacementM: Double = 0.0,
)

/**
 * Turns loosely digitised line layers into a routable graph.
 *
 * Two things happen, both controlled by [toleranceM] - the "maximum distance
 * between two lines to be connected" setting:
 *
 *  1. Endpoints within the tolerance are welded into a shared node, which is what
 *     makes separately drawn access lines join up.
 *  2. Interior crossings are detected and both lines split there, without which a
 *     route cannot turn at a crossroads.
 *
 * Node identity is a snapped grid key rather than float equality, so two endpoints
 * 0.4 m apart reliably weld while a genuine fork stays separate.
 */
class NetworkBuilder(
    private val toleranceM: Double = 3.0,
    /**
     * Permit joining lines further apart than [MAX_UNSUPERVISED_TOLERANCE_M].
     *
     * Only the supervised repair flow may set this. Welding across a wide gap will
     * happily "build" a road over a collapsed bridge or a landslide, producing a
     * network that confidently routes across ground nobody can cross. An automatic
     * build must never invent infrastructure; bridging a real gap requires a person
     * to look at it and, ideally, a surveyed track over the gap.
     */
    private val allowSupervisedGaps: Boolean = false,
) {
    init {
        require(toleranceM > 0) { "tolerance must be positive, was $toleranceM" }
        require(toleranceM <= MAX_UNSUPERVISED_TOLERANCE_M || allowSupervisedGaps) {
            "tolerance $toleranceM m exceeds the $MAX_UNSUPERVISED_TOLERANCE_M m unsupervised " +
                "limit; pass allowSupervisedGaps = true only from the supervised repair flow"
        }
    }

    /**
     * Spatial index granularity, deliberately decoupled from [toleranceM].
     *
     * Indexing at the welding tolerance sounds tidy but is ruinous at map scale: a
     * 170 km line at a 3 m cell would be registered in ~56,000 buckets. The index is
     * sized from the data extent instead, while [toleranceM] continues to govern
     * exactly how close two things must be to count as joined.
     */
    private var indexCell = max(toleranceM, 1.0)
    private val cell: Double get() = max(toleranceM, 0.001)

    companion object {
        private const val MAX_CELLS_PER_SEGMENT = 4096

        /**
         * Largest gap an automatic build may bridge. Beyond this the network would
         * assert a connection that may not physically exist, so it is refused unless
         * a person has reviewed the gap.
         */
        const val MAX_UNSUPERVISED_TOLERANCE_M = 5.0

        /**
         * Builder for welding a **flight path**, where a much larger tolerance is
         * legitimate and needs a different justification.
         *
         * A flight path is a GPS track, not a surveyed road. Successive fixes of the
         * same flown line scatter by tens of metres, and the line is often drawn as
         * hundreds of short segments that must be rejoined before they can be flown.
         * Welding at [weldM] therefore reconnects ground the aircraft demonstrably
         * crossed - it is not inventing a crossing.
         *
         * This is the opposite of the road case, which is why it does not go through
         * [allowSupervisedGaps]: there, a wide weld could build a road over a collapsed
         * bridge, and no amount of review makes an automatic join safe. The road cap
         * of [MAX_UNSUPERVISED_TOLERANCE_M] is unchanged.
         */
        fun forFlightPath(weldM: Double = FLIGHT_PATH_WELD_M): NetworkBuilder =
            NetworkBuilder(weldM, allowSupervisedGaps = true)

        /**
         * Default flight-path welding distance: **250 m**.
         *
         * Raised from 30 m, then from 150 m, because each step recovered connections that
         * were genuinely there. Measured on the real `flight_path`, the gaps between
         * feature endpoints are bimodal: 76% have a neighbour within 30 m (median 14 m),
         * but 24% need 30-500 m. At 30 m the layer shattered into 572 pieces and only 3 of
         * the 290 transits could be followed; at 150 m that rose to 74.
         *
         * 250 m is defensible for the same reason 500 m is: the closest any two flight
         * lines lie is **638 m**, so a weld this wide cannot merge two of them.
         *
         * Note that [FlightPlan.buildTraced] currently traces at 500 m rather than this,
         * because a wider weld traces more of the plan and the drawn plan is what the crew
         * flies. That is a deliberate split for now, not an oversight, but the two should
         * be reconciled before this is relied on.
         */
        const val FLIGHT_PATH_WELD_M = 250.0
    }
    private val overflowSegments = ArrayList<Int>()

    fun build(sources: List<SourceLine>): Pair<NetworkGraph, BuildReport> {
        // ---- pass 1: clean input, gather segments ----
        var discarded = 0
        val segs = ArrayList<Seg>()
        val segsBySource = Array(sources.size) { mutableListOf<Int>() }
        for ((si, s) in sources.withIndex()) {
            val pts = dedupe(s.points)
            if (pts.size < 4) { discarded++; continue }
            var localSeg = 0
            for (i in 0 until pts.size / 2 - 1) {
                val a = i * 2
                val b = (i + 1) * 2
                val len = hypot(pts[b] - pts[a], pts[b + 1] - pts[a + 1])
                if (len < 1e-6) { discarded++; continue }
                segsBySource[si].add(segs.size)
                segs.add(Seg(pts, a, b, s, localSeg))
                localSeg++
            }
        }
        if (segs.isEmpty()) {
            return NetworkGraph(
                DoubleArray(0), DoubleArray(0), emptyList(), emptyArray(),
                IntArray(0), 0, emptyList(),
            ) to BuildReport(sources.size, 0, 0, 0, 0, 0, discarded, 0, 0, 0.0)
        }

        // ---- pass 2: interior intersections via a uniform grid ----
        // size the index from the data extent so long lines stay cheap to index
        var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE
        var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
        for (sg in segs) {
            bx0 = min(bx0, min(sg.ax(), sg.bx())); bx1 = max(bx1, max(sg.ax(), sg.bx()))
            by0 = min(by0, min(sg.ay(), sg.by())); by1 = max(by1, max(sg.ay(), sg.by()))
        }
        indexCell = max(toleranceM, max(bx1 - bx0, by1 - by0) / 512.0)

        val splitsPerSeg = HashMap<Int, MutableList<Double>>()
        val grid = HashMap<Long, MutableList<Int>>()
        overflowSegments.clear()
        for ((i, sg) in segs.withIndex()) {
            val gx0 = floor((min(sg.ax(), sg.bx()) - toleranceM) / indexCell).toLong()
            val gx1 = floor((max(sg.ax(), sg.bx()) + toleranceM) / indexCell).toLong()
            val gy0 = floor((min(sg.ay(), sg.by()) - toleranceM) / indexCell).toLong()
            val gy1 = floor((max(sg.ay(), sg.by()) + toleranceM) / indexCell).toLong()
            val spanX = gx1 - gx0 + 1
            val spanY = gy1 - gy0 + 1
            if (spanX * spanY > MAX_CELLS_PER_SEGMENT) {
                overflowSegments.add(i)      // compared against everything instead
                continue
            }
            for (gx in gx0..gx1) for (gy in gy0..gy1) {
                grid.getOrPut(gx * 73856093L xor gy * 19349663L) { mutableListOf() }.add(i)
            }
        }
        val isOverflow = BooleanArray(segs.size)
        for (i in overflowSegments) isOverflow[i] = true

        var splits = 0
        val seenPairs = HashSet<Long>()
        for (si in segs.indices) {
            val p = segs[si]
            // scan every cell the segment spans, not just the one its midpoint lands
            // in: a long line crosses many others far from its own midpoint
            val gx0 = floor((min(p.ax(), p.bx()) - toleranceM) / indexCell).toLong()
            val gx1 = floor((max(p.ax(), p.bx()) + toleranceM) / indexCell).toLong()
            val gy0 = floor((min(p.ay(), p.by()) - toleranceM) / indexCell).toLong()
            val gy1 = floor((max(p.ay(), p.by()) + toleranceM) / indexCell).toLong()

            // candidates come from the shared buckets; a segment too long to index is
            // simply compared against everything
            val candidates = if (isOverflow[si]) {
                segs.indices
            } else {
                val acc = ArrayList<Int>()
                for (gx in gx0..gx1) for (gy in gy0..gy1) {
                    acc.addAll(grid[gx * 73856093L xor gy * 19349663L].orEmpty())
                }
                acc
            }

            for (otherIdx in candidates) {
                if (otherIdx == si) continue
                val lo = minOf(si, otherIdx).toLong()
                val hi = maxOf(si, otherIdx).toLong()
                if (!seenPairs.add(lo * 1000000L + hi)) continue
                val q = segs[otherIdx]
                // crossings count across layers too: an access line that meets a road
                // must form a junction, else a combined network stays broken
                val t = segmentIntersection(p, q) ?: continue
                if (t.first > 1e-9 && t.first < 1 - 1e-9) {
                    splitsPerSeg.getOrPut(si) { mutableListOf() }.add(t.first)
                    splits++
                }
                if (t.second > 1e-9 && t.second < 1 - 1e-9) {
                    splitsPerSeg.getOrPut(otherIdx) { mutableListOf() }.add(t.second)
                    splits++
                }
            }
        }

        // ---- pass 3: node registry with tolerance welding ----
        val registry = NodeRegistry(cell)  // welding stays at toleranceM
        var snapped = 0
        for (s in sources) {
            val pts = dedupe(s.points)
            if (pts.size < 4) continue
            if (registry.intern(pts[0], pts[1]).second) snapped++
            if (registry.intern(pts[pts.size - 2], pts[pts.size - 1]).second) snapped++
        }

        // Place welded nodes before any edge is emitted, since edges read their
        // endpoints back out of the registry.
        val nodeDisplacementM = registry.recentre()

        val nodeE = ArrayList<Double>()
        val nodeN = ArrayList<Double>()
        val registryToGraph = HashMap<Int, Int>()
        fun nodeAt(e: Double, n: Double): Int {
            val regId = registry.intern(e, n).first
            registryToGraph[regId]?.let { return it }
            val idx = nodeE.size
            nodeE.add(registry.eList[regId])
            nodeN.add(registry.nList[regId])
            registryToGraph[regId] = idx
            return idx
        }

        // ---- pass 4: emit noded edges ----
        val edges = ArrayList<NetworkEdge>()
        val noded = ArrayList<NodedLine>()

        for ((si, s) in sources.withIndex()) {
            val pts = dedupe(s.points)
            if (pts.size < 4) continue
            val speed = if (s.speedKph > 0) s.speedKph else s.cls.defaultSpeedKph
            var chainE = ArrayList<Double>()
            var chainN = ArrayList<Double>()
            var chainIds = ArrayList<Int>()

            fun flush() {
                if (chainIds.isNotEmpty() && chainE.size >= 2) {
                    // interleaved e,n; writing only the eastings would leave every
                    // chain a single vertex and the whole layer would be discarded
                    val flat = DoubleArray(chainE.size * 2)
                    for (i in chainE.indices) {
                        flat[i * 2] = chainE[i]
                        flat[i * 2 + 1] = chainN[i]
                    }
                    noded.add(
                        NodedLine(
                            flat, chainIds.toIntArray(),
                            s.layer, s.cls, s.oneway, speed, s.confidence,
                        ),
                    )
                }
                chainE = ArrayList(); chainN = ArrayList(); chainIds = ArrayList()
            }

            for (i in 0 until pts.size / 2 - 1) {
                val a = i * 2
                val b = (i + 1) * 2
                val len = hypot(pts[b] - pts[a], pts[b + 1] - pts[a + 1])
                if (len < 1e-6) continue
                val globalSeg = segsBySource[si].getOrNull(i) ?: continue
                val cuts = splitsPerSeg[globalSeg].orEmpty().sorted()

                var prevT = 0.0
                var startNode = -1
                var started = false
                for (t in cuts + listOf(1.0)) {
                    val cx = pts[a] + (pts[b] - pts[a]) * t
                    val cy = pts[a + 1] + (pts[b + 1] - pts[a + 1]) * t
                    if (!started) {
                        startNode = nodeAt(pts[a], pts[a + 1])
                        chainE.add(pts[a]); chainN.add(pts[a + 1])
                        started = true
                    }
                    val endNode = nodeAt(cx, cy)
                    val partLen = len * (t - prevT)
                    if (partLen > 1e-6) {
                        val edgeId = edges.size
                        edges.add(
                            NetworkEdge(edgeId, startNode, endNode, partLen, s.cls, speed, s.oneway, s.layer, s.confidence),
                        )
                        chainE.add(cx); chainN.add(cy)
                        chainIds.add(edgeId)
                        // a crossing terminates this chain and starts the next one
                        flush()
                        chainE.add(cx); chainN.add(cy)
                    }
                    startNode = endNode
                    prevT = t
                }
            }
            flush()
        }

        // ---- pass 5: adjacency + regions ----
        val n = nodeE.size
        val adj = Array(n) { mutableListOf<Arc>() }
        for (ed in edges) {
            val sec = ed.travelSeconds
            if (ed.allows(ed.fromNode)) adj[ed.fromNode].add(Arc(ed.id, ed.toNode, sec, ed.lengthM))
            if (ed.oneway != OneWay.FORWARD && ed.allows(ed.toNode)) {
                adj[ed.toNode].add(Arc(ed.id, ed.fromNode, sec, ed.lengthM))
            }
        }
        val nodeRegion = IntArray(n) { -1 }
        val regionOfEdge = IntArray(edges.size) { -1 }
        // indexed by region, not by edge: a region can be far fewer than the edge count
        val edgesPerRegion = IntArray(n + 1)
        val lengthPerRegion = DoubleArray(n + 1)
        var regionCount = 0
        var biggest = 0
        for (start in 0 until n) {
            if (nodeRegion[start] >= 0) continue
            var count = 0
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            nodeRegion[start] = regionCount
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                count++
                for (a in adj[node]) {
                    // adjacency has an arc each way, so the same edge shows up twice
                    // for a node in the middle of it. Only the first visit counts.
                    if (regionOfEdge[a.edge] == regionCount) continue
                    regionOfEdge[a.edge] = regionCount
                    edgesPerRegion[regionCount]++
                    lengthPerRegion[regionCount] += edges[a.edge].lengthM
                    if (nodeRegion[a.toNode] < 0) {
                        nodeRegion[a.toNode] = regionCount
                        stack.addLast(a.toNode)
                    }
                }
            }
            if (count > biggest) biggest = count
            regionCount++
        }

        val graph = NetworkGraph(
            nodeE.toDoubleArray(), nodeN.toDoubleArray(), edges,
            adj.map { it.toList() }.toTypedArray(), regionOfEdge, regionCount, noded,
        )
        val biggestEdges = if (regionCount > 0) edgesPerRegion.take(regionCount).maxOrNull() ?: 0 else 0
        val biggestKm = if (regionCount > 0) (lengthPerRegion.take(regionCount).maxOrNull() ?: 0.0) / 1000.0 else 0.0

        return graph to BuildReport(
            sources.size, graph.nodeCount, edges.size, regionCount,
            snapped, splits, discarded, biggest, biggestEdges, biggestKm,
            nodeDisplacementM,
        )
    }

    /** Removes consecutive duplicate vertices, which otherwise create zero-length edges. */
    private fun dedupe(p: DoubleArray): DoubleArray {
        if (p.size < 4) return p
        val out = ArrayList<Double>(p.size)
        out.add(p[0]); out.add(p[1])
        for (i in 1 until p.size / 2) {
            val e = p[i * 2]; val n = p[i * 2 + 1]
            val le = out[out.size - 2]; val ln = out[out.size - 1]
            if (hypot(e - le, n - ln) > 1e-9) { out.add(e); out.add(n) }
        }
        return out.toDoubleArray()
    }

    private class Seg(val pts: DoubleArray, val a: Int, val b: Int, val src: SourceLine, val local: Int) {
        fun ax() = pts[a]
        fun ay() = pts[a + 1]
        fun bx() = pts[b]
        fun by() = pts[b + 1]
    }

    /**
     * Weld nearby points into shared node ids using a grid so the cost stays
     * near-linear in the number of endpoints.
     */
    private class NodeRegistry(private val cell: Double) {
        val eList = ArrayList<Double>()
        val nList = ArrayList<Double>()
        private val buckets = HashMap<Long, MutableList<Int>>()

        /** Running centre of each node's folded-in points, and how many it has. */
        private val sumE = HashMap<Int, Double>()
        private val sumN = HashMap<Int, Double>()
        private val count = HashMap<Int, Int>()

        fun intern(e: Double, n: Double): Pair<Int, Boolean> {
            val existing = internExisting(e, n)
            if (existing != null) {
                // Only join if the point is within tolerance of where the node
                // currently sits. Testing against the node position rather than against
                // each earlier point stops clusters chaining: without this, A welds B,
                // B welds C, and a "150 m" weld ends up spanning 300 m and dragging
                // the node further from the real track with every extra link.
                val cx = eList[existing]
                val cy = nList[existing]
                if (hypot(e - cx, n - cy) <= cell) {
                    val k = (count[existing] ?: 1) + 1
                    sumE[existing] = (sumE[existing] ?: cx * (k - 1)) + e
                    sumN[existing] = (sumN[existing] ?: cy * (k - 1)) + n
                    count[existing] = k
                }
                return existing to true
            }
            val id = eList.size
            eList.add(e); nList.add(n)
            sumE[id] = e; sumN[id] = n; count[id] = 1
            buckets.getOrPut(keyOf(e, n)) { mutableListOf() }.add(id)
            return id to false
        }

        /**
         * Move each welded node to the centre of the points folded into it, and report
         * the largest displacement that caused.
         *
         * Without this a node sits wherever the first of its points happened to be, so a
         * joint welded across a [cell] gap is displaced from the real track by up to the
         * whole tolerance. At the 5 m road tolerance that is invisible; at the 150 m
         * flight-path tolerance it moved joints by up to 148 m, which dragged drawn legs
         * visibly off the GPS track. Averaging halves the worst case and, unlike picking
         * one point, does not systematically favour whichever end came first in the file.
         *
         * The residual is unavoidable while welding across a gap: the joint is somewhere
         * between the two ends. The returned figure is the honest cost of the tolerance.
         */
        fun recentre(): Double {
            var worst = 0.0
            for (id in eList.indices) {
                val k = count[id] ?: 1
                if (k < 2) continue
                val ce = (sumE[id] ?: eList[id]) / k
                val cn = (sumN[id] ?: nList[id]) / k
                val shift = hypot(ce - eList[id], cn - nList[id])
                if (shift > worst) worst = shift
                eList[id] = ce
                nList[id] = cn
            }
            return worst
        }

        fun internExisting(e: Double, n: Double): Int? {
            val gx = floor(e / cell).toLong()
            val gy = floor(n / cell).toLong()
            for (dx in -1..1) for (dy in -1..1) {
                for (id in buckets[(gx + dx) * 73856093L xor (gy + dy) * 19349663L].orEmpty()) {
                    if (hypot(eList[id] - e, nList[id] - n) <= cell) return id
                }
            }
            return null
        }

        private fun keyOf(e: Double, n: Double): Long =
            floor(e / cell).toLong() * 73856093L xor floor(n / cell).toLong() * 19349663L
    }

    /** Proper segment-segment intersection, returning parametric positions. */
    private fun segmentIntersection(p: Seg, q: Seg): Pair<Double, Double>? {
        val px = p.ax(); val py = p.ay()
        val rx = p.bx() - px; val ry = p.by() - py
        val qx = q.ax(); val qy = q.ay()
        val sx = q.bx() - qx; val sy = q.by() - qy
        val denom = rx * sy - ry * sx
        if (abs(denom) < 1e-12) return null      // parallel or colinear
        val t = ((qx - px) * sy - (qy - py) * sx) / denom
        val u = ((qx - px) * ry - (qy - py) * rx) / denom
        if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) return null
        return Pair(t.coerceIn(0.0, 1.0), u.coerceIn(0.0, 1.0))
    }
}
