package org.gcap.core.nav

import kotlin.math.abs

/** One continuous line produced by welding, with what went into it. */
data class RepairedLine(
    /** Flat `e,n,e,n,...` vertices, ordered along the line. */
    val points: DoubleArray,
    /** Indices of the source features joined into this line, in order. */
    val featureIds: List<Int>,
    /** True when the chain met its own starting end and became a ring. */
    val closed: Boolean,
) {
    val vertexCount: Int get() = points.size / 2
    val joinCount: Int get() = featureIds.size - 1

    fun lengthM(): Double {
        var sum = 0.0
        var i = 0
        while (i + 3 < points.size) {
            sum += Geodesy.distanceProjectedM(points[i], points[i + 1], points[i + 2], points[i + 3])
            i += 2
        }
        return sum
    }
}

/** An end that could have joined two others, so the choice was not forced. */
data class AmbiguousJoin(val feature: Int, val atStart: Boolean, val candidates: Int)

/**
 * Welds loose line ends into continuous lines before any route is built.
 *
 * The flight path as drawn is many short pieces that do not quite meet, and building a
 * route across that produces the three failures worth avoiding: connections made in the
 * middle of a line, the same stretch of ground used twice, and shortcuts because the
 * pieces nearest each other are not the pieces that continue the flight. Welding first
 * removes all three, because afterwards each line is one object with two ends.
 *
 * The join rule is exactly the one given: **two long lines whose ends lie within
 * [weldM] are joined.** Short features are discarded first, because they are the debris
 * that makes neighbouring lines ambiguous.
 *
 * Welding is greedy and nearest-first. Where an end could join more than one other, the
 * nearest is taken and the ambiguity is recorded in [ambiguous], so a place where the
 * drawing is genuinely unclear is visible rather than silently decided.
 */
