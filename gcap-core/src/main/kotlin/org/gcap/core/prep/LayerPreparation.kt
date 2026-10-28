package org.gcap.core.prep

import org.gcap.core.ShpFormat

/**
 * Preparing a project folder so the map starts fast and stays responsive.
 *
 * Everything here is opt-in and reversible: the app only ever *proposes*, and anything
 * it writes goes to a new file name with the original left untouched. A survey folder is
 * days of work, and a phone is the worst possible place to discover a layer was rewritten.
 *
 * The audit deliberately reports cost that is measured, or measured from file size,
 * rather than opinion - because the whole value here is that the advice and the app agree.
 */
object LayerPreparation {

    /** What the audit knows about one layer. */
    data class LayerFacts(
        val stem: String,
        /** Size on disk; the basis of the memory estimate. */
        val fileBytes: Long,
        val crsLabel: String,
        val crsZone: Int = 0,
        val geographic: Boolean = false,
        /**
         * Kept in degrees on purpose for point-collector interchange.
         *
         * Never proposed for reprojection: rewriting these would break the handoff
         * between the two apps that the mixed-CRS folder exists to support.
         */
        val deliberateDegrees: Boolean = false,
        val routable: Boolean = false,
    )

    /** One thing worth doing about a layer, or nothing. */
    data class Advice(
        val stem: String,
        val issue: Issue,
        val detail: String,
        /** What the app offers to write, or null when there is nothing safe to offer. */
        val action: Action?,
        /** The new file name the action would create. */
        val outputStem: String? = null,
    )

    enum class Issue {
        FINE,
        /** In a different UTM zone to the rest of the project. */
        OFF_ZONE,
        /** So large it will cost memory and time on the phone. */
        HEAVY,
        /** No .prj, so its coordinates cannot be interpreted. */
        NO_CRS,
        /** Kept in degrees deliberately; must not be changed. */
        DELIBERATE_DEGREES,
    }

    enum class Action { REPROJECT, SIMPLIFY }

    /**
     * The heap a parsed layer costs, estimated from its size on disk.
     *
     * Measured on the real FOR_NAV folder: parsed geometry runs 1.2x to 2.7x the file
     * size, so 2.7x is the figure used for a warning. It is an estimate and is labelled
     * as one; the point is to catch the one heavy layer, not to be precise about it.
     */
    fun estimatedHeapMiB(fileBytes: Long): Double =
        fileBytes * HEAP_TO_FILE_RATIO / (1024.0 * 1024.0)

    /** Above this, a layer is worth simplifying for the phone. */
    const val HEAVY_HEAP_MIB = 8.0

    /** Above this, say so before writing rather than after. */
    const val WARN_HEAP_MIB = 24.0

    /** Tolerance used when simplifying for display. */
    const val SIMPLIFY_TOLERANCE_M = 10.0

    private const val HEAP_TO_FILE_RATIO = 2.7

    /** What to do about each layer, in the order given. */
    fun audit(
        layers: List<LayerFacts>,
        projectZone: Int,
        projectGeographic: Boolean = false,
    ): List<Advice> {
        // Only one layer can be the heaviest, so only that one is told it is.
        val heaviest = layers.maxByOrNull { estimatedHeapMiB(it.fileBytes) }?.stem
        return layers.map { f ->
        val heap = estimatedHeapMiB(f.fileBytes)
        val heapText = "%.0f MiB".format(heap)
        when {
            f.deliberateDegrees -> Advice(
                f.stem, Issue.DELIBERATE_DEGREES,
                "kept in ${f.crsLabel} on purpose for point-collector interchange; used as it is",
                null,
            )

            f.crsLabel.equals("no .prj", true) -> Advice(
                f.stem, Issue.NO_CRS,
                "has no .prj, so its coordinates cannot be read",
                null,
            )

            !f.geographic && f.crsZone in 1..60 && projectZone in 1..60 && f.crsZone != projectZone ->
                Advice(
                    f.stem, Issue.OFF_ZONE,
                    "is in UTM zone ${f.crsZone} while the project is zone $projectZone",
                    Action.REPROJECT, outStem(f.stem, "utm$projectZone"),
                )

            !f.geographic && projectGeographic -> Advice(
                f.stem, Issue.OFF_ZONE,
                "is projected (${f.crsLabel}) while the project is in degrees",
                Action.REPROJECT, outStem(f.stem, "wgs84"),
            )

            heap >= HEAVY_HEAP_MIB -> Advice(
                f.stem, Issue.HEAVY,
                buildString {
                    append("costs about $heapText in memory")
                    if (f.stem == heaviest) append(", the largest in this folder")
                    append("; a simplified copy drops vertices, though how many ")
                    append("depends on the geometry")
                },
                Action.SIMPLIFY, outStem(f.stem, "lite"),
            )

            else -> Advice(f.stem, Issue.FINE, "about $heapText in memory; fine as it is", null)
        }
        }
    }

