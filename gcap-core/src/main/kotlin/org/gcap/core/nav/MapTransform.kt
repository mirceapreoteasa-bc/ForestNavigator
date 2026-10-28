package org.gcap.core.nav

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rotation-capable, isotropic world<->screen transform.
 *
 * World coordinates are metres in the working projected CRS (easting, northing).
 * Screen coordinates are pixels, y increasing downward.
 *
 * The previous canvas transform computed metres-per-pixel independently for east
 * and north, so the map was stretched whenever the view aspect differed from the
 * hardcoded 500x400 m window, and rotating it would have sheared the image. Both
 * defects are structural: there is a single [metresPerPixel] here, and rotation is
 * about [focusE]/[focusN] (the view centre), not the origin.
 *
 * @param bearingDeg compass bearing that should point up the screen. 0 = north up,
 *   90 = east up.
 */
class MapTransform(
    private val focusE: Double,
    private val focusN: Double,
    val metresPerPixel: Double,
    private val bearingDeg: Double,
    private val viewWidthPx: Int,
    private val viewHeightPx: Int,
) {
    init {
        require(metresPerPixel > 0.0 && !metresPerPixel.isNaN()) { "metresPerPixel must be positive, was $metresPerPixel" }
        require(viewWidthPx > 0 && viewHeightPx > 0) { "view must have positive size" }
    }

    private val theta = Math.toRadians(normaliseBearing(bearingDeg))
    private val cosT = cos(theta)
    private val sinT = sin(theta)

    /** Screen position of a world point, in pixels. */
    fun worldToScreen(e: Double, n: Double): DoubleArray {
        val dx = e - focusE
        val dy = n - focusN
        // rotate so that `bearingDeg` ends up pointing up the screen
        val rx = dx * cosT - dy * sinT
        val ry = dx * sinT + dy * cosT
        return doubleArrayOf(
            viewWidthPx / 2.0 + rx / metresPerPixel,
            viewHeightPx / 2.0 - ry / metresPerPixel,
        )
    }

    /** World position under a screen pixel. Exact inverse of [worldToScreen]. */
    fun screenToWorld(x: Double, y: Double): DoubleArray {
        val rx = (x - viewWidthPx / 2.0) * metresPerPixel
        val ry = -(y - viewHeightPx / 2.0) * metresPerPixel
        val dx = rx * cosT + ry * sinT
        val dy = -rx * sinT + ry * cosT
        return doubleArrayOf(focusE + dx, focusN + dy)
    }

    /** Ground distance covered by the full view width, in metres. */
    fun viewportWidthMetres(): Double = metresPerPixel * viewWidthPx

    fun viewportHeightMetres(): Double = metresPerPixel * viewHeightPx

    /**
     * Traditional map scale denominator for a screen of the given density.
     *
     * scaleDen = groundMetres / displayMetres, where one display pixel is
     * 0.0254/dpi metres. Uses true physical pixels, so the same scale number
     * means the same thing on any device.
     */
    fun scaleDenominator(dpi: Double): Double = metresPerPixel * dpi / 0.0254

    /** Ground metres per display pixel implied by a scale denominator. */
    fun metresPerPixelForScale(scaleDen: Double, dpi: Double): Double = scaleDen * 0.0254 / dpi

    /**
     * Metres spanned by one screen pixel along the horizontal screen axis, expressed
     * as a bearing. At 0 this is the bearing that points up the screen.
     */
    fun screenAxisBearing(): Double = normaliseBearing(bearingDeg)

    /**
     * Ground bounding box of the visible area, as [minE, minN, maxE, maxN].
     *
     * Once the map is rotated the visible ground is no longer the axis-aligned window,
     * and a naive rectangle around the focus would clip shapes near the corners that
     * are plainly on screen - which then vanish as the map turns.
     */
    fun viewportBounds(): DoubleArray {
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        val w = viewWidthPx.toDouble()
        val h = viewHeightPx.toDouble()
        for (cx in CORNERS_X) for (cy in CORNERS_Y) {
            val p = screenToWorld(cx * w, cy * h)
            if (p[0] < x0) x0 = p[0]
            if (p[0] > x1) x1 = p[0]
            if (p[1] < y0) y0 = p[1]
            if (p[1] > y1) y1 = p[1]
        }
        return doubleArrayOf(x0, y0, x1, y1)
    }

    fun movedFocusTo(e: Double, n: Double): MapTransform =
        MapTransform(e, n, metresPerPixel, bearingDeg, viewWidthPx, viewHeightPx)

    fun withBearing(bearing: Double): MapTransform =
        MapTransform(focusE, focusN, metresPerPixel, bearing, viewWidthPx, viewHeightPx)

    fun zoomedTo(mpp: Double): MapTransform =
        MapTransform(focusE, focusN, mpp, bearingDeg, viewWidthPx, viewHeightPx)

    fun resizedTo(w: Int, h: Int): MapTransform =
        MapTransform(focusE, focusN, metresPerPixel, bearingDeg, w, h)

    companion object {
        private val CORNERS_X = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        private val CORNERS_Y = doubleArrayOf(0.0, 1.0, 0.0, 1.0)

        /** Fold any bearing into [0, 360). */
        fun normaliseBearing(deg: Double): Double {
            // NaN % 360 is NaN, and a NaN bearing poisons every screen coordinate it
            // reaches, so the map silently draws nothing. Treat it as north.
            if (deg.isNaN()) return 0.0
            val m = deg % 360.0
            return if (m < 0) m + 360.0 else m
        }
    }
}

/**
 * Great-circle helpers on a sphere of the WGS84 mean radius.
 *
 * Navigation distances and bearings must not come from projected easting/northing
 * arithmetic: a project spanning two UTM zones has real scale distortion, and
 * mixing an easting from one zone with a northing from another is meaningless.
 */
object Geodesy {
    const val EARTH_RADIUS_M = 6371008.8

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * Math.asin(minOf(1.0, sqrt(a)))
    }

    /** Initial bearing in degrees, [0,360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return MapTransform.normaliseBearing(Math.toDegrees(Math.atan2(y, x)))
    }

    /** Great-circle distance between two points already projected to metres. */
    fun distanceProjectedM(e1: Double, n1: Double, e2: Double, n2: Double): Double =
        hypot(e2 - e1, n2 - n1)

    /**
     * Perpendicular distance in metres from point (px,py) to segment (ax,ay)-(bx,by).
     * Also returns the parametric position of the closest point along the segment.
     */
    fun pointSegmentDistance(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
    ): DoubleArray {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 <= 0.0) return doubleArrayOf(hypot(px - ax, py - ay), 0.0)
        var t = ((px - ax) * vx + (py - ay) * vy) / len2
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * vx
        val cy = ay + t * vy
        return doubleArrayOf(hypot(px - cx, py - cy), t)
    }

    /**
     * Approximate destination point, for dead reckoning and synthetic leg building.
     */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): DoubleArray {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        var l2 = Math.toDegrees(l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2)))
        while (l2 > 180.0) l2 -= 360.0
        while (l2 < -180.0) l2 += 360.0
        return doubleArrayOf(Math.toDegrees(p2), l2)
    }
}
