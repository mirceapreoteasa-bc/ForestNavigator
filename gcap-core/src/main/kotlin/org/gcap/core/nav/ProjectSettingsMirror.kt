package org.gcap.core.nav

import org.json.JSONObject

/**
 * The project's own settings, as stored in mirshp.json beside the layer list.
 *
 * Kept out of the spreadsheets on purpose: a survey folder should not need a .ods to be
 * usable, and values changed in Settings have to survive being saved from the phone alone.
 * Lives in the core so the round trip is testable without a device.
 */
data class ProjectSettingsMirror(
    val goToLayer: String = "",
    val goToField: String = "",
    val waypointLayer: String = "",
    val waypointField: String = "",
    val addPointLayer: String = "",
    val addPointField: String = "",
    /** Null means "leave whatever the sheet or the app default says". */
    val trackIntervalSec: Int? = null,
    val trackMinDistanceM: Double? = null,
    val saveEditsEverySec: Int? = null,
    val defaultScaleDenominator: Int? = null,
    /**
     * Which raster is drawn underneath, or "" for none.
     *
     * Only one at a time: two rasters of the same ground stacked on top of each other
     * hide whichever was meant to show through, and neither can be read.
     */
    val basemapRaster: String = "",
    /**
     * The layer whose coordinate system the whole project is treated as being in.
     *
     * Empty means "work it out from the layers". Setting it settles folders that hold
     * more than one coordinate system, where the majority rule can pick the wrong one.
     */
    val defaultCrsLayer: String = "",
) {
    companion object {
        private const val KEY = "settings"

        fun parse(text: String?): ProjectSettingsMirror {
            if (text.isNullOrBlank()) return ProjectSettingsMirror()
            return try {
                val o = JSONObject(text).optJSONObject(KEY) ?: return ProjectSettingsMirror()
                ProjectSettingsMirror(
                    goToLayer = o.optString("goToLayer"),
                    goToField = o.optString("goToField"),
                    waypointLayer = o.optString("waypointLayer"),
                    waypointField = o.optString("waypointField"),
                    addPointLayer = o.optString("addPointLayer"),
                    addPointField = o.optString("addPointField"),
                    // -1 marks absence, so 0 and 1 remain usable values
                    trackIntervalSec = o.optInt("trackIntervalSec", -1).takeIf { it >= 0 },
                    trackMinDistanceM = o.optDouble("trackMinDistanceM", -1.0).takeIf { it >= 0 },
                    saveEditsEverySec = o.optInt("saveEditsEverySec", -1).takeIf { it >= 0 },
                    defaultScaleDenominator = o.optInt("defaultScaleDenominator", -1).takeIf { it >= 0 },
                    basemapRaster = o.optString("basemapRaster"),
                    defaultCrsLayer = o.optString("defaultCrsLayer"),
                )
            } catch (e: Exception) {
                ProjectSettingsMirror()
            }
        }

        /** Merge into [existingText], leaving the layer list untouched. */
        fun writeInto(existingText: String?, value: ProjectSettingsMirror): String {
            val root = try {
                if (existingText != null) JSONObject(existingText) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
            val o = JSONObject()
            fun put(k: String, v: String) { if (v.isNotBlank()) o.put(k, v) }
            put("goToLayer", value.goToLayer)
            put("goToField", value.goToField)
            put("waypointLayer", value.waypointLayer)
            put("waypointField", value.waypointField)
            put("addPointLayer", value.addPointLayer)
            put("addPointField", value.addPointField)
            put("basemapRaster", value.basemapRaster)
            put("defaultCrsLayer", value.defaultCrsLayer)
            value.trackIntervalSec?.let { o.put("trackIntervalSec", it) }
            value.trackMinDistanceM?.let { o.put("trackMinDistanceM", it) }
            value.saveEditsEverySec?.let { o.put("saveEditsEverySec", it) }
            value.defaultScaleDenominator?.let { o.put("defaultScaleDenominator", it) }
            root.put(KEY, o)
            return root.toString(2)
        }
    }
}
