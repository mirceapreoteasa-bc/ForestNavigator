package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A straight flight line from `from` to `to`, as separate segments. */
private fun line(from: Double, to: Double, segLenM: Double = 1000.0, gapM: Double = 0.0): List<DoubleArray> {
    val parts = ArrayList<DoubleArray>()
    var x = from
    while (x < to - 0.001) {
        val nx = minOf(x + segLenM, to)
        parts.add(doubleArrayOf(x, 0.0, nx, 0.0))
        x = nx + gapM
    }
    return parts
}

class AirCallOrderTest {

    @Test
    fun `numbers are read out of the name not sorted as text`() {
        assertEquals(2, AirCall("002A", 0.0, 0.0).order)
        assertEquals(101, AirCall("101", 0.0, 0.0).order)
        assertEquals(217, AirCall("217A", 0.0, 0.0).order)
        assertEquals(101, AirCall("air_call 101", 0.0, 0.0).order)
    }

    @Test
    fun `names without digits sort after numbered ones`() {
        assertEquals(Int.MAX_VALUE, AirCall("base", 0.0, 0.0).order)
        val sorted = listOf(
            AirCall("base", 0.0, 0.0),
            AirCall("003A", 0.0, 0.0),
            AirCall("002A", 0.0, 0.0),
        ).sortedWith(AirCallOrder)
        assertEquals(listOf("002A", "003A", "base"), sorted.map { it.name })
    }

    @Test
    fun `zero padding does not affect the order`() {
        // The whole point: as text this is 1, 10, 100, 101, 102, 11, 12 ...
        val names = listOf("1", "10", "100", "101", "102", "11", "12", "2", "3")
        val sorted = names.map { AirCall(it, 0.0, 0.0) }.sortedWith(AirCallOrder)
        assertEquals(listOf("1", "2", "3", "10", "11", "12", "100", "101", "102"), sorted.map { it.name })
    }

    @Test
    fun `the documented A bare B convention holds`() {
        // Exactly the sequence given: A first, then bare, then B, grouped by number.
        val expected = listOf(
            "101A", "101", "101B",
            "102A", "102", "102B",
            "103A", "103", "103B",
            "104A", "104", "104B",
        )
        val sorted = expected.map { AirCall(it, 0.0, 0.0) }.sortedWith(AirCallOrder)
        assertEquals(expected, sorted.map { it.name })
    }

    @Test
    fun `suffix is read separately from the number`() {
        assertEquals(101, AirCall("101A", 0.0, 0.0).order)
        assertEquals("A", AirCall("101A", 0.0, 0.0).suffix)
        assertEquals("", AirCall("101", 0.0, 0.0).suffix)
        assertEquals("B", AirCall("101B", 0.0, 0.0).suffix)
        assertEquals(0, AirCall("101A", 0.0, 0.0).suffixRank)
        assertEquals(1, AirCall("101", 0.0, 0.0).suffixRank)
        assertEquals(2, AirCall("101B", 0.0, 0.0).suffixRank)
    }

    @Test
    fun `text sort puts the bare name first, which is wrong`() {
        val names = listOf("101A", "101", "101B")
        // the trap: as text this is 101, 101A, 101B
        assertEquals(listOf("101", "101A", "101B"), names.sorted())
        val byNumber = names.map { AirCall(it, 0.0, 0.0) }.sortedWith(AirCallOrder).map { it.name }
        assertEquals(listOf("101A", "101", "101B"), byNumber)
    }

    @Test
    fun `an unexpected suffix still sorts stably after A and B`() {
        val sorted = listOf("101C", "101B", "101", "101A", "101D")
            .map { AirCall(it, 0.0, 0.0) }
            .sortedWith(AirCallOrder)
            .map { it.name }
        assertEquals(listOf("101A", "101", "101B", "101C", "101D"), sorted)
    }

    @Test
    fun `case of the suffix does not matter`() {
        val sorted = listOf("101b", "101a", "101")
            .map { AirCall(it, 0.0, 0.0) }
            .sortedWith(AirCallOrder)
            .map { it.name }
        assertEquals(listOf("101a", "101", "101b"), sorted)
    }

    @Test
    fun `text sort would have been wrong`() {
        val names = listOf("1", "2", "10", "11")
        val asText = names.sorted()
        val byNumber = names.map { AirCall(it, 0.0, 0.0) }.sortedWith(AirCallOrder).map { it.name }
        assertTrue(asText != byNumber, "guard: the fixture must actually differ from text order")
        assertEquals(listOf("1", "2", "10", "11"), byNumber)
    }
}

class FlightNetworkTest {

    @Test
    fun `segments are welded into one route and calls sequence along it`() {
        // 10 segments with 0.05 m gaps: well inside the 30 m weld distance
        val parts = line(0.0, 10_000.0, segLenM = 1000.0, gapM = 0.05)
        val calls = (1..5).map { AirCall("c${it}00", it * 1800.0, 0.0) }

        val fn = FlightNetwork(parts, calls)
        assertEquals(0, fn.unplacedCalls.size, "every call should sit on the welded path")
        assertEquals(1, fn.runs.size, "welded segments are one continuous flight")
        assertEquals(
            listOf("c100", "c200", "c300", "c400", "c500"),
            fn.orderedCalls.map { it.name },
        )
    }

    @Test
    fun `the next call after the one the crew reached is offered`() {
        val parts = line(0.0, 10_000.0)
        val calls = (1..4).map { AirCall("${it}01", it * 2000.0, 0.0) }
        val fn = FlightNetwork(parts, calls)

        // the resume case: crew stops after 101 and picks up again
        assertEquals("201", fn.nextAfter(calls[0])?.name)
        assertEquals("301", fn.nextAfter(calls[1])?.name)
        assertEquals("401", fn.nextAfter(calls[2])?.name)
        assertNull(fn.nextAfter(calls[3]), "no target beyond the last call")
        assertEquals(3, fn.callsRemainingAfter(calls[0]))
        assertEquals(0, fn.callsRemainingAfter(calls[3]))
    }

    @Test
    fun `a large jump between calls splits the flight`() {
        // 1.5 km apart: a repositioning leg, same flight
        val parts = line(0.0, 60_000.0, segLenM = 1000.0)
        val calls = listOf(
            AirCall("001", 1_000.0, 0.0),
            AirCall("002", 2_500.0, 0.0),
        )
        val sameFlight = FlightNetwork(parts, calls)
        assertEquals(1, sameFlight.runs.size, "500 m apart is not a new flight")
        assertTrue(!sameFlight.runs[0].brokeAfterPrevious)

        // 40 km apart: a different flight
        val far = listOf(
            AirCall("001", 1_000.0, 0.0),
            AirCall("002", 41_000.0, 0.0),
        )
        val split = FlightNetwork(parts, far)
        assertEquals(2, split.runs.size, "40 km apart is a new flight")
        assertTrue(split.runs[1].brokeAfterPrevious)

        // and the last call of one flight must not lead into the next one
        assertNull(split.nextAfter(far[0]), "001 must not lead into 002 on another flight")
    }

    @Test
    fun `a gap inside the weld distance does not split the flight`() {
        // 20 m gaps: inside the 30 m weld, so still one route
        val parts = line(0.0, 10_000.0, segLenM = 500.0, gapM = 20.0)
        val calls = (1..4).map { AirCall("$it", it * 2000.0, 0.0) }
        val fn = FlightNetwork(parts, calls)
        assertEquals(1, fn.runs.size, "20 m gaps should weld into one route")
    }

    @Test
    fun `long return legs do not split a flight`() {
        // Consecutive calls 8 km apart as the aircraft repositions to begin another
        // line. Under the 20 km threshold this is one flight, which is the point: the
        // real layer has gaps of this size between every line of a flight.
        val parts = line(0.0, 60_000.0, segLenM = 1000.0)
        val calls = listOf(
            AirCall("001", 2_000.0, 0.0),
            AirCall("002", 10_000.0, 0.0),
            AirCall("003", 18_000.0, 0.0),
        )
        val fn = FlightNetwork(parts, calls)
        assertEquals(1, fn.runs.size, "8 km between calls is still one flight")
        assertEquals(3, fn.runs[0].calls.size)
    }

    @Test
    fun `the flight break threshold sits between repositioning and real breaks`() {
        // 9 km: repositioning. 19 km: still the same flight. 21 km and 40 km: new flight.
        val parts = line(0.0, 60_000.0, segLenM = 1000.0)
        fun runsFor(gapM: Double) = FlightNetwork(
            parts, listOf(AirCall("001", 1_000.0, 0.0), AirCall("002", 1_000.0 + gapM, 0.0)),
        ).runs.size

        assertEquals(1, runsFor(9_000.0), "9 km is a repositioning leg")
        assertEquals(1, runsFor(19_000.0), "19 km is still the same flight")
        assertEquals(2, runsFor(21_000.0), "21 km is a new flight")
        assertEquals(2, runsFor(40_000.0), "40 km is a new flight")
    }

    @Test
    fun `calls off the flight path are reported but still sequenced`() {
        val parts = line(0.0, 10_000.0)
        val calls = listOf(
            AirCall("001", 2_000.0, 0.0),
            AirCall("002", 5_000.0, 9_000.0),      // 9 km off the path
            AirCall("003", 8_000.0, 0.0),
        )
        val fn = FlightNetwork(parts, calls)

        // reported, because no leg can be drawn along the path to it
        assertEquals(listOf("002"), fn.unplacedCalls.map { it.name })
        // but still in the sequence: the crew still has to reach it, and silently
        // skipping it would drop work
        assertEquals(listOf("001", "002", "003"), fn.orderedCalls.map { it.name })
        assertEquals(1, fn.runs.size)
        assertEquals(listOf("001", "002", "003"), fn.runs[0].calls.map { it.name })
        assertEquals("002", fn.nextAfter(calls[0])?.name)
    }

