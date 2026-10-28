package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlightRouteLinesTest {

    private fun calls(vararg spec: Triple<String, Double, Double>) =
        spec.map { AirCall(it.first, it.second, it.third) }

    @Test
    fun `short features are dropped and long ones kept`() {
        val lines = listOf(
            doubleArrayOf(0.0, 0.0, 50.0, 0.0),       // 50 m, dropped
            doubleArrayOf(0.0, 0.0, 1000.0, 0.0),     // 1000 m, kept
            doubleArrayOf(0.0, 0.0, 199.0, 0.0),      // 199 m, dropped
            doubleArrayOf(0.0, 0.0, 200.0, 0.0),      // exactly 200 m, kept
        )
        val out = FlightRouteLines.build(lines, emptyList())
        assertEquals(2, out.longFeatures.size)
        assertEquals(2, out.droppedShortFeatures)
    }

    @Test
    fun `length is the true polyline length not the endpoint distance`() {
        // a zig-zag whose endpoints are close but whose path is long
        val zig = doubleArrayOf(0.0, 0.0, 100.0, 100.0, 0.0, 200.0, 100.0, 300.0)
        val len = FlightRouteLines.lengthM(zig)
        assertTrue(len > 400.0, "expected the summed length, got $len")
        val out = FlightRouteLines.build(listOf(zig), emptyList())
        assertEquals(1, out.longFeatures.size, "a long zig-zag should survive")
    }

    @Test
    fun `connectors are made for each A B pair`() {
        val cs = calls(
            Triple("001A", 0.0, 0.0),
            Triple("001B", 150.0, 0.0),
            Triple("002A", 1000.0, 0.0),
            Triple("002B", 1150.0, 0.0),
        )
        val out = FlightRouteLines.build(emptyList(), cs)
        assertEquals(2, out.connectorCount)
        assertEquals(0, out.rejected.size)
        assertEquals(150.0, FlightRouteLines.lengthM(out.connectors[0]), 0.01)
        assertEquals(0.0, out.connectors[0][0], 0.01)
        assertEquals(150.0, out.connectors[0][2], 0.01)
    }

    @Test
    fun `a pair further apart than the limit is reported not drawn`() {
        val cs = calls(
            Triple("001A", 0.0, 0.0),
            Triple("001B", 150.0, 0.0),      // 150 m: kept
            Triple("002A", 0.0, 0.0),
            Triple("002B", 900.0, 0.0),      // 900 m: rejected
        )
        val out = FlightRouteLines.build(emptyList(), cs)
        assertEquals(1, out.connectorCount)
        assertEquals(1, out.rejected.size)
        assertEquals("002A", out.rejected[0].from.name)
        assertEquals(900.0, out.rejected[0].lengthM, 0.01)
        assertTrue(out.summary().contains("rejected"), out.summary())
    }

    @Test
    fun `pairs are formed only within one triple`() {
        val pairs = FlightRouteLines.pairsOf(
            calls(
                Triple("001A", 0.0, 0.0),
                Triple("001", 50.0, 0.0),
                Triple("001B", 100.0, 0.0),
                Triple("002A", 200.0, 0.0),
                Triple("002", 250.0, 0.0),
                Triple("002B", 300.0, 0.0),
            ),
        )
        assertEquals(listOf("001A" to "001B", "002A" to "002B"), pairs.map { it.first.name to it.second.name })
    }

    @Test
    fun `a triple missing its bare call still pairs`() {
        val pairs = FlightRouteLines.pairsOf(
            calls(Triple("001A", 0.0, 0.0), Triple("001B", 100.0, 0.0)),
        )
        assertEquals(1, pairs.size)
    }

    @Test
    fun `a B call with no A of its own does not pair with the next triple`() {
        val pairs = FlightRouteLines.pairsOf(
            calls(
                Triple("001A", 0.0, 0.0),
                Triple("002A", 200.0, 0.0),
                Triple("002B", 300.0, 0.0),
            ),
        )
        assertEquals(listOf("002A" to "002B"), pairs.map { it.first.name to it.second.name })
    }

    @Test
    fun `pairs do not cross triples`() {
        // 001B and 002A are adjacent in the sequence but belong to different triples, so
        // the only pair is 001A-001B and 002A is left without one.
        val pairs = FlightRouteLines.pairsOf(
            calls(
                Triple("001A", 0.0, 0.0),
                Triple("001B", 100.0, 0.0),
                Triple("002A", 200.0, 0.0),
            ),
        )
        assertEquals(listOf("001A" to "001B"), pairs.map { it.first.name to it.second.name })
        assertTrue(pairs.none { it.first.name == "001B" && it.second.name == "002A" })
    }

    @Test
    fun `connectors follow flying order not file order`() {
        val cs = calls(
            Triple("002B", 300.0, 0.0),
            Triple("001A", 0.0, 0.0),
            Triple("002A", 200.0, 0.0),
            Triple("001B", 100.0, 0.0),
        )
        val out = FlightRouteLines.build(emptyList(), cs)
        assertEquals(0.0, out.connectors[0][0], 0.01, "001A comes first")
        assertEquals(200.0, out.connectors[1][0], 0.01, "002A second")
    }

    @Test
    fun `all combines long features and connectors`() {
        val out = FlightRouteLines.build(
            listOf(doubleArrayOf(0.0, 0.0, 1000.0, 0.0)),
            calls(Triple("001A", 0.0, 0.0), Triple("001B", 100.0, 0.0)),
        )
        assertEquals(2, out.all.size)
    }

    @Test
    fun `degenerate features are dropped not kept`() {
        val out = FlightRouteLines.build(
            listOf(doubleArrayOf(5.0, 5.0), doubleArrayOf(5.0, 5.0, 5.0, 5.0)),
            emptyList(),
        )
        assertEquals(0, out.longFeatures.size)
        assertEquals(2, out.droppedShortFeatures)
    }

    @Test
    fun `no calls means no connectors and no crash`() {
        val out = FlightRouteLines.build(listOf(doubleArrayOf(0.0, 0.0, 1000.0, 0.0)), emptyList())
        assertEquals(0, out.connectorCount)
        assertEquals(1, out.longFeatures.size)
    }

    @Test
    fun `thresholds are independent`() {
        val lines = listOf(doubleArrayOf(0.0, 0.0, 300.0, 0.0))
        val cs = calls(Triple("001A", 0.0, 0.0), Triple("001B", 250.0, 0.0))
        // a tighter feature threshold drops the 300 m line but the 250 m pair is still
        // inside a 200 m... no: 250 > 200 so it is rejected too
        val out = FlightRouteLines.build(lines, cs, minFeatureM = 400.0, maxConnectorM = 200.0)
        assertEquals(0, out.longFeatures.size)
        assertEquals(0, out.connectorCount)
        assertEquals(1, out.rejected.size)
    }
}