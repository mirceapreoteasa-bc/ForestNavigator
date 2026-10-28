package org.gcap.core.prep

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Repairing digitised line layers before a routing network is built from them.
 *
 * A survey folder's access, road and trail layers arrive with three recurring defects, all
 * from the same cause: several people, or several GPS recordings, digitising the same
 * ground separately. Two lines that meet do not share a vertex, so the network treats them
 * as unconnected and a route stops for no visible reason. A line ends a few metres short of
 * the line it should join. A gently curving run carries a vertex every few metres because
 * that is where the GPS put it.
 *
 * The order of operations follows what road-network tooling does (pgRouting's
 * `pgr_nodeNetwork`, OSMnx's simplify and consolidate passes): measure first, repair
 * second, and report in a form a person can check. Nothing is written without a [Plan] the
 * user has seen.
 *
 * **Cuts are never healed.** A cut marks ground that genuinely cannot be travelled, so
 * welding across one would invent a route over a collapsed bridge. [plan] reports such
 * conflicts rather than resolving them, because only the user knows whether a gap is a
 * bridge or a mistake.
 */
object LineHealing {

    /** One line feature: its parts, plus the attribute row that travels with it. */
    data class Line(
        val parts: List<List<DoubleArray>>,
        val recordIndex: Int = 0,
        val attributes: List<String> = emptyList(),
    )

    /** A pair of endpoints that a build at [toleranceM] would join. */
    data class Weld(
        val fromRecord: Int,
        val toRecord: Int,
        val distanceM: Double,
    )

    /**
     * What healing at [toleranceM] would change, before anything is written.
     *
     * [junctionCandidates] is the count the user cares about: endpoints that would gain a
     * connection. [lengthChangeM] is the honest check on whether the tolerance was too
     * generous - welding moves geometry, and a change of kilometres means genuinely
     * separate roads have been fused.
     */
    data class Plan(
        val toleranceM: Double,
        val lineCount: Int,
        val vertexCount: Int,
        /** Endpoint pairs close enough to join at this tolerance. */
        val junctionCandidates: Int,
        /** Of those, the pairs belonging to two different features. */
        val crossFeatureWelds: Int,
        /** Pairs where a line's two ends meet each other. */
        val selfWelds: Int,
        /** The widest gap this tolerance would close. */
        val maxWeldDistanceM: Double,
        val lengthChangeM: Double,
        /** Connected groups of geometry, before and after. */
        val componentsBefore: Int,
        val componentsAfter: Int,
        /** Welds that would undo a user cut: reported, never applied. */
        val cutsAtRisk: Int,
        /** False when the tolerance exceeds the automatic-build cap. */
        val withinAutomaticCap: Boolean,
        /** Individual welds, capped so the report stays readable. */
        val welds: List<Weld>,
    ) {
        val healsDisconnect: Boolean get() = componentsAfter < componentsBefore
        val overAutomaticCap: Boolean get() = !withinAutomaticCap
    }

    /**
     * Default healing tolerance: **7 m**.
     *
     * Chosen after exporting healed copies of the real `access` layer for visual review;
     * 7 m joins 2,243 junctions against 1,292 at 2 m, and no separate track was fused.
     * Forestry access lines are single-lane and often drawn by several people or recorded
     * from several GPX tracks, so the wider setting recovers more real junctions than it
     * risks. The ladder is offered on every run, because a folder with genuinely separate
     * parallel tracks will show it.
     *
     * This does not relax [AUTOMATIC_CAP_M]: healing makes endpoints the same coordinate,
     * so the routing engine's own conservative cap no longer gates them.
     */
    const val DEFAULT_TOLERANCE_M = 7.0

    /**
     * The routing engine's own cap on welding gaps unasked.
     *
     * Unchanged by healing. It bounds what the engine will do on its own; a supervised
     * healing step is the separate, reviewed decision to join something wider.
     */
    const val AUTOMATIC_CAP_M = 5.0

    private const val DISPLAY_WELD_LIMIT = 400

    /**
     * Measure what healing at [toleranceM] would do. Pure: reads nothing, writes nothing.
     */
    fun plan(
        lines: List<Line>,
        toleranceM: Double,
        cuts: List<Cut> = emptyList(),
    ): Plan {
        require(toleranceM > 0) { "tolerance must be positive, was $toleranceM" }
        val clusters = clusterEndpoints(lines, toleranceM)
        val cutRecords = cuts.map { it.record }.toSet()
        val risky = clusters.filter { c -> c.any { it.record in cutRecords } }
        val safeClusters = clusters.filterNot { c -> c.any { it.record in cutRecords } }

        val junctions = clusters.sumOf { pairCount(it) }
        val vertices = lines.sumOf { l -> l.parts.sumOf { it.size } }
        val lengthBefore = lines.sumOf { l -> l.parts.sumOf { partLength(it) } }
        val weldedLength = safeClusters.sumOf { c -> weldDelta(lines, c) }

        return Plan(
            toleranceM = toleranceM,
            lineCount = lines.size,
            vertexCount = vertices,
            junctionCandidates = junctions,
            crossFeatureWelds = safeClusters.count { c -> c.map { it.record }.distinct().size > 1 },
            selfWelds = safeClusters.count { c -> c.map { it.record }.distinct().size == 1 },
            maxWeldDistanceM = safeClusters.flatMap { weldDistances(it) }.maxOrNull() ?: 0.0,
            lengthChangeM = weldedLength,
            componentsBefore = connectivity(lines).components,
            componentsAfter = connectivity(applyWeld(lines, safeClusters)).components,
            cutsAtRisk = risky.size,
            withinAutomaticCap = toleranceM <= AUTOMATIC_CAP_M,
            welds = clusters.flatMap { pairsFor(it) }.take(DISPLAY_WELD_LIMIT),
        )
    }

    /**
     * The cheap half of [plan]: how many junctions a tolerance would join and how far the
     * geometry would move. Skips the connected-component counts and the weld application.
     *
     * The tolerance ladder needs eight of these before the user has chosen anything, and
     * on the real access layer the full [plan] takes seconds each on a phone - long enough
     * that the screen looks dead. The figures the ladder shows come from here; the full
     * plan runs once, on the tolerance that is actually applied.
     */
    data class Summary(
        val toleranceM: Double,
        val junctionCandidates: Int,
        val crossFeatureWelds: Int,
        val lengthChangeM: Double,
        val maxWeldDistanceM: Double,
        val cutsAtRisk: Int,
        val withinAutomaticCap: Boolean,
    )

    fun summarise(lines: List<Line>, toleranceM: Double, cuts: List<Cut> = emptyList()): Summary {
        require(toleranceM > 0) { "tolerance must be positive, was $toleranceM" }
        val clusters = clusterEndpoints(lines, toleranceM)
        val cutRecords = cuts.map { it.record }.toSet()
        val safe = clusters.filterNot { c -> c.any { it.record in cutRecords } }
        return Summary(
            toleranceM = toleranceM,
            junctionCandidates = safe.sumOf { c -> c.size * (c.size - 1) / 2 },
            crossFeatureWelds = safe.count { c -> c.map { it.record }.distinct().size > 1 },
            lengthChangeM = safe.sumOf { weldDelta(lines, it) },
            maxWeldDistanceM = safe.flatMap { weldDistances(it) }.maxOrNull() ?: 0.0,
            cutsAtRisk = clusters.size - safe.size,
            withinAutomaticCap = toleranceM <= AUTOMATIC_CAP_M,
        )
    }

    /** The whole ladder, cheaply. See [summarise]. */
    fun summariseLadder(
        lines: List<Line>,
        tolerances: List<Double> = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 15.0),
        cuts: List<Cut> = emptyList(),
    ): List<Summary> = tolerances.map { summarise(lines, it, cuts) }

    /** Preview several tolerances at once, so the user can compare before choosing. */
    fun toleranceLadder(
        lines: List<Line>,
        tolerances: List<Double> = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 15.0),
        cuts: List<Cut> = emptyList(),
    ): List<Plan> = tolerances.map { plan(lines, it, cuts) }

    /**
     * Heal at [toleranceM]: endpoints within it are pulled onto one shared position.
     *
     * Clustering is transitive, so a chain of short GPS segments - hundreds of them a few
     * metres apart - becomes one usable line rather than only its adjacent pairs.
     */
    fun heal(
        lines: List<Line>,
        toleranceM: Double,
        cuts: List<Cut> = emptyList(),
    ): List<Line> {
        val clusters = clusterEndpoints(lines, toleranceM)
        val cutRecords = cuts.map { it.record }.toSet()
        val safe = clusters.filterNot { c -> c.any { it.record in cutRecords } }
        return applyWeld(lines, safe)
    }

    /**
     * Drop vertices that sit on a straight run through their neighbours.
     *
     * A vertex survives if it strays further than [toleranceM] from the straight line
     * between the kept vertices either side of it. That is a deviation rather than a
     * coordinate distance, so a long gentle bend is judged on how far it actually bows
     * rather than on how long its segments are. The first and last vertex of a part always
     * stay: they are what the network connects on.
     */
    fun simplifyCollinear(lines: List<Line>, toleranceM: Double): SimplifyResult {
        require(toleranceM >= 0) { "tolerance must not be negative, was $toleranceM" }
        var removed = 0
        val out = lines.map { l ->
            l.copy(
                parts = l.parts.map { part ->
                    if (part.size <= 2) {
                        part
                    } else {
                        val kept = dropCollinear(part, toleranceM)
                        removed += part.size - kept.size
                        kept
                    }
                },
            )
        }
        return SimplifyResult(out, removed)
    }

    data class SimplifyResult(val lines: List<Line>, val verticesRemoved: Int)

    /**
     * Split a line at fractions along its length, leaving a real gap of [gapM] metres.
     *
     * The gap is what makes a cut work. Two features whose nearest points are [gapM] apart
     * are further apart than any automatic build will weld, so a network built afterwards
     * cannot connect them. A cut with no gap would simply be noded back together and the
     * road would be passable again.
     */
    fun cutAtFractions(
        lines: List<Line>,
        record: Int,
        fractions: List<Double>,
        gapM: Double,
    ): List<Line> {
        require(gapM > 0) { "a cut needs a gap, was $gapM" }
        require(fractions.isNotEmpty()) { "no cut positions given" }
        val source = lines.getOrNull(record)
            ?: throw IllegalArgumentException("no line $record in a layer of ${lines.size}")
        val total = source.parts.sumOf { partLength(it) }
        // Cut from the far end backwards, so each fraction still refers to the original
        // line rather than to a piece already shortened by an earlier cut.
        val pieces = ArrayList<List<DoubleArray>>()
        for (part in source.parts) {
            pieces.add(part)
        }
        for (f in fractions.sortedDescending()) {
            require(f > 0.0 && f < 1.0) { "cut fraction must be inside the line, was $f" }
            val target = total * f
            // find which current piece contains that distance and split it. The list is
            // rebuilt rather than inserted into, because inserting while iterating it
            // throws.
            var acc = 0.0
            var done = false
            val rebuilt = ArrayList<List<DoubleArray>>(pieces.size + 1)
            for ((idx, piece) in pieces.withIndex()) {
                val len = partLength(piece)
                if (!done && acc + len >= target && len > gapM) {
                    val split = splitWithGap(piece, target - acc, gapM)
                    if (split != null) {
                        rebuilt.add(split.first)
                        rebuilt.add(split.second)
                        done = true
                        acc += len
                        continue
                    }
                }
                rebuilt.add(piece)
                acc += len
            }
            if (!done) throw IllegalArgumentException("cut at $f does not fall on the line")
            pieces.clear()
            pieces.addAll(rebuilt)
        }
        val result = ArrayList<Line>(lines.size + pieces.size)
        for ((i, l) in lines.withIndex()) {
            if (i != record) {
                result.add(l)
            } else {
                pieces.forEach { piece -> result.add(l.copy(parts = listOf(piece))) }
            }
        }
        return result
    }

    /** A deliberate break in a line, kept so healing can refuse to close it. */
    data class Cut(val record: Int, val x: Double, val y: Double, val gapM: Double)

    /**
     * Cut one part at [atDist] along its length, returning the two pieces either side of
     * a gap of [gapM] metres. Null when the position is not on this part.
     */
    private fun splitWithGap(
        part: List<DoubleArray>,
        atDist: Double,
        gapM: Double,
    ): Pair<List<DoubleArray>, List<DoubleArray>>? {
        var acc = 0.0
        for (i in 0 until part.size - 1) {
            val segLen = dist(part[i], part[i + 1])
            if (acc + segLen >= atDist && segLen > gapM) {
                val t = (atDist - acc) / segLen
                val dx = part[i + 1][0] - part[i][0]
                val dy = part[i + 1][1] - part[i][1]
                val ux = dx / segLen
                val uy = dy / segLen
                val cutX = part[i][0] + dx * t
                val cutY = part[i][1] + dy * t
                val trim = gapM / 2.0
                // Two separate parts, each with its own trimmed end, so the gap is a real
                // break between features rather than a jump inside one. A network treats
                // two features this far apart as unconnected; it treats a single line with
                // a jump as one route it would happily drive across.
                val head = part.subList(0, i + 1) +
                    listOf(doubleArrayOf(cutX - ux * trim, cutY - uy * trim))
                val tail = listOf(doubleArrayOf(cutX + ux * trim, cutY + uy * trim)) +
                    part.subList(i + 1, part.size)
                return head to tail
            }
            acc += segLen
        }
        return null
    }

    // ---- clustering ---------------------------------------------------------

    private class End(val record: Int, val partIndex: Int, val head: Boolean, val x: Double, val y: Double)

    /**
     * Group endpoints that lie within [toleranceM] of each other, transitively.
     *
     * A uniform grid keeps this proportional to the data rather than quadratic: at survey
     * scale there are tens of thousands of endpoints and the layer is read on a phone.
     */
    private fun clusterEndpoints(lines: List<Line>, toleranceM: Double): List<List<End>> {
        val ends = ArrayList<End>()
        for ((r, l) in lines.withIndex()) {
            l.parts.forEachIndexed { p, part ->
                if (part.size >= 2) {
                    ends.add(End(r, p, true, part.first()[0], part.first()[1]))
                    ends.add(End(r, p, false, part.last()[0], part.last()[1]))
                }
            }
        }
        if (ends.size < 2) return emptyList()

        val cell = max(toleranceM, 0.001)
        val buckets = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        for (i in ends.indices) {
            buckets.getOrPut(key(floor(ends[i].x / cell).toInt(), floor(ends[i].y / cell).toInt())) {
                mutableListOf()
            }.add(i)
        }

        val parent = IntArray(ends.size) { it }
        fun find(i: Int): Int {
            var k = i
            while (parent[k] != k) {
                parent[k] = parent[parent[k]]
                k = parent[k]
            }
            return k
        }

        for (i in ends.indices) {
            val cx = floor(ends[i].x / cell).toInt()
            val cy = floor(ends[i].y / cell).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                for (j in buckets[key(cx + dx, cy + dy)] ?: emptyList()) {
                    if (j <= i) continue
                    val ddx = ends[i].x - ends[j].x
                    val ddy = ends[i].y - ends[j].y
                    if (ddx * ddx + ddy * ddy <= toleranceM * toleranceM) {
                        val ri = find(i)
                        val rj = find(j)
                        if (ri != rj) parent[ri] = rj
                    }
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<End>>()
        for (i in ends.indices) groups.getOrPut(find(i)) { mutableListOf() }.add(ends[i])
        return groups.values.filter { it.size > 1 }
    }

    /**
     * The first [DISPLAY_WELD_LIMIT] pairs in [cluster], for the report.
     *
     * Deliberately capped while iterating: a cluster of size k has k(k-1)/2 pairs, and a
     * wide tolerance merges thousands of endpoints into clusters where building every pair
     * costs more than the whole measurement. The count itself is arithmetic, not a walk.
     */
    private fun pairsFor(cluster: List<End>): List<Weld> {
        if (cluster.size < 2) return emptyList()
        val out = ArrayList<Weld>(minOf(DISPLAY_WELD_LIMIT, cluster.size * 8))
        outer@ for (a in 0 until cluster.size) {
            for (b in a + 1 until cluster.size) {
                if (out.size >= DISPLAY_WELD_LIMIT) break@outer
                val u = cluster[a]
                val v = cluster[b]
                out.add(Weld(u.record, v.record, dist(u.x, u.y, v.x, v.y)))
            }
        }
        return out
    }

    /** How many endpoint pairs [cluster] represents, without building them. */
    private fun pairCount(cluster: List<End>): Int {
        val k = cluster.size
        return if (k < 2) 0 else k * (k - 1) / 2
    }

    private fun weldDistances(cluster: List<End>): List<Double> {
        val centre = centroid(cluster)
        return cluster.map { dist(it.x, it.y, centre[0], centre[1]) }
    }

    /** How much longer the geometry becomes if this cluster is welded to one position. */
    /**
     * How much the total length changes if this cluster is welded to one position.
     *
     * Computed from the two or so segments each endpoint belongs to, rather than by
     * rebuilding the layer. Rebuilding per cluster was quadratic in the cluster count:
     * on the real access layer, several thousand clusters each copying 5,000 features.
     */
    private fun weldDelta(lines: List<Line>, cluster: List<End>): Double {
        if (cluster.size < 2) return 0.0
        val centre = centroid(cluster)
        var delta = 0.0
        for (e in cluster) {
            val part = lines.getOrNull(e.record)?.parts?.getOrNull(e.partIndex) ?: continue
            if (part.size < 2) continue
            // only the segment at this end changes; the rest of the part is untouched
            val neighbour = if (e.head) part[1] else part[part.size - 2]
            val oldLen = dist(if (e.head) part[0] else part[part.size - 1], neighbour)
            val newLen = dist(centre, neighbour)
            // an inward move lengthens the segment and an outward move shortens it; the
            // signed total is what the user should see
            delta += newLen - oldLen
            // a part whose two ends are both in this cluster is counted once, not twice
            if (part.size == 2 && cluster.any { it.record == e.record && it.partIndex == e.partIndex && it.head != e.head }) {
                delta = delta - (newLen - oldLen)
            }
        }
        return delta
    }

    private fun centroid(cluster: List<End>): DoubleArray =
        doubleArrayOf(cluster.sumOf { it.x } / cluster.size, cluster.sumOf { it.y } / cluster.size)

    private fun applyWeld(lines: List<Line>, clusters: List<List<End>>): List<Line> {
        if (clusters.isEmpty()) return lines
        val target = HashMap<String, DoubleArray>()
        for (c in clusters) {
            val centre = centroid(c)
            for (e in c) target["${e.record}:${e.partIndex}:${e.head}"] = centre
        }
        return lines.mapIndexed { r, l ->
            l.copy(
                parts = l.parts.mapIndexed { p, part ->
                    if (part.size < 2) {
                        part
                    } else {
                        part.toMutableList().also { m ->
                            target["$r:$p:true"]?.let { m[0] = it }
                            target["$r:$p:false"]?.let { m[m.size - 1] = it }
                        }
                    }
                },
            )
        }
    }

    private fun endpointPosition(lines: List<Line>, e: End): DoubleArray? {
        val part = lines.getOrNull(e.record)?.parts?.getOrNull(e.partIndex) ?: return null
        return if (e.head) part.first() else part.last()
    }

    private fun applyWeldTo(lines: List<Line>, clusters: List<List<End>>): List<Line> =
        applyWeld(lines, clusters)

    // ---- connectivity -------------------------------------------------------

    data class Components(val components: Int, val groupSizes: List<Int>)

    /**
     * Connected groups of line geometry, ignoring direction.
     *
     * Component count is the only honest measure of a repair: a map cannot show a 4 cm gap,
     * and a length total does not change when a network falls apart into pieces.
     */
    fun connectivity(lines: List<Line>, toleranceM: Double = 0.0): Components {
        val n = lines.size
        if (n == 0) return Components(0, emptyList())
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var k = i
            while (parent[k] != k) {
                parent[k] = parent[parent[k]]
                k = parent[k]
            }
            return k
        }

        // Grid the vertices rather than comparing every pair of features. On the real
        // access layer that is ~1.9 billion distance checks as a nested scan - minutes of
        // work on a phone - against a few hundred thousand here. The grid cell is sized
        // from the data extent, decoupled from the tolerance, exactly as the routing
        // builder does, so a wide tolerance does not collapse the index.
        val pts = ArrayList<DoubleArray>(n * 8)
        val owner = IntArray(ptsCapacity(lines))
        var k = 0
        for ((r, l) in lines.withIndex()) {
            for (part in l.parts) {
                for (p in part) {
                    pts.add(p)
                    owner[k++] = r
                }
            }
        }
        if (pts.size < 2) return Components(n, listOf(n))

        val cell = gridCellSize(pts, toleranceM)
        val buckets = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        for (i in pts.indices) {
            buckets.getOrPut(key(floor(pts[i][0] / cell).toInt(), floor(pts[i][1] / cell).toInt())) {
                mutableListOf()
            }.add(i)
        }

        val tol2 = toleranceM * toleranceM
        for (i in pts.indices) {
            val cx = floor(pts[i][0] / cell).toInt()
            val cy = floor(pts[i][1] / cell).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                for (j in buckets[key(cx + dx, cy + dy)] ?: emptyList()) {
                    if (j <= i) continue
                    val ddx = pts[i][0] - pts[j][0]
                    val ddy = pts[i][1] - pts[j][1]
                    if (ddx * ddx + ddy * ddy > tol2) continue
                    val a = owner[i]
                    val b = owner[j]
                    if (a == b) continue
                    val ra = find(a)
                    val rb = find(b)
                    if (ra != rb) parent[ra] = rb
                }
            }
        }

        val sizes = HashMap<Int, Int>()
        for (i in 0 until n) sizes.merge(find(i), 1, Int::plus)
        return Components(sizes.size, sizes.values.sortedDescending())
    }

    private fun ptsCapacity(lines: List<Line>): Int {
        var n = 0
        for (l in lines) for (p in l.parts) n += p.size
        return n
    }

    /**
     * Grid cell width for [pts]. Sized from the extent so the index stays balanced whatever
     * the tolerance, and never below a floor, so a coincident-vertex scan (tolerance 0)
     * does not put every point in its own cell.
     */
    private fun gridCellSize(pts: List<DoubleArray>, toleranceM: Double): Double {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in pts) {
            if (p[0] < minX) minX = p[0]
            if (p[0] > maxX) maxX = p[0]
            if (p[1] < minY) minY = p[1]
            if (p[1] > maxY) maxY = p[1]
        }
        val span = max(max(maxX - minX, maxY - minY), 1.0)
        return max(max(span / 64.0, toleranceM), 1.0)
    }

    // ---- collinear ----------------------------------------------------------

    private fun dropCollinear(part: List<DoubleArray>, toleranceM: Double): List<DoubleArray> {
        if (part.size <= 2 || toleranceM <= 0) return part
        val keep = BooleanArray(part.size)
        keep[0] = true
        keep[part.size - 1] = true
        // Walk the run from left to right, keeping the furthest vertex still within
        // tolerance. Each pass must advance: an earlier version set `from = to - 1`, which
        // stopped advancing once `to` reached the last vertex and spun forever.
        var i = 0
        while (i < part.size - 1) {
            // Furthest vertex reachable from i without any intermediate vertex bowing
            // further than the tolerance. Tested as j grows, so j itself is always the
            // furthest honest keep point.
            var j = i + 1
            while (j < part.size - 1) {
                if (maxDeviation(part, i, j + 1) <= toleranceM) {
                    j++
                } else {
                    break
                }
            }
            keep[j] = true
            i = j
        }
        return part.filterIndexed { idx, _ -> keep[idx] }
    }

    private fun maxDeviation(part: List<DoubleArray>, from: Int, to: Int): Double {
        val a = part[from]
        val b = part[to]
        var worst = 0.0
        for (k in from + 1 until to) worst = max(worst, pointToSegment(part[k], a, b))
        return worst
    }

    // ---- geometry -----------------------------------------------------------

    private fun partLength(part: List<DoubleArray>): Double {
        var t = 0.0
        for (i in 0 until part.size - 1) t += dist(part[i], part[i + 1])
        return t
    }

    private fun pointToSegment(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val len2 = dx * dx + dy * dy
        if (len2 <= 0.0) return dist(p, a)
        val t = (((p[0] - a[0]) * dx) + ((p[1] - a[1]) * dy)) / len2
        val ct = max(0.0, min(1.0, t))
        val cx = a[0] + ct * dx
        val cy = a[1] + ct * dy
        return dist(p, doubleArrayOf(cx, cy))
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = dist(a[0], a[1], b[0], b[1])

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }
}