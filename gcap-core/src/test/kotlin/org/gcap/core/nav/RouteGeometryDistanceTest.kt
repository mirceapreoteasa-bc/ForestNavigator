package org.gcap.core.nav

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The distance read out during navigation, pinned against the mixed-CRS failure seen on
 * the BV5200: a 7.02 km trip reported "5475.15 km left".
 */
class RouteGeometryDistanceTest {

    /** A short route in UTM 10N metres, roughly 6 km from (600000, 5460000) north. */
    private val route = doubleArrayOf(
        600_000.0, 5_460_000.0,
        600_000.0, 5_463_000.0,
        600_000.0, 5_466_000.0,
    )

    @Test
    fun countsDownTheRemainingRouteFromWhereTheFixIs() {
        val atStart = RouteGeometry.remainingM(route, 600_000.0, 5_460_000.0)
        assertEquals(6000.0, atStart, 1.0)

        // partway along: the figure must shrink, not grow, as ground is covered
        val midway = RouteGeometry.remainingM(route, 600_000.0, 5_463_000.0)
        assertTrue(midway < atStart, "distance left should fall as the crew drives")
        assertEquals(3000.0, midway, 1.0)
    }

    @Test
    fun refusesADegreesPositionAgainstAMetreRoute() {
        // the failing case: the fix stayed in degrees while the route is in UTM metres
        val mixed = RouteGeometry.remainingM(route, -123.07, 49.19)
        assertTrue(
            mixed.isNaN(),
            "a degrees fix against a metre route must not report $mixed metres",
        )
    }

    @Test
    fun aRefusedDistanceIsNotZero() {
        // 0 would read as "you have arrived", which is worse than an absent number
        val mixed = RouteGeometry.remainingM(route, -123.07, 49.19)
        assertTrue(mixed.isNaN(), "expected NaN, not a real-looking figure")
    }

    @Test
    fun anEmptyRouteReportsNothingLeft() {
        assertEquals(0.0, RouteGeometry.remainingM(DoubleArray(0), 600_000.0, 5_460_000.0))
    }
}