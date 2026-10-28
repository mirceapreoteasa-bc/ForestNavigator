package org.forestnav.app.map

import org.gcap.core.prep.LineCutTarget
import org.gcap.core.prep.LineHealing

/**
 * Cuts made this session, held until the user saves.
 *
 * A cut removes a stretch of road, so it is staged rather than written the moment the map
 * is tapped. That buys three things the immediate version did not have: nothing changes on
 * disk until one deliberate save; a mistap is undone by discarding rather than by
 * re-drawing the line; and several cuts across a folder land together, so the network is
 * invalidated once at the end instead of once per cut.
 *
 * Starting one of these is deliberately awkward - a layer, a gap and a confirmation, all
 * from Settings. Saving and stopping it use the ordinary main-page buttons, because those
 * are the ones a user already reaches for and they carry no risk of their own.
 */
class LineEditSession {

    /** One staged cut, in the layer's own coordinates. */
    data class Cut(
        val stem: String,
        val recordIndex: Int,
        val fraction: Double,
        val gapM: Double,
        val x: Double,
        val y: Double,
    )

    private val staged = ArrayList<Cut>()

    /** Layers this session will change, for the banner and the save message. */
    val layers: Set<String> get() = staged.map { it.stem }.toSet()

    val count: Int get() = staged.size
    val dirty: Boolean get() = staged.isNotEmpty()

    /** Record a cut, or return why it could not be recorded. */
    fun stage(
        stem: String,
        target: LineCutTarget.Target,
        gapM: Double,
    ): String? {
        if (staged.any {
                it.stem.equals(stem, true) && it.recordIndex == target.recordIndex &&
                    kotlin.math.abs(it.fraction - target.fraction) < 0.005
            }
        ) {
            // Two taps on the same spot would otherwise cut twice and leave a double gap
            // the user never asked for.
            return "A cut is already staged at that spot"
        }
        staged.add(Cut(stem, target.recordIndex, target.fraction, gapM, target.x, target.y))
        return null
    }

    fun discard() = staged.clear()

    /**
     * Apply every staged cut and write each layer once.
     *
     * Cuts are applied back to front within a layer, so that cutting twice on one line uses
     * positions measured against the geometry as it was when the user tapped, not against
     * the piece the previous cut left behind.
     */
    fun commit(dir: MapFileDir, onLayerWritten: (stem: String) -> Unit = {}): Result {
        val messages = ArrayList<String>()
        var failures = 0

        for (stem in staged.map { it.stem }.distinct()) {
            val mine = staged.filter { it.stem.equals(stem, true) }
            val result = runCatching { applyTo(dir, stem, mine) }
            if (result.isFailure) {
                failures++
                messages.add("$stem not cut: ${result.exceptionOrNull()?.message}")
                continue
            }
            onLayerWritten(stem)
            messages.add(
                "$stem: ${mine.size} cut(s) of ${"%.0f".format(mine.first().gapM)} m. " +
                    "Its saved network is now out of date: build the network again to route on it.",
            )
        }
        val applied = staged.size
        staged.clear()
        return Result(
            written = applied - failures,
            attempted = applied,
            failed = failures,
            messages = messages,
        )
    }

    private fun applyTo(dir: MapFileDir, stem: String, mine: List<Cut>) {
        val files = dir.listFiles()
        fun find(ext: String) = files.firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(ext, true)
        }
        val shpName = find(".shp") ?: error("$stem.shp not found")
        val dbf = find(".dbf")?.let { dir.readFile(it) } ?: ByteArray(0)
        val prj = find(".prj")?.let { dir.readFile(it)?.toString(Charsets.UTF_8) }.orEmpty()

        val shapes = org.gcap.core.ShpFormat.readShp(
            dir.readFile(shpName) ?: error("$shpName unreadable"), -1e18, -1e18, 1e18, 1e18,
        )
        if (shapes.isEmpty()) error("$stem.shp holds no features")
        var lines = shapes.map { LineHealing.Line(it.parts, it.recordIndex) }

        // Back to front within each feature: an earlier cut shortens the line, and a later
        // fraction measured against the original would then land somewhere else entirely.
        for (cut in mine.sortedWith(compareByDescending<Cut> { it.recordIndex }.thenByDescending { it.fraction })) {
            lines = LineHealing.cutAtFractions(lines, cut.recordIndex, listOf(cut.fraction), cut.gapM)
            // after one cut the feature count shifts by one for everything after it
        }

        val parts = lines.map { it.parts.flatMap { p -> p } }
        val writeStem = find(".shp")!!.substringBeforeLast('.')
        val written = org.gcap.core.prep.LayerPreparation.writeHealedPolylineLayer(
            parts, dbf, prj, writeStem,
        )
        var ok = true
        for ((name, bytes) in written) {
            val done = if (dir.listFiles().any { it.equals(name, true) }) {
                dir.overwriteFile(name, bytes)
            } else {
                dir.createFile(name, bytes)
            }
            if (!done) ok = false
        }
        if (!ok) error("$stem could not be fully written")

        // Saved networks are deliberately left alone. A network built before the cut still
        // routes across the gap until the user builds it again; that rebuild is the user's
        // step, not the app's, so the save message says so.
        dir.clearCaches()
    }

    data class Result(
        val written: Int,
        val attempted: Int,
        val failed: Int,
        val messages: List<String>,
    )
}