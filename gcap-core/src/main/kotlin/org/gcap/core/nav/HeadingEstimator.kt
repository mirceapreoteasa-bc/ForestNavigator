package org.gcap.core.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Course over ground from the recent track, for "map rotates with me" mode.
 *
 * Two decisions shape this:
 *
 *  - The window is measured in **metres, not seconds**. Helicopter navigation
 *    covers 0-200 km/h, so a time window describes no consistent stretch of
 *    ground; 100 m of travel is a consistent one.
 *  - Heading is the principal axis of the window's points (a least-squares line
 *    fit), not the bearing between consecutive fixes. Differencing raw fixes
 *    amplifies receiver noise, which is what makes naive heading unusable at a
 *    standstill.
 */
class HeadingEstimator(
    private val windowMetres: Double = 40.0,
    private val minSpeedMps: Double = 1.0,
    private val maxFixAgeMs: Long = 30_000L,
    private val maxWindowMs: Long = 20_000L,
    private val minFixes: Int = 4,
    /**
     * Age weighting of the fit. The newest fix counts fully and each [recencyHalfLifeS]
     * seconds of age halves a fix's influence, so a turn is picked up within a few
     * seconds instead of after the whole window has rolled past. At speed the window is
     * short in time and this changes nothing; it bites at walking pace, which is where
     * the long catch-up was reported. Zero or negative disables the weighting.
     */
    private val recencyHalfLifeS: Double = 6.0,
) {
    private class Fix(val e: Double, val n: Double, val tMs: Long)

    private val fixes = ArrayDeque<Fix>()

    /**
     * Ground speed over the retained window, m/s.
     *
     * Averaged rather than differenced across the last two fixes: under canopy a
     * two-fix speed is mostly receiver noise, and a noise-driven speed either hides
     * the arrow or lets a stationary phone look like it is travelling.
     */
    var lastSpeedMps: Double = 0.0
        private set

    fun add(e: Double, n: Double, tMs: Long) {
        fixes.addLast(Fix(e, n, tMs))
        trim()
    }

    /**
     * Bound the window three ways, because one bound cannot serve every speed.
     *
     *  - [maxWindowMs] caps staleness: the arrow is never built from history older
     *    than this, so the lag is bounded in seconds and not only in metres.
     *  - [minFixes] keeps enough points to fit a line, so a fast vehicle does not
     *    reduce the fit to two noisy fixes.
     *  - [windowMetres] then shortens it further when moving slowly, which is where
     *    a metre-only window produced the long catch-up after a turn.
     */
    private fun trim() {
        val n = fixes.size
        if (n < 2) return

        var oldest = 0
        val newestT = fixes[n - 1].tMs
        while (n - oldest > minFixes && newestT - fixes[oldest].tMs > maxWindowMs) oldest++

var acc = 0.0
        var dropCount = oldest
        var i = n - 1
        while (i > oldest) {
            acc += hypot(fixes[i].e - fixes[i - 1].e, fixes[i].n - fixes[i - 1].n)
            if (acc >= windowMetres) {
                dropCount = i - 1
                break
            }
            i--
        }
        repeat(dropCount) { fixes.removeFirst() }
    }

    fun reset() {
        fixes.clear()
        lastSpeedMps = 0.0
    }

    /**
     * Current course estimate.
     *
     * [quality] is 1.0 for a dead-straight run and 0.5 for directionless scatter,
     * so the caller can refuse to rotate on a noisy fix set.
     */
    fun estimate(nowMs: Long): HeadingState {
        trim()
        val f = fixes.toList()
        if (f.size < 2) return HeadingState.INVALID

        val last = f.last()
        val first = f.first()
        val spanS = (last.tMs - first.tMs) / 1000.0
        val pathM = run {
            var p = 0.0
            for (i in 1 until f.size) p += hypot(f[i].e - f[i - 1].e, f[i].n - f[i - 1].n)
            p
        }
        lastSpeedMps = if (spanS > 0.0) pathM / spanS else 0.0

        val netE = last.e - f.first().e
        val netN = last.n - f.first().n
        val netM = hypot(netE, netN)

        if (netM < 1.0) return HeadingState.INVALID

        // age-weight the fit: the newest fix counts fully, older fixes fade by half-life
        val w = DoubleArray(f.size)
        var wsum = 0.0
        for (i in f.indices) {
            val ageS = (last.tMs - f[i].tMs) / 1000.0
            val wi = if (recencyHalfLifeS > 0.0) Math.pow(2.0, -ageS / recencyHalfLifeS) else 1.0
            w[i] = wi
            wsum += wi
        }
        if (wsum <= 0.0) return HeadingState.INVALID

        var me = 0.0
        var mn = 0.0
        for (i in f.indices) { me += w[i] * f[i].e; mn += w[i] * f[i].n }
        me /= wsum; mn /= wsum

        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in f.indices) {
            val dx = f[i].e - me
            val dy = f[i].n - mn
            sxx += w[i] * dx * dx; syy += w[i] * dy * dy; sxy += w[i] * dx * dy
        }

        // principal axis: theta measured CCW from east
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        var ux = kotlin.math.cos(theta)
        var uy = kotlin.math.sin(theta)
        // orient along the direction actually travelled
        if (ux * netE + uy * netN < 0) { ux = -ux; uy = -uy }

        // variance along vs across the fitted axis
        var along = 0.0
        var across = 0.0
        for (i in f.indices) {
            val dx = f[i].e - me
            val dy = f[i].n - mn
            val a = dx * ux + dy * uy
            val c = -dx * uy + dy * ux
            along += w[i] * a * a; across += w[i] * c * c
        }
        val total = along + across
        val quality = if (total <= 0.0) 0.0 else along / total

        // a doubling-back track has a poor course even if it is geometrically straight
        val straightness = if (pathM <= 0.0) 0.0 else netM / pathM
        // a silent receiver is not the same as standing still: keep the last heading
        // but stop claiming we are travelling
        val ageMs = (nowMs - last.tMs).coerceAtLeast(0L)
        val stale = ageMs > maxFixAgeMs

        val bearing = MapTransform.normaliseBearing(Math.toDegrees(atan2(ux, uy)))
        // A short window must not make the arrow demand a long run before it appears,
        // so the gate is on ground actually covered, capped by what the window holds.
        val movedEnough = netM >= minOf(windowMetres * 0.25, 10.0)
        val moving = !stale && movedEnough && lastSpeedMps >= minSpeedMps

        return HeadingState(
            bearingDeg = bearing,
            speedMps = lastSpeedMps,
            distanceM = netM,
            pathM = pathM,
            quality = quality,
            straightness = straightness,
            moving = moving,
            valid = true,
            stale = stale,
            ageMs = ageMs,
        )
    }
}

/** Result of a [HeadingEstimator] pass. */
data class HeadingState(
    val bearingDeg: Double = Double.NaN,
    val speedMps: Double = 0.0,
    val distanceM: Double = 0.0,
    val pathM: Double = 0.0,
    val quality: Double = 0.0,
    val straightness: Double = 0.0,
    val moving: Boolean = false,
    val valid: Boolean = false,
    val stale: Boolean = false,
    val ageMs: Long = 0L,
) {
    /**
     * A course worth showing, even slowly.
     *
     * [moving] gates the map rotation and needs speed. The direction-of-travel arrow only
     * needs the track to be coherent: a slow but steady walk has [straightness] near 1.0,
     * while a parked phone's drift scatters in every direction and scores low. So a crew
     * walking at half a metre per second still sees which way they are going.
     */
    val hasCourse: Boolean get() = valid && !stale && straightness >= 0.5

    companion object {
        val INVALID = HeadingState()
    }
}