class FlightPathRepair(
    flightPath: List<DoubleArray>,
    private val weldM: Double = 30.0,
    private val minFeatureM: Double = 200.0,
) {
    /** Source features kept for welding: the long ones. */
    val kept: List<DoubleArray> = flightPath.filter {
        it.size >= 4 && FlightRouteLines.lengthM(it) >= minFeatureM
    }

    /** Source features discarded as too short to be a real line. */
    val discarded: Int = flightPath.count {
        it.size >= 4 && FlightRouteLines.lengthM(it) < minFeatureM
    }

    private val n = kept.size

    /** Endpoint coordinates, indexed `feature * 2 + (0 start, 1 end)`. */
    private val ex = DoubleArray(n * 2)
    private val en = DoubleArray(n * 2)

    /** Whether each end has been consumed by a join. */
    private val endUsed = BooleanArray(n * 2)

    /** Features already emitted into some line, so none is missed or doubled. */
    private val emitted = BooleanArray(n)

    private fun idx(f: Int, atStart: Boolean) = f * 2 + (if (atStart) 0 else 1)

    private fun other(atStart: Boolean) = !atStart

    val repaired: List<RepairedLine>
    val ambiguous: List<AmbiguousJoin>

    init {
        for (f in 0 until n) {
            val line = kept[f]
            ex[idx(f, true)] = line[0]; en[idx(f, true)] = line[1]
            ex[idx(f, false)] = line[line.size - 2]; en[idx(f, false)] = line[line.size - 1]
        }
        val result = ArrayList<RepairedLine>()
        val amb = ArrayList<AmbiguousJoin>()
        buildChains(result, amb)
        repaired = result
        ambiguous = amb
    }

    /** Lines that never joined anything, i.e. single features left on their own. */
    val unjoinedLines: Int get() = repaired.count { it.featureIds.size == 1 }

    fun summary(): String = buildString {
        append("$n long features welded into ${repaired.size} lines")
        append(" ($discarded short features discarded, weld ${weldM.toInt()} m)")
        append("; joins made ${repaired.sumOf { it.joinCount }}")
        append("; $unjoinedLines never joined")
        if (ambiguous.isNotEmpty()) append("; ${ambiguous.size} ambiguous joins")
        val longest = repaired.maxByOrNull { it.lengthM() }
        longest?.let { append("; longest ${"%.1f".format(it.lengthM() / 1000.0)} km from ${it.featureIds.size} pieces") }
    }

    private fun buildChains(out: ArrayList<RepairedLine>, amb: ArrayList<AmbiguousJoin>) {
        // Phase 1: walk a chain from every end that actually has a neighbour. Starting a
        // chain from an isolated end would emit that feature on its own and then mark it
        // spent, so the two lines either side of a genuine join would never be welded.
        for (originF in 0 until n) {
            for (originAtStart in booleanArrayOf(true, false)) {
                if (endUsed[idx(originF, originAtStart)]) continue
                val ox = ex[idx(originF, originAtStart)]
                val oy = en[idx(originF, originAtStart)]
                // the origin's own end sits at distance 0, so it must be excluded or the
                // chain would immediately join itself and close
                if (freeEndsNear(ox, oy, exclude = idx(originF, originAtStart)).isEmpty()) {
                    continue                                     // nothing to join here
                }
                walkFrom(originF, originAtStart, out, amb)
            }
        }

        // Phase 2: whatever never joined anything is a line in its own right.
        for (f in 0 until n) {
            if (emitted[f]) continue
            val line = kept[f]
            val pts = ArrayList<DoubleArray>()
            for (i in line.indices step 2) pts.add(doubleArrayOf(line[i], line[i + 1]))
            val flat = DoubleArray(pts.size * 2)
            pts.forEachIndexed { i, p -> flat[i * 2] = p[0]; flat[i * 2 + 1] = p[1] }
            out.add(RepairedLine(flat, listOf(f), closed = false))
            emitted[f] = true
            endUsed[idx(f, true)] = true
            endUsed[idx(f, false)] = true
        }
    }

    private fun walkFrom(
        originF: Int,
        originAtStart: Boolean,
        out: ArrayList<RepairedLine>,
        amb: ArrayList<AmbiguousJoin>,
    ) {
        // The origin end opens the chain and is marked used, so no other chain can start
        // from it and walk the same ground again.
        endUsed[idx(originF, originAtStart)] = true

        val pts = ArrayList<DoubleArray>()
        val ids = ArrayList<Int>()
        val first = kept[originF]
        for (i in first.indices step 2) pts.add(doubleArrayOf(first[i], first[i + 1]))
        ids.add(originF)

        var tipF = originF
        var tipAtStart = originAtStart
        var closed = false

        // Features already in this chain. A chain must never fold back onto a line it has
        // already taken in, or the same ground is welded in twice. The origin is the one
        // exception: coming back to its far end is what closes a ring.
        val inChain = HashSet<Int>()
        inChain.add(originF)

        while (true) {
            val tx = ex[idx(tipF, tipAtStart)]
            val ty = en[idx(tipF, tipAtStart)]
            // the tip is already marked used, so it is never offered as its own candidate
            val cands = freeEndsNear(tx, ty).filter { it / 2 !in inChain || it / 2 == originF }
            if (cands.isEmpty()) break
            if (cands.size > 1) amb.add(AmbiguousJoin(tipF, tipAtStart, cands.size))

            val pick = cands.minByOrNull {
                Geodesy.distanceProjectedM(tx, ty, ex[it], en[it])
            }!!
            val g = pick / 2
            val gAtStart = pick % 2 == 0

            val gl = kept[g]
            if (gAtStart) {
                var i = 0
                while (i < gl.size) { pts.add(doubleArrayOf(gl[i], gl[i + 1])); i += 2 }
            } else {
                var i = gl.size - 2
                while (i >= 0) { pts.add(doubleArrayOf(gl[i], gl[i + 1])); i -= 2 }
            }
            // the origin can be re-entered to close a ring: its geometry still joins the
            // chain, but it must not be listed twice or the same feature looks welded twice
            if (inChain.add(g)) ids.add(g)
            endUsed[pick] = true

            val newF = g
            val newAtStart = !gAtStart
            if (newF == originF && newAtStart == originAtStart) {
                closed = true
                break
            }
            if (endUsed[idx(newF, newAtStart)]) break
            endUsed[idx(newF, newAtStart)] = true
            tipF = newF
            tipAtStart = newAtStart
        }

        // Every feature in a finished chain has been spoken for, both ends.
        for (f in ids) {
            emitted[f] = true
            endUsed[idx(f, true)] = true
            endUsed[idx(f, false)] = true
        }

        if (pts.size >= 2) {
            val flat = DoubleArray(pts.size * 2)
            pts.forEachIndexed { i, p -> flat[i * 2] = p[0]; flat[i * 2 + 1] = p[1] }
            out.add(RepairedLine(flat, ids, closed))
        }
    }

    /** Free ends within [weldM] of (x, y), excluding the end itself. */
    private fun freeEndsNear(x: Double, y: Double, exclude: Int = -1): List<Int> {
        val out = ArrayList<Int>()
        for (k in 0 until n * 2) {
            if (endUsed[k] || k == exclude) continue
            // a feature already placed in a line must never be joined into another, so
            // its remaining ends are not offered as candidates at all
            if (emitted[k / 2]) continue
            val d = Geodesy.distanceProjectedM(x, y, ex[k], en[k])
            if (d <= weldM) out.add(k)
        }
        return out
    }
}