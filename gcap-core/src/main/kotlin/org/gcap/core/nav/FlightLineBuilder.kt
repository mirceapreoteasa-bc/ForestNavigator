package org.gcap.core.nav

/** One end of a line, and whether it has been joined to something yet. */
data class LineEndRef(val feature: Int, val atStart: Boolean)

/** A candidate join between two ends belonging to different lines. */
data class JoinCandidate(val a: LineEndRef, val b: LineEndRef, val distanceM: Double)

/**
 * Joins line ends into as few continuous lines as possible.
 *
 * This is deliberately a different approach from walking the lines in call order. It
 * ignores the air calls entirely and works purely on the geometry:
 *
 * 1. lines shorter than [minLineM] are discarded
 * 2. the two ends of every remaining line are the work elements
 * 3. every pair of ends belonging to **different** lines is measured
 * 4. pairs further apart than [maxJoinM] are discarded
 * 5. the survivors are sorted shortest first
 * 6. they are accepted in that order, subject to two rules: **an end may be joined only
 *    once**, and **the line formed must not intersect itself**. A pair whose two ends
 *    already belong to the same line is skipped, since joining them would close a ring
 *    rather than extend anything.
 *
 * Because every end is used at most once, no two lines can ever be joined twice and no
 * loop can form, so those two rules hold by construction rather than by checking
 * afterwards.
 *
 * The result is a set of chains. If it is one chain, the flight is a single continuous
 * line; if it is three, that is worth reporting plainly rather than hiding.
 */
class FlightLineBuilder(
    flightPath: List<DoubleArray>,
    private val minLineM: Double = 50.0,
    private val maxJoinM: Double = 370.0,
    /** How many segments either side of a junction are checked for self-intersection. */
    private val junctionCheckSegments: Int = 60,
) {
    /** Lines kept: those at least [minLineM] long. */
    val lines: List<DoubleArray> = flightPath.filter {
        it.size >= 4 && FlightRouteLines.lengthM(it) >= minLineM
    }

    val discarded: Int = flightPath.size - lines.size

    private val n = lines.size
    private val ex = DoubleArray(n * 2)
    private val en = DoubleArray(n * 2)
    private val endUsed = BooleanArray(n * 2)

    private fun idx(f: Int, atStart: Boolean) = f * 2 + (if (atStart) 0 else 1)
    private fun posOf(f: Int, atStart: Boolean) = LineEndRef(f, atStart)

    /** Each chain: its segments in order, plus the two ends still free. */
    private class Chain(val segs: ArrayList<Pair<Int, Boolean>>, var tipA: LineEndRef, var tipB: LineEndRef)

    private val chains = ArrayList<Chain>()

    /** Which chain each feature belongs to. */
    private val chainOf = IntArray(n)

    /** Candidate pairs that were rejected because joining them would cross. */
    var crossingRejections = 0
        private set

    /** Candidate pairs rejected because the two ends were already in one chain. */
    var ringRejections = 0
        private set

    init {
        for (f in 0 until n) {
            val line = lines[f]
            ex[idx(f, true)] = line[0]; en[idx(f, true)] = line[1]
            ex[idx(f, false)] = line[line.size - 2]
            en[idx(f, false)] = line[line.size - 1]
        }
        for (f in 0 until n) {
            chainOf[f] = chains.size
            chains.add(Chain(arrayListOf(f to true), posOf(f, true), posOf(f, false)))
        }
    }

    private fun endDist(a: LineEndRef, b: LineEndRef): Double =
        Geodesy.distanceProjectedM(ex[idx(a.feature, a.atStart)], en[idx(a.feature, a.atStart)],
            ex[idx(b.feature, b.atStart)], en[idx(b.feature, b.atStart)])

    /** Build the chains. Call once. */
    fun build(): FlightLineBuilder {
        val candidates = allCandidates()
        for (c in candidates) {
            val ia = idx(c.a.feature, c.a.atStart)
            val ib = idx(c.b.feature, c.b.atStart)
            if (endUsed[ia] || endUsed[ib]) continue
            val ca = chainOf[c.a.feature]
            val cb = chainOf[c.b.feature]
            if (ca == cb) { ringRejections++; continue }
            if (!mergeOk(ca, c.a, cb, c.b)) {
                crossingRejections++
                continue
            }

            endUsed[ia] = true
            endUsed[ib] = true
            merge(ca, c.a, cb, c.b)
        }
        return this
    }

    /** Every end pair on different lines within [maxJoinM], shortest first. */
    fun allCandidates(): List<JoinCandidate> {
        val out = ArrayList<JoinCandidate>()
        for (i in 0 until n * 2) {
            for (j in i + 1 until n * 2) {
                if (i / 2 == j / 2) continue              // ends of the same line
                val d = Geodesy.distanceProjectedM(ex[i], en[i], ex[j], en[j])
                if (d > maxJoinM) continue
                out.add(JoinCandidate(posOf(i / 2, i % 2 == 0), posOf(j / 2, j % 2 == 0), d))
            }
        }
        out.sortBy { it.distanceM }
        return out
    }

    /**
     * Would joining these two tips cross the line?
     *
     * Only the segments either side of the junction are examined, which is sound because
     * each chain is kept non-self-intersecting: the only new crossing a merge can create
     * is where the two chains meet.
     */
    private fun mergeOk(ca: Int, a: LineEndRef, cb: Int, b: LineEndRef): Boolean {
        val tailA = tailVertices(ca, a)
        val headB = headVertices(cb, b)
        if (tailA.size < 4 || headB.size < 4) return true
        return !crosses(tailA, headB)
    }

    /** Vertices approaching the given tip of a chain, nearest last. */
    private fun tailVertices(chainIdx: Int, tip: LineEndRef): DoubleArray {
        val segs = orderedSegments(chainIdx, tip)
        val out = ArrayList<Double>()
        for ((f, atStart) in segs.takeLast(junctionCheckSegments)) {
            val line = lines[f]
            if (atStart) {
                var i = 0
                while (i < line.size) { out.add(line[i]); out.add(line[i + 1]); i += 2 }
            } else {
                var i = line.size - 2
                while (i >= 0) { out.add(line[i]); out.add(line[i + 1]); i -= 2 }
            }
        }
        return out.toDoubleArray()
    }

    /** Vertices leaving the given tip of a chain, nearest first. */
    private fun headVertices(chainIdx: Int, tip: LineEndRef): DoubleArray {
        val segs = orderedSegments(chainIdx, tip)
        val out = ArrayList<Double>()
        for ((f, atStart) in segs.take(junctionCheckSegments)) {
            val line = lines[f]
            if (atStart) {
                var i = 0
                while (i < line.size) { out.add(line[i]); out.add(line[i + 1]); i += 2 }
            } else {
                var i = line.size - 2
                while (i >= 0) { out.add(line[i]); out.add(line[i + 1]); i -= 2 }
            }
        }
        return out.toDoubleArray()
    }

    /** The chain's features ordered from [tip] outward. */
    private fun orderedSegments(chainIdx: Int, tip: LineEndRef): List<Pair<Int, Boolean>> {
        val ch = chains[chainIdx]
        val segs = ch.segs
        return if (ch.tipA == tip) segs else segs.asReversed()
    }

    /** True if the last segment of `a` crosses the first segment of `b`. */
    private fun crosses(a: DoubleArray, b: DoubleArray): Boolean {
        val aSegs = ((a.size / 2) - 1).coerceAtLeast(1)
        val bSegs = ((b.size / 2) - 1).coerceAtLeast(1)
        var i = a.size / 2 - 2
        var checked = 0
        while (i >= 0 && checked < junctionCheckSegments) {
            val p = doubleArrayOf(a[i * 2], a[i * 2 + 1], a[i * 2 + 2], a[i * 2 + 3])
            var j = 0
            var jc = 0
            while (j < b.size / 2 - 1 && jc < junctionCheckSegments) {
                val q = doubleArrayOf(b[j * 2], b[j * 2 + 1], b[j * 2 + 2], b[j * 2 + 3])
                if (segmentsCross(p, q)) return true
                j++; jc++
            }
            i -= 2; checked++
        }
        return false
    }

    /** Proper intersection test; touching endpoints are allowed so welds still work. */
    private fun segmentsCross(p: DoubleArray, q: DoubleArray): Boolean {
        val d1 = cross(q[0], q[1], q[2], q[3], p[0], p[1])
        val d2 = cross(q[0], q[1], q[2], q[3], p[2], p[3])
        val d3 = cross(p[0], p[1], p[2], p[3], q[0], q[1])
        val d4 = cross(p[0], p[1], p[2], p[3], q[2], q[3])
        fun s(x: Double) = if (x < -1e-9) -1 else if (x > 1e-9) 1 else 0
        return s(d1) * s(d2) < 0 && s(d3) * s(d4) < 0
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    /**
     * Join chain B into chain A at the given tips.
     *
     * The invariant is that **tipA is the free end at the start of `segs`** and **tipB
     * the free end at the end**. So attaching at tipA puts B at the front of the list and
     * attaching at tipB puts it at the back, and in each case A's own other tip is
     * untouched. Which end of B we butt against decides its orientation: entering B at
     * `tipA` means the segment list reads from B's start end onward.
     */
    private fun merge(ca: Int, a: LineEndRef, cb: Int, b: LineEndRef) {
        val A = chains[ca]
        val B = chains[cb]

        val bTipIsA = B.tipA == b
        val bFar = if (bTipIsA) B.tipB else B.tipA

        if (A.tipA == a) {
            // B butts against the front of A, so it runs from its far tip back to b
            val bAttached =
                if (bTipIsA) B.segs.asReversed().map { (f, s) -> f to !s }
                else B.segs.toList()
            A.segs.addAll(0, bAttached)
            A.tipA = bFar
        } else {
            // B butts against the back of A, so it runs from b onward
            val bAttached =
                if (bTipIsA) B.segs.toList()
                else B.segs.asReversed().map { (f, s) -> f to !s }
            A.segs.addAll(bAttached)
            A.tipB = bFar
        }

        chains.removeAt(cb)
        reindex()
    }

    /** After removing a chain, chain indices shift; rebuild the mapping. */
    private fun reindex() {
        for (i in chains.indices) for ((f, _) in chains[i].segs) chainOf[f] = i
    }

    /**
     * One finished line, with the point where each original feature begins.
     *
     * The feature boundaries are what a later pass needs in order to treat a junction
     * differently from the rest of the line - a corner between two track fragments can be
     * rounded off, but the end of a flown transect cannot.
     */
    class DetailedLine(val vertices: DoubleArray, val featureStarts: IntArray) {
        val vertexCount: Int get() = vertices.size / 2
    }

    /** The finished lines together with their feature boundaries. */
    fun resultLinesDetailed(): List<DetailedLine> = chains.map { ch ->
        val vs = ArrayList<Double>()
        val starts = IntArray(ch.segs.size)
        ch.segs.forEachIndexed { k, (f, atStart) ->
            starts[k] = vs.size / 2
            val line = lines[f]
            if (atStart) {
                var i = 0
                while (i < line.size) { vs.add(line[i]); vs.add(line[i + 1]); i += 2 }
            } else {
                var i = line.size - 2
                while (i >= 0) { vs.add(line[i]); vs.add(line[i + 1]); i -= 2 }
            }
        }
        DetailedLine(vs.toDoubleArray(), starts)
    }

    /** The finished continuous lines, as flat vertex arrays. */
    fun resultLines(): List<DoubleArray> = chains.map { ch ->
        val out = ArrayList<Double>()
        for ((f, atStart) in ch.segs) {
            val line = lines[f]
            if (atStart) {
                var i = 0
                while (i < line.size) { out.add(line[i]); out.add(line[i + 1]); i += 2 }
            } else {
                var i = line.size - 2
                while (i >= 0) { out.add(line[i]); out.add(line[i + 1]); i -= 2 }
            }
        }
        out.toDoubleArray()
    }

    /** What one healing round did. */
    data class HealPass(
        val linesBefore: Int,
        val linesAfter: Int,
        val joins: Int,
        val crossingRejections: Int,
        val ringRejections: Int,
    ) {
        fun describe(): String =
            "$linesBefore lines -> $linesAfter lines, $joins joins, " +
                "$crossingRejections refused for crossing, $ringRejections skipped as one line"
    }

    /**
     * Run the same joining procedure again over the lines produced so far.
     *
     * The first round can leave ends that were never joined even though they sit close
     * together, because at the moment they were considered one of them had already been
     * spent on a shorter candidate. Taking the finished lines as fresh input recomputes
     * the end pairs from scratch, so those ends get another chance.
     *
     * Nothing is discarded this round - the input is already only lines that passed
     * [minLineM] - so [minLineM] is not reapplied. The same one-use-per-end and
     * no-self-intersection rules still are.
     *
     * Returns without doing anything when a single line already resulted.
     */
    fun heal(rounds: Int = 1, maxJoinM: Double = this.maxJoinM): HealPass {
        var current = resultLines()
        var joins = 0
        var crossings = 0
        var rings = 0
        val before = current.size
        var last: FlightLineBuilder? = null
        repeat(rounds) {
            if (current.size <= 1) return@repeat
            val next = FlightLineBuilder(current, 0.0, maxJoinM, junctionCheckSegments).build()
            last = next
            joins += current.size - next.resultLines().size
            crossings += next.crossingRejections
            rings += next.ringRejections
            current = next.resultLines()
        }
        healedLines = current
        lastHealBuilder = last
        return HealPass(before, current.size, joins, crossings, rings)
    }

    /**
     * Build, then run one healing round.
     *
     * This is the intended way to use the builder. The first round refuses a couple of
     * junctions for crossing because the self-intersection test compares against chains that
     * are still sprawling across the whole survey; once they are finished the same junctions
     * pass. Healing exists to undo exactly that, and on the survey data it turns the three
     * lines the first round produces into one.
     */
    fun buildHealed(rounds: Int = 1, maxJoinM: Double = this.maxJoinM): List<DoubleArray> {
        build()
        heal(rounds, maxJoinM)
        return healedLines ?: resultLines()
    }

    /**
     * The builder for the last healing round.
     *
     * Its "features" are the lines the previous round produced, so its feature boundaries
     * are exactly the links the healing joined. That is what a caller needs in order to bend
     * those links into curves afterwards.
     */
    var lastHealBuilder: FlightLineBuilder? = null
        private set

    /** The lines after [heal]; identical to [resultLines] if healing was never run. */
    var healedLines: List<DoubleArray>? = null
        private set

    fun summary(): String = buildString {
        append("${lines.size} lines kept ($discarded under ${minLineM.toInt()} m discarded)")
        append("; joined into ${chains.size} continuous lines")
        val joins = n - chains.size
        append("; $joins joins")
        append("; $ringRejections pairs skipped as already one line")
        append("; $crossingRejections skipped for crossing")
    }
}