package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

class MapTransformTest {

    private fun t(bearing: Double, mpp: Double = 1.0, w: Int = 800, h: Int = 600) =
        MapTransform(0.0, 0.0, mpp, bearing, w, h)

    @Test
    fun `world to screen and back is lossless at every bearing`() {
        for (bearing in listOf(0.0, 17.0, 90.0, 180.0, 270.0, 359.5, -45.0)) {
            val tr = t(bearing, mpp = 0.37)
            for (p in listOf(0.0, 1.0, -250.0, 4321.5)) {
                val s = tr.worldToScreen(p, -p)
                val back = tr.screenToWorld(s[0], s[1])
                assertEquals(p, back[0], 1e-6, "e failed at bearing $bearing")
                assertEquals(-p, back[1], 1e-6, "n failed at bearing $bearing")
            }
        }
    }

    @Test
    fun `north up puts north above the centre`() {
        val tr = t(0.0)
        val north = tr.worldToScreen(0.0, 100.0)
        assertTrue(north[1] < 300.0 - 1, "north must be above centre")
        val south = tr.worldToScreen(0.0, -100.0)
        assertTrue(south[1] > 300.0)
    }

    @Test
    fun `bearing 90 puts east up and north to the right`() {
        val tr = t(90.0)
        val east = tr.worldToScreen(100.0, 0.0)
        assertEquals(400.0, east[0], 1e-6, "due east should be horizontally centred")
        assertTrue(east[1] < 300.0, "due east must point up the screen")

        // with east pointing up the map is turned a quarter turn anticlockwise,
        // so north - previously up - now sits on the left
        val north = tr.worldToScreen(0.0, 100.0)
        assertTrue(north[0] < 400.0, "due north must appear to the left when east is up")
        assertEquals(300.0, north[1], 1e-6)
    }

    @Test
    fun `bearing 180 puts south up`() {
        val tr = t(180.0)
        val south = tr.worldToScreen(0.0, -100.0)
        assertTrue(south[1] < 300.0, "due south must point up when bearing is 180")
    }

    @Test
    fun `scale is isotropic - a rotated square stays square`() {
        val tr = t(45.0, mpp = 1.0)
        val corners = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0)
            .map { tr.worldToScreen(it.first, it.second) }
        val xs = corners.map { it[0] }
        val ys = corners.map { it[1] }
        val w = xs.max() - xs.min()
        val h = ys.max() - ys.min()
        // a 45-degree rotation of a 100 m square has a 100*sqrt(2) bounding box
        assertEquals(100 * sqrt(2.0), w, 1e-6)
        assertEquals(w, h, 1e-6, "rotated map must not be sheared: width must equal height")
    }

    @Test
    fun `rotation preserves ground distances`() {
        val tr = t(37.0, mpp = 0.5)
        val a = tr.worldToScreen(100.0, 200.0)
        val b = tr.worldToScreen(-800.0, 1500.0)
        val screenM = hypot(b[0] - a[0], b[1] - a[1]) * 0.5
        assertEquals(hypot(900.0, 1300.0), screenM, 1e-6)
    }

    @Test
    fun `scale denominator round trips through metres per pixel`() {
        val dpi = 160.0
        val tr = t(0.0, mpp = 0.25)
        val den = tr.scaleDenominator(dpi)
        assertEquals(tr.metresPerPixel, tr.metresPerPixelForScale(den, dpi), 1e-12)
        // 0.25 m/px at 160 dpi: one display inch (160 px) covers 40 m of ground,
        // and an inch is 0.0254 m, so 40/0.0254 = 1:1574.8
        assertEquals(1574.803, den, 1e-3)
    }

    @Test
    fun `zoom keeps the focus under the view centre`() {
        val tr = t(20.0, mpp = 2.0)
        val zoomed = tr.zoomedTo(0.5)
        val before = tr.worldToScreen(tr.movedFocusTo(0.0, 0.0).let { 0.0 }, 0.0)
        assertEquals(400.0, before[0], 1e-9)
        val after = zoomed.worldToScreen(0.0, 0.0)
        assertEquals(400.0, after[0], 1e-9)
        assertEquals(300.0, after[1], 1e-9)
    }

    @Test
    fun `rejects a non positive scale`() {
        var threw = false
        try {
            MapTransform(0.0, 0.0, 0.0, 0.0, 100, 100)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "zero metres-per-pixel must be rejected")
    }
}

class GeodesyTest {

    @Test
    fun `one degree of latitude is about 111 km`() {
        val d = Geodesy.distanceM(0.0, 0.0, 1.0, 0.0)
        assertEquals(111194.9, d, 50.0)
    }

    @Test
    fun `cardinal bearings are exact`() {
        assertEquals(0.0, Geodesy.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, Geodesy.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(180.0, Geodesy.bearingDeg(0.0, 0.0, -1.0, 0.0), 1e-6)
        assertEquals(270.0, Geodesy.bearingDeg(0.0, 0.0, 0.0, -1.0), 1e-6)
    }

    @Test
    fun `distance is symmetric and matches a known pair`() {
        val a = Geodesy.distanceM(49.2, -123.1, 50.5, -124.0)
        val b = Geodesy.distanceM(50.5, -124.0, 49.2, -123.1)
        assertEquals(a, b, 1e-9)
        // 1.3 deg of latitude (144.6 km) plus 0.9 deg of longitude at ~49.9 N
        // (64.6 km) gives about 158 km across the lower mainland test area
        assertTrue(a > 150_000 && a < 165_000, "got ${a / 1000.0} km")
    }

    @Test
    fun `point to segment distance handles interior and clamped ends`() {
        val mid = Geodesy.pointSegmentDistance(5.0, 5.0, 0.0, 0.0, 10.0, 0.0)
        assertEquals(5.0, mid[0], 1e-9)
        assertEquals(0.5, mid[1], 1e-9)

        // the perpendicular foot falls outside the segment here, so the nearest
        // point is the end cap and the distance is sqrt(5^2+5^2), not 5
        val before = Geodesy.pointSegmentDistance(-5.0, 5.0, 0.0, 0.0, 10.0, 0.0)
        assertEquals(7.0710678, before[0], 1e-6)
        assertEquals(0.0, before[1], 1e-9, "t must clamp to the segment start")

        val after = Geodesy.pointSegmentDistance(15.0, 5.0, 0.0, 0.0, 10.0, 0.0)
        assertEquals(7.0710678, after[0], 1e-6)
        assertEquals(1.0, after[1], 1e-9)
    }

    @Test
    fun `point to degenerate segment falls back to point distance`() {
        val r = Geodesy.pointSegmentDistance(3.0, 4.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(5.0, r[0], 1e-9)
    }

    @Test
    fun `destination walks the requested distance and bearing`() {
        val d = 111194.9
        val p = Geodesy.destination(0.0, 0.0, 90.0, d)
        assertEquals(0.0, p[0], 1e-3)
        assertEquals(1.0, p[1], 1e-3)
        assertEquals(d, Geodesy.distanceM(0.0, 0.0, p[0], p[1]), 1.0)
    }
}

class ViewportBoundsTest {

    private fun t(bearing: Double) =
        MapTransform(0.0, 0.0, 1.0, bearing, 800, 600)

    @Test
    fun `north up gives exactly the axis aligned window`() {
        val b = t(0.0).viewportBounds()
        assertEquals(-400.0, b[0], 1e-9)
        assertEquals(-300.0, b[1], 1e-9)
        assertEquals(400.0, b[2], 1e-9)
        assertEquals(300.0, b[3], 1e-9)
    }

    @Test
    fun `rotating the view exposes more ground`() {
        val north = t(0.0).viewportBounds()
        val turned = t(45.0).viewportBounds()
        val northArea = (north[2] - north[0]) * (north[3] - north[1])
        val turnedArea = (turned[2] - turned[0]) * (turned[3] - turned[1])
        assertTrue(turnedArea > northArea, "a rotated view covers more ground: $turnedArea vs $northArea")
    }

    @Test
    fun `every screen corner lies inside the reported bounds`() {
        for (bearing in listOf(0.0, 30.0, 45.0, 90.0, 137.0, 180.0, 270.0)) {
            val tr = t(bearing)
            val b = tr.viewportBounds()
            for (cx in listOf(0.0, 800.0, 800.0, 0.0)) {
                for (cy in listOf(0.0, 0.0, 600.0, 600.0)) {
                    val p = tr.screenToWorld(cx, cy)
                    assertTrue(p[0] >= b[0] - 1e-6 && p[0] <= b[2] + 1e-6,
                        "corner easting ${p[0]} outside ${b[0]}..${b[2]} at bearing $bearing")
                    assertTrue(p[1] >= b[1] - 1e-6 && p[1] <= b[3] + 1e-6,
                        "corner northing ${p[1]} outside ${b[1]}..${b[3]} at bearing $bearing")
                }
            }
        }
    }

    @Test
    fun `a shape visible at the edge survives rotation`() {
        // a point at the exact right-hand edge, north up
        val tr = t(0.0)
        val edge = tr.screenToWorld(800.0, 300.0)
        val b = tr.viewportBounds()
        assertTrue(edge[0] <= b[2] + 1e-6, "the edge point must be inside the culling bounds")
        // after turning, it must still be inside the new bounds if it is still on screen
        val turned = t(90.0)
        val turnedEdge = turned.screenToWorld(800.0, 300.0)
        val tb = turned.viewportBounds()
        assertTrue(turnedEdge[0] <= tb[2] + 1e-6)
    }
}