    @Test
    fun `an empty layer does not fail`() {
        val fn = FlightNetwork(emptyList(), listOf(AirCall("001", 0.0, 0.0)))
        assertTrue(fn.isEmpty)
        assertEquals(1, fn.unplacedCalls.size)
        // the call is still sequenced, so the crew is not silently given an empty task
        assertEquals(1, fn.runs.size)
        assertEquals(listOf("001"), fn.runs[0].calls.map { it.name })
        assertNull(fn.nextAfter(AirCall("001", 0.0, 0.0)))
        assertNull(fn.nextAfter(AirCall("001", 0.0, 0.0)))
        assertEquals(0.0, fn.pathLengthM(), 0.001)
    }

    @Test
    fun `no calls still welds the path`() {
        val fn = FlightNetwork(line(0.0, 10_000.0), emptyList())
        assertTrue(fn.isEmpty, "nothing to navigate without calls")
        assertTrue(fn.pathLengthM() >= 9_900.0, "path length should survive: ${fn.pathLengthM()}")
        assertEquals(0, fn.runs.size)
    }

    @Test
    fun `single segment path works`() {
        val fn = FlightNetwork(listOf(doubleArrayOf(0.0, 0.0, 5_000.0, 0.0)), listOf(
            AirCall("001", 1_000.0, 0.0),
            AirCall("002", 4_000.0, 0.0),
        ))
        assertEquals(1, fn.runs.size)
        assertEquals("002", fn.nextAfter(AirCall("001", 1_000.0, 0.0))?.name)
    }

    @Test
    fun `flight path welding does not relax the road limit`() {
        // The road cap must be untouched by the flight-path factory.
        assertEquals(5.0, NetworkBuilder.MAX_UNSUPERVISED_TOLERANCE_M, 0.001)
        assertEquals(250.0, NetworkBuilder.FLIGHT_PATH_WELD_M, 0.001)

        // and a plain builder still refuses to go beyond the road cap
        val refused = runCatching { NetworkBuilder(toleranceM = 30.0) }
        assertTrue(refused.isFailure, "an automatic road build must refuse a 30 m weld")
    }

    @Test
    fun `flight path builder accepts the flight weld distance`() {
        val b = NetworkBuilder.forFlightPath()
        assertTrue(b != null)
    }

    @Test
    fun `welded nodes are placed between the points they replace`() {
        // Two segments whose endpoints are 100 m apart. After welding they share a node,
        // and that node must not sit on one endpoint and drag the other segment's edge
        // 100 m off its true geometry.
        val a = doubleArrayOf(0.0, 0.0, 1000.0, 0.0)
        val b = doubleArrayOf(1000.0 + 100.0, 0.0, 2000.0, 0.0)
        val fn = FlightNetwork(listOf(a, b), emptyList())
        assertEquals(1, fn.report!!.regionCount, "the gap is inside the weld distance")

        // the middle node is the only welded one, and must sit mid-gap
        val e = fn.graph!!.nodeE
        val n = fn.graph!!.nodeN
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in e.indices) {
            val d = kotlin.math.abs(e[i] - 1050.0)
            if (d < bestD) { bestD = d; best = i }
        }
        assertTrue(bestD < 1.0, "welded node should sit near x=1050, was ${"%.1f".format(e[best])}")

        // and the reported displacement must be about half the gap, not the whole gap
        val disp = fn.report!!.nodeDisplacementM
        assertTrue(disp in 40.0..60.0, "expected ~50 m displacement, got ${"%.1f".format(disp)}")
    }

    @Test
    fun `run lookup finds the right flight`() {
        val parts = line(0.0, 60_000.0, segLenM = 1000.0)
        val calls = listOf(
            AirCall("001", 1_000.0, 0.0),
            AirCall("002", 5_000.0, 0.0),
            AirCall("003", 45_000.0, 0.0),
        )
        val fn = FlightNetwork(parts, calls)
        assertEquals(2, fn.runs.size)
        assertEquals(listOf("001", "002"), fn.runOf(calls[0])?.calls?.map { it.name })
        assertEquals(listOf("003"), fn.runOf(calls[2])?.calls?.map { it.name })
    }

    @Test
    fun `a fragmented weld does not break the sequence`() {
        // Path features scattered 400 m apart, far beyond the 30 m weld, so the graph
        // shatters into many components. The name order must survive regardless: the
        // crew's work is sequenced by the names, not by the geometry.
        val scattered = (0 until 20).map { i ->
            doubleArrayOf(i * 5000.0, 0.0, i * 5000.0 + 400.0, 0.0)
        }
        val calls = (1..5).map { AirCall("$it", it * 3000.0, 0.0) }
        val fn = FlightNetwork(scattered, calls)

        assertTrue(fn.report!!.regionCount > 5, "the weld should fragment here")
        assertEquals(listOf("1", "2", "3", "4", "5"), fn.orderedCalls.map { it.name })
        assertEquals(1, fn.runs.size, "3 km between calls is one flight, weld or not")
        assertEquals("2", fn.nextAfter(calls[0])?.name)
        assertEquals("5", fn.nextAfter(calls[3])?.name)
        assertNull(fn.nextAfter(calls[4]))
    }
}
