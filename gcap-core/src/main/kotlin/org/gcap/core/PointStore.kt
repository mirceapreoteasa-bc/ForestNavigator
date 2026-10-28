package org.gcap.core

/**
 * Reading and appending points in a shapefile: what MOB and Add point both write.
 *
 * Kept free of Android and of the SAF so the on-disk result can be checked in tests -
 * a shapefile that is subtly wrong still opens, it just quietly misbehaves in ArcGIS and
 * QGIS, which is exactly the kind of fault that is not worth finding on a phone in a
 * helicopter.
 */
object PointStore {

    /** Field width. Names are timestamp-like, so 12 leaves room and keeps rows aligned. */
    const val NAME_WIDTH = 12

    /** Sidecars a usable point layer needs. */
    val SIDECARS = listOf("shp", "shx", "dbf", "prj")

    /**
     * MOB's suggested name: day of month, then hour, then minute, all two digits.
     *
     * Sorted as text this is chronological within a month, and a whole day's MOBs sort
     * together, which is what makes the point layer readable afterwards.
     */
    fun defaultName(dayOfMonth: Int, hour: Int, minute: Int): String =
        "%02d%02d%02d".format(dayOfMonth, hour, minute)

    /** Values of [field] already present, in file order, for duplicate checks. */
    fun names(dbf: ByteArray, field: String): List<String> {
        val header = ShpFormat.dbfHeader(dbf) ?: return emptyList()
        val idx = header.fields.indexOfFirst { it.name.equals(field, true) }
        if (idx < 0) return emptyList()
        return ShpFormat.readDbfRows(dbf).mapNotNull { row ->
            row.values.getOrNull(idx)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /** Point coordinates already in [shp], in file order. */
    fun coords(shp: ByteArray): List<DoubleArray> {
        val info = ShpFormat.shpHeader(shp) ?: return emptyList()
        return ShpFormat.readShp(shp, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
            .mapNotNull { s -> s.parts.firstOrNull()?.firstOrNull() }
    }

    /**
     * A brand new empty point layer, ready to write.
     *
     * A .shp with zero features is not valid, so one placeholder point is written at the
     * origin and [append] always writes the real ones; [isPlaceholder] tells the two apart
     * so the origin never shows on the map.
     */
    fun newLayer(nameField: String, crsText: String?): Map<String, ByteArray> {
        val fields = listOf(nameField.uppercase().take(10) to NAME_WIDTH)
        return mapOf(
            "shp" to ShpFormat.buildPointShp(listOf(doubleArrayOf(0.0, 0.0))),
            "shx" to ShpFormat.buildPointShx(listOf(doubleArrayOf(0.0, 0.0))),
            "dbf" to ShpFormat.buildDbfFields(fields, listOf(listOf(""))),
            "prj" to (crsText ?: PRJ_WGS84).toByteArray(Charsets.UTF_8),
        )
    }

    /** True when the layer holds only the origin placeholder and no real feature. */
    fun isPlaceholder(coords: List<DoubleArray>, names: List<String>): Boolean =
        coords.size <= 1 && names.none { it.isNotBlank() }

    /**
     * [existing] sidecars plus one more point, named [name].
     *
     * Returns the full set to write back, so a caller never has to remember which of the
     * four files a new feature invalidates.
     */
    fun append(
        existing: Map<String, ByteArray>,
        nameField: String,
        name: String,
        e: Double,
        n: Double,
        crsText: String? = null,
    ): Map<String, ByteArray> {
        val shp = existing["shp"] ?: error("missing .shp")
        val dbf = existing["dbf"] ?: error("missing .dbf")

        var points = coords(shp)
        var fields = existingFields(dbf).toMutableList()
        if (fields.none { it.first.equals(nameField, true) }) {
            // The layer has no such field. Add it rather than writing into whatever field
            // happens to be first: a layer from another app may lead with OBJECTID, and
            // silently filling that in corrupts data that has nothing to do with this name.
            fields.add(nameField.uppercase().take(10) to NAME_WIDTH)
        }
        val field = fields.first { it.first.equals(nameField, true) }
        var rows: List<List<String>> = ShpFormat.readDbfRows(dbf).map { it.values.toList() }
        if (rows.any { it.size < fields.size }) {
            rows = rows.map { it + List(fields.size - it.size) { "" } }
        }

        if (isPlaceholder(points, rows.flatten())) {
            points = emptyList()
            rows = emptyList()
        }

        points = points + doubleArrayOf(e, n)
        // one cell per field, so the value lands under the right column name
        val row = MutableList(fields.size) { "" }
        row[fields.indexOfFirst { it.first.equals(nameField, true) }] = name.take(field.second)
        rows = rows + listOf(row)

        return mapOf(
            "shp" to ShpFormat.buildPointShp(points),
            "shx" to ShpFormat.buildPointShx(points),
            "dbf" to ShpFormat.buildDbfFields(fields, rows),
            "prj" to (existing["prj"] ?: (crsText ?: PRJ_WGS84).toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * Index of the record whose [field] equals [value], or -1.
     *
     * Compared on the trimmed text, because DBF pads to a fixed width and the user picks
     * the value from a list built out of those same padded strings.
     */
    fun indexOf(dbf: ByteArray, field: String, value: String): Int {
        val h = ShpFormat.dbfHeader(dbf) ?: return -1
        val idx = h.fields.indexOfFirst { it.name.equals(field, true) }
        if (idx < 0) return -1
        return ShpFormat.readDbfRows(dbf).indexOfFirst {
            !it.deleted && it.values.getOrNull(idx)?.trim() == value.trim()
        }
    }

    /** Coordinate of record [index] in [shp], or null when out of range. */
    fun coordAt(shp: ByteArray, index: Int): DoubleArray? =
        coords(shp).getOrNull(index)

    /** Field name and width pairs declared by [dbf]. */
    fun existingFields(dbf: ByteArray): List<Pair<String, Int>> {
        val h = ShpFormat.dbfHeader(dbf) ?: return emptyList()
        return h.fields.map { it.name to it.len }
    }

    const val PRJ_WGS84 =
        "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"
}