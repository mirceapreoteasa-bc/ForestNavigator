package org.forestnav.app.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * Layer styling engine: parses the project model (project.json, converted
 * from layer_settings.ods), matches it against the .shp files on disk with
 * fuzzy names, and resolves per-layer draw settings.
 *
 * Sheet conventions honored: order top->bottom is draw-on-top order; a row
 * name matches exact stems first, then prefix either way ("roads" finds
 * "roads4", "Air_Calls.." finds both Air_Calls_* files); unmatched files go
 * last with neutral defaults, always visible so nothing hides silently.
 */
object LayerStyle {

    data class LayerSpec(
        val name: String,
        val visible: Boolean = true,
        val editable: Boolean = false,
        val symbolRaw: String = "",
        val colorRaw: String = "",
        val sizeDp: Float? = null,
        val labelField: String? = null,
        val labelSizeSp: Float? = null,
    )

    data class Entry(val file: String, val spec: LayerSpec?)

    data class MatchResult(
        /** Display order: sheet order top->bottom, unmatched files appended last. */
        val ordered: List<Entry>,
        /** Active editable file: XGV_MP when eligible, else POINTS, else first eligible. */
        val activeEditable: String?,
    )

    // ---- color table (tuned for the near-white canvas) -----------------------

    private val COLORS = mapOf(
        "orange" to 0xFFCC6600.toInt(),
        "blue" to 0xFF3366CC.toInt(),
        "green" to 0xFF2E7D32.toInt(),
        "violet" to 0xFF8A2BE2.toInt(),
        "red" to 0xFFCC0000.toInt(),
        "brown" to 0xFF795548.toInt(),
        "yellow" to 0xFFD6A800.toInt(),
        "black" to 0xFF000000.toInt(),
        "pink" to 0xFFE91E63.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "gray" to 0xFF888888.toInt(),
        "grey" to 0xFF888888.toInt(),
    )

    fun colorOf(name: String): Int =
        COLORS[name.trim().lowercase()] ?: 0xFF888888.toInt()

    /** "green border" -> border-only green; plain "blue" -> fill + border blue. */
    fun borderOf(colorRaw: String): Int? {
        val t = colorRaw.trim()
        return if (t.lowercase().endsWith("border")) {
            colorOf(t.substring(0, t.length - "border".length))
        } else null
    }

    // ---- symbol parsing (case-insensitive, sheet typos tolerated) -------------

