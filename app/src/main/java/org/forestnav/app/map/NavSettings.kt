package org.forestnav.app.map

/**
 * Every setting the app honours, with the value in force.
 *
 * Compiled defaults exist so a first run works before the user has opened a spreadsheet,
 * and so a missing or damaged `.ods` degrades to something usable rather than to nothing.
 */
data class NavSettings(
    val goToLayer: String = "Ground_Call",
    val goToField: String = "Call_Num",
    val addPointLayer: String = "XGV_MP",
    val addPointNameField: String = "GCALL_ID",
    val mobLayer: String = "points",
    val trackLayer: String = "tracks",
    val trackIntervalSec: Int = 10,
    val trackMinDistanceM: Double = 5.0,
    /** GPS jump beyond this is a glitch, not movement. Not in the spreadsheet yet. */
    val trackMaxDistanceM: Double = 1000.0,
    /**
     * How often unsaved edits are written while editing.
     *
     * `null` means autosave is off. That is a real state, not an absence: `settings_1.ods`
     * A8 reads "leave it blank ... if blank no autosave for edits", while the app's own
     * default is 40 seconds when there is no spreadsheet at all. A blank cell and a missing
     * file therefore mean different things, and must not collapse into one.
     */
    val saveEditsEverySec: Int? = 40,
    val defaultScaleDenominator: Int = 10_000,
    val navigationMode: String = "on_ground",
    /** Supplied by the project actually opened, not by the sheet: B4 only displays it. */
    val workingMapName: String = "",
    /** Sheet design: "by default the first layer in the layer settings list". */
    val defaultCrsLayer: String = "XGV_MP",
    /**
     * Where each resolved value came from, for display.
     *
     * The name field can be set in two places, and the user chose that deliberately so a
     * newcomer can find it either way. Showing which one is in force is what makes that safe.
     */
    val addPointNameFieldSource: String = "app default",
    /**
     * Name field per layer, because the survey folder does not use one field name
     * everywhere: `points.shp` holds `NAME`, `xgv_mp.shp` holds `GCALL_ID`, and
     * `ground_call.shp` holds `Call_Num`. A single setting cannot name all three, so each
     * layer is asked separately and falls back to its own known field.
     */
    val nameFields: Map<String, String> = emptyMap(),
) {
    /**
     * Field to write a point name into for [layer].
     *
     * Order of preference: a setting for that layer, then [addPointNameField] when the
     * layer is the one being edited, then the field the layer is known to use.
     */
    fun nameFieldFor(layer: String): String {
        nameFields[layer]?.takeIf { it.isNotBlank() }?.let { return it }
        val stem = layer.substringBeforeLast('.')
        nameFields[stem]?.takeIf { it.isNotBlank() }?.let { return it }
        return KNOWN_NAME_FIELDS.entries
            .firstOrNull { it.key.equals(layer, true) || it.key.equals(stem, true) }
            ?.value
            ?: addPointNameField
    }

    companion object {
        /** Name fields the survey layers are actually known to use. */
        val KNOWN_NAME_FIELDS = mapOf(
            "points" to "NAME",
            "POINTS" to "NAME",
            "xgv_mp" to "GCALL_ID",
            "XGV_MP" to "GCALL_ID",
            "ground_call" to "Call_Num",
            "Ground_Call" to "Call_Num",
        )
    }
}

/** One row of `layer_settings.ods`: how a layer looks and whether it may be edited. */
data class LayerSetting(
    val name: String,
    val visible: Boolean = true,
    val editable: Boolean = false,
    val symbol: String = "",
    val colour: String = "",
    val size: Double = 1.0,
    val labelField: String = "",
    val labelSize: Double = 7.0,
)

/**
 * Turns the two spreadsheets into one [NavSettings].
 *
 * The name field is the only setting specified in both files - `settings_1.ods` A27 and
 * `layer_settings.ods` column G. That redundancy is deliberate, so it is resolved by
 * **which file was edited most recently** rather than by a fixed winner, and the result is
 * reported back so it can be shown.
 *
 * `settings_1.ods` otherwise owns which layer or field each function uses;
 * `layer_settings.ods` owns how each layer looks and whether it may be edited.
 */
class SettingsResolver(
    private val settings: OdsTable?,
    private val settingsModified: Long,
    private val layers: OdsTable?,
    private val layersModified: Long,
) {
    /**
     * Where each setting's value is typed, as an A1 address in `settings_1.ods`.
     *
     * In the sheet as it stands each setting occupies two rows: a description row that
     * carries the button caption, then a row reading "Show here ...". The value belongs in
     * that "Show here" cell, which is also where the app displays it.
     *
     * Kept as one table because this mapping is the single most likely thing to need
     * correcting once real values are typed in, and it should then be a one-line change.
     */
    object Cells {
        // yellow function button, and the display field directly beneath it that
        // shows what was settled through that button
        const val MAP_NAME_BUTTON = "B3"
        const val MAP_NAME = "B4"
        const val CRS_BUTTON = "A5"
        const val CRS = "A6"
        const val SAVE_INTERVAL = "A8"
        const val TRACK_INTERVAL = "A10"
        const val TRACK_MIN_DISTANCE = "A12"
        const val SCALES = "A14"
        const val DEFAULT_SCALE = "A16"
        const val GO_TO_LAYER = "A18"
        const val GO_TO_FIELD = "A20"
        const val ADD_POINT_LAYER = "A22"
        const val MOB_LAYER = "A24"
        const val TRACK_LAYER = "A26"
        const val NAME_FIELD = "A28"
        const val NAV_MODE = "B24"

        // actions that settle nothing, so they have no display field beneath them
        const val RESTORE_DEFAULTS = "B7"
        const val VERIFY_LAYERS = "B9"
    }

    /** Layer settings in file order. */
    fun layers(): List<LayerSetting> {
        val t = layers ?: return emptyList()
        val out = ArrayList<LayerSetting>()
        for (row in 1..400) {
            val name = t.cell("A$row")?.trim().orEmpty()
            if (name.isEmpty()) continue
            // sheet title and the column headings sit above the real layer rows
            if (name.equals("Layers Settings", true)) continue
            if (t.cell("B$row")?.trim()?.contains("/", true) == true) continue
            if (name.startsWith("Move", true)) break
            out.add(
                LayerSetting(
                    name = name,
                    visible = onOff(literal(t.cell("B$row"))),
                    editable = yes(literal(t.cell("C$row"))),
                    symbol = literal(t.cell("D$row")) ?: "",
                    colour = literal(t.cell("E$row")) ?: "",
                    size = literal(t.cell("F$row"))?.toDoubleOrNull() ?: 1.0,
                    labelField = literal(t.cell("G$row")) ?: "",
                    labelSize = literal(t.cell("H$row"))?.toDoubleOrNull() ?: 7.0,
                ),
            )
        }
        return out
    }

    /**
     * Layer setting for [name], ignoring the file extension.
     *
     * The sheet spells the recorded-track row `track` while the layer itself is
     * `tracks.shp`, so a bare/trailing-s match is accepted: without it the track layer
     * would silently lose its symbol and editability the first time a track was drawn.
     */
    fun layer(name: String): LayerSetting? {
        val all = layers()
        // substringBeforeLast('.', "") yields "" when there is no dot, which would make
        // every extension-less name fail to match; take the whole string in that case
        val exact = if (name.contains('.')) name.substringBeforeLast('.') else name
        return all.firstOrNull { it.name.equals(name, true) || it.name.equals(exact, true) }
            ?: all.firstOrNull { sameLayer(it.name, exact) }
    }

    private fun sameLayer(a: String, b: String): Boolean {
        if (a.equals(b, true)) return true
        val x = a.replace("_", "").lowercase()
        val y = b.replace("_", "").lowercase()
        return x == y || (x + "s") == y || x == (y + "s")
    }

    fun resolve(): NavSettings {
        val d = NavSettings()

        fun v(addr: String): String? = literal(settings?.cell(addr))

        val goTo = v(Cells.GO_TO_LAYER)
        val goToFld = v(Cells.GO_TO_FIELD)
        val addPt = v(Cells.ADD_POINT_LAYER)
        val mob = v(Cells.MOB_LAYER)
        val track = v(Cells.TRACK_LAYER)
        val fromSettings = v(Cells.NAME_FIELD)

        val fromLayers = layer(addPt ?: d.addPointLayer)?.labelField?.takeIf { it.isNotBlank() }

        // Reported so the user can see which of the two places is actually in force.
        val layersNewer = layersModified > settingsModified
        val (nameField, source) = when {
            fromSettings == null && fromLayers == null -> d.addPointNameField to "app default"
            fromSettings == null -> fromLayers!! to "layer_settings.ods"
            fromLayers == null -> fromSettings to "settings_1.ods"
            layersNewer -> fromLayers to "layer_settings.ods (newer)"
            else -> fromSettings to "settings_1.ods (newer)"
        }

        return d.copy(
            goToLayer = goTo ?: d.goToLayer,
            goToField = goToFld ?: d.goToField,
            addPointLayer = addPt ?: d.addPointLayer,
            addPointNameField = nameField,
            mobLayer = mob ?: d.mobLayer,
            trackLayer = track ?: d.trackLayer,
            trackIntervalSec = v(Cells.TRACK_INTERVAL)?.toIntOrNull()?.coerceAtLeast(1) ?: d.trackIntervalSec,
            trackMinDistanceM = v(Cells.TRACK_MIN_DISTANCE)?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: d.trackMinDistanceM,
            saveEditsEverySec = autosaveInterval(settings, d.saveEditsEverySec),
            defaultScaleDenominator = scaleOf(v(Cells.DEFAULT_SCALE)) ?: d.defaultScaleDenominator,
            navigationMode = v(Cells.NAV_MODE) ?: d.navigationMode,
            // B4/A6 are display fields the app writes; the project name and the chosen
            // coordinate layer come from the opened project, not from sheet prose
            defaultCrsLayer = firstLayerName() ?: d.defaultCrsLayer,
            addPointNameFieldSource = source,
        )
    }

    /**
     * The autosave interval, which has three distinct cases:
     *
     *  - a number in the cell: that is the interval
     *  - the cell genuinely emptied: the user chose no autosave, so off
     *  - the cell still holds the sheet's placeholder prose: nobody has decided yet,
     *    so the app default stands
     *
     * The last two are easy to conflate and mean opposite things. A placeholder is what
     * the sheet ships with; an empty cell is a decision the user made.
     */
    private fun autosaveInterval(sheet: OdsTable?, appDefault: Int?): Int? {
        if (sheet == null) return appDefault
        val raw = sheet.cell(Cells.SAVE_INTERVAL)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val n = raw.toIntOrNull()
        if (n != null) return n.coerceAtLeast(0)
        return appDefault
    }

    /** First layer listed in `layer_settings.ods`, which the sheet names as the default. */
    private fun firstLayerName(): String? = layers()?.firstOrNull()?.name

    /**
     * Informational line for the verifier, naming the settings that are in force because
     * no spreadsheet said otherwise. Purely informational: nothing here is repaired, but a
     * wrong default should never be invisible either.
     */
    fun defaultsInForce(s: NavSettings): String? {
        if (settings != null) return null
        return "no settings_1.ods - using app defaults: " +
            "MOB layer = ${s.mobLayer}, track layer = ${s.trackLayer}, " +
            "Go to layer = ${s.goToLayer} in ${s.goToField}"
    }

    /** Problems worth telling the user about rather than tolerating silently. */
    fun warnings(s: NavSettings): List<String> {
        val out = ArrayList<String>()
        val haveLayerFile = layers?.sheetName?.isNotEmpty() == true
        for ((what, target) in listOf(
            "Go to\u2026" to s.goToLayer,
            "Add point" to s.addPointLayer,
            "MOB" to s.mobLayer,
        )) {
            if (haveLayerFile && layer(target) == null) {
                out.add("$what: layer \"$target\" is not listed in layer_settings.ods")
            }
        }
        val addPt = layer(s.addPointLayer)
        if (haveLayerFile && addPt != null && !addPt.editable) {
            out.add("Add point: layer \"${s.addPointLayer}\" is marked not editable in layer_settings.ods")
        }
        if (haveLayerFile && addPt != null && addPt.labelField != s.addPointNameField) {
            out.add(
                "Point name: using \"${s.addPointNameField}\" from ${s.addPointNameFieldSource}, " +
                    "but \"${s.addPointLayer}\" is labelled from \"${addPt.labelField}\"",
            )
        }
        return out
    }

    /**
     * A cell's text only counts as a setting when it is an actual value.
     *
     * Column A of `settings_1.ods` is the designer's commentary - tracked-change author and
     * timestamp run into the same cell as the prose, with a "Show here ..." note in the row
     * below. Reading those as values would put a whole paragraph where a layer name belongs,
     * so they are rejected outright rather than quietly becoming the layer name.
     */
    private fun literal(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty()) return null
        if (t.contains("Unknown Author")) return null
        if (t.length > 80) return null
        if (WINDOW.any { t.startsWith(it, true) }) return null
        if (DISPLAY_NOTE.any { t.startsWith(it, true) }) return null
        if (t.equals("Show", true) || t.equals("Display", true)) return null
        return t
    }

    // The sheet ships these cells holding notes such as "Show here ..." rather than a
    // value, so a populated display field is the only thing that counts as configured.
    private val DISPLAY_NOTE = listOf(
        "Show here", "Show", "Display here", "display here",
    )

    private val WINDOW = listOf(
        "Show", "Show here", "Show here on what layer", "Display here", "Show here default scale",
    )

    private fun scaleOf(text: String?): Int? {
        val t = text?.replace("1:", "")?.trim()?.toIntOrNull() ?: return null
        return if (t > 0) t else null
    }

    private fun onOff(v: String?): Boolean {
        val t = v?.trim()?.uppercase() ?: return true
        return t == "ON" || t == "Y" || t == "YES" || t == "TRUE" || t == "1"
    }

    private fun yes(v: String?): Boolean {
        val t = v?.trim()?.uppercase() ?: return false
        return t == "Y" || t == "YES" || t == "TRUE" || t == "1" || t == "ON"
    }
}