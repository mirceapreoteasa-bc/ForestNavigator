package org.forestnav.app.map

import org.gcap.core.PointStore

/**
 * Writing a MOB point into the open project folder.
 *
 * Separate from the activity so it can be tested against a real directory: the failure
 * that matters here is not a wrong byte in the .shp but a .prj or .shx left behind, which
 * opens without complaint and then misbehaves in a GIS.
 */
object MobWriter {

    /** Result of a write, in words fit to show the user. */
    sealed interface Outcome {
        data class Written(val layer: String, val name: String, val created: Boolean) : Outcome
        data class Failed(val layer: String, val reason: String) : Outcome
    }

    /**
     * Field to write a name into for [layer].
     *
     * A setting that names a field the layer does not have is worse than useless - it
     * creates a second, empty-looking field - so the layer's own field wins when the
     * setting disagrees. `points.shp` holds `NAME`, so it gets `NAME` even when the
     * Add-point setting still says `GCALL_ID`.
     */
    fun resolveField(dir: MapFileDir, layer: String, preferred: String): String {
        val stem = layer.substringBeforeLast('.')
        val dbf = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
        } ?: return preferred
        val bytes = dir.readFile(dbf) ?: return preferred
        val fields = PointStore.existingFields(bytes)
        if (fields.isEmpty()) return preferred
        if (fields.any { it.first.equals(preferred, true) }) return fields.first { it.first.equals(preferred, true) }.first
        val exact = fields.firstOrNull { it.first.equals(layer.substringBeforeLast('.'), true) }
        if (exact != null) return exact.first
        return fields.first().first
    }

    /** Names already used in [layer]'s name field. */
    fun existingNames(dir: MapFileDir, layer: String, field: String): List<String> {
        val stem = layer.substringBeforeLast('.')
        val dbf = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
        } ?: return emptyList()
        return dir.readFile(dbf)?.let { PointStore.names(it, field) } ?: emptyList()
    }

    /** The .shp for [layer], or null when the layer does not exist yet. */
    fun shpName(dir: MapFileDir, layer: String): String? {
        val stem = layer.substringBeforeLast('.')
        return dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".shp", true)
        }
    }

    /**
     * Append one point named [name] at ([e], [n]) to [layer], creating the layer and all
     * four sidecars when it is absent.
     *
     * [prjHint] is the coordinate text used for a newly created layer; without it a fresh
     * MOB layer would be geographic while everything else on the map is projected, and the
     * point would land in the wrong place on the next load.
     */
    fun write(
        dir: MapFileDir,
        layer: String,
        field: String,
        name: String,
        e: Double,
        n: Double,
        prjHint: String? = null,
        /** Working zone, needed when the target layer stores degrees. */
        zone: Int = Utm.ASSUMED_ZONE,
    ): Outcome {
        if (name.isBlank()) return Outcome.Failed(layer, "MOB needs a name")
        if (existingNames(dir, layer, field).contains(name)) {
            return Outcome.Failed(layer, "\"$name\" already exists in $layer.shp")
        }
        val stem = layer.substringBeforeLast('.')
        val present = shpName(dir, layer)
        val sidecars = if (present == null) {
            emptyMap()
        } else {
            val s = present.substringBeforeLast('.')
            PointStore.SIDECARS.mapNotNull { ext ->
                val hit = dir.listFiles().firstOrNull {
                    it.substringBeforeLast('.').equals(s, true) && it.endsWith(".$ext", true)
                }
                // No .prj left to read is not a crash: the sidecar may simply be absent.
                hit?.let { f -> dir.readFile(f)?.let { bytes -> ext to bytes } }
            }.toMap()
        }
        // A layer created from nothing has no .prj to match, so it is created in the
        // degrees the point collector app uses - which is what makes a new points.shp
        // interchangeable with an existing one. An existing layer keeps whatever it
        // declares, and gets coordinates in that system.
        val creating = sidecars["shp"] == null
        val targetPrj = if (creating) org.gcap.core.PointStore.PRJ_WGS84 else LayerCrs.prjOf(dir, layer)
        // An existing layer whose units cannot be read must not be guessed at. Writing
        // metres into a degrees layer produces a file that still opens and still shows a
        // marker - in the wrong hemisphere - so the only safe answer is to refuse.
        if (!creating && targetPrj.isNullOrBlank()) {
            return Outcome.Failed(
                layer,
                "$layer.shp has no readable .prj, so its coordinate system is unknown. " +
                    "Restore the .prj before adding points.",
            )
        }
        val (x, y) = LayerCrs.toLayerCoords(e, n, zone, targetPrj)
        // No fallback to some other layer's .prj: a degrees point layer that adopted the
        // project UTM would move every point ~5,600 km. newLayer() defaults to degrees,
        // and an existing layer keeps the .prj it came in with.
        val crs: String? = null
        val out = try {
            // A missing layer still goes through append: newLayer is only an empty shell,
            // and writing that alone would create the file but drop the point.
            val base = if (sidecars["shp"] == null) {
                PointStore.newLayer(field, crs)
            } else {
                sidecars
            }
            PointStore.append(base, field, name, x, y, crs)
        } catch (e: Exception) {
            return Outcome.Failed(layer, "${e.javaClass.simpleName}: ${e.message}")
        }
        // Write back under the name the layer actually has on disk. The configured name
        // is matched case-insensitively on the way in, so writing with it would create a
        // second file beside the existing one - `points.shp` and `POINTS.shp` holding one
        // point each - and the map would show half the MOBs.
        val writeStem = present?.substringBeforeLast('.') ?: stem
        for ((ext, bytes) in out) {
            if (bytes == null) continue
            val fname = "$writeStem.$ext"
            val ok = if (dir.listFiles().any { it.equals(fname, true) }) {
                dir.overwriteFile(fname, bytes)
            } else {
                dir.createFile(fname, bytes)
            }
            if (!ok) return Outcome.Failed(layer, "$fname could not be written")
        }
        dir.clearCaches()
        return Outcome.Written(layer, name, present == null)
    }

    /** Coordinate text to reuse when creating a layer, taken from an existing .prj. */}