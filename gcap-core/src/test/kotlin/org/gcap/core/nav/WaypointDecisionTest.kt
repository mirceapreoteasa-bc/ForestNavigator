package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Three thresholds, because a waypoint 20 m off the road, 400 m off it, and 3 km off it
 * are three different situations and must not be handled the same way.
 */
class WaypointDecisionTest {

    /** A straight 1 km east-west line through the origin. */
    private fun line(): NetworkGraph {
        val edge = NetworkEdge(
            id = 0, fromNode = 0, toNode = 1, lengthM = 1000.0,
            cls = RouteClass.ROAD, speedKph = 40.0,
            oneway = OneWay.BOTH, sourceLayer = "access",
        )
        val arc = Arc(edge = 0, toNode = 1, seconds = 1000.0 / (40.0 / 3.6), lengthM = 1000.0)
        return NetworkGraph(
            nodeE = doubleArrayOf(0.0, 1000.0),
            nodeN = doubleArrayOf(0.0, 0.0),
            edges = listOf(edge),
            adjacency = arrayOf(listOf(arc)),
            regionOfEdge = intArrayOf(0),
            regionCount = 1,
            nodedLines = emptyList(),
        )
    }

    // ---- 1. within 70 m: silent --------------------------------------------

    @Test
    fun onTheLineIsDirectAndNeedsNoComment() {
        val d = line().snapWaypoint(500.0, 0.0)
        assertTrue(d is WaypointDecision.Direct)
        assertEquals(500.0, d.targetE, 1e-9)
    }

    @Test
    fun justInsideSeventyMetresIsStillSilent() {
        assertTrue(line().snapWaypoint(500.0, 69.0) is WaypointDecision.Direct)
    }

    @Test
    fun exactlySeventyIsSilent() {
        assertTrue(line().snapWaypoint(500.0, 70.0) is WaypointDecision.Direct)
    }

    // ---- 2. 70 m to 1500 m: projected, and said out loud ------------------

    @Test
    fun justOverSeventyIsSnappedAndReported() {
        val d = line().snapWaypoint(500.0, 71.0)
        assertTrue(d is WaypointDecision.Snapped)
        assertEquals(71.0, d.distanceM, 1e-6)
        assertEquals(0.0, d.targetN, 1e-9, "delivered on the road")
    }

    @Test
    fun fourHundredMetresIsSnappedAndReported() {
        val d = line().snapWaypoint(500.0, 400.0)
        assertTrue(d is WaypointDecision.Snapped)
        assertEquals(400.0, d.distanceM, 1e-6)
        assertEquals(0.0, d.targetN, 1e-9)
    }

    @Test
    fun exactlyTheLimitIsStillSnappedNotAsked() {
        assertTrue(line().snapWaypoint(500.0, 1500.0) is WaypointDecision.Snapped)
    }

    // ---- 3. beyond 1500 m: ask the user ------------------------------------

    @Test
    fun justOverTheLimitAsksRatherThanAssuming() {
        val d = line().snapWaypoint(500.0, 1501.0)
        assertTrue(d is WaypointDecision.TooFar)
        assertEquals(1501.0, d.distanceM, 1e-6)
    }

    @Test
    fun tooFarKeepsTheWaypointButOffersTheProjection() {
        val d = line().snapWaypoint(500.0, 3000.0) as WaypointDecision.TooFar
        assertEquals(3000.0, d.targetN, 1e-9, "Cancel keeps the waypoint where it is")
        assertEquals(0.0, d.projectionN, 1e-9, "Bypass would go to the line")
        assertEquals(500.0, d.projectionE, 1e-9)
    }

    // ---- the limit is settable ---------------------------------------------

    @Test
    fun aShorterLimitMovesThePromptCloserIn() {
        // a walking track where 200 m is already too far to project silently
        val d = line().snapWaypoint(500.0, 300.0, limitM = 200.0)
        assertTrue(d is WaypointDecision.TooFar, "300 m exceeds a 200 m limit, so it must ask")
    }

    @Test
    fun aLongerLimitDefersThePrompt() {
        val d = line().snapWaypoint(500.0, 3000.0, limitM = 5000.0)
        assertTrue(d is WaypointDecision.Snapped)
    }

    @Test
    fun theDefaultLimitIsFifteenHundredMetres() {
        assertEquals(1500.0, NetworkGraph.PROJECT_LIMIT_M, 0.0)
        assertEquals(70.0, NetworkGraph.SNAP_SILENT_M, 0.0)
    }

    // ---- no network at all --------------------------------------------------

    @Test
    fun anEmptyNetworkAsksRatherThanCrashing() {
        val empty = NetworkGraph(
            doubleArrayOf(), doubleArrayOf(), emptyList(), emptyArray(), intArrayOf(), 0, emptyList())
        val d = empty.snapWaypoint(1.0, 2.0)
        assertTrue(d is WaypointDecision.TooFar)
        assertEquals(1.0, d.targetE, 0.0)
    }
}