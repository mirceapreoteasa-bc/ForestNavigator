package org.gcap.core.nav

/**
 * The whole flight-line assembly as one pass: join, fillet, heal, blend.
 *
 * The order is forced by the transect rule. Transect classification exists per
 * original feature, and healing rebuilds its lines from the chains it is given,
 * so the fillet has to run while original feature identity is still available:
 *
 *   1. join the raw fragments (round 1); original feature identity is intact
 *   2. fillet the interior corners, transect ends excluded
 *   3. join the filleted lines into as few as they allow (the healing round)
 *   4. bend the links that joining created into tangent-continuous curves
 *
 * Steps 3 and 4 are what the earlier separate exports could not combine: the
 * fillet used to run on the round-1 lines and the blend on the healed ones, so
 * no single file carried both. Chaining them produces one continuous line whose
 * interior corners are rounded, whose transect ends are untouched, and whose
 * transit links leave and arrive along the bearing of the transect they touch.
 */
object FlightLinePipeline {

    /** What each stage of the pipeline did. */
    class Report(
        val kept: Int,
        val discarded: Int,
        val round1Lines: Int,
        val joins: Int,
        val crossingRejections: Int,
        val cornersRounded: Int,
        val cornersGentle: Int,
        val cornersAtTransectEnds: Int,
        val cornersTooShort: Int,
        val finalLines: Int,
        val linksBlended: Int,
        /** How many links the final join created but could not blend. */
        val linksLeftStraight: Int,
    ) {
        fun describe(): String = buildString {
            append("$kept lines kept ($discarded under the minimum discarded)")
            append("; round 1 joined them into $round1Lines ($joins joins, $crossingRejections refused for crossing)")
            append("; $cornersRounded corners rounded, $cornersGentle gentle, ")
            append("$cornersAtTransectEnds left at transect ends, $cornersTooShort too short")
            append("; final join produced $finalLines line(s), $linksBlended link(s) blended")
            if (linksLeftStraight > 0) append(", $linksLeftStraight left straight")
        }
    }

    class Result(
        /** The continuous lines: one if the survey allowed it. */
        val lines: List<DoubleArray>,
        val report: Report,
    )

    /**
     * Run the whole chain. [calls] must include the bare-named ones: the
     * transect classifier finds the middle of each transect with them.
     */
    fun run(
        flightPath: List<DoubleArray>,
        calls: List<AirCall>,
        minLineM: Double = 50.0,
        maxJoinM: Double = 1000.0,
        healRounds: Int = 1,
    ): Result {
        // transects, and the kept-index mapping the fillet needs: its feature
        // boundaries are numbered by the builder's kept-lines list, not the file
        val cls = TransectClassifier(calls)
        val isTransect = BooleanArray(flightPath.size) { cls.isTransect(flightPath[it]) }
        var k = 0
        val keptIdx = IntArray(flightPath.size)
        for ((i, f) in flightPath.withIndex()) {
            if (f.size >= 4 && FlightRouteLines.lengthM(f) >= minLineM) keptIdx[k++] = i
        }
        val kept = keptIdx.copyOf(k)

        // 1. round 1: join the raw fragments
        val b = FlightLineBuilder(flightPath, minLineM, maxJoinM)
        b.build()
        val round1Lines = b.resultLines().size

        // 2. fillet the interior corners, transect ends excluded
        val detailed = b.resultLinesDetailed()
        val filleted = ArrayList<DoubleArray>(detailed.size)
        var rounded = 0
        var gentle = 0
        var transectEnds = 0
        var tooShort = 0
        for (d in detailed) {
            val (out, rep) = FlightLineFillet.apply(
                d.vertices, d.featureStarts,
                { i -> isTransect[kept[i]] },
            )
            rounded += rep.rounded
            gentle += rep.tooShallow
            transectEnds += rep.transectEnd
            tooShort += rep.tooShort
            filleted.add(out)
        }

        // 3. join the filleted lines into as few as they allow. Nothing is
        // discarded here, so minLineM is 0, exactly as heal() uses internally.
        val hb = FlightLineBuilder(filleted, 0.0, maxJoinM)
        hb.build()
        val joinedInRound1 = hb.resultLines().size == 1
        if (!joinedInRound1) hb.heal(rounds = healRounds, maxJoinM = maxJoinM)
        val joined = hb.lastHealBuilder ?: hb
        val finalLines = hb.healedLines?.size ?: hb.resultLines().size

        // 4. bend the links the final join created
        val hd = joined.resultLinesDetailed()
        val out = ArrayList<DoubleArray>(hd.size)
        var blended = 0
        var leftStraight = 0
        for (d in hd) {
            val v = d.vertices
            if (d.featureStarts.size < 2) {
                out.add(v)
                continue
            }
            val buf = ArrayList<Double>()
            var prev = 0
            for (bi in 1 until d.featureStarts.size) {
                val at = d.featureStarts[bi]
                val p0 = at - 1
                val p1 = at
                if (p0 < 1 || p1 + 1 >= v.size / 2) continue
                val gap = Geodesy.distanceProjectedM(
                    v[p0 * 2], v[p0 * 2 + 1], v[p1 * 2], v[p1 * 2 + 1],
                )
                if (gap > 1.0 && p0 - 1 >= prev) {
                    val a = v.copyOfRange(p0 * 2, p0 * 2 + 2)
                    val c = v.copyOfRange(p1 * 2, p1 * 2 + 2)
                    val outDir = TransitTransition.direction(v, (p0 - 1) * 2, p0 * 2)
                    val inDir = TransitTransition.direction(v, p1 * 2, (p1 + 1) * 2)
                    val curve = TransitTransition.curve(a, outDir, c, inDir)
                    var i = prev
                    while (i < p0) { buf.add(v[i * 2]); buf.add(v[i * 2 + 1]); i++ }
                    var j = 0
                    while (j < curve.size) { buf.add(curve[j]); buf.add(curve[j + 1]); j += 2 }
                    blended++
                    prev = p1
                } else if (gap > 1.0) {
                    leftStraight++
                }
            }
            var i = prev
            while (i < v.size / 2) { buf.add(v[i * 2]); buf.add(v[i * 2 + 1]); i++ }
            out.add(buf.toDoubleArray())
        }

        return Result(
            out,
            Report(
                kept = b.lines.size,
                discarded = b.discarded,
                round1Lines = round1Lines,
                joins = b.lines.size - b.resultLines().size,
                crossingRejections = b.crossingRejections,
                cornersRounded = rounded,
                cornersGentle = gentle,
                cornersAtTransectEnds = transectEnds,
                cornersTooShort = tooShort,
                finalLines = finalLines,
                linksBlended = blended,
                linksLeftStraight = leftStraight,
            ),
        )
    }
}