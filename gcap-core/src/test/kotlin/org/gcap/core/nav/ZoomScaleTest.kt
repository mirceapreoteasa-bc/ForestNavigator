package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ZoomScaleTest {

    @Test
    fun `the reference scale still means 500 metres across`() {
        assertEquals(500.0, ZoomScale.groundWidthM(10000), 1e-9)
        assertEquals(250.0, ZoomScale.groundWidthM(5000), 1e-9)
        assertEquals(20000.0, ZoomScale.groundWidthM(400000), 1e-6)
    }

    @Test
    fun `a wider view shows more ground at the same nominal scale`() {
        val narrow = ZoomScale.windowMeters(10000, 1000, 600)
        val wide = ZoomScale.windowMeters(10000, 2000, 600)
        assertEquals(500.0, narrow.first, 1e-9)
        assertEquals(500.0, wide.first, 1e-9, "the nominal width is fixed by the scale")
        assertEquals(300.0, narrow.second, 1e-9)
        assertEquals(150.0, wide.second, 1e-9, "a wider, shorter view covers less height")
    }

    @Test
    fun `the old fixed 500 by 400 window is reproduced at the old aspect ratio`() {
        // backwards compatibility: 1000x800 px was effectively 500 m x 400 m before
        val (w, h) = ZoomScale.windowMeters(10000, 1000, 800)
        assertEquals(500.0, w, 1e-9)
        assertEquals(400.0, h, 1e-9)
    }

    @Test
    fun `metres per pixel is isotropic and depends only on width`() {
        val mpp = ZoomScale.metresPerPixel(10000, 1000)
        assertEquals(0.5, mpp, 1e-9)
    }

    @Test
    fun `physical scale is density dependent, not just the zoom index`() {
        // same nominal zoom, different screens, different true scale
        val mpp = ZoomScale.metresPerPixel(10000, 1600)
        val lowDensity = ZoomScale.physicalScaleDenominator(mpp, 120.0)
        val highDensity = ZoomScale.physicalScaleDenominator(mpp, 400.0)
        assertTrue(highDensity > lowDensity * 2.5, "a denser screen shows the same ground in fewer physical units")
        // 500 m / 1600 px = 0.3125 m/px; at 120 dpi that is 0.3125*120/0.0254
        assertEquals(1476.378, lowDensity, 0.01)
    }

    @Test
    fun `physical scale snaps to a conventional step`() {
        val dpi = 160.0
        for (mpp in listOf(0.08, 0.31, 1.25, 5.0, 20.0)) {
            val exact = ZoomScale.physicalScaleDenominator(mpp, dpi)
            val snapped = ZoomScale.scaleDenominatorFor(mpp, dpi)
            assertTrue(snapped > 0, "got $snapped for mpp $mpp")
            // snapping must stay in the same neighbourhood, not jump an order of magnitude
            assertTrue(
                abs(snapped - exact) <= exact * 0.75,
                "mpp $mpp gives physical $exact but snapped to $snapped",
            )
        }
    }

    @Test
    fun `scales below the listed family clamp to its floor`() {
        // 0.02 m/px is a physical 1:126, finer than the smallest scale the app
        // offers, so it must clamp rather than report something unlisted
        val belowFloor = ZoomScale.scaleDenominatorFor(0.02, 160.0)
        assertEquals(500, belowFloor)
    }

    @Test
    fun `the nominal scale is a zoom index, not a true map scale`() {
        // Documented consequence of the inherited convention: ground coverage is
        // 500 m x (scale / 10000). Useful working scales therefore sit high in the
        // settings list, which is worth knowing before picking defaults.
        assertEquals(0.5, ZoomScale.groundWidthM(10000) / 1000.0, 1e-9)
        assertEquals(3.0, ZoomScale.groundWidthM(60000) / 1000.0, 1e-9, "road level")
        assertEquals(12.5, ZoomScale.groundWidthM(250000) / 1000.0, 1e-9)
        assertEquals(50.0, ZoomScale.groundWidthM(1000000) / 1000.0, 1e-9, "block overview")
    }

    @Test
    fun `the whole 200 km block only fits at the top of the scale list`() {
        assertEquals(200_000.0, ZoomScale.groundWidthM(4000000), 1.0, "1:4,000,000 is the only listed scale covering 200 km")
        assertTrue(ZoomScale.groundWidthM(1000000) >= 50_000.0, "1:1,000,000 gives a usable block overview")
        // a default well below this would open the app looking at a single road
        assertTrue(ZoomScale.groundWidthM(10000) <= 1_000.0)
    }

    @Test
    fun `rejects a non positive view width`() {
        var threw = false
        try {
            ZoomScale.metresPerPixel(10000, 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
