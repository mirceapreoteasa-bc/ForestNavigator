package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class NetworkWriterTest {

    private fun smallNetwork(): NetworkGraph {
        val src = listOf(
            SourceLine(doubleArrayOf(0.0, 0.0, 100.0, 0.0), "access", RouteClass.ACCESS),
            SourceLine(doubleArrayOf(100.5, 0.0, 100.5, 100.0), "access", RouteClass.ACCESS),
            SourceLine(doubleArrayOf(100.5, 100.0, 0.0, 100.0), "access", RouteClass.ACCESS),
            SourceLine(doubleArrayOf(0.0, 100.0, 0.0, 40.0), "roads", RouteClass.ROAD, speedKph = 40.0),
        )
        return NetworkBuilder(toleranceM = 3.0).build(src).first
    }

    @Test
    fun `writes all four shapefile components`() {
        val files = NetworkWriter.write(smallNetwork(), zone = 10)
        for (ext in listOf(".shp", ".shx", ".dbf", ".prj")) {
            assertNotNull(files["network$ext"], "missing $ext")
            assertTrue(files["network$ext"]!!.isNotEmpty(), "$ext is empty")
        }
    }

    @Test
    fun `geometry survives the round trip`() {
        val g = smallNetwork()
        val files = NetworkWriter.write(g, zone = 10)

        val info = ShpFormat.shpHeader(files["network.shp"]!!)
        assertNotNull(info)
        assertEquals(3, info!!.shapeType, "a network layer must be a Polyline")

        val back = ShpFormat.readShp(
            files["network.shp"]!!, info.xmin, info.ymin, info.xmax, info.ymax,
        )
        assertEquals(g.nodedLines.size, back.size, "feature count must survive")

        for ((i, shape) in back.withIndex()) {
            val original = g.nodedLines[i].points
            val written = shape.parts[0]
            assertEquals(original.size / 2, written.size, "vertex count differs on feature $i")
            for (j in written.indices) {
                assertEquals(original[j * 2], written[j][0], 1e-3, "e differs on feature $i vertex $j")
                assertEquals(original[j * 2 + 1], written[j][1], 1e-3, "n differs on feature $i vertex $j")
            }
        }
    }

    @Test
    fun `attributes survive the round trip`() {
        val g = smallNetwork()
        val files = NetworkWriter.write(g, zone = 10)
        val dbf = files["network.dbf"]!!
        val rows = ShpFormat.readDbfRows(dbf)
        assertEquals(g.nodedLines.size, rows.size)

        // field names live in the header; the rows carry values only
        val names = ShpFormat.dbfHeader(dbf).fields.map { it.name.trim() }
        for (required in listOf("CLASS", "SRC", "SPEED_KPH", "ONEWAY", "NODE_A", "NODE_B", "LEN_M")) {
            assertTrue(names.any { it.equals(required, true) }, "dbf is missing $required, got $names")
        }

        // spot check a value of each type survives as its own type
        val first = rows.first().values.map { it.trim() }
        val clsIdx = names.indexOfFirst { it.equals("CLASS", true) }
        val spdIdx = names.indexOfFirst { it.equals("SPEED_KPH", true) }
        val oneIdx = names.indexOfFirst { it.equals("ONEWAY", true) }
        assertEquals(g.nodedLines.first().cls.name, first[clsIdx])
        assertEquals(g.nodedLines.first().speedKph, first[spdIdx].toDouble(), 1e-6)
        assertEquals(g.nodedLines.first().oneway.toString(), first[oneIdx])
    }

    @Test
    fun `numeric fields are typed as numbers not text`() {
        val fields = listOf(
            NetworkWriter.Field("N", 'N', 8, 2),
            NetworkWriter.Field("C", 'C', 6),
        )
        val dbf = NetworkWriter.buildDbf(fields, listOf(listOf("12.50", "abc")))
        val rows = ShpFormat.readDbfRows(dbf)
        assertEquals(1, rows.size)
        val values = rows[0].values
        assertEquals(12.5, values[0].trim().toDouble(), 1e-9, "numeric field must parse as a number")
        assertEquals("abc", values[1].trim())
    }

    @Test
    fun `the dbf header is self consistent`() {
        val fields = listOf(
            NetworkWriter.Field("A", 'C', 10),
            NetworkWriter.Field("B", 'N', 8, 2),
        )
        val dbf = NetworkWriter.buildDbf(fields, listOf(listOf("x", "1.0"), listOf("y", "2.0")))
        // header length and record length are what QGIS follows
        val headerLen = (dbf[8].toInt() and 0xFF) or ((dbf[9].toInt() and 0xFF) shl 8)
        val recordLen = (dbf[10].toInt() and 0xFF) or ((dbf[11].toInt() and 0xFF) shl 8)
        assertEquals(1 + 10 + 8, recordLen, "record length must match the field widths")
        assertEquals(32 + 32 * fields.size + 1, headerLen)
        assertEquals(0x0D, dbf[headerLen - 1].toInt() and 0xFF, "header terminator")
        val expectedSize = headerLen + 2 * recordLen + 1
        assertEquals(expectedSize, dbf.size, "file size must match header plus records plus EOF")
    }

    @Test
    fun `prj names the zone and datum so the app can classify it`() {
        val nad = NetworkWriter.prjText(10, NetworkWriter.Datum.NAD83)
        assertTrue(nad.contains("UTM_Zone_10N"), nad)
        assertTrue(nad.contains("Central_Meridian\",-123.0"), nad)
        val wgs = NetworkWriter.prjText(33, NetworkWriter.Datum.WGS84)
        assertTrue(wgs.contains("UTM_Zone_33N"), wgs)
        assertTrue(wgs.contains("Central_Meridian\",15.0"), wgs)
        // Utm.classifyCrs normalises by upper-casing and keeping only alphanumerics,
        // so the generated name has to survive that transformation intact
        val flat = nad.uppercase().filter { it.isLetterOrDigit() }
        assertTrue(flat.contains("UTMZONE10N"), "classifier key missing: $flat")
        val flat33 = wgs.uppercase().filter { it.isLetterOrDigit() }
        assertTrue(flat33.contains("UTMZONE33N"), "classifier key missing: $flat33")
    }

    @Test
    fun `an empty network still produces a valid empty layer`() {
        val g = NetworkBuilder(3.0).build(emptyList()).first
        val files = NetworkWriter.write(g, zone = 10)
        assertTrue(files["network.shp"]!!.isNotEmpty())
        assertEquals(0, ShpFormat.readDbfRows(files["network.dbf"]!!).size)
    }

    @Test
    fun `node indices make the graph rebuildable from the layer alone`() {
        val g = smallNetwork()
        val files = NetworkWriter.write(g, zone = 10)
        val dbf = files["network.dbf"]!!
        val rows = ShpFormat.readDbfRows(dbf)
        val names = ShpFormat.dbfHeader(dbf).fields.map { it.name.trim() }
        val ia = names.indexOfFirst { it.equals("NODE_A", true) }
        val ib = names.indexOfFirst { it.equals("NODE_B", true) }
        assertTrue(ia >= 0 && ib >= 0, "node index columns are required")
        for (r in rows) {
            val a = r.values[ia].trim().toInt()
            val b = r.values[ib].trim().toInt()
            assertTrue(a in 0 until g.nodeCount, "node_a $a out of range")
            assertTrue(b in 0 until g.nodeCount, "node_b $b out of range")
        }
    }
}
