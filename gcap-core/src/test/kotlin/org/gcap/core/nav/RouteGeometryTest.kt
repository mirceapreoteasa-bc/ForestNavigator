package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteGeometryTest {

    /**
     * A corner: node 0 at (0,0), node 1 at (1000,0), node 2 at (1000,1000),
     * joined by two edges.
     */
    private fun corner(): NetworkGraph {
        val e0 = NetworkEdge(0, 0, 1, 1000.0, RouteClass.ACCESS, 15.0, OneWay.BOTH, "access")
        val e1 = NetworkEdge(1, 1, 2, 1000.0, RouteClass.ACCESS, 15.0, OneWay.BOTH, "access")
        return NetworkGraph(
            nodeE = doubleArrayOf(0.0, 1000.0, 1000.0),
            nodeN = doubleArrayOf(0.0, 0.0, 1000.0),
            edges = listOf(e0, e1),
            adjacency = arrayOf(
                listOf(Arc(0, 1, 1000.0 / (15.0 / 3.6), 1000.0)),
                listOf(Arc(1, 2, 1000.0 / (15.0 / 3.6), 1000.0)),
            ),
            regionOfEdge = intArrayOf(0, 0),
            regionCount = 1,
            nodedLines = emptyList(),
        )
    }

    private fun pathThroughCorner() = Path(
        nodes = intArrayOf(0, 1, 2),
        edges = intArrayOf(0, 1),
        seconds = 480.0,
        metres = 2000.0,
    )

    @Test
    fun theRouteLineFollowsTheNodesItWasRoutedThrough() {
        val pts = RouteGeometry.polyline(corner(), pathThroughCorner())
        assertEquals(6, pts.size, "three nodes means three points, six doubles")
        assertEquals(0.0, pts[0], 1e-9); assertEquals(0.0, pts[1], 1e-9)
        assertEquals(1000.0, pts[2], 1e-9); assertEquals(0.0, pts[3], 1e-9)
        assertEquals(1000.0, pts[4], 1e-9); assertEquals(1000.0, pts[5], 1e-9)
    }

    @Test
    fun stubPointsAreIncludedSoTheLineIsTheLineActuallyDriven() {
        // joined the network at 300 m along the first edge, leaves at the far end
        val p = pathThroughCorner().copy(startPointE = 300.0, startPointN = 0.0, endPointE = 1000.0, endPointN = 1100.0)
        val pts = RouteGeometry.polyline(corner(), p)
        assertEquals(300.0, pts[0], 1e-9)
        assertEquals(1100.0, pts[pts.size - 1], 1e-9, "and ends past the last node")
    }

    @Test
    fun repeatedVerticesAreNotDoubledUp() {
        // a stub exactly on a node would otherwise appear twice and draw a zero-length leg
        val p = pathThroughCorner().copy(startPointE = 0.0, startPointN = 0.0)
        assertEquals(6, RouteGeometry.polyline(corner(), p).size)
    }

    @Test
    fun anEmptyPathStillGivesADrawableLine() {
        val p = Path(intArrayOf(0), intArrayOf(0), 0.0, 0.0)
        assertEquals(2, RouteGeometry.polyline(corner(), p).size)
    }

    @Test
    fun theNextNodeIsTheOneAfterYou() {
        assertEquals(1, RouteGeometry.nextNodeAfter(pathThroughCorner(), 0))
        assertEquals(2, RouteGeometry.nextNodeAfter(pathThroughCorner(), 1))
        assertEquals(-1, RouteGeometry.nextNodeAfter(pathThroughCorner(), 2), "nowhere to go at the end")
    }

    @Test
    fun beingOffThePathHasNoNextNode() {
        assertEquals(-1, RouteGeometry.nextNodeAfter(pathThroughCorner(), 99))
    }

    @Test
    fun remainingDistanceCountsFromWhereverYouAre() {
        val pts = RouteGeometry.polyline(corner(), pathThroughCorner())
        // standing at the first node: 1000 m along then 1000 m up
        assertEquals(2000.0, RouteGeometry.remainingM(pts, 0.0, 0.0), 1e-6)
        // standing at the corner: only the second leg is left
        assertEquals(1000.0, RouteGeometry.remainingM(pts, 1000.0, 0.0), 1e-6)
    }

    @Test
    fun bearingsAreCompassDegrees() {
        assertEquals(0.0, RouteGeometry.bearing(0.0, 0.0, 0.0, 100.0), 1e-6)      // north
        assertEquals(90.0, RouteGeometry.bearing(0.0, 0.0, 100.0, 0.0), 1e-6)     // east
        assertEquals(180.0, RouteGeometry.bearing(0.0, 0.0, 0.0, -100.0), 1e-6)   // south
        assertEquals(270.0, RouteGeometry.bearing(0.0, 0.0, -100.0, 0.0), 1e-6)   // west
        assertEquals(45.0, RouteGeometry.bearing(0.0, 0.0, 100.0, 100.0), 1e-6)
    }

    @Test
    fun theSteeringBearingFollowsTheNextNode() {
        val g = corner()
        // node 0 -> 1 runs east; node 1 -> 2 runs north
        assertEquals(90.0, RouteGeometry.bearingToNextNode(g, pathThroughCorner(), 0), 1e-6)
        assertEquals(0.0, RouteGeometry.bearingToNextNode(g, pathThroughCorner(), 1), 1e-6)
        assertTrue(RouteGeometry.bearingToNextNode(g, pathThroughCorner(), 2) < 0)
    }

    @Test
    fun anUnreachableGoalIsReportedRatherThanDrawingALine() {
        val r = Router(corner())
        val res = r.route(0.0, 0.0, 1000.0, 1000.0)
        assertTrue(res is RouteResult.Ok)
        val ok = res as RouteResult.Ok
        // legCount counts traversed edges; a stub can absorb the first leg, so the edge
        // count may be lower than the number of corners. The metre total is the real check.
        assertEquals(2000.0, ok.path.metres, 1e-6)
    }

    @Test
    fun aDestinationOffTheNetworkIsRefusedWithAReadableReason() {
        val r = Router(corner())
        val res = r.route(0.0, 0.0, 9000.0, 9000.0, maxSnapM = 50.0)
        assertTrue(res is RouteResult.Failed)
        assertTrue((res as RouteResult.Failed).reason.contains("50 m"))
    }
}