    /**
     * The new name for a derived layer.
     *
     * A suffix rather than a replacement, so the original is still there to go back to.
     */
    fun outStem(stem: String, tag: String): String = "${stem}_$tag"

    /**
     * Douglas-Peucker simplification of one part.
     *
     * Applied to display geometry only. At 1:10000, 10 m is a fraction of a millimetre
     * on screen, so a line that keeps every vertex within 10 m of the original is
     * indistinguishable - and costs a fraction of the vertices to draw.
     */
    fun simplifyPart(points: List<DoubleArray>, toleranceM: Double): List<DoubleArray> {
        if (points.size < 3 || toleranceM <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            val ax = points[a][0]; val ay = points[a][1]
            val bx = points[b][0]; val by = points[b][1]
            val dx = bx - ax; val dy = by - ay
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            var maxD = 0.0
            var maxI = -1
            for (i in a + 1 until b) {
                val px = points[i][0]; val py = points[i][1]
                val d = if (len < 1e-9) {
                    kotlin.math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
                } else {
                    kotlin.math.abs(dy * px - dx * py + bx * ay - by * ax) / len
                }
                if (d > maxD) { maxD = d; maxI = i }
            }
            if (maxD > toleranceM && maxI > 0) {
                keep[maxI] = true
                stack.addLast(intArrayOf(a, maxI))
                stack.addLast(intArrayOf(maxI, b))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Assemble a point layer, keeping the original attributes untouched.
     *
     * The .dbf is copied verbatim: neither reprojecting nor simplifying changes an
     * attribute, and rebuilding the table would risk dropping fields the app does not
     * know about but the desktop tool does.
     */
    fun writePointLayer(
        coords: List<DoubleArray>,
        originalDbf: ByteArray,
        prjText: String,
        stem: String,
    ): Map<String, ByteArray> = mapOf(
        "$stem.shp" to ShpFormat.buildPointShp(coords),
        "$stem.shx" to ShpFormat.buildPointShx(coords),
        "$stem.dbf" to originalDbf,
        "$stem.prj" to prjText.toByteArray(Charsets.US_ASCII),
    )

    /** Assemble a polyline layer, keeping the original attributes untouched. */
    fun writePolylineLayer(
        parts: List<List<DoubleArray>>,
        originalDbf: ByteArray,
        prjText: String,
        stem: String,
    ): Map<String, ByteArray> = mapOf(
        "$stem.shp" to ShpFormat.buildPolylineShp(parts),
        "$stem.shx" to ShpFormat.buildPolylineShx(parts),
        "$stem.dbf" to originalDbf,
        "$stem.prj" to prjText.toByteArray(Charsets.US_ASCII),
    )

    /**
     * Rebuild [originalDbf] so it carries exactly [rowCount] rows, keeping the field names,
     * widths and every existing row in order.
     *
     * Needed whenever a line operation changes the feature count - a cut adds one. Passing
     * the source .dbf through unchanged would then leave the table and the geometry
     * disagreeing, which some readers tolerate and QGIS reports as a broken layer.
     */
    fun resizeDbf(originalDbf: ByteArray, rowCount: Int): ByteArray {
        val header = ShpFormat.dbfHeader(originalDbf)
        // an unreadable or empty table has no schema worth preserving, so build the
        // simplest one that will hold the rows
            ?: return ShpFormat.buildDbfFields(listOf("FID" to 10), List(rowCount) { listOf("") })
        if (header.numRecords == rowCount) return originalDbf
        val fields = header.fields.map { it.name to it.len }
        val existing = ShpFormat.readDbfRows(originalDbf)
        val blank = List(fields.size) { "" }
        val rows = (0 until rowCount).map { i -> existing.getOrNull(i)?.values ?: blank }
        return ShpFormat.buildDbfFields(fields, rows)
    }

    /**
     * Write a polyline layer whose feature count may differ from the source, with the
     * attribute table resized to match.
     */
    fun writeHealedPolylineLayer(
        parts: List<List<DoubleArray>>,
        originalDbf: ByteArray,
        prjText: String,
        stem: String,
    ): Map<String, ByteArray> = mapOf(
        "$stem.shp" to ShpFormat.buildPolylineShp(parts),
        "$stem.shx" to ShpFormat.buildPolylineShx(parts),
        "$stem.dbf" to resizeDbf(originalDbf, parts.size),
        "$stem.prj" to prjText.toByteArray(Charsets.US_ASCII),
    )
}
