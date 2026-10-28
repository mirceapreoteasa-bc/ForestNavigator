package org.gcap.core.nav

import org.gcap.core.ShpFormat
import java.io.File

/**
 * Writes the flight plan out as shapefiles, so it can be opened in QGIS alongside the
 * original layers and judged there rather than only in the app.
 *
 * Two layers are written:
 *
 * - **`<name>_legs`** — one line per leg of the plan, transects and transits alike, with
 *   the two air calls it joins, which kind it is, its length, and **whether flight-path
 *   geometry actually runs along it**. That last attribute is the one worth styling on:
 *   it separates legs drawn along recorded track from legs that are only straight lines
 *   because nothing was recorded.
 * - **`<name>_calls`** — the air calls themselves, so the legs can be related to points.
 *
 * The projection is whatever the caller says. Coordinates are written unchanged, so the
 * plan must be tagged with the same CRS as the source layers or it will not line up in
 * QGIS.
 */
class FlightPlanWriter(
    private val plan: FlightPlan,
    private val calls: List<AirCall>,
) {
    /**
     * The legs, one feature per plan segment.
     *
     * Each feature carries the leg's real geometry, not a chord between the two calls: the
     * recorded lines follow the ascent and descent the pilot flew, avoiding high peaks and
     * ridges, so a straight line would cut across ground that was deliberately avoided.
     * A feature is a list of points, each a flat `e,n` pair, which is the shape
     * [ShpFormat.buildPolylineShp] and [NetworkWriter] use.
     */
    private fun legParts(): List<List<DoubleArray>> = plan.segments.map { seg ->
        val pts = ArrayList<DoubleArray>(seg.vertexCount)
        for (i in 0 until seg.vertexCount) {
            pts.add(doubleArrayOf(seg.geometry[i * 2], seg.geometry[i * 2 + 1]))
        }
        if (pts.size < 2) pts.add(doubleArrayOf(seg.to.e, seg.to.n))
        pts
    }

    fun writeLegs(dir: File, name: String, prj: String): File {
        val rows = plan.segments.map { s ->
            listOf(
                s.from.name,
                s.to.name,
                if (s.leg == PlanLeg.TRANSECT) "TRANSECT" else "TRANSIT",
                fmt(s.distanceM),
                fmt(s.worstGapM),
                if (s.traced) "Y" else "N",
                s.vertexCount.toString(),
            )
        }
        return write(
            dir, name, legParts(), rows,
            listOf(
                "FROM_CALL" to 8,
                "TO_CALL" to 8,
                "LEG" to 9,
                "LEN_M" to 12,
                "GAP_M" to 12,
                "TRACED" to 7,
                "VERTS" to 6,
            ),
            prj,
        )
    }

    /**
     * The whole plan as **one continuous line**, call after call in flying order.
     *
     * Each leg starts and ends on its calls, so joining them in order yields a single
     * polyline that visits every call in sequence. Where a leg could not be traced it
     * appears as a straight hop, which is visible as a straight run in the one line.
     */
    fun writeContinuous(dir: File, name: String, prj: String): File {
        val pts = ArrayList<DoubleArray>()
        for (seg in plan.segments) {
            for (i in 0 until seg.vertexCount) {
                val p = doubleArrayOf(seg.geometry[i * 2], seg.geometry[i * 2 + 1])
                // drop repeats so the vertex count stays sane
                val last = pts.lastOrNull()
                if (last == null || last[0] != p[0] || last[1] != p[1]) pts.add(p)
            }
        }
        if (pts.size < 2) pts.add(doubleArrayOf(plan.segments.last().to.e, plan.segments.last().to.n))
        return write(dir, name, listOf(pts), listOf(
            listOf(
                "${plan.segments.size}",
                "${plan.segments.count { it.traced }}",
                "${plan.segments.size - plan.segments.count { it.traced }}",
                pts.size.toString(),
            ),
        ), listOf(
            "LEGS" to 6, "TRACED" to 7, "STRAIGHT" to 9, "VERTS" to 7,
        ), prj)
    }

    fun writeCalls(dir: File, name: String, prj: String): File {
        val ordered = calls.sortedWith(AirCallOrder)
        // buildPointShp takes a flat list of points, one per feature
        val parts = ordered.map { doubleArrayOf(it.e, it.n) }
        val rows = ordered.map { c ->
            listOf(
                c.name,
                c.suffix.ifEmpty { "-" },
                c.order.toString(),
                fmt(c.e),
                fmt(c.n),
            )
        }
        return writePoints(
            dir, name, parts, rows,
            listOf(
                "CALL_ID" to 8,
                "ROLE" to 4,
                "NUM" to 6,
                "EASTING" to 14,
                "NORTHING" to 14,
            ),
            prj,
        )
    }

    /** What was written, for the report the app shows. */
    fun summary(): String {
        val traced = plan.segments.count { it.traced }
        return "${plan.segments.size} legs, $traced traced from the flight path and " +
            "${plan.segments.size - traced} drawn straight (${plan.transects.size} transects, " +
            "${plan.transits.size} transits), and ${calls.size} air calls"
    }

    private fun fmt(v: Double): String =
        if (!v.isFinite()) "" else String.format("%.1f", v)

    private fun writePoints(
        dir: File,
        name: String,
        points: List<DoubleArray>,
        rows: List<List<String>>,
        fields: List<Pair<String, Int>>,
        prj: String,
    ): File {
        require(points.size == rows.size) { "one row per point" }
        dir.mkdirs()
        writeFiles(
            dir, name,
            ShpFormat.buildPointShp(points),
            ShpFormat.buildPointShx(points),
            rows, fields, prj,
        )
        return File(dir, "$name.shp")
    }

    private fun write(
        dir: File,
        name: String,
        parts: List<List<DoubleArray>>,
        rows: List<List<String>>,
        fields: List<Pair<String, Int>>,
        prj: String,
    ): File {
        require(parts.size == rows.size) { "one row per feature" }
        dir.mkdirs()
        writeFiles(
            dir, name,
            ShpFormat.buildPolylineShp(parts),
            ShpFormat.buildPolylineShx(parts),
            rows, fields, prj,
        )
        return File(dir, "$name.shp")
    }

    private fun writeFiles(
        dir: File,
        name: String,
        shp: ByteArray,
        shx: ByteArray,
        rows: List<List<String>>,
        fields: List<Pair<String, Int>>,
        prj: String,
    ) {
        File(dir, "$name.shp").writeBytes(shp)
        File(dir, "$name.shx").writeBytes(shx)
        File(dir, "$name.dbf").writeBytes(ShpFormat.buildDbfFields(fields, rows))
        if (prj.isNotBlank()) File(dir, "$name.prj").writeText(prj)
        // A .qmd is what makes QGIS label a layer from a chosen field instead of showing
        // nothing useful, which matters when the interesting attribute is LEG or ON_PATH.
        File(dir, "$name.qmd").writeText(
            "<!DOCTYPE qgis PUBLIC 'http://mapserver.org/dtd/qgis-3.0.dtd' 'SYSTEM'>\n" +
                "<qgis version='3.0'>\n  <pipe>\n" +
                "    < rasterrenderer/>\n" +
                "    <labeling type=\"simple\">\n" +
                "      <settings>\n" +
                "        <text-style fieldName=\"${fields.first().first}\" size=\"8\"/>\n" +
                "      </settings>\n" +
                "    </labeling>\n" +
                "  </pipe>\n</qgis>\n",
        )
    }

    companion object {
        /** Reads the CRS text of the air-calls layer so the export carries the same tag. */
        fun prjTextOf(dir: File, shpName: String): String {
            val stem = shpName.substringBeforeLast('.')
            val f = File(dir, "$stem.prj")
            return if (f.exists()) f.readText() else ""
        }
    }
}