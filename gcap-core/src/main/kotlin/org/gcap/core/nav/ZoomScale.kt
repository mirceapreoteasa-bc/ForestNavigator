package org.gcap.core.nav

/**
 * The map canvas's zoom convention, kept here rather than in the view so it can be
 * unit tested without an Android device.
 *
 * The nominal convention is preserved from the original canvas: a scale denominator
 * of 10,000 means 500 m of ground across the view's width. What is new is that the
 * vertical extent follows from the real view aspect instead of being fixed at 400 m,
 * and that the *physical* scale reported to the user is derived from screen density
 * rather than being a restatement of the zoom index.
 */
object ZoomScale {
    /** Ground metres across the view width at [REFERENCE_SCALE]. */
    const val REFERENCE_GROUND_WIDTH_M = 500.0
    const val REFERENCE_SCALE = 10000

    fun groundWidthM(scaleDenominator: Int): Double =
        REFERENCE_GROUND_WIDTH_M * scaleDenominator / REFERENCE_SCALE

    fun metresPerPixel(scaleDenominator: Int, viewWidthPx: Int): Double {
        require(viewWidthPx > 0) { "view width must be positive, was $viewWidthPx" }
        return groundWidthM(scaleDenominator) / viewWidthPx
    }

    /** Ground rectangle visible, isotropic: [widthM] across, height from the aspect. */
    fun windowMeters(scaleDenominator: Int, viewWidthPx: Int, viewHeightPx: Int): Pair<Double, Double> {
        val w = groundWidthM(scaleDenominator)
        if (viewWidthPx <= 0 || viewHeightPx <= 0) return w to w
        return w to (w * viewHeightPx.toDouble() / viewWidthPx.toDouble())
    }

    /**
     * True scale denominator for a screen of the given pixel density, i.e. the
     * conventional 1:N printed on a map. One display pixel is 0.0254/dpi metres.
     */
    fun physicalScaleDenominator(metresPerPixel: Double, dpi: Double): Double =
        metresPerPixel * dpi / 0.0254

    /** Inverse of [physicalScaleDenominator], rounded to a conventional step. */
    fun scaleDenominatorFor(metresPerPixel: Double, dpi: Double): Int {
        val raw = metresPerPixel * dpi / 0.0254
        // snap to the usual 1:2.5 family so the readout stays recognisable
        val steps = intArrayOf(500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 7500,
                10000, 15000, 20000, 25000, 40000, 50000, 75000, 100000, 150000,
                200000, 250000, 400000, 500000, 750000, 1000000, 1500000, 2000000,
                2500000, 3000000, 4000000)
        return steps.minByOrNull { kotlin.math.abs(it - raw) } ?: raw.toInt()
    }
}
