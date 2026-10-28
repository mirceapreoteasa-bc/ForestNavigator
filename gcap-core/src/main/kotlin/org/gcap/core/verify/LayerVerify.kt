package org.gcap.core.verify

import org.gcap.core.ShpFormat

/** Minimal read access needed to verify a project folder. */
interface LayerSource {
    fun listFiles(): List<String>
    fun readHeader(name: String, maxBytes: Int): ByteArray?
    fun readFile(name: String): ByteArray?

    companion object {
        /** In-memory source, for tests and for callers that already hold the bytes. */
        fun of(files: Map<String, ByteArray>): LayerSource = object : LayerSource {
            override fun listFiles() = files.keys.toList()
            override fun readHeader(name: String, maxBytes: Int): ByteArray? {
                val b = files[name] ?: return null
                return b.copyOf(maxBytes.coerceAtMost(b.size))
            }
            override fun readFile(name: String) = files[name]
        }
    }
}

/** How a layer's .prj was understood. Supplied by the app, which owns the CRS code. */
data class CrsVerdict(
    val label: String,
    val utmZone: Int = 0,
    val recognised: Boolean = true,
    val geographic: Boolean = false,
)

fun interface CrsClassifier {
    fun classify(prjText: String?): CrsVerdict
}

/** Severity of a layer's problems, mirroring the Forest Point Collector report. */
enum class LayerStatus { OK, WARN, SKIP }

/** What verification found about one layer. */
data class LayerCheck(
    val file: String,
    val stem: String,
    val kind: String,
    val crsNote: String,
    val zone: Int,
    val status: LayerStatus,
    val problems: List<String>,
    val featureCount: Int,
    val bbox: DoubleArray?,
) {
    /** `"access.shp [line, UTM 10N] WARN - no .shx"` */
    fun line(): String {
        val tail = if (problems.isEmpty()) "" else " - ${problems.joinToString("; ")}"
        return "$file [$kind, $crsNote] $status$tail"
    }
}

/** Whole-project verdict. */
data class VerifyReport(
    val layers: List<LayerCheck>,
    val lines: List<String>,
    val summary: String,
    val extentNote: String,
    val crsNote: String,
) {
    val problemCount: Int get() = layers.sumOf { it.problems.size }
}

/**
 * Read-only health check over a project folder.
 *
 * Answers the questions that decide whether the app can show a layer at all: are the
 * sidecars present, is the header readable, is the geometry a type we can draw, is the
 * CRS one we recognise, and - because a project assembled from several sources so
 * easily mixes zones - do the layers actually cover the same ground.
 *
 * Nothing is fixed or rewritten. A verification pass that could alter data would be
 * dangerous to run on a folder you cannot easily restore.
 */
class LayerVerifier(
    private val source: LayerSource,
    private val crsClassifier: CrsClassifier,
    private val projectRows: Set<String> = emptySet(),
) {

    fun verify(): VerifyReport {
        val files = try {
            source.listFiles().filter { it.endsWith(".shp", true) }.sorted()
        } catch (e: Exception) {
            return VerifyReport(emptyList(), emptyList(), "Cannot list the layers folder: ${e.message}", "", "")
        }
        if (files.isEmpty()) {
            return VerifyReport(emptyList(), emptyList(), "No .shp files in the layers folder.", "", "")
        }

        val names = try { source.listFiles() } catch (e: Exception) { emptyList() }

        fun has(stem: String, ext: String): Boolean = names.any {
            stemOf(it).equals(stem, true) && it.endsWith(".$ext", true)
        }
        fun sidecar(stem: String, vararg exts: String): String? =
            names.firstOrNull {
                stemOf(it).equals(stem, true) && exts.any { e -> it.endsWith(".$e", true) }
            }

        val checks = ArrayList<LayerCheck>(files.size)
        for (f in files) {
            val stem = stemOf(f)
            val problems = ArrayList<String>()

            if (!has(stem, "shx")) problems += "no .shx"
            if (!has(stem, "dbf")) problems += "no .dbf (no labels or attributes)"

            var kind = "?"
            var featureCount = 0
            var bbox: DoubleArray? = null
            val header = try { source.readHeader(f, HEADER_BYTES) } catch (e: Exception) { null }
            if (header == null || header.size < HEADER_BYTES) {
                problems += "unreadable header"
            } else {
                val info = try { ShpFormat.shpHeader(header) } catch (e: Exception) { null }
                if (info == null) {
                    problems += "unreadable header"
                } else {
                    kind = when (info.shapeType) {
                        1, 11, 21 -> "point"
                        3, 13, 23 -> "line"
                        5, 15, 25 -> "polygon"
                        8, 18, 28 -> "multipoint"
                        else -> "type ${info.shapeType}?"
                    }
                    featureCount = info.recordCount
                    if (info.xmin.isFinite() && info.xmax.isFinite() && info.xmin <= info.xmax) {
                        bbox = doubleArrayOf(info.xmin, info.ymin, info.xmax, info.ymax)
                    }
                    // A 100-byte header cannot report a record count reliably, so the
                    // honest emptiness test is the file length: a .shp with no records
                    // is exactly the 100-byte header and nothing else.
                    if (fileLengthBytes(header) <= HEADER_BYTES) problems += "no features"
                }
            }

            val prjName = sidecar(stem, "prj")
            var crsNote: String
            var zone = 0
            if (prjName == null) {
                crsNote = "no .prj (assumed UTM 10N)"
                problems += "no .prj"
            } else {
                val verdict = try {
                    crsClassifier.classify(source.readFile(prjName)?.let { String(it, Charsets.UTF_8) })
                } catch (e: Exception) { null }
                if (verdict == null) {
                    crsNote = "unreadable .prj"
                    problems += "unreadable .prj"
                } else {
                    zone = verdict.utmZone
                    crsNote = if (verdict.geographic) {
                        verdict.label
                    } else {
                        "${verdict.label} ${verdict.utmZone}N".trim()
                    }
                    if (!verdict.recognised) problems += "unrecognised CRS - will be skipped"
                }
            }
            if (stem in projectRows) crsNote += ", in project"

            val status = when {
                problems.any { it.startsWith("unreadable") || it.contains("unrecognised") } -> LayerStatus.SKIP
                problems.isNotEmpty() -> LayerStatus.WARN
                else -> LayerStatus.OK
            }
            checks += LayerCheck(f, stem, kind, crsNote, zone, status, problems, featureCount, bbox)
        }

        return VerifyReport(
            layers = checks,
            lines = checks.map { it.line() },
            summary = summarise(checks),
            extentNote = extentNote(checks),
            crsNote = crsNote(checks),
        )
    }

    private fun summarise(checks: List<LayerCheck>): String {
        val ok = checks.count { it.status == LayerStatus.OK }
        val warn = checks.count { it.status == LayerStatus.WARN }
        val skip = checks.count { it.status == LayerStatus.SKIP }
        return "${checks.size} layers: $ok OK, $warn warnings, $skip skipped."
    }

    /** .shp file length in bytes, from the header's big-endian 16-bit word count. */
    private fun fileLengthBytes(header: ByteArray): Int {
        if (header.size < 28) return 0
        val words = ((header[24].toInt() and 0xFF) shl 24) or
            ((header[25].toInt() and 0xFF) shl 16) or
            ((header[26].toInt() and 0xFF) shl 8) or
            (header[27].toInt() and 0xFF)
        return words * 2
    }

    /**
     * Are the layers over roughly the same ground?
     *
     * Geographic layers carry degrees while projected layers carry metres, so unioning
     * them together produces a nonsense extent thousands of kilometres tall. Extents
     * are only comparable *within* one coordinate system, so the dominant group is
     * used and any others are reported but not compared.
     */
    private fun extentNote(checks: List<LayerCheck>): String {
        val usable = checks.filter { it.bbox != null && it.status != LayerStatus.SKIP }
        if (usable.isEmpty()) return "No layer extents available."

        val groups = usable.groupBy { crsKey(it) }
        val entry = groups.maxByOrNull { it.value.size } ?: return "No layer extents available."
        val key = entry.key
        val members = entry.value

        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (c in members) {
            val b = c.bbox!!
            x0 = minOf(x0, b[0]); y0 = minOf(y0, b[1])
            x1 = maxOf(x1, b[2]); y1 = maxOf(y1, b[3])
        }
        val area = ((x1 - x0) * (y1 - y0)).coerceAtLeast(1.0)
        val geographic = crsIsGeographic(members.first())
        val div = if (geographic) 1.0 else 1000.0
        val unit = if (geographic) "deg" else "m"
        val notes = ArrayList<String>()
        notes.add(
            String.format(
                "%d layers in %s cover %.1f x %.1f km (%.0f,%.0f to %.0f,%.0f %s).",
                members.size, key, (x1 - x0) / div, (y1 - y0) / div, x0, y0, x1, y1, unit,
            ),
        )

        val odd = ArrayList<String>()
        for (c in members) {
            val b = c.bbox!!
            val share = ((b[2] - b[0]) * (b[3] - b[1])) / area
            if (share < 0.01) {
                odd += "${c.stem} ${String.format("%.2f", share * 100)}% of area"
            }
        }
        if (odd.isEmpty()) {
            notes.add("All of them cover broadly the same ground.")
        } else {
            notes.add("${odd.size} outlier(s): ${odd.joinToString("; ")}")
        }
        groups.filterKeys { it != key }.forEach { (k, v) ->
            notes.add("${v.size} further layer(s) in $k, extent not compared.")
        }
        return notes.joinToString(" ")
    }

    /** Grouping key: the full CRS note, so zone 10 and zone 11 stay apart. */
    private fun crsKey(c: LayerCheck): String =
        if (c.crsNote.startsWith("no .prj")) "assumed UTM" else c.crsNote.trim()

    private fun crsIsGeographic(c: LayerCheck): Boolean = c.crsNote.trim().startsWith("WGS84")

    /** Which coordinate systems are in play - mixed zones silently shift layers apart. */
    private fun crsNote(checks: List<LayerCheck>): String {
        val groups = checks.filter { it.status != LayerStatus.SKIP }
            .groupBy { crsKey(it) }
            .filterValues { it.isNotEmpty() }
        if (groups.isEmpty()) return "No coordinate systems in use."
        if (groups.size == 1) return "All layers share one coordinate system (${groups.keys.first()})."
        return groups.entries.joinToString("; ") { "${it.value.size} layer(s) ${it.key}" }
    }

    companion object {
        /** A .shp with no records is exactly the 100-byte header and nothing else. */
        const val HEADER_BYTES = 100

        /** Filename without its extension. */
        fun stemOf(name: String): String = name.substringBeforeLast('.')
    }
}
