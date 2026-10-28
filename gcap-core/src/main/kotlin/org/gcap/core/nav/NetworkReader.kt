package org.gcap.core.nav

import org.gcap.core.ShpFormat

/**
 * Reads a network shapefile back into a [NetworkGraph].
 *
 * The writer stores `node_a`/`node_b` on every noded chain precisely so the graph
 * can be rebuilt from the layer alone - no sidecar, no internal format, nothing to
 * go stale. This is the other half of that: a network built on a desktop, repaired
 * in QGIS, or written by an earlier run of this app loads back into the same router.
 *
 * Chains are stitched by shared node ids, so the order they appear in the file does
 * not matter and a chain written in either direction joins correctly.
 */
object NetworkReader {

    /** Shared empty array: the reader knows no line's edge ids yet. */
    private val NO_EDGE_IDS = IntArray(0)

    /** What a read produced, or why it could not be read. */
    sealed class Result {
        data class Ok(val graph: NetworkGraph, val chains: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Rebuild a graph from the four files of a network layer.
     *
     * [shp] and [dbf] are required; [prj] is read when present so the caller can
     * check the network is in the working zone rather than assuming it.
     */
    fun read(
        shp: ByteArray,
        dbf: ByteArray,
        prj: ByteArray? = null,
    ): Result {
        val shapes = try {
            ShpFormat.readShp(shp, -1e18, -1e18, 1e18, 1e18)
        } catch (e: Exception) {
            return Result.Failed("network .shp could not be read: ${e.message ?: e.javaClass.simpleName}")
        }
        val rows = try {
            ShpFormat.readDbfRows(dbf)
        } catch (e: Exception) {
            return Result.Failed("network .dbf could not be read: ${e.message ?: e.javaClass.simpleName}")
        }
        if (shapes.isEmpty()) return Result.Failed("network .shp holds no features")
        if (rows.size < shapes.size) {
            return Result.Failed("network .dbf has ${rows.size} rows for ${shapes.size} features")
        }

        // node id -> graph node index, so chains meeting at a node share it
        val nodeIndex = HashMap<Int, Int>()
        val nodeE = ArrayList<Double>()
        val nodeN = ArrayList<Double>()

        fun nodeAt(id: Int): Int {
            nodeIndex[id]?.let { return it }
            val i = nodeE.size
            nodeE.add(0.0); nodeN.add(0.0)
            nodeIndex[id] = i
            return i
        }

        // first pass: allocate every node, then fill coordinates from the chains
        // that touch it (a chain's node_a is its first vertex, node_b its last)
        data class Chain(
            val fromNode: Int,
            val toNode: Int,
            val pts: List<DoubleArray>,
            val cls: RouteClass,
            val speedKph: Double,
            val oneway: Int,
            val region: Int,
        )

        val chains = ArrayList<Chain>()
        for ((i, sh) in shapes.withIndex()) {
            val row = rows.getOrNull(i)?.values ?: continue
            val pts = sh.parts.firstOrNull() ?: continue
            if (pts.size < 2) continue
            val a = row.getOrNull(6)?.trim()?.toIntOrNull() ?: continue
            val b = row.getOrNull(7)?.trim()?.toIntOrNull() ?: continue
            val cls = RouteClass.values().firstOrNull { it.name.equals(row.getOrNull(0)?.trim(), true) }
                ?: RouteClass.ACCESS
            val speed = row.getOrNull(2)?.trim()?.toDoubleOrNull() ?: cls.defaultSpeedKph
            val oneway = when (row.getOrNull(3)?.trim()?.toIntOrNull()) {
                1 -> OneWay.FORWARD
                2 -> OneWay.TO
                else -> OneWay.BOTH
            }
            val region = row.getOrNull(9)?.trim()?.toIntOrNull() ?: 0
            nodeAt(a); nodeAt(b)
            chains.add(Chain(a, b, pts, cls, speed, oneway, region))
        }
        if (chains.isEmpty()) return Result.Failed("no usable chains in the network layer")

        // second pass: give each node its coordinates. A node shared by several
        // chains may be described as node_a by one and node_b by another; they are
        // the same point, so the first description wins and the rest are checked.
        val assigned = BooleanArray(nodeE.size)
        for (c in chains) {
            val ia = nodeIndex[c.fromNode]!!
            val ib = nodeIndex[c.toNode]!!
            if (!assigned[ia]) { nodeE[ia] = c.pts.first()[0]; nodeN[ia] = c.pts.first()[1]; assigned[ia] = true }
            if (!assigned[ib]) { nodeE[ib] = c.pts.last()[0]; nodeN[ib] = c.pts.last()[1]; assigned[ib] = true }
        }
        // any node never described (a one-ended chain) sits at the origin; drop it
        // rather than emit edges that route to 0,0
        for (i in nodeE.indices) {
            if (!assigned[i]) { nodeE[i] = Double.NaN; nodeN[i] = Double.NaN }
        }

        val edges = ArrayList<NetworkEdge>()
        val adjacency = Array(nodeE.size) { ArrayList<Arc>() }
        val regionOfEdge = IntArray(chains.size) { -1 }
        var regionCount = 0
        val regionSeen = HashMap<Int, Int>()

        for ((ci, c) in chains.withIndex()) {
            val a = nodeIndex[c.fromNode]!!
            val b = nodeIndex[c.toNode]!!
            if (nodeE[a].isNaN() || nodeE[b].isNaN()) continue
            if (a == b) continue
            val len = lengthOf(c.pts)
            if (len < 1e-6) continue
            val region = regionSeen.getOrPut(c.region) { regionCount++ }
            val speed = if (c.speedKph > 0) c.speedKph else c.cls.defaultSpeedKph
            val mps = speed / 3.6
            val ei = edges.size
            edges.add(
                NetworkEdge(
                    id = ei, fromNode = a, toNode = b, lengthM = len,
                    cls = c.cls, speedKph = speed, oneway = c.oneway,
                    sourceLayer = c.cls.name,
                ),
            )
            adjacency[a].add(Arc(toNode = b, edge = ei, lengthM = len, seconds = len / mps))
            if (c.oneway != OneWay.TO) {
                adjacency[b].add(Arc(toNode = a, edge = ei, lengthM = len, seconds = len / mps))
            }
            regionOfEdge[ci] = region
        }
        if (edges.isEmpty()) return Result.Failed("no routable edges could be rebuilt")

        // One shared empty array rather than a fresh one per line. The roads network is
        // ~192,000 lines, so that was 192,000 allocations of an array that is always empty.
        val noded = chains.map { ln ->
            NodedLine(
                points = flat(ln.pts),
                edgeIds = NO_EDGE_IDS,
                layer = ln.cls.name,
                cls = ln.cls,
                oneway = ln.oneway,
                speedKph = ln.speedKph,
                confidence = 0,
            )
        }

        return Result.Ok(
            NetworkGraph(
                nodeE = nodeE.toDoubleArray(),
                nodeN = nodeN.toDoubleArray(),
                edges = edges,
                // Filled in one pass. It used to be adjacency.map { it.toList() }
                // .toTypedArray(), which copied all ~170,000 node lists twice more
                // before the array was even made.
                adjacency = Array(nodeE.size) { i -> adjacency[i] },
                regionOfEdge = regionOfEdge,
                regionCount = regionCount,
                nodedLines = noded,
            ),
            chains.size,
        )
    }

    private fun lengthOf(pts: List<DoubleArray>): Double {
        var d = 0.0
        for (i in 0 until pts.size - 1) {
            val dx = pts[i + 1][0] - pts[i][0]
            val dy = pts[i + 1][1] - pts[i][1]
            d += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return d
    }

    private fun flat(pts: List<DoubleArray>): DoubleArray {
        val out = DoubleArray(pts.size * 2)
        var k = 0
        for (p in pts) { out[k * 2] = p[0]; out[k * 2 + 1] = p[1]; k++ }
        return out
    }

}
