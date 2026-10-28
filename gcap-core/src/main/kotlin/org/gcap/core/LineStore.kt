package org.gcap.core

import java.util.Calendar

/**
 * Appending recorded tracks to the track line layer.
 *
 * Two things this does that a plain append would not:
 *
 *  - It writes the coordinate text the project actually uses. The supplied `tracks.shp`
 *    declared WGS84 degrees while every layer around it is UTM metres, so a track saved
 *    into it without this landed tens of degrees away - and still opened without warning.
 *  - It adds `NAME` and `DATE` when they are missing. The template arrived carrying only
 *    `OBJECTID` and `SHAPE_Leng`, which cannot tell one recorded track from another.
 *
 * Existing records and fields are always carried through, so the upgrade is safe on a
 * layer that already holds data.
 */
object LineStore {

    /** Sidecars a track line layer needs, including the encoding marker. */
    val SIDECARS = listOf("shp", "shx", "dbf", "prj", "cpg")

    const val NAME_FIELD = "NAME"
    const val DATE_FIELD = "DATE"
    private const val NAME_WIDTH = 24
    private const val DATE_WIDTH = 10

    /** Track name from the moment it stopped: `2026-10-07 1432`. */
    fun defaultName(cal: Calendar): String =
        "%04d-%02d-%02d %02d%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
        )

    fun defaultDate(cal: Calendar): String =
        "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

    /**
     * True when [text] describes geographic coordinates rather than projected ones.
     *
     * Tested for the absence of a PROJECTION element rather than the presence of
     * "Degree": every projected WKT embeds a GEOGCS block with UNIT["Degree", ...], so a
     * substring test calls a perfectly good UTM projection geographic. That mistake made
     * a mismatched layer look like it agreed with the project, and nothing was corrected.
     */
    internal fun isDegrees(text: String): Boolean =
        text.contains("GEOGCS", true) && !text.contains("PROJECTION[", true)

    /**
     * True when the layer declares a coordinate system that differs from the project's.
     *
     * Reported for information only. Nothing is rewritten on the phone: tracks.shp and
     * points.shp are deliberately kept in degrees, and reprojecting is a QGIS job on a
     * desktop where a mistake is easy to see.
     */
    fun crsIsWrong(existing: Map<String, ByteArray>?, projectCrs: String?): Boolean {
        val want = projectCrs?.trim().orEmpty()
        if (want.isEmpty()) return false
        val have = existing?.get("prj")?.let { String(it).trim() }.orEmpty()
        if (have.isEmpty()) return false
        return isDegrees(have) != isDegrees(want)
    }

    /**
     * [existing] plus one more track line, named [name] and dated [date].
     *
     * [existing] may be empty, in which case the layer is created. Existing records and
     * fields are always preserved; `NAME` and `DATE` are added only when missing.
     *
     * The .prj is never rewritten. Coordinates are expected to already be in whatever
     * system the layer declares, so a degrees layer keeps degrees.
     */
    fun append(
        existing: Map<String, ByteArray>,
        coords: List<DoubleArray>,
        name: String,
        date: String,
        projectCrs: String? = null,
    ): Map<String, ByteArray> {
        val clean = coords.filter { it.size >= 2 && !it[0].isNaN() && !it[1].isNaN() }
        require(clean.size >= 2) { "a track needs at least two usable fixes" }

        val parts = existingParts(existing) + listOf(clean)

        val dbf = existing["dbf"]
        val fields = dbf?.let { PointStore.existingFields(it) }?.toMutableList() ?: mutableListOf()
        val rows = (dbf?.let { ShpFormat.readDbfRows(it).map { r -> r.values.toMutableList() } }
            ?: mutableListOf()).toMutableList()

        fun ensure(field: String, width: Int) {
            if (fields.none { it.first.equals(field, true) }) {
                fields.add(field to width)
                rows.forEach { it.add("") }
            }
        }
        ensure(NAME_FIELD, NAME_WIDTH)
        ensure(DATE_FIELD, DATE_WIDTH)

        val newRow = MutableList(fields.size) { "" }
        newRow[fields.indexOfFirst { it.first.equals(NAME_FIELD, true) }] = name.take(NAME_WIDTH)
        newRow[fields.indexOfFirst { it.first.equals(DATE_FIELD, true) }] = date.take(DATE_WIDTH)
        rows.add(newRow)

        val have = existing["prj"]?.let { String(it).trim() }.orEmpty()
        val declared = have.ifEmpty {
            (projectCrs?.trim() ?: "").ifEmpty { PointStore.PRJ_WGS84 }
        }

        return mapOf(
            "shp" to ShpFormat.buildPolylineShp(parts),
            "shx" to ShpFormat.buildPolylineShx(parts),
            "dbf" to ShpFormat.buildDbfFields(fields.toList(), rows.map { it.toList() }),
            "prj" to declared.toByteArray(Charsets.UTF_8),
            "cpg" to "UTF-8".toByteArray(Charsets.UTF_8),
        )
    }

    /** Polylines already in the layer, in file order. */
    fun existingParts(existing: Map<String, ByteArray>?): List<List<DoubleArray>> {
        val shp = existing?.get("shp") ?: return emptyList()
        if (shp.size < 100) return emptyList()
        return ShpFormat.readShp(shp, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
            .mapNotNull { s -> s.parts.firstOrNull() }
            .filter { it.isNotEmpty() }
    }

    /** Names already recorded, for duplicate checks. */
    fun names(existing: Map<String, ByteArray>?): List<String> {
        val dbf = existing?.get("dbf") ?: return emptyList()
        return PointStore.names(dbf, NAME_FIELD)
    }

    /** Number of track lines already stored. */
    fun count(existing: Map<String, ByteArray>?): Int = existingParts(existing).size
}