    /** A round colour chip, outlined when it is the layer's current colour. */
    fun swatchDrawable(argb: Int, isSelected: Boolean): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(argb)
            val ring = if (isSelected) 0xFFFFFFFF.toInt() else 0xFF9E9E9E.toInt()
            setStroke((if (isSelected) 4 else 1).coerceAtLeast(1), ring)
        }

    /** Colour names the Layer settings screen offers, in the order the table lists them. */
    fun colourNames(): List<String> = COLORS.keys.toList()

    /** Symbol names the Layer settings screen offers: point, line and polygon styles. */
    /** Line and fill symbols. */
    val lineSymbolNames: List<String> = listOf(
        "", "single line", "dash", "doted line", "double line",
    )

    val fillSymbolNames: List<String> = listOf(
        "", "simple fill", "no fill", "green border",
        "hashed /", "hashed \\", "hashed vertically", "hashed horizontally", "pattern dot",
    )

    val pointSymbolNames: List<String> = listOf(
        "", "triangle", "square", "diamond", "circle",
    )

    /** Everything, kept for reading old files that named any of these. */
    val symbolNames: List<String> = pointSymbolNames + lineSymbolNames + fillSymbolNames

    /**
     * Symbols that make sense for a layer of this shape. A point layer was offering line
     * symbols it cannot draw, so the list is chosen by kind.
     */
    fun symbolNamesFor(kind: org.forestnav.app.map.MapCanvasView.MapLayer.Kind): List<String> =
        when (kind) {
            org.forestnav.app.map.MapCanvasView.MapLayer.Kind.POINT -> pointSymbolNames
            org.forestnav.app.map.MapCanvasView.MapLayer.Kind.LINE -> lineSymbolNames
            else -> fillSymbolNames
        }

    fun pointSymbolOf(s: String): MapCanvasView.PointSymbol = when (s.trim().lowercase()) {
        "triangle" -> MapCanvasView.PointSymbol.TRIANGLE
        "square" -> MapCanvasView.PointSymbol.SQUARE
        "diamond" -> MapCanvasView.PointSymbol.DIAMOND
        else -> MapCanvasView.PointSymbol.CIRCLE // "circle", "dot", blank, unknown
    }

    fun lineSymbolOf(s: String): MapCanvasView.LineSymbol = when (s.trim().lowercase()) {
        "dash", "dashed", "dash line" -> MapCanvasView.LineSymbol.DASH
        "doted line", "dotted line", "dotted", "dot", "dots" -> MapCanvasView.LineSymbol.DOT
        "double", "double line", "double line topo road", "topo road" -> MapCanvasView.LineSymbol.DOUBLE_ROAD
        else -> MapCanvasView.LineSymbol.SIMPLE // "single line", "simple line", blank
    }

    /**
     * Colour for a layer that has no entry in mirshp.json. Every layer used to fall back to
     * the same green, so a folder with no saved styling drew as one continuous green area.
     * Each name now gets its own colour from a fixed palette, the same on every run.
     */
    private val DEFAULT_PALETTE = intArrayOf(
        0xFF1565C0.toInt(), 0xFFD84315.toInt(), 0xFF6A1B9A.toInt(), 0xFF00838F.toInt(),
        0xFFEF6C00.toInt(), 0xFF2E7D32.toInt(), 0xFFAD1457.toInt(), 0xFF5D4037.toInt(),
        0xFF283593.toInt(), 0xFF9E9D24.toInt(), 0xFF455A64.toInt(), 0xFF00695C.toInt(),
    )

    /**
     * Layers switched on when a folder has no saved choice for them. Anything else starts
     * off, so the map is not a pile of every layer in the folder.
     */
    private val GROUND_DEFAULT_ON = setOf("ground_call", "xgv_mp", "access", "roads", "no_work")

    /** Extra layers shown in AIR mode. */
    private val AIR_MODE_ADDS = setOf("air_calls", "flight_path")

    fun defaultVisible(layerName: String): Boolean = layerName.lowercase() in GROUND_DEFAULT_ON

    fun airModeAdds(layerName: String): Boolean = layerName.lowercase() in AIR_MODE_ADDS

    fun defaultColorFor(layerName: String): Int {
        val h = layerName.lowercase().hashCode()
        return DEFAULT_PALETTE[Math.floorMod(h, DEFAULT_PALETTE.size)]
    }

    fun polySymbolOf(s: String): MapCanvasView.PolySymbol = when (s.trim().lowercase()) {
        "no fill", "none" -> MapCanvasView.PolySymbol.NOFILL
        "hashed /", "hatch /", "/", "diagonal /", "45" -> MapCanvasView.PolySymbol.HATCH_45
        "hashed \\", "hatch \\", "\\" -> MapCanvasView.PolySymbol.HATCH_NEG45
        "hashed vertically", "hashed vertical", "vertical", "|" -> MapCanvasView.PolySymbol.HATCH_V
        "hashed horizontally", "hashed horizontal", "horizontal", "-" -> MapCanvasView.PolySymbol.HATCH_H
        "pattern dot", "dot", "dots" -> MapCanvasView.PolySymbol.DOT
        else -> MapCanvasView.PolySymbol.FILL // "simple fill", blank, unknown
    }

    // ---- project.json ----------------------------------------------------------

    /**
     * Null-safe string: Android's framework org.json returns the literal
     * "null" from optString() for explicit JSON nulls (and hand-edited files
     * may contain them), so treat missing / null / "null" all as absent.
     */
    private fun optStr(o: JSONObject, key: String): String? {
        if (o.isNull(key)) return null
        return o.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }

    fun parseProject(json: String): List<LayerSpec> {
        val out = mutableListOf<LayerSpec>()
        val arr = JSONObject(json).optJSONArray("layers") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                LayerSpec(
                    name = optStr(o, "name").orEmpty(),
                    visible = o.optBoolean("visible", true),
                    editable = o.optBoolean("editable", false),
                    symbolRaw = optStr(o, "symbol").orEmpty(),
                    colorRaw = optStr(o, "color").orEmpty(),
                    sizeDp = optStr(o, "size")?.toFloatOrNull(),
                    labelField = optStr(o, "label"),
                    labelSizeSp = optStr(o, "labelSize")?.toFloatOrNull(),
                ),
            )
        }
        return out
    }

    /** Merge a layers array into an existing project doc, preserving settings. */
    fun docWithLayers(existingText: String?, specs: List<LayerSpec>): String {
        val root = try {
            if (existingText != null) JSONObject(existingText) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        root.put("layers", layersJson(specs))
        return root.toString(1)
    }

    /** Merge a settings object into an existing project doc, preserving layers. */
    fun docWithSettings(existingText: String?, s: Settings): String {
        val root = try {
            if (existingText != null) JSONObject(existingText) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        root.put("settings", settingsToJson(s))
        return root.toString(1)
    }

    private fun layersJson(specs: List<LayerSpec>): JSONArray {
        val arr = JSONArray()
        for (s in specs) {
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("visible", s.visible)
                put("editable", s.editable)
                put("symbol", s.symbolRaw)
                put("color", s.colorRaw)
                if (s.sizeDp != null) put("size", s.sizeDp.toDouble()) else put("size", JSONObject.NULL)
                put("label", s.labelField ?: "")
                if (s.labelSizeSp != null) put("labelSize", s.labelSizeSp.toDouble()) else put("labelSize", JSONObject.NULL)
            })
        }
        return arr
    }

    /** Serialize back to project.json schema (round-trips through [parseProject]). */
    fun toJson(specs: List<LayerSpec>): String {
        return JSONObject().put("layers", layersJson(specs)).toString(1)
    }

    /** General (map) settings. Persisted in project.json next to "layers". */
    data class Settings(
        /** "" = auto from first layer, "WGS84" = auto zone, else "UTM Zone NN". */
        val workingCrs: String = "",
        /** Null = off. Sheet default 40. */
        val autosaveSeconds: Int? = 40,
        /** Null = blank (recorder falls back to 10). Crew default 10. */
        val trackIntervalSeconds: Int? = 10,
        /** Sheet default 5. */
        val trackMinDistanceM: Double = 5.0,
        /** QField-style outlier rejection: a jump beyond this (metres) is
         *  discarded as a GPS glitch instead of becoming a track segment. */
        val trackMaxDistanceM: Double = 500.0,
        val zoomMin: Int = 500,
        val zoomMax: Int = 400000,
        val defaultScale: Int = 10000,
        val goToLayer: String = "Ground_Call",
        val goToColumn: String = "Call_Num",
        /** Layer the Waypoint command offers targets from. */
        val waypointLayer: String = "Ground_Call",
        val waypointColumn: String = "Call_Num",
        val addPointLayer: String = "XGV_MP",
        val mobLayer: String = "points",
        val trackLayer: String = "tracks",
    )

    val FIXED_SCALES = listOf(
        500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 7500, 10000,
        15000, 20000, 40000, 50000, 75000, 100000, 150000, 200000,
        250000, 300000, 400000,
    )

    val CRS_OPTIONS = listOf("WGS84") + (7..11).map { "UTM Zone $it" + "N" }

    fun parseSettings(json: String): Settings {
        val o = try {
            JSONObject(json).optJSONObject("settings")
        } catch (e: Exception) {
            null
        } ?: return Settings()
        fun optInt(key: String): Int? =
            if (o.isNull(key)) null else o.optDouble(key, Double.NaN).takeIf { it.isFinite() }?.toInt()
        // Blank (off) must survive a round-trip: missing key -> sheet default,
        // explicit null -> off (null). toJson() writes null for off.
        fun autosaveOrDefault(): Int? =
            if (!o.has("autosaveSeconds")) 40 else optInt("autosaveSeconds")
        return Settings(
            workingCrs = optStr(o, "workingCrs").orEmpty(),
            autosaveSeconds = autosaveOrDefault(),
            trackIntervalSeconds = if (!o.has("trackIntervalSeconds")) 10 else optInt("trackIntervalSeconds"),
            trackMinDistanceM = if (o.isNull("trackMinDistanceM")) 5.0
            else o.optDouble("trackMinDistanceM", 5.0),
            zoomMin = optInt("zoomMin") ?: 500,
            zoomMax = optInt("zoomMax") ?: 400000,
            defaultScale = optInt("defaultScale") ?: 10000,
            goToLayer = optStr(o, "goToLayer") ?: "Ground_Call",
            goToColumn = optStr(o, "goToColumn") ?: "Call_Num",
            addPointLayer = optStr(o, "addPointLayer") ?: "XGV_MP",
            mobLayer = optStr(o, "mobLayer") ?: "points",
            trackLayer = optStr(o, "trackLayer") ?: "tracks",
        )
    }

    fun settingsToJson(s: Settings): JSONObject = JSONObject().apply {
        put("workingCrs", s.workingCrs)
        if (s.autosaveSeconds != null) put("autosaveSeconds", s.autosaveSeconds) else put("autosaveSeconds", JSONObject.NULL)
        if (s.trackIntervalSeconds != null) put("trackIntervalSeconds", s.trackIntervalSeconds) else put("trackIntervalSeconds", JSONObject.NULL)
        put("trackMinDistanceM", s.trackMinDistanceM)
        put("zoomMin", s.zoomMin)
        put("zoomMax", s.zoomMax)
        put("defaultScale", s.defaultScale)
        put("goToLayer", s.goToLayer)
        put("goToColumn", s.goToColumn)
        put("addPointLayer", s.addPointLayer)
        put("mobLayer", s.mobLayer)
        put("trackLayer", s.trackLayer)
    }

    /** Resolve the working UTM zone: explicit zone, WGS84/live auto, or first layer. */
    fun resolveWorkingZone(
        workingCrs: String,
        liveLon: Double?,
        firstLayerZone: Int?,
    ): Int {
        // NOTE: check the WGS84 keyword before digits ("WGS84" contains "84").
        if (!workingCrs.trim().equals("WGS84", true)) {
            workingCrs.filter { it.isDigit() }.toIntOrNull()?.let { return it }
        }
        if (liveLon != null) return Utm.meridianZone(liveLon)
        return firstLayerZone ?: 10
    }

    /** Load project specs: data-folder override first, assets fallback. */
    fun loadProject(readRootFile: (String) -> ByteArray?, readAsset: (String) -> ByteArray?): List<LayerSpec> {
        val fromFolder: String? = try {
            readRootFile("project.json")?.toString(Charsets.UTF_8)
        } catch (e: Exception) { null }
        val fromAssets: String? = try {
            readAsset("project.json")?.toString(Charsets.UTF_8)
        } catch (e: Exception) { null }
        return try {
            parseProject(fromFolder ?: fromAssets ?: "{\"layers\":[]}")
        } catch (e: Exception) { emptyList() }
    }

    /** Shape type -> canvas kind (POINT, LINE or POLYGON); Z variants included. */
    fun kindFor(shapeType: Int?): MapCanvasView.MapLayer.Kind = when (shapeType) {
        org.gcap.core.ShpFormat.TYPE_POINT, org.gcap.core.ShpFormat.TYPE_POINTZ ->
            MapCanvasView.MapLayer.Kind.POINT
        org.gcap.core.ShpFormat.TYPE_POLYGON, org.gcap.core.ShpFormat.TYPE_POLYGONZ ->
            MapCanvasView.MapLayer.Kind.POLYGON
        else -> MapCanvasView.MapLayer.Kind.LINE
    }

    // ---- fuzzy matching + ordering ----------------------------------------------

    private fun rowKey(name: String): String = name.trim().trimEnd('.').lowercase()

    /**
     * Match .shp [files] (full file names) against [specs] (sheet order).
     * Pass 1: exact stem match. Pass 2: prefix either way, first row in sheet
     * order wins (one row may take several files, e.g. "Air_Calls..").
     * Leftovers go last, alphabetically, with null spec (neutral defaults).
     */
    fun match(files: List<String>, specs: List<LayerSpec>): MatchResult {
        fun stem(f: String): String = f.substringBeforeLast('.')
        val remaining = files.toMutableList()
        val ordered = mutableListOf<Entry>()
        val taken = mutableSetOf<String>()

        // Pass 1: exact stems.
        val exactByKey = mutableMapOf<String, MutableList<String>>()
        for (f in remaining) {
            exactByKey.getOrPut(stem(f).lowercase()) { mutableListOf() }.add(f)
        }
        val pass1Hits = mutableMapOf<LayerSpec, MutableList<String>>()
        for (spec in specs) {
            // A file belongs to the FIRST matching row only — duplicate rows
            // (same name twice in project.json) must not both claim it, or the
            // Layer settings list grows one row per save and never shrinks.
            val hit = exactByKey[rowKey(spec.name)]?.filter { it !in taken } ?: continue
            if (hit.isEmpty()) continue
            pass1Hits.getOrPut(spec) { mutableListOf() }.addAll(hit)
            taken.addAll(hit)
        }
        // Pass 2: prefix either way, sheet order wins.
        val pass2Hits = mutableMapOf<LayerSpec, MutableList<String>>()
        for (f in remaining.filter { it !in taken }) {
            val key = stem(f).lowercase()
            val spec = specs.firstOrNull { s ->
                val rk = rowKey(s.name)
                rk.isNotEmpty() && (key.startsWith(rk) || rk.startsWith(key))
            } ?: continue
            pass2Hits.getOrPut(spec) { mutableListOf() }.add(f)
            taken.add(f)
        }
        for (spec in specs) {
            for (f in (pass1Hits[spec].orEmpty() + pass2Hits[spec].orEmpty()).sorted()) {
                ordered.add(Entry(f, spec))
            }
        }
        for (f in remaining.filter { it !in taken }.sorted()) {
            ordered.add(Entry(f, null))
        }
        val editableFiles = ordered.filter { it.spec?.editable == true }.map { it.file }
        // Add_Point targets XGV_MP; POINTS is the MOB-only layer.
        val active = editableFiles.firstOrNull { stem(it).equals("XGV_MP", true) }
            ?: editableFiles.firstOrNull { stem(it).equals("POINTS", true) }
            ?: editableFiles.firstOrNull()
        return MatchResult(ordered, active)
    }
}
