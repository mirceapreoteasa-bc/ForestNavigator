package org.gcap.core.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rounds off the corners where two recorded track fragments were joined.
 *
 * The flight path arrives as many separate pieces. Where two of them meet there is often a
 * visible kink, because the aircraft turned while the track was being recorded rather than
 * because the path really has a corner there. Those kinks are rounded off with a circular
 * arc so the joined line reads as a continuous track.
 *
 * Two rules keep this from doing damage:
 *
 *  * **Transect ends are never rounded.** A transect is a line the aircraft deliberately
 *    flew straight, bracketed by its `A` and `B` calls, so its ends are real and its corners
 *    are meaningful. Only the joins between fragments get blended.
 *  * **Gentle bends are left alone.** A corner is only rounded when the angle between the
 *    two pieces is under [maxAngleDeg]. A straight-through join measures 180 degrees and is
 *    untouched, which keeps real flying visible and only fixes the sharp joins that come from
 *    the track being cut up.
 *
 * Corners are also left alone when there is not enough line either side to hold the curve.
 */
object FlightLineFillet {

    /** A corner that was rounded off, and why not when it was not. */
    data class Report(
        val rounded: Int = 0,
        val tooShallow: Int = 0,
        val transectEnd: Int = 0,
        val tooShort: Int = 0,
    ) {
        fun describe(): String =
            "$rounded corners rounded; $tooShallow left because they are gentle; " +
                "$transectEnd left at transect ends; $tooShort left for want of line"
    }

    /**
     * Blend the corners of [line] that fall on a feature boundary.
     *
     * [transect] answers whether a given feature index is a flown transect. [starts] lists
     * the vertex index at which each original feature begins, so index k of the boundary
     * list belongs between feature k-1 and feature k.
     */
    fun apply(
        line: DoubleArray,
        starts: IntArray,
        transect: (Int) -> Boolean,
        radiusM: Double = 60.0,
        maxAngleDeg: Double = 160.0,
        /** Straightest length of arc allowed; keeps the polyline light. */
        maxArcStepDeg: Double = 12.0,
        /**
         * How far the curve may bow away from the recorded path.
         *
         * A fillet of radius r turned through angle t bows out by r*(1/cos(t/2) - 1), which
         * runs away as the corner sharpens: at r = 60 a 140 degree turn already bows out
         * 115 m. Capping the bow keeps the curve near the track the aircraft actually flew,
         * and the radius is reduced to achieve that rather than the corner being skipped.
         */
        maxBulgeM: Double = 40.0,
    ): Pair<DoubleArray, Report> {
        if (starts.size < 2) return line to Report()

        val vs = ArrayList<Double>()
        var rep = Report()
        var prev = 0
        // the last point actually written out. When one corner has just been rounded, the
        // room left on the approach to the next corner is bounded by where that arc ended,
        // not by any original vertex, so this has to be tracked separately.
        var lastOutE = 0.0
        var lastOutN = 0.0

        for (b in 1 until starts.size) {
            val at = starts[b]
            val leftFeat = b - 1
            val rightFeat = b
            val corner = at * 2

            // the incoming leg belongs to feature b-1, which begins at starts[b-1]
            val featureStart = starts[leftFeat]
            val decision = tryFillet(
                line, featureStart, prev, doubleArrayOf(lastOutE, lastOutN), at, corner,
                transect(leftFeat), transect(rightFeat),
                radiusM, maxAngleDeg, maxArcStepDeg, maxBulgeM,
            )
            when (decision) {
                is Fillet -> {
                    // copy the run up to the tangent point, then the arc
                    var i = prev
                    while (i < decision.t1Index) {
                        vs.add(line[i * 2]); vs.add(line[i * 2 + 1])
                        lastOutE = line[i * 2]; lastOutN = line[i * 2 + 1]
                        i++
                    }
                    var k = 0
                    while (k < decision.arc.size) {
                        vs.add(decision.arc[k]); vs.add(decision.arc[k + 1])
                        lastOutE = decision.arc[k]; lastOutN = decision.arc[k + 1]
                        k += 2
                    }
                    rep = rep.copy(rounded = rep.rounded + 1)
                    // skip the vertex the arc already covers: it is either the far copy of
                    // the corner or the first vertex of the next fragment, and the tangent
                    // point lies beyond it in both cases
                    prev = decision.t2Index
                }
                is Skip -> {
                    rep = when (decision.why) {
                        Why.TOO_SHALLOW -> rep.copy(tooShallow = rep.tooShallow + 1)
                        Why.TRANSECT -> rep.copy(transectEnd = rep.transectEnd + 1)
                        Why.TOO_SHORT -> rep.copy(tooShort = rep.tooShort + 1)
                    }
                }
            }
        }

        var i = prev
        while (i < line.size / 2) { vs.add(line[i * 2]); vs.add(line[i * 2 + 1]); i++ }
        return vs.toDoubleArray() to rep
    }

    private enum class Why { TOO_SHALLOW, TRANSECT, TOO_SHORT }
    private class Skip(val why: Why)
    private class Fillet(val t1Index: Int, val t2Index: Int, val arc: DoubleArray)

    private fun tryFillet(
        line: DoubleArray, featureStart: Int, fromIndex: Int, lastOut: DoubleArray, at: Int, corner: Int,
        leftTransect: Boolean, rightTransect: Boolean,
        radiusM: Double, maxAngleDeg: Double, maxArcStepDeg: Double, maxBulgeM: Double,
    ): Any {
        if (leftTransect || rightTransect) return Skip(Why.TRANSECT)

        // need a real segment each side of the boundary to measure a direction from
        val i0 = at - 1
        val i1 = at
        // a vertex either side of the boundary is needed to read a direction from
        // `fromIndex` is how far the output has already been written. A corner behind it
        // was consumed by an earlier arc, and rounding it again would reach back over
        // geometry that has already been emitted.
        if (i0 < fromIndex) return Skip(Why.TOO_SHORT)
        if (i0 < featureStart || i0 < 1 || i1 + 1 >= line.size / 2) return Skip(Why.TOO_SHORT)

        val ax = line[(i0 - 1) * 2]; val ay = line[(i0 - 1) * 2 + 1]
        val bx = line[i0 * 2]; val by = line[i0 * 2 + 1]
        val cx = line[i1 * 2]; val cy = line[i1 * 2 + 1]
        val dx = line[(i1 + 1) * 2]; val dy = line[(i1 + 1) * 2 + 1]

        val inLen = Geodesy.distanceProjectedM(ax, ay, bx, by)
        val outLen = Geodesy.distanceProjectedM(cx, cy, dx, dy)
        if (inLen <= 0.0 || outLen <= 0.0) return Skip(Why.TOO_SHORT)

        val ux = (bx - ax) / inLen; val uy = (by - ay) / inLen
        val vx = (dx - cx) / outLen; val vy = (dy - cy) / outLen

        // The rule is about the interior angle at the junction: 180 degrees means the two
        // pieces run straight through each other. That is 0 on the turn angle, so the two
        // have to be told apart or every gentle bend looks sharp.
        val dot = ux * vx + uy * vy
        val turnDeg = Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1.0, 1.0)))
        val interiorDeg = 180.0 - turnDeg
        if (interiorDeg >= maxAngleDeg || turnDeg < 1e-6) return Skip(Why.TOO_SHALLOW)

        // The corner is where the two leg lines meet when extended, not the midpoint of the
        // gap between them: when fragments are welded across a turn, the gap does not run
        // along either leg, and building the arc from its midpoint puts the curve off in
        // space. Extending the legs to their intersection keeps the arc in the wedge.
        val a1x = bx - ux * (Geodesy.distanceProjectedM(ax, ay, bx, by))
        val a1y = by - uy * (Geodesy.distanceProjectedM(ax, ay, bx, by))
        val den = ux * vy - uy * vx
        if (kotlin.math.abs(den) < 1e-9) return Skip(Why.TOO_SHALLOW)
        val rx = cx - a1x
        val ry = cy - a1y
        val s = (rx * vy - ry * vx) / den
        val px0 = a1x + ux * s
        val py0 = a1y + uy * s

        // the corner has to lie ahead along both legs for a fillet to make sense here
        val toCornerIn = Geodesy.distanceProjectedM(bx, by, px0, py0)
        val toCornerOut = Geodesy.distanceProjectedM(cx, cy, px0, py0)
        if (toCornerIn < 0.0 || toCornerOut < 0.0) return Skip(Why.TOO_SHORT)

        val half = Math.toRadians(turnDeg) / 2.0

        // Pick the largest radius that still fits. Three things limit it: the cap the caller
        // asked for, how far the curve may bow from the recorded path, and the length of the
        // two legs between the corner and the vertices beyond it. A radius wider than those
        // would put the tangent point past the neighbouring vertex, so the curve would start
        // behind geometry already written and the line would double back on itself.
        val tanHalf = kotlin.math.tan(half)
        val bulgePerR = 1.0 / cos(half) - 1.0
        var r = radiusM
        if (bulgePerR > 1e-9) r = min(r, maxBulgeM / bulgePerR)
        val roomBefore = Geodesy.distanceProjectedM(lastOut[0], lastOut[1], bx, by)
        val roomIn = roomBefore - toCornerIn
        val roomOut = outLen - toCornerOut
        if (tanHalf > 1e-9) r = min(r, min(roomIn, roomOut) / tanHalf)
        if (r <= 0.5) return Skip(Why.TOO_SHORT)

        val t = r * tanHalf

        var nx = vx - ux
        var ny = vy - uy
        val nLen = sqrt(nx * nx + ny * ny)
        if (nLen < 1e-9) return Skip(Why.TOO_SHORT)
        nx /= nLen; ny /= nLen
        val cDist = r / cos(half)
        val cxp = px0 + nx * cDist
        val cyp = py0 + ny * cDist

        val t1x = px0 - ux * t; val t1y = py0 - uy * t
        val t2x = px0 + vx * t; val t2y = py0 + vy * t

        // emit every vertex that lies before the incoming tangent point: the first index
        // whose distance from the corner has come back inside t is where the arc takes over
        var t1Index = fromIndex
        while (t1Index < i0 &&
            Geodesy.distanceProjectedM(line[t1Index * 2], line[t1Index * 2 + 1], px0, py0) > t
        ) t1Index++
        // ... and resume at the first vertex beyond the outgoing one
        var t2Index = i1
        while (t2Index + 1 < line.size / 2 &&
            Geodesy.distanceProjectedM(line[t2Index * 2], line[t2Index * 2 + 1], px0, py0) <= t
        ) t2Index++

        val ang1 = Math.atan2(t1y - cyp, t1x - cxp)
        val ang2 = Math.atan2(t2y - cyp, t2x - cxp)
        var sweep = ang2 - ang1
        while (sweep > Math.PI) sweep -= 2.0 * Math.PI
        while (sweep < -Math.PI) sweep += 2.0 * Math.PI

        val steps = min(
            64,
            maxOf(2, (Math.abs(Math.toDegrees(sweep)) / maxArcStepDeg).toInt() + 1),
        )
        val arc = DoubleArray(steps * 2)
        for (k in 0 until steps) {
            val a = ang1 + sweep * k / (steps - 1)
            arc[k * 2] = cxp + r * cos(a)
            arc[k * 2 + 1] = cyp + r * sin(a)
        }
        // pin the ends to the true tangent points so the curve meets both legs exactly
        arc[0] = t1x; arc[1] = t1y
        arc[arc.size - 2] = t2x; arc[arc.size - 1] = t2y
        return Fillet(t1Index, t2Index, arc)
    }
}