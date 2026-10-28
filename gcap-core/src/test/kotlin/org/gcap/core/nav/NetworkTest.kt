package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.math.sqrt

private fun line(vararg xy: Double, layer: String = "L", cls: RouteClass = RouteClass.ACCESS, oneway: Int = OneWay.BOTH, speed: Double = 0.0, conf: Int = 0) =
    SourceLine(xy, layer, cls, oneway, speed, conf)

class NetworkBuilderTest {

    @Test
    fun `endpoints within tolerance are welded into one network`() {
        val b = NetworkBuilder(toleranceM = 3.0)
        val (g, r) = b.build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(100.5, 0.0, 200.0, 0.0),   // 0.5 m gap
            ),
        )
        assertEquals(1, r.regionCount, "a 0.5 m gap inside 3 m tolerance must connect")
        assertEquals(3, g.nodeCount, "four endpoints with one pair welded give three nodes")
        assertEquals(2, g.edges.size)
        assertTrue(r.snappedEndpointCount > 0, "the weld should be reported")
    }

    @Test
    fun `endpoints beyond tolerance stay separate`() {
        val b = NetworkBuilder(toleranceM = 3.0)
        val (g, r) = b.build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(110.0, 0.0, 200.0, 0.0),  // 10 m gap
            ),
        )
        assertEquals(2, r.regionCount, "a 10 m gap must not be bridged by a 3 m tolerance")
        assertEquals(4, g.nodeCount)
    }

    @Test
    fun `crossing lines are split so a route can turn at the junction`() {
        val b = NetworkBuilder(toleranceM = 3.0)
        val (g, r) = b.build(
            listOf(
                line(0.0, 0.0, 100.0, 100.0),
                line(0.0, 100.0, 100.0, 0.0),
            ),
        )
        // both lines split at the crossing: 2 + 2 edges, sharing one node
        assertEquals(4, g.edges.size, "a crossing must split both lines in two")
        assertEquals(5, g.nodeCount, "four ends plus the shared crossing node")
        assertEquals(1, r.regionCount, "a crossing must not create two separate networks")
        assertTrue(r.intersectionSplitCount >= 1, "the split should be reported")
    }

    @Test
    fun `a crossing between different layers still forms a junction`() {
        val b = NetworkBuilder(toleranceM = 3.0)
        val (g, r) = b.build(
            listOf(
                line(0.0, 50.0, 100.0, 50.0, layer = "roads", cls = RouteClass.ROAD),
                line(50.0, 0.0, 50.0, 100.0, layer = "access", cls = RouteClass.ACCESS),
            ),
        )
        assertEquals(1, r.regionCount, "access must be able to join the road network")
        assertEquals(4, g.edges.size)
    }

    @Test
    fun `disconnected pieces become separate regions`() {
        val b = NetworkBuilder(toleranceM = 3.0)
        val (_, r) = b.build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(0.0, 500.0, 100.0, 500.0),
                line(0.0, 1000.0, 100.0, 1000.0),
            ),
        )
        assertEquals(3, r.regionCount)
        assertEquals(2, r.maxRegionNodeCount, "each isolated line is a two-node region")
    }

    @Test
    fun `duplicate vertices and zero length segments are discarded`() {
        val b = NetworkBuilder(toleranceM = 3.0)
        val (g, _) = b.build(
            listOf(
                line(0.0, 0.0, 0.0, 0.0, 50.0, 0.0, 50.0, 0.0, 100.0, 0.0),
            ),
        )
        // three distinct vertices make two segments; the repeats must not survive
        assertEquals(2, g.edges.size, "the repeated vertex must not create zero length edges")
        assertEquals(3, g.nodeCount)
    }

    @Test
    fun `parallel collinear lines do not generate spurious junctions`() {
        val b = NetworkBuilder(toleranceM = 3.0)
        val (g, r) = b.build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(0.0, 0.0, 100.0, 0.0),   // exact duplicate
            ),
        )
        // collinear overlap is not a crossing, so nothing is split and the
        // duplicated geometry welds into a single connected network
        assertEquals(1, r.regionCount)
        assertEquals(0, r.intersectionSplitCount, "collinear overlap must not create junctions")
        assertTrue(g.edges.all { g.regionOfEdge[it.id] == 0 })
    }

    @Test
    fun `empty input yields an empty graph rather than throwing`() {
        val (g, r) = NetworkBuilder().build(emptyList())
        assertEquals(0, g.nodeCount)
        assertEquals(0, g.edges.size)
        assertEquals(0, r.regionCount)
    }

    @Test
    fun `refuses a tolerance that could invent a missing crossing`() {
        var refused = false
        try {
            NetworkBuilder(toleranceM = 30.0)
        } catch (e: IllegalArgumentException) {
            refused = true
            assertTrue(
                e.message!!.contains("supervised"),
                "the refusal should explain that supervision is required, got: ${e.message}",
            )
        }
        assertTrue(refused, "an automatic build must not bridge gaps wider than the limit")
    }

    @Test
    fun `supervised repair may widen the tolerance`() {
        val (g, r) = NetworkBuilder(toleranceM = 30.0, allowSupervisedGaps = true).build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(120.0, 0.0, 200.0, 0.0),   // 20 m gap, bridged under supervision
            ),
        )
        assertEquals(1, r.regionCount, "a supervised build may bridge a reviewed gap")
        assertTrue(g.edges.size >= 2)
    }

    @Test
    fun `the unsupervised limit accepts the boundary value`() {
        val r = NetworkBuilder(toleranceM = NetworkBuilder.MAX_UNSUPERVISED_TOLERANCE_M)
        assertEquals(5.0, NetworkBuilder.MAX_UNSUPERVISED_TOLERANCE_M, 1e-9)
        assertTrue(r.build(listOf(line(0.0, 0.0, 100.0, 0.0))).second.regionCount == 1)
    }

    @Test
    fun `rejects a non positive tolerance`() {
        var threw = false
        try { NetworkBuilder(0.0) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `noded output keeps one polyline per source`() {
        val b = NetworkBuilder(toleranceM = 3.0)
        val (g, _) = b.build(
            listOf(
                line(0.0, 0.0, 50.0, 0.0, 100.0, 0.0, layer = "access"),
                line(0.0, 0.0, 50.0, 0.0, 100.0, 0.0, layer = "roads", cls = RouteClass.ROAD),
            ),
        )
        assertTrue(g.nodedLines.isNotEmpty(), "network must be writable back to a layer")
        val layers = g.nodedLines.map { it.layer }.toSet()
        assertTrue(layers.containsAll(setOf("access", "roads")))
    }

    @Test
    fun `handles a realistic load without blowing up`() {
        val src = ArrayList<SourceLine>()
        // a 40 x 40 street grid, 100 m blocks, some vertices nudged within tolerance
        val rnd = java.util.Random(11)
        for (i in 0..40) {
            val jitter = rnd.nextGaussian() * 0.8
            src.add(line(0.0, i * 100.0 + jitter, 4000.0, i * 100.0 + jitter, layer = "roads", cls = RouteClass.ROAD))
            src.add(line(i * 100.0 + jitter, 0.0, i * 100.0 + jitter, 4000.0, layer = "roads", cls = RouteClass.ROAD))
        }
        val (g, r) = NetworkBuilder(toleranceM = 3.0).build(src)
        assertEquals(1, r.regionCount, "a grid with 0.8 m jitter must weld into one network")
        assertTrue(g.edges.size > 100)
        val route = Router(g).route(10.0, 10.0, 3990.0, 3990.0)
        assertTrue(route is RouteResult.Ok, "grid corners must be routable")
    }
}

class RouterTest {

    private fun lShape(): NetworkGraph {
        // A --100-- J --100-- B
        val b = NetworkBuilder(toleranceM = 3.0)
        return b.build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0, layer = "access", cls = RouteClass.ACCESS),
                line(100.0, 0.0, 200.0, 0.0, layer = "access", cls = RouteClass.ACCESS),
            ),
        ).first
    }

    @Test
    fun `routes along a simple chain`() {
        val g = lShape()
        val res = Router(g).route(0.0, 0.0, 200.0, 0.0)
        assertTrue(res is RouteResult.Ok, "got $res")
        val ok = res as RouteResult.Ok
        assertEquals(200.0, ok.path.metres, 1e-6)
        assertEquals(1, ok.path.legCount, "the first 100 m is a snap stub, not a graph edge")
    }

    @Test
    fun `time and distance metrics can disagree`() {
        // road: long detour, fast.  trail: near straight, slow.
        val g = NetworkBuilder(toleranceM = 3.0).build(
            listOf(
                line(0.0, 0.0, 500.0, 300.0, 1000.0, 0.0, layer = "roads", cls = RouteClass.ROAD),
                line(0.0, 0.0, 500.0, 20.0, 1000.0, 0.0, layer = "tracks", cls = RouteClass.TRAIL),
            ),
        ).first
        val router = Router(g)

        val byTime = router.route(0.0, 0.0, 1000.0, 0.0, RouteMetric.TIME)
        val byDist = router.route(0.0, 0.0, 1000.0, 0.0, RouteMetric.DISTANCE)
        assertTrue(byTime is RouteResult.Ok && byDist is RouteResult.Ok)

        val roadLen = 2 * hypot(500.0, 300.0)
        val trailLen = 2 * hypot(500.0, 20.0)
        assertEquals(roadLen, (byTime as RouteResult.Ok).path.metres, 1e-6, "fast detour should win on time")
        assertEquals(trailLen, (byDist as RouteResult.Ok).path.metres, 1e-6, "short trail should win on distance")
    }

    @Test
    fun `unreachable destinations fail with a readable reason`() {
        val g = NetworkBuilder(3.0).build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(0.0, 9000.0, 100.0, 9000.0),
            ),
        ).first
        val res = Router(g).route(50.0, 0.0, 50.0, 9000.0)
        assertTrue(res is RouteResult.Failed)
        assertTrue((res as RouteResult.Failed).reason.isNotBlank())
    }

    @Test
    fun `a start far from any network is refused rather than guessed`() {
        val g = lShape()
        val res = Router(g).route(0.0, 5000.0, 200.0, 0.0, maxSnapM = 25.0)
        assertTrue(res is RouteResult.Failed)
        assertTrue((res as RouteResult.Failed).reason.contains("50 m") || res.reason.contains("25 m"))
    }

    @Test
    fun `starting mid edge costs only the partial edge`() {
        val g = lShape()
        val res = Router(g).route(50.0, 0.0, 200.0, 0.0) as RouteResult.Ok
        assertEquals(150.0, res.path.metres, 1e-6, "from the midpoint only 150 m remain")
        assertEquals(0.0, res.snapStart.distanceM, 1e-6, "the start is already on the network")
        assertEquals(50.0, res.snapStart.pointE, 1e-6)
    }

    @Test
    fun `one way edges cannot be traversed backwards`() {
        val g = NetworkBuilder(3.0).build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0, oneway = OneWay.FORWARD),
            ),
        ).first
        val router = Router(g)
        assertTrue(router.route(0.0, 0.0, 100.0, 0.0) is RouteResult.Ok, "forward travel must work")
        assertTrue(router.route(100.0, 0.0, 0.0, 0.0) is RouteResult.Failed, "reverse travel must fail")
    }

    @Test
    fun `nearest point reports the edge and the position along it`() {
        val g = lShape()
        val snap = Router(g).nearestPoint(150.0, 7.0, -1, 50.0)
        assertNotNull(snap)
        assertTrue(snap!!.distanceM in 6.9..7.1, "got ${snap.distanceM}")
        assertTrue(snap.t in 0.49..0.51, "got ${snap.t}")
    }

    @Test
    fun `waypoints are visited in the order given`() {
        val g = NetworkBuilder(3.0).build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(100.0, 0.0, 100.0, 100.0),
                line(100.0, 100.0, 0.0, 100.0),
            ),
        ).first
        val res = Router(g).routeThrough(
            0.0, 0.0,
            listOf(0.0 to 100.0, 100.0 to 100.0),
            listOf("aircall-1", "aircall-2"),
        )
        assertTrue(res is MultiRouteResult.Ok, "got $res")
        val ok = res as MultiRouteResult.Ok
        assertEquals(2, ok.legs.size)
        assertEquals(0, ok.legs[0].legIndex)
        // the U shape forces the long way round: 100 m east, 100 m north, 100 m west
        assertEquals(300.0, (ok.legs[0].result as RouteResult.Ok).path.metres, 1e-6)
        assertEquals("aircall-2", ok.legs[1].toLabel)
        assertEquals(400.0, ok.totalMetres, 1e-6)
    }

    @Test
    fun `a broken leg is reported rather than silently dropped`() {
        val g = NetworkBuilder(3.0).build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(0.0, 5000.0, 100.0, 5000.0),
            ),
        ).first
        val res = Router(g).routeThrough(
            50.0, 0.0,
            listOf(50.0 to 5000.0, 0.0 to 5000.0),
            listOf("a", "b"),
        )
        assertTrue(res is MultiRouteResult.FailedAtLeg)
        assertEquals(0, (res as MultiRouteResult.FailedAtLeg).leg.legIndex)
    }

    @Test
    fun `progress along the route shrinks as we advance`() {
        val g = lShape()
        val router = Router(g)
        val route = router.route(0.0, 0.0, 200.0, 0.0) as RouteResult.Ok
        val near = router.progressAlong(route.path, 10.0, 0.0)!!
        val far = router.progressAlong(route.path, 190.0, 0.0)!!
        assertTrue(far.remainingM < near.remainingM, "${far.remainingM} should be under ${near.remainingM}")
        assertTrue(near.offRouteM < 0.01 && far.offRouteM < 0.01, "on the route means no offset")
    }

    @Test
    fun `being off the route is detected beyond the radius`() {
        val g = lShape()
        val router = Router(g)
        val route = router.route(0.0, 0.0, 200.0, 0.0) as RouteResult.Ok
        assertNull(router.progressAlong(route.path, 100.0, 400.0, withinRadiusM = 50.0))
    }

    @Test
    fun `routing is confined to the requested region`() {
        val g = NetworkBuilder(3.0).build(
            listOf(
                line(0.0, 0.0, 100.0, 0.0),
                line(0.0, 9000.0, 100.0, 9000.0),
            ),
        ).first
        // with no region restriction the two blocks are unreachable from each other
        assertTrue(Router(g).route(50.0, 0.0, 50.0, 9000.0, region = -1) is RouteResult.Failed)
        // restricted to the near region, the far waypoint is simply not reachable
        val res = Router(g).route(50.0, 0.0, 50.0, 9000.0, region = g.regionOfEdge[0])
        assertTrue(res is RouteResult.Failed)
    }
}
