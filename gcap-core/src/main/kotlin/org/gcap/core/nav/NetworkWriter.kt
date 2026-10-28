package org.gcap.core.nav

import org.gcap.core.ShpFormat
import java.io.ByteArrayOutputStream
import java.util.Calendar
import kotlin.math.roundToLong

/**
 * Writes a built network out as a shapefile layer.
 *
 * A network is only useful if it can leave the app: users inspect it in QGIS, script
 * against it, and exchange it with the desktop tool that will do supervised repair.
 * So the output is an ordinary Polyline layer, not an internal format.
 *
 * Each feature is one **noded chain** - a run of edges between junctions - carrying
 * its class and speed, plus `node_a`/`node_b` so the routing graph can be rebuilt
 * from the layer alone. Writing one feature per edge would be tidier for routing but
 * destroys the road's shape on screen.
 *
 * All four files are returned as bytes rather than written to disk, because the app
 * targets a user-chosen SAF folder where direct file writes are not available.
 */
object NetworkWriter {

    enum class Datum(val prjName: String) {
        WGS84("WGS_1984_UTM_Zone_%dN"),
        NAD83("NAD_1983_UTM_Zone_%dN"),
    }

    data class Field(val name: String, val type: Char, val length: Int, val decimals: Int = 0)

    /** Attribute schema of a network layer. Names are upper-cased by the DBF writer. */
    val FIELDS: List<Field> = listOf(
        Field("CLASS", 'C', 10),
        Field("SRC", 'C', 12),
        Field("SPEED_KPH", 'N', 8, 2),
        Field("ONEWAY", 'N', 1),
        Field("CONF", 'N', 4),
        Field("N_EDGES", 'N', 6),
        Field("NODE_A", 'N', 9),
        Field("NODE_B", 'N', 9),
        Field("LEN_M", 'N', 12, 2),
        Field("REGION", 'N', 7),
    )

    fun write(
        graph: NetworkGraph,
        zone: Int,
        datum: Datum = Datum.NAD83,
        baseName: String = "network",
    ): Map<String, ByteArray> {
        val lines = graph.nodedLines.filter { it.edgeIds.isNotEmpty() && it.points.size >= 4 }

        val parts = ArrayList<List<DoubleArray>>(lines.size)
        val rows = ArrayList<List<String>>(lines.size)

        for (ln in lines) {
            val pts = ArrayList<DoubleArray>(ln.points.size / 2)
            for (i in 0 until ln.points.size / 2) {
                pts.add(doubleArrayOf(ln.points[i * 2], ln.points[i * 2 + 1]))
            }
            parts.add(pts)

            val firstEdge = graph.edges[ln.edgeIds.first()]
            val lastEdge = graph.edges[ln.edgeIds.last()]
            var lenM = 0.0
            for (ei in ln.edgeIds) lenM += graph.edges[ei].lengthM
            val region = graph.regionOfEdge[ln.edgeIds.first()]

            rows.add(
                listOf(
                    ln.cls.name,
                    ln.layer,
                    fmt(ln.speedKph, 2),
                    ln.oneway.toString(),
                    ln.confidence.toString(),
                    ln.edgeIds.size.toString(),
                    firstEdge.fromNode.toString(),
                    lastEdge.toNode.toString(),
                    fmt(lenM, 2),
                    region.toString(),
                ),
            )
        }

        return mapOf(
            "$baseName.shp" to ShpFormat.buildPolylineShp(parts),
            "$baseName.shx" to ShpFormat.buildPolylineShx(parts),
            "$baseName.dbf" to buildDbf(FIELDS, rows),
            "$baseName.prj" to prjText(zone, datum).toByteArray(Charsets.US_ASCII),
        )
    }

    /** The .prj text, written to match what the app's CRS classifier recognises. */
    fun prjText(zone: Int, datum: Datum): String {
        val centralMeridian = zone * 6 - 183
        val geoName = if (datum == Datum.NAD83) "GCS_North_American_1983" else "GCS_WGS_1984"
        val datumName = if (datum == Datum.NAD83) "D_North_American_1983" else "D_WGS_1984"
        val spheroid = if (datum == Datum.NAD83) "GRS_1980" else "WGS_1984"
        val flattening = if (datum == Datum.NAD83) "298.257222101" else "298.257223563"
        return "PROJCS[\"${datum.prjName.format(zone)}\"," +
            "GEOGCS[\"$geoName\",DATUM[\"$datumName\",SPHEROID[\"$spheroid\",6378137.0,$flattening]]," +
            "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]," +
            "PROJECTION[\"Transverse_Mercator\"]," +
            "PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0]," +
            "PARAMETER[\"Central_Meridian\",$centralMeridian.0]," +
            "PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0]," +
            "UNIT[\"Meter\",1.0]]"
    }

    private fun fmt(v: Double, dec: Int): String {
        var s = String.format("%.${dec}f", v)
        if (s == "-0" || s.startsWith("-0.") && s.drop(3).all { it == '0' }) s = s.removePrefix("-")
        return s
    }

    /**
     * dBASE III table with typed fields, so numbers are stored as numbers rather than
     * text. dBASE is fussy - 32-byte descriptors, a 4-byte record count, exact record
     * length - and a wrong header offset makes QGIS silently mis-read the table.
     */
    fun buildDbf(fields: List<Field>, rows: List<List<String>>): ByteArray {
        require(fields.isNotEmpty() && fields.size <= 255)
        require(fields.all { it.length in 1..254 })
        val out = ByteArrayOutputStream()
        val recordLen = 1 + fields.sumOf { it.length }
        val headerLen = 32 + 32 * fields.size + 1

        out.write(3)
        val now = Calendar.getInstance()
        out.write(now.get(Calendar.YEAR) - 1900)
        out.write(now.get(Calendar.MONTH) + 1)
        out.write(now.get(Calendar.DAY_OF_MONTH))
        writeIntLe(out, rows.size)
        writeInt16Le(out, headerLen)
        writeInt16Le(out, recordLen)
        out.write(ByteArray(20))

        for (f in fields) {
            val clean = f.name.uppercase().filter { it.code in 32..126 }.take(10)
            val padded = ByteArray(11) { i -> if (i < clean.length) clean[i].code.toByte() else 0 }
            out.write(padded)
            out.write(f.type.code)
            out.write(ByteArray(4))          // field data address, unused
            out.write(f.length)
            out.write(if (f.type == 'N') f.decimals else 0)
            out.write(ByteArray(14))
        }
        out.write(0x0D)                      // header terminator

        for (row in rows) {
            out.write(0x20)                  // not deleted
            for ((i, f) in fields.withIndex()) {
                val v = row.getOrNull(i).orEmpty()
                val bytes = when (f.type) {
                    'N' -> {
                        val d = v.toDoubleOrNull() ?: 0.0
                        val t = String.format("%.${f.decimals}f", d)
                        t.padStart(f.length, ' ').take(f.length)
                    }
                    else -> v.padEnd(f.length, ' ').take(f.length)
                }.toByteArray(Charsets.US_ASCII)
                out.write(bytes)
            }
        }
        out.write(0x1A)                      // EOF marker
        return out.toByteArray()
    }

    private fun writeIntLe(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v shr 8) and 0xFF)
        o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF)
    }

    private fun writeInt16Le(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v shr 8) and 0xFF)
    }
}
