package org.gcap.core.nav

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Recognises the flown transects inside the flight path.
 *
 * A transect is the short segment that one pair of calls brackets: the aircraft flies it
 * straight, so it must be kept straight. In the data it shows up as a feature roughly a
 * hundred metres long with a bare-named call at its middle and the A and B calls near its
 * two ends - for example call `001`, with `001A` and `001B` at either extremity. The
 * bare call sits within about 33 m of the middle of that span in the survey data.
 *
 * Knowing which features are transects matters because those are the corners the app must
 * **not** round off. Every other corner is a join between two recorded track fragments and is
 * a digitising artefact rather than something the aircraft flew, so those can be blended.
 */
class TransectClassifier(
    calls: List<AirCall>,
    /** How near the middle of a feature a bare call has to lie. */
    private val midToleranceM: Double = 35.0,
    /** How near an end of a feature an A or B call has to lie. */
    private val endToleranceM: Double = 45.0,
    /**
     * Plausible length band for a transect.
     *
     * On the survey data the A-to-B call distance runs from 75 m to 328 m, so a transect
     * feature is a little longer than the call span. Raising the ceiling from 220 m to
     * 400 m takes the count from 228 to exactly 291 - one per A/_/B triple - and widening
     * it further finds nothing new, so 400 m is where the population ends.
     */
    private val minLengthM: Double = 40.0,
    private val maxLengthM: Double = 400.0,
) {
    private val bare: List<AirCall> = calls.filter { it.suffix.isEmpty() }
    private val ends: List<AirCall> = calls.filter { it.suffix.isNotEmpty() }

    /** True when [feature] looks like a flown transect. */
    fun isTransect(feature: DoubleArray): Boolean {
        if (feature.size < 4) return false
        val len = FlightRouteLines.lengthM(feature)
        if (len < minLengthM || len > maxLengthM) return false

        val sx = feature[0]; val sy = feature[1]
        val ex = feature[feature.size - 2]; val ey = feature[feature.size - 1]
        val mx = (sx + ex) / 2.0; val my = (sy + ey) / 2.0

        val bareOk = bare.any { dist(it.e, it.n, mx, my) <= midToleranceM }
        if (!bareOk) return false

        // an A/B pair at opposite ends, and nothing else near the middle
        val atStart = ends.any { dist(it.e, it.n, sx, sy) <= endToleranceM }
        val atEnd = ends.any { dist(it.e, it.n, ex, ey) <= endToleranceM }
        return atStart && atEnd
    }

    private fun dist(e1: Double, n1: Double, e2: Double, n2: Double): Double =
        Geodesy.distanceProjectedM(e1, n1, e2, n2)

    /** Index of the transect that brackets [call]'s number, for reporting. */
    fun transectNumbers(features: List<DoubleArray>): List<Int> {
        val out = ArrayList<Int>()
        features.forEachIndexed { i, f ->
            if (isTransect(f)) {
                val sx = f[0]; val sy = f[1]
                val near = ends.filter { dist(it.e, it.n, sx, sy) <= endToleranceM }
                val n = near.minByOrNull { abs(it.order - nearestBareOrder(f)) }?.order
                if (n != null) out.add(n)
            }
        }
        return out
    }

    private fun nearestBareOrder(f: DoubleArray): Int {
        val mx = (f[0] + f[f.size - 2]) / 2.0
        val my = (f[1] + f[f.size - 1]) / 2.0
        return bare.minByOrNull { dist(it.e, it.n, mx, my) }?.order ?: 0
    }
}