package org.gcap.core.prep

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Merges line features that run alongside each other without ever touching.
 *
 * A road digitised twice, or a trail recorded by two passes of the receiver, comes out as
 * two near-identical lines a metre or two apart. They never intersect, so the ordinary
 * healing step cannot join them, and every later stage pays for the duplicates: a network
 * built over them welds a dense mesh of phantom junctions. On the survey's trails layer
 * that is 42 million vertex pairs within 3 m, against 1 million for roads, and it is why
 * a network over trails exhausts the heap.
 *
 * This finds lines whose vertices stay within [maxGapM] of each other over most of their
 * length and keeps one of each group. It never invents geometry: the kept line is one of
 * the inputs, unchanged.
 */
object ParallelMerge {

    /** What a merge would do, before anything is written. */
    data class Plan(
        val groups: Int,
        val dropped: Int,
        val kept: Int,
        /** Length in metres that would disappear, i.e. the duplicated run. */
        val removedM: Double,
        val example: String?,
    )

    fun plan(
        lines: List<LineHealing.Line>,
        maxGapM: Double = 4.0,
        minOverlapFrac: Double = 0.6,
    ): Plan {
        if (lines.size < 2) return Plan(0, 0, lines.size, 0.0, null)
        val cell = max(maxGapM, 1.0)
        val grid = HashMap<Long, MutableList<Int>>()

        fun key(e: Double, n: Double): Long =
            floor(e / cell).toLong() * 73856093L xor floor(n / cell).toLong() * 19349663L

        lines.forEachIndexed { i, l ->
            for (p in l.parts) for (xy in p)
                grid.getOrPut(key(xy[0], xy[1])) { ArrayList() }.add(i)
        }

        val parent = IntArray(lines.size) { it }
        fun find(a: Int): Int { var r = a; while (parent[r] != r) r = parent[r]; return r }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // Two lines are candidates when their vertices coincide closely at the same place.
        lines.forEachIndexed { i, l ->
            for (p in l.parts) for (xy in p) {
                val gx = floor(xy[0] / cell).toLong(); val gy = floor(xy[1] / cell).toLong()
                for (dx in -1..1) for (dy in -1..1) {
                    grid[(gx + dx) * 73856093L xor (gy + dy) * 19349663L]?.forEach { j ->
                        if (j <= i) return@forEach
                        if (find(i) == find(j)) return@forEach
                        if (closeness(lines[i], lines[j], maxGapM) >= minOverlapFrac) union(i, j)
                    }
                }
            }
        }

        val groupsByRoot = HashMap<Int, MutableList<Int>>()
        lines.indices.forEach { groupsByRoot.getOrPut(find(it)) { ArrayList() }.add(it) }
        val mergeable = groupsByRoot.values.filter { it.size > 1 }
        var dropped = 0
        var removed = 0.0
        var example: String? = null
        for (g in mergeable) {
            // Keep the longest; drop the rest, so the survivor is the best-drawn one.
            val keep = g.maxByOrNull { lengthM(lines[it]) }!!
            for (i in g) if (i != keep) { dropped++; removed += lengthM(lines[i]) }
            if (example == null) {
                example = g.joinToString(", ") { "record ${lines[it].recordIndex}" }
            }
        }
        return Plan(mergeable.size, dropped, lines.size - dropped, removed, example)
    }

    /** Apply [plan]: the same lines minus the duplicates it would drop. */
    fun apply(lines: List<LineHealing.Line>, maxGapM: Double = 4.0): List<LineHealing.Line> {
        val cell = max(maxGapM, 1.0)
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(e: Double, n: Double): Long =
            floor(e / cell).toLong() * 73856093L xor floor(n / cell).toLong() * 19349663L
        lines.forEachIndexed { i, l ->
            for (p in l.parts) for (xy in p)
                grid.getOrPut(key(xy[0], xy[1])) { ArrayList() }.add(i)
        }
        val parent = IntArray(lines.size) { it }
        fun find(a: Int): Int { var r = a; while (parent[r] != r) r = parent[r]; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }
        lines.forEachIndexed { i, l ->
            for (p in l.parts) for (xy in p) {
                val gx = floor(xy[0] / cell).toLong(); val gy = floor(xy[1] / cell).toLong()
                for (dx in -1..1) for (dy in -1..1)
                    grid[(gx + dx) * 73856093L xor (gy + dy) * 19349663L]?.forEach { j ->
                        if (j > i && find(i) != find(j) &&
                            closeness(lines[i], lines[j], maxGapM) >= 0.6) union(i, j)
                    }
            }
        }
        val groups = HashMap<Int, MutableList<Int>>()
        lines.indices.forEach { groups.getOrPut(find(it)) { ArrayList() }.add(it) }
        val drop = HashSet<Int>()
        for (g in groups.values) if (g.size > 1) {
            val keep = g.maxByOrNull { lengthM(lines[it]) }!!
            for (i in g) if (i != keep) drop.add(i)
        }
        return lines.filterIndexed { i, _ -> i !in drop }
    }

    private fun lengthM(l: LineHealing.Line): Double {
        var d = 0.0
        for (p in l.parts) for (k in 0 until p.size - 1)
            d += hypot(p[k][0] - p[k + 1][0], p[k][1] - p[k + 1][1])
        return d
    }

    private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

    /** Fraction of [a]'s vertices that sit within [gapM] of [b]. */
    private fun closeness(a: LineHealing.Line, b: LineHealing.Line, gapM: Double): Double {
        var total = 0
        var near = 0
        val bpts = ArrayList<DoubleArray>()
        for (p in b.parts) for (xy in p) bpts.add(doubleArrayOf(xy[0], xy[1]))
        if (bpts.isEmpty()) return 0.0
        val cell = max(gapM, 1.0)
        val grid = HashMap<Long, MutableList<DoubleArray>>()
        for (p in bpts) {
            val kk = floor(p[0] / cell).toLong() * 73856093L xor floor(p[1] / cell).toLong() * 19349663L
            grid.getOrPut(kk) { ArrayList() }.add(p)
        }
        for (p in a.parts) for (xy in p) {
            total++
            val e = xy[0]; val n = xy[1]
            val gx = floor(e / cell).toLong(); val gy = floor(n / cell).toLong()
            var found = false
            for (dx in -1..1) if (!found) for (dy in -1..1) if (!found) {
                grid[(gx + dx) * 73856093L xor (gy + dy) * 19349663L]?.forEach { q ->
                    if (!found && hypot(e - q[0], n - q[1]) <= gapM) found = true
                }
            }
            if (found) near++
        }
        return if (total == 0) 0.0 else near.toDouble() / total
    }
}
