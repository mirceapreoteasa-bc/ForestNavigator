package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class FlightPlanWriterTest {

    private fun samplePlan(): Pair<FlightPlan, List<AirCall>> {
        val calls = listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001B", 150.0, 0.0),
            AirCall("002A", 2000.0, 0.0),
            AirCall("002B", 2150.0, 0.0),
        )
        return FlightPlan.build(calls) to calls
    }

    private fun tmp(): File = Files.createTempDirectory("flightplan").toFile()

    @Test
    fun `legs are written and read back`() {
        val dir = tmp()
        val (plan, calls) = samplePlan()
        val shp = FlightPlanWriter(plan, calls).writeLegs(dir, "plan_legs", "")
        assertTrue(shp.exists())

        val b = shp.readBytes()
        val info = ShpFormat.shpHeader(b)!!
        assertEquals(3, info.recordCount, "3 legs: transect, transit, transect")
        assertEquals(3, ShpFormat.readShp(b, info.xmin, info.ymin, info.xmax, info.ymax).size)
    }

    @Test
    fun `each leg carries both call names and its kind`() {
        val dir = tmp()
        val (plan, calls) = samplePlan()
        FlightPlanWriter(plan, calls).writeLegs(dir, "plan_legs", "")
        val rows = ShpFormat.readDbfRows(File(dir, "plan_legs.dbf").readBytes())
        assertEquals(3, rows.size)
        val f = ShpFormat.dbfHeader(File(dir, "plan_legs.dbf").readBytes()).fields.map { it.name }
        val from = f.indexOf("FROM_CALL")
        val to = f.indexOf("TO_CALL")
        val leg = f.indexOf("LEG")
        assertEquals("001A", rows[0].values[from])
        assertEquals("001B", rows[0].values[to])
        assertEquals("TRANSECT", rows[0].values[leg])
        assertEquals("TRANSIT", rows[1].values[leg])
        assertEquals("TRANSECT", rows[2].values[leg])
    }

    @Test
    fun `the traced flag marks which legs are straight fallbacks`() {
        val dir = tmp()
        val (plan, calls) = samplePlan()
        FlightPlanWriter(plan, calls).writeLegs(dir, "plan_legs", "")
        val bytes = File(dir, "plan_legs.dbf").readBytes()
        val f = ShpFormat.dbfHeader(bytes).fields.map { it.name }
        val on = f.indexOf("TRACED")
        val rows = ShpFormat.readDbfRows(bytes)
        // no flight path was supplied, so every leg falls back to a straight line
        assertTrue(rows.all { it.values[on] == "N" }, rows.joinToString { it.values[on] })
    }

    @Test
    fun `calls are written as points in flying order`() {
        val dir = tmp()
        val calls = listOf(
            AirCall("002B", 2150.0, 0.0),
            AirCall("001A", 0.0, 0.0),
            AirCall("001B", 150.0, 0.0),
        )
        val plan = FlightPlan.build(calls)
        val shp = FlightPlanWriter(plan, calls).writeCalls(dir, "plan_calls", "")
        val info = ShpFormat.shpHeader(shp.readBytes())!!
        assertEquals(3, info.recordCount)
        val bytes = File(dir, "plan_calls.dbf").readBytes()
        val f = ShpFormat.dbfHeader(bytes).fields.map { it.name }
        val id = f.indexOf("CALL_ID")
        val rows = ShpFormat.readDbfRows(bytes)
        assertEquals(listOf("001A", "001B", "002B"), rows.map { it.values[id] })
    }

    @Test
    fun `the bare calls role is marked rather than left blank`() {
        val dir = tmp()
        val calls = listOf(
            AirCall("001A", 0.0, 0.0),
            AirCall("001", 75.0, 0.0),
            AirCall("001B", 150.0, 0.0),
        )
        val plan = FlightPlan.build(calls)
        FlightPlanWriter(plan, calls).writeCalls(dir, "plan_calls", "")
        val bytes = File(dir, "plan_calls.dbf").readBytes()
        val f = ShpFormat.dbfHeader(bytes).fields.map { it.name }
        val role = f.indexOf("ROLE")
        val rows = ShpFormat.readDbfRows(bytes)
        assertEquals(listOf("A", "-", "B"), rows.map { it.values[role] })
    }

    @Test
    fun `the projection is written so the layer lines up in qgis`() {
        val dir = tmp()
        val (plan, calls) = samplePlan()
        val prj = "GEOGCS[\"WGS 84\"]"
        FlightPlanWriter(plan, calls).writeLegs(dir, "plan_legs", prj)
        assertEquals(prj, File(dir, "plan_legs.prj").readText())
    }

    @Test
    fun `all four files are written`() {
        val dir = tmp()
        val (plan, calls) = samplePlan()
        val w = FlightPlanWriter(plan, calls)
        w.writeLegs(dir, "plan_legs", "x")
        w.writeCalls(dir, "plan_calls", "x")
        for (ext in listOf("shp", "shx", "dbf", "prj")) {
            assertTrue(File(dir, "plan_legs.$ext").exists(), "plan_legs.$ext missing")
            assertTrue(File(dir, "plan_calls.$ext").exists(), "plan_calls.$ext missing")
        }
    }

    @Test
    fun `the summary states what was written`() {
        val (plan, calls) = samplePlan()
        val s = FlightPlanWriter(plan, calls).summary()
        assertTrue(s.contains("3 legs"), s)
        assertTrue(s.contains("2 transects"), s)
        assertTrue(s.contains("1 transits"), s)
        assertTrue(s.contains("4 air calls"), s)
    }

    @Test
    fun `the prj of the source layer is picked up`() {
        val dir = tmp()
        File(dir, "Air_Calls_YR_4.prj").writeText("PROJCS[\"x\"]")
        assertEquals("PROJCS[\"x\"]", FlightPlanWriter.prjTextOf(dir, "Air_Calls_YR_4.shp"))
        assertEquals("", FlightPlanWriter.prjTextOf(dir, "missing.shp"))
    }
}