package org.forestnav.app.map

import org.gcap.core.PointStore

/**
 * Points added while editing, held in memory until they are saved.
 *
 * Nothing touches disk while editing. That matters on two counts: a shapefile rewrite per
 * point would be unusably slow over SAF, and an edit that is never saved should leave no
 * trace at all.
 *
 * The whole batch is committed in a single pass over the sidecars, so saving five points
 * costs one rewrite, not five.
 */
class EditSession(
    val layer: String,
    val field: String,
    existing: List<String>,
) {
    /** Names already in the file, plus anything added this session. */
    private val taken = existing.toMutableList()
    private val pending = ArrayList<DoubleArray>()

    /** True when there is something to save. */
    val dirty: Boolean get() = pending.isNotEmpty()

    /** Points added so far. */
    val count: Int get() = pending.size

    /** Why the last [add] was refused, or null when it was accepted. */
    var lastError: String? = null
        private set

    /**
     * Stage a point named [name].
     *
     * Refuses a blank name and a name already in the file or already staged, because
     * duplicates are indistinguishable on the map afterwards and cannot be told apart in
     * the DBF without opening it.
     */
    fun add(e: Double, n: Double, name: String): Boolean {
        val t = name.trim()
        if (t.isEmpty()) {
            lastError = "A point needs a name"
            return false
        }
        if (taken.any { it.equals(t, true) }) {
            lastError = "\"$t\" is already used in $layer.shp"
            return false
        }
        taken.add(t)
        pending.add(doubleArrayOf(e, n))
        lastError = null
        return true
    }

    /** Points staged so far, with their names. */
    fun stagedNames(): List<String> = pending.mapIndexed { i, _ ->
        taken[taken.size - pending.size + i]
    }

    /** Throw away everything not yet saved. */
    fun discard() {
        pending.clear()
        lastError = null
    }

    /**
     * Write every staged point into the layer, one rewrite of the sidecars.
     *
     * [dir] is the open project folder. [crs] is used only when the layer has to be
     * created; an existing layer keeps whatever it already declares.
     */
    fun commit(
        dir: MapFileDir,
        crs: String? = null,
        /** Working zone, needed when the target layer stores degrees. */
        zone: Int = Utm.ASSUMED_ZONE,
    ): Commit {
        if (!dirty) return Commit.Nothing
        val wasCreated = mobShpMissing(dir, stemOf(layer))
        val stem = layer.substringBeforeLast('.')
        val sidecars: Map<String, ByteArray>
        try {
            var acc = readSidecars(dir, stem)
            // Read the layer's own declaration *before* newLayer() writes a default .prj,
            // or a brand-new layer would be mistaken for a degrees one and have its
            // points converted when it was created in project metres.
            val declared = acc["prj"]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
            // An existing layer with no readable .prj has unknown units. Falling back to
            // the project CRS here would write metres into a degrees layer, and the file
            // would still open cleanly - so refuse instead of guessing.
            if (acc["shp"] != null && declared.isNullOrBlank()) {
                return Commit.Failed(
                    layer,
                    "$layer.shp has no readable .prj, so its coordinate system is unknown. " +
                        "Restore the .prj before adding points.",
                )
            }
            val layerPrj = declared ?: crs
            // An edit-created layer takes the project's CRS and the coordinates are left
            // in the working zone to match. Only the MOB layer is created in degrees,
            // because only it is exchanged with the point collector app.
            if (acc["shp"] == null) acc = PointStore.newLayer(field, crs)
            // Staged points are in the working zone. The layer may be geographic, so they
            // are converted into whatever *it* declares before being written. Writing
            // metres into a degrees layer puts every point ~100,000x too far away, and
            // the file still opens without complaint.
            for (i in pending.indices) {
                val c = pending[i]
                val (x, y) = LayerCrs.toLayerCoords(c[0], c[1], zone, layerPrj)
                acc = PointStore.append(acc, field, taken[taken.size - pending.size + i], x, y, crs)
            }
            sidecars = acc
        } catch (e: Exception) {
            // Reading can fail on a file that is not really a shapefile, or on a full disk.
            // Report it rather than throwing, and leave the points staged for another try.
            return Commit.Failed(layer, "${e.javaClass.simpleName}: ${e.message}")
        }

        // Same rule as MobWriter: read case-insensitively, but write back under the name
        // the layer already has on disk. Otherwise `xgv_mp` beside `XGV_MP.shp` produces
        // two layers and the map shows only one of them.
        val onDisk = acc0Stem(dir, stem)
        val writeStem = onDisk ?: stem
        var ok = true
        for ((ext, bytes) in sidecars) {
            val name = "$writeStem.$ext"
            val done = if (dir.listFiles().any { it.equals(name, true) }) {
                dir.overwriteFile(name, bytes)
            } else {
                dir.createFile(name, bytes)
            }
            if (!done) ok = false
        }
        if (!ok) {
            // keep the session dirty so the user can try again rather than lose the edits
            return Commit.Failed(layer, "$writeStem could not be fully written")
        }
        dir.clearCaches()
        val n = pending.size
        discard()
        return Commit.Written(layer, n, wasCreated)
    }

    sealed interface Commit {
        data object Nothing : Commit
        data class Written(val layer: String, val points: Int, val created: Boolean) : Commit
        data class Failed(val layer: String, val reason: String) : Commit
    }

    private fun stemOf(layer: String) = layer.substringBeforeLast('.')

    /** The stem of the existing .shp, matched without regard to case, or null. */
    private fun acc0Stem(dir: MapFileDir, stem: String): String? =
        dir.listFiles().firstOrNull {
            it.endsWith(".shp", true) && it.substringBeforeLast('.').equals(stem, true)
        }?.substringBeforeLast('.')

    private fun mobShpMissing(dir: MapFileDir, stem: String): Boolean = dir.listFiles().none {
        it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".shp", true)
    }

    private fun readSidecars(dir: MapFileDir, stem: String): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        for (ext in PointStore.SIDECARS) {
            val hit = dir.listFiles().firstOrNull {
                it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".$ext", true)
            } ?: continue
            dir.readFile(hit)?.let { out[ext] = it }
        }
        return out
    }
}