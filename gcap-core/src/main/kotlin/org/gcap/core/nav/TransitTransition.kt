package org.gcap.core.nav

import kotlin.math.hypot
import kotlin.math.min

/**
 * Builds the curve that carries the aircraft out of one flown line and into the next.
 *
 * A transit used to be drawn as a straight line between the end of one line and the start of
 * the next. That puts a corner where a straight line suddenly becomes a different direction,
 * and the corner lands right on the end of a transect - a place the aircraft deliberately
 * flew straight, and which must not be bent.
 *
 * The fix is to leave both lines exactly as they are and bend only the connection. The curve
 * starts at the end of the first line already pointing along that line's bearing, holds that
 * bearing for [leadInM], then turns gradually and arrives at the second line pointing along
 * its bearing, holding it for [leadOutM] before the line itself begins. Because the curve
 * leaves and arrives along the bearing of the line it meets, there is no corner at either
 * end and the transects are not altered by a single metre.
 */
object TransitTransition {

    /**
     * @param p0 where the curve starts: the end of the line being left.
     * @param outDir unit vector in the direction of travel leaving [p0].
     * @param p1 where the curve ends: the start of the line being joined.
     * @param inDir unit vector in the direction of travel arriving at [p1].
     */
    fun curve(
        p0: DoubleArray,
        outDir: DoubleArray,
        p1: DoubleArray,
        inDir: DoubleArray,
        leadInM: Double = 10.0,
        leadOutM: Double = 10.0,
        stepM: Double = 5.0,
    ): DoubleArray {
        // a long lead would reach past the far end on a short connection, so it is capped
        // against the distance actually available
        val span = hypot(p1[0] - p0[0], p1[1] - p0[1])
        val lim = if (span > 0.0) span * 0.4 else 0.0
        val li = min(leadInM, lim)
        val lo = min(leadOutM, lim)
        val a = doubleArrayOf(p0[0] + outDir[0] * li, p0[1] + outDir[1] * li)
        val b = doubleArrayOf(p1[0] - inDir[0] * lo, p1[1] - inDir[1] * lo)

        val out = ArrayList<Double>()
        out.add(p0[0]); out.add(p0[1])
        if (li > 0.0) { out.add(a[0]); out.add(a[1]) }

        val chord = hypot(b[0] - a[0], b[1] - a[1])
        // handles long enough to bend smoothly, but never longer than the chord itself
        val h = if (chord <= 0.0) 0.0 else min(chord * 0.45, maxOf(li, lo) * 3.0 + chord * 0.35)
        val c1 = doubleArrayOf(a[0] + outDir[0] * h, a[1] + outDir[1] * h)
        val c2 = doubleArrayOf(b[0] - inDir[0] * h, b[1] - inDir[1] * h)

        if (chord > 1e-9) {
            val steps = maxOf(2, min(128, (chord / stepM).toInt() + 1))
            // interior points only: s=0 is a and s=1 is b, both added separately
            for (i in 1 until steps - 1) {
                val s = i.toDouble() / (steps - 1)
                val u = 1.0 - s
                val w0 = u * u * u
                val w1 = 3.0 * u * u * s
                val w2 = 3.0 * u * s * s
                val w3 = s * s * s
                out.add(w0 * a[0] + w1 * c1[0] + w2 * c2[0] + w3 * b[0])
                out.add(w0 * a[1] + w1 * c1[1] + w2 * c2[1] + w3 * b[1])
            }
        }
        if (lo > 0.0) { out.add(b[0]); out.add(b[1]) }
        out.add(p1[0]); out.add(p1[1])
        return out.toDoubleArray()
    }

    /** Unit vector from [a] to [b]; falls back to east when the points coincide. */
    fun direction(flat: DoubleArray, i: Int, j: Int): DoubleArray {
        val de = flat[j] - flat[i]
        val dn = flat[j + 1] - flat[i + 1]
        val l = hypot(de, dn)
        return if (l < 1e-9) doubleArrayOf(1.0, 0.0) else doubleArrayOf(de / l, dn / l)
    }

    /** Direction of travel along [line] leaving its final vertex. */
    fun outBearingAtEnd(line: DoubleArray): DoubleArray {
        val n = line.size / 2
        return if (n >= 2) direction(line, (n - 3) * 2, (n - 2) * 2) else doubleArrayOf(1.0, 0.0)
    }

    /** Direction of travel along [line] arriving at its first vertex. */
    fun inBearingAtStart(line: DoubleArray): DoubleArray =
        if (line.size / 2 >= 2) direction(line, 0, 2) else doubleArrayOf(1.0, 0.0)
}