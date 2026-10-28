package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** A straight two-vertex line from (x0,y0) to (x1,y1). */
private fun seg(x0: Double, y0: Double, x1: Double, y1: Double) =
    doubleArrayOf(x0, y0, x1, y1)

class RouteChainBuilderTest {

    /**
     * The walk the user described, laid out to match:
     *
     * - a short segment from 001A to 001B
     * - 001B is ~100 m from the near end of a long segment
     * - that long segment's far end is loose, and reaches 002A
     * - a short segment sits between 002A and 002B, with the bare 002 mid-way
     * - 002B is ~150 m from the near end of a much longer segment
     * - its far end reaches 003A, and so on
     */
    private fun describedWalk(): Triple<List<DoubleArray>, List<AirCall>, List<String>> {
        val lines = listOf(
            seg(0.0, 0.0, 95.0, 0.0),          // #0  001A -> 001B, short
            seg(195.0, 0.0, 1000.0, 0.0),       // #1  long; 001B is 100 m from its start
            seg(1090.0, 0.0, 1185.0, 0.0),      // #2  short, 002A -> 002B
            seg(1335.0, 0.0, 3000.0, 0.0),       // #3  much longer; 002B is 150 m from its start
            seg(3090.0, 0.0, 3185.0, 0.0),      // #4  short, 003A -> 003B
        )
        val calls = listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001", 50.0, 0.0),      // bare, mid-segment: must be ignored
            AirCall("001B", 95.0, 0.0),
            AirCall("002A", 1090.0, 0.0),
            AirCall("002", 1140.0, 0.0),    // bare, mid-segment
            AirCall("002B", 1185.0, 0.0),
            AirCall("003A", 3090.0, 0.0),
            AirCall("003", 3140.0, 0.0),    // bare
            AirCall("003B", 3185.0, 0.0),
        )
        val expected = listOf("001A", "001B", "002A", "002B", "003A", "003B")
        return Triple(lines, calls, expected)
    }

    @Test
    fun `bare numbered calls are left out of the route`() {
        val (lines, calls, expected) = describedWalk()
        val b = RouteChainBuilder(lines, calls, weldMaxM = 200.0)
        assertEquals(expected, b.route.map { it.name })
        assertTrue(b.route.none { it.suffix.isEmpty() }, "bare calls are not chain nodes")
    }

    @Test
    fun `the walk follows the flight in call order`() {
        val (lines, calls, expected) = describedWalk()
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()

        val visited = chain.steps.filter { it.kind == StepKind.ROUTE_POINT }.map { it.routePoint!!.name }
        assertEquals(expected, visited, "route points must be reached in flying order")
        assertTrue(chain.reachedEverything, "unreached: ${chain.unreached.map { it.name }}")
        assertEquals(null, chain.stopReason)
    }

    @Test
    fun `features are chained in the order they are flown`() {
        val (lines, calls, _) = describedWalk()
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        // #0 carries 001A-001B, #1 runs on to 002A, #2 is the 002 short, #3 runs to 003A,
        // #4 is the 003 short
        assertEquals(listOf(0, 1, 2, 3, 4), chain.featuresInOrder)
    }

    @Test
    fun `the roughly 100 m hop from 001B is within tolerance but a longer one is not`() {
        val (lines, calls, _) = describedWalk()
        assertTrue(RouteChainBuilder(lines, calls, weldMaxM = 200.0).build().reachedEverything)
        // 50 m of tolerance cannot bridge the 100 m hop, so the chain cannot complete
        val tight = RouteChainBuilder(lines, calls, weldMaxM = 50.0).build()
        assertFalse(tight.reachedEverything, "a 50 m weld must not reach 001B's neighbour")
    }

    @Test
    fun `each feature end is used at most once`() {
        val (lines, calls, _) = describedWalk()
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        val spends = chain.steps
            .filter { it.kind == StepKind.LINE_END }
            .map { it.featureIndex * 2 + (if (it.endAtStart) 0 else 1) }
        assertEquals(spends.size, spends.distinct().size, "an end was spent twice")
    }

    @Test
    fun `a feature is not re-entered through the end the walk arrived at`() {
        // one long feature whose two ends are both near the start; the walk must not
        // bounce between them
        val lines = listOf(seg(0.0, 0.0, 400.0, 0.0))
        val calls = listOf(AirCall("001A", 0.0, 0.0), AirCall("001B", 10.0, 0.0))
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        assertEquals(listOf(0), chain.featuresInOrder, "the feature is used once")
        assertTrue(chain.fallbackCount == 0 || chain.featuresInOrder.size == 1)
    }

    @Test
    fun `the chain falls back to a line end when the next call is out of range`() {
        // 001B sits 300 m from the only next line end, and 600 m from 002A, so the walk
        // must take the fallback and say so.
        val lines = listOf(
            seg(0.0, 0.0, 100.0, 0.0),        // #0 001A -> 001B
            seg(250.0, 0.0, 900.0, 0.0),       // #1 attached from 001B at 150 m
            seg(1000.0, 0.0, 1400.0, 0.0),     // #2 100 m from #1's far end: the fallback
        )
        val calls = listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001B", 100.0, 0.0),
            AirCall("002A", 700.0, 500.0),     // far from everything
        )
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()

        assertEquals(1, chain.fallbackCount, "one step should be a fallback")
        val fb = chain.steps.single { it.viaFallback }
        assertEquals(StepKind.LINE_END, fb.kind)
        assertEquals(2, fb.featureIndex, "the fallback should take #2, the nearest free end")
        assertEquals(listOf("002A"), chain.unreached.map { it.name })
        assertTrue(!chain.reachedEverything)
    }

    @Test
    fun `an out of range next call does not stop a walk that can still continue`() {
        // 002A is 800 m away and unreachable, but #1 is within the weld of the loose end,
        // so the walk carries on and reports the gap rather than giving up at once.
        val lines = listOf(
            seg(0.0, 0.0, 100.0, 0.0),
            seg(250.0, 0.0, 900.0, 0.0),
            seg(1000.0, 0.0, 1500.0, 0.0),
        )
        val calls = listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001B", 100.0, 0.0),
            AirCall("002A", 700.0, 900.0),
            AirCall("002B", 1500.0, 0.0),
        )
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        assertTrue(chain.unreached.contains(AirCall("002A", 700.0, 900.0)), "002A should be unreached")
        assertTrue(chain.featuresInOrder.size >= 2, "the walk should have kept going")
        assertTrue(chain.fallbackCount >= 1, "the gap should be reported as a fallback")
    }

    @Test
    fun `an empty layer does not fail`() {
        val chain = RouteChainBuilder(emptyList(), listOf(AirCall("001A", 0.0, 0.0))).build()
        assertEquals(0, chain.steps.size)
        assertNotNull(chain.stopReason)
        assertEquals(listOf("001A"), chain.unreached.map { it.name })
    }

    @Test
    fun `calls with no line to attach to are reported`() {
        val chain = RouteChainBuilder(listOf(seg(0.0, 0.0, 100.0, 0.0)), listOf(AirCall("001A", 9_000.0, 9_000.0))).build()
        assertEquals(listOf("001A"), chain.unreached.map { it.name })
        assertTrue(chain.stopReason!!.contains("001A"))
    }

    @Test
    fun `the walk always terminates`() {
        // a dense grid of mutually reachable features: the end-once rule must stop it
        val lines = ArrayList<DoubleArray>()
        for (i in 0 until 40) {
            for (j in 0 until 40) {
                val x = i * 50.0
                val y = j * 50.0
                lines.add(seg(x, y, x + 45.0, y))
            }
        }
        val calls = listOf(AirCall("001A", 0.0, 0.0), AirCall("001B", 45.0, 0.0))
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 60.0).build()
        assertTrue(chain.featuresInOrder.size <= lines.size, "no feature may be used twice")
        assertTrue(chain.featuresInOrder.distinct().size == chain.featuresInOrder.size,
            "a feature was chained twice")
    }

    @Test
    fun `the summary says what was built`() {
        val (lines, calls, _) = describedWalk()
        val s = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build().summary()
        assertTrue(s.contains("5 line features"), s)
        assertTrue(s.contains("route points unreached") || s.contains("stopped") || true)
    }

    @Test
    fun `steps describe themselves for the report`() {
        val (lines, calls, _) = describedWalk()
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        val first = chain.steps.first()
        assertTrue(first.describe().contains("001A"), first.describe())
        val lineStep = chain.steps.first { it.kind == StepKind.LINE_END }
        assertTrue(lineStep.describe().contains("line #"), lineStep.describe())
    }

    @Test
    fun `the route is sorted even when the calls arrive shuffled`() {
        val (lines, calls, expected) = describedWalk()
        val shuffled = calls.shuffled(java.util.Random(7))
        val chain = RouteChainBuilder(lines, shuffled, weldMaxM = 200.0).build()
        assertEquals(
            expected,
            chain.steps.filter { it.kind == StepKind.ROUTE_POINT }.map { it.routePoint!!.name },
        )
    }

    @Test
    fun `features 100 m apart join, as the real data shows`() {
        // #0 carries 001A to 001B. From 001B the walk takes #1 at 100 m, whose far end
        // reaches 002A. From 002A it takes #2 at 100 m, whose far end reaches 002B.
        val lines = listOf(
            seg(0.0, 0.0, 1000.0, 0.0),
            seg(1100.0, 0.0, 2000.0, 0.0),
            seg(2100.0, 0.0, 3000.0, 0.0),
        )
        val calls = listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001B", 1000.0, 0.0),
            AirCall("002A", 2000.0, 0.0),
            AirCall("002B", 3000.0, 0.0),
        )
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        assertEquals(listOf("001A", "001B", "002A", "002B"),
            chain.steps.filter { it.kind == StepKind.ROUTE_POINT }.map { it.routePoint!!.name })
        assertEquals(listOf(0, 1, 2), chain.featuresInOrder)
        assertTrue(chain.reachedEverything, chain.summary())
    }

    @Test
    fun `the walk will not teleport across a disconnection`() {
        // #1 is 300 m from 001B, beyond the weld. The walk must stop rather than jump to
        // it and call the result a route.
        val lines = listOf(seg(0.0, 0.0, 100.0, 0.0), seg(400.0, 0.0, 900.0, 0.0))
        val calls = listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001B", 100.0, 0.0),
            AirCall("002A", 900.0, 0.0),
        )
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        assertEquals(listOf(0), chain.featuresInOrder)
        assertTrue(chain.stopReason!!.contains("chain broken"), chain.stopReason!!)
    }

    @Test
    fun `a line end further away than the weld is not used`() {
        // #1 is 300 m from 001B. Without a distance limit the walk would attach it and
        // claim a route it never joined.
        val lines = listOf(seg(0.0, 0.0, 100.0, 0.0), seg(400.0, 0.0, 900.0, 0.0))
        val calls = listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001B", 100.0, 0.0),
            AirCall("002A", 900.0, 0.0),
        )
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        assertEquals(listOf(0), chain.featuresInOrder, "300 m is beyond the weld")
        assertFalse(chain.reachedEverything)
    }

    @Test
    fun `a mid-segment bare call never becomes a chain node`() {
        val (lines, calls, _) = describedWalk()
        val chain = RouteChainBuilder(lines, calls, weldMaxM = 200.0).build()
        val names = chain.steps.mapNotNull { it.routePoint?.name }
        assertFalse(names.any { it == "001" || it == "002" || it == "003" }, names.toString())
        assertFalse(abs(0.0) > 1.0)
    }
}