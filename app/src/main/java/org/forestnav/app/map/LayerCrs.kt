package org.forestnav.app.map

/**
 * What coordinate system a layer's own `.prj` declares.
 *
 * The survey folder is genuinely mixed: `points.shp` from the point collector app is in
 * WGS84 degrees while `ground_call.shp` and `xgv_mp.shp` are UTM metres. A new point
 * therefore has to be written in whichever system the *target layer* already uses, or it
 * lands tens of degrees away from everything else while still opening cleanly.
 */
object LayerCrs {

    /**
     * True when [prj] describes geographic coordinates.
     *
     * Tested for the absence of a `PROJECTION[` element rather than the presence of
     * "Degree": projected WKT embeds a `GEOGCS` block carrying `UNIT["Degree", ...]`, so
     * a substring test mistakes a good UTM projection for a geographic one.
     */
    fun isGeographic(prj: String?): Boolean {
        val t = prj?.trim().orEmpty()
        if (t.isEmpty()) return false
        return t.contains("GEOGCS", true) && !t.contains("PROJECTION[", true)
    }

    /**
     * [e], [n] in working-zone metres, expressed the way [prj] stores them.
     *
     * A geographic layer gets (lon, lat) in degrees, which is the axis order shapefiles
     * use for X and Y. A projected layer, or one with no `.prj` at all, is passed through
     * unchanged so a missing declaration never silently moves data.
     */
    fun toLayerCoords(e: Double, n: Double, zone: Int, prj: String?): DoubleArray =
        if (!isGeographic(prj)) doubleArrayOf(e, n)
        else {
            val (lat, lon) = Utm.utmToLatLon(e, n, zone)
            doubleArrayOf(lon, lat)
        }

    /** The `.prj` text of [layer] in [dir], or null when it has none. */
    fun prjOf(dir: MapFileDir, layer: String): String? {
        val stem = layer.substringBeforeLast('.')
        val hit = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".prj", true)
        } ?: return null
        return dir.readFile(hit)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** True when [layer] stores geographic coordinates. */
    fun isGeographic(dir: MapFileDir, layer: String): Boolean = isGeographic(prjOf(dir, layer))
}