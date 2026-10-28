package org.forestnav.app.map

import org.gcap.core.nav.Geodesy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distance to the active waypoint, computed the way the map overlay does it.
 *
 * This exists because the overlay showed about 5000 km between points a few tens of
 * km apart. Two mistakes combined: the GPS fix was passed through the inverse
 * projection as though it were metres, and the waypoint's easting and northing were
 * given in the wrong order. Both are invisible in a screenshot, because the number
 * on screen is always a plausible-looking distance.
 */
class NavDistanceTest {

    private val zone = 10

    /**
     * Distance from a GPS fix to a waypoint held in working-zone metres.
     *
     * The fix is latitude/longitude in degrees; the waypoint is easting/northing.
     */
    private fun distanceToWaypoint(
        fixLat: Double,
        fixLon: Double,
        waypointE: Double,
        waypointN: Double,
    ): Double {
        val (lat2, lon2) = Utm.utmToLatLon(waypointE, waypointN, zone)
        return Geodesy.distanceM(fixLat, fixLon, lat2, lon2)
    }

    @Test
    fun `distance between nearby points is metres not thousands of kilometres`() {
        // Real extent of the FOR_NAV project: roughly 333207,5451730 to 503862,5694026.
        val fixLat = 50.7666
        val fixLon = -125.0189
        val d = distanceToWaypoint(fixLat, fixLon, 448000.0, 5509000.0)

        assertTrue(
            "distance was $d m; a bug like this one reports about 5,000,000 m",
            d < 200_000.0,
        )
        assertEquals(
            "the reading should also not be near zero",
            true,
            d > 1_000.0,
        )
    }

    @Test
    fun `a fix and its own waypoint are at the same place`() {
        // Project the fix, use it as the waypoint, and read the distance back.
        val fixLat = 50.7666
        val fixLon = -125.0189
        val (e, n) = Utm.wgs84ToUtmZone(fixLat, fixLon, zone)
        val d = distanceToWaypoint(fixLat, fixLon, e, n)

        assertTrue("expected a rounding error of well under a metre, got $d m", d < 1.0)
    }

    @Test
    fun `east and north are not interchangeable`() {
        // Swapping the waypoint's coordinates puts it in a different place entirely.
        val dCorrect = distanceToWaypoint(50.7666, -125.0189, 448000.0, 5509000.0)
        val dSwapped = distanceToWaypoint(50.7666, -125.0189, 5509000.0, 448000.0)

        assertTrue(
            "swapping east and north changed the distance by only ${kotlin.math.abs(dSwapped - dCorrect)} m",
            kotlin.math.abs(dSwapped - dCorrect) > 100_000.0,
        )
    }

    @Test
    fun `a degrees fix must not be sent through the inverse projection`() {
        // This is the mistake that produced the 5000 km reading: feeding degrees into
        // utmToLatLon, which expects metres.
        val fixLat = 50.7666
        val fixLon = -125.0189
        val (mangledLat, mangledLon) = Utm.utmToLatLon(fixLat, fixLon, zone)
        val (wpLat, wpLon) = Utm.utmToLatLon(448000.0, 5509000.0, zone)

        val dGood = Geodesy.distanceM(fixLat, fixLon, wpLat, wpLon)
        val dMangled = Geodesy.distanceM(mangledLat, mangledLon, wpLat, wpLon)

        assertTrue(
            "the mangled reading was $dMangled m, which is the size of the reported bug",
            dMangled > 1_000_000.0,
        )
        assertTrue("the correct reading was $dGood m", dGood < 200_000.0)
    }

    @Test
    fun `bearing points the right way`() {
        // Build the geometry in metres so "due north" is exact, then read the bearing
        // back the way the overlay does: fix in degrees, waypoint in easting/northing.
        val (e, n) = Utm.wgs84ToUtmZone(50.7666, -125.0189, zone)
        val (northLat, northLon) = Utm.utmToLatLon(e, n + 20_000.0, zone)
        val (eastLat, eastLon) = Utm.utmToLatLon(e + 20_000.0, n, zone)

        val toNorth = Geodesy.bearingDeg(50.7666, -125.0189, northLat, northLon)
        val toEast = Geodesy.bearingDeg(50.7666, -125.0189, eastLat, eastLon)

        // "North in UTM" is not exactly due north on a sphere: the grid meridian is
        // straight only on the central meridian, and this project sits about 2 degrees
        // west of it. Measured at 358.44, so the tolerance is 2 degrees rather than 1.
        assertTrue("bearing north was $toNorth degrees", toNorth > 357.0 && toNorth < 359.5)
        // Likewise east in UTM grid is 88.43 on a sphere at this latitude.
        assertTrue("bearing east was $toEast degrees", kotlin.math.abs(toEast - 88.4) < 1.0)
        assertTrue(
            "north and east must differ, or the bearing is not reading its inputs",
            kotlin.math.abs(toNorth - toEast) > 80.0,
        )
    }
}