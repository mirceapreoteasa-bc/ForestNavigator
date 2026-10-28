package org.gcap.core.nav

import java.io.File

/**
 * Finds a named layer in the survey folder, whichever spelling it currently has.
 *
 * Survey folders are reworked between seasons: a layer called `Air_Calls_YR_4` one week ago
 * is `air_calls` the next, and a test or tool that hard-codes one name breaks with
 * "No such file". Matching case-insensitively and ignoring an "_YR_n" suffix finds the
 * layer either way, so renaming it in QGIS does not stop the rest of the app.
 */
object SurveyLayers {

    private val IGNORED = setOf(
        "points", "tracks", "network", "no_work", "resultst", "ground_call",
        "xgv_mp", "access", "roads", "fire", "grid", "lakes", "landings",
        "delineation", "prov_parks", "rivers", "transect", "trails",
    )

    /** Normalised form: lower case, and any "_yr_<digits>" removed. */
    fun normalise(name: String): String {
        val stem = name.substringBeforeLast('.')
        return Regex("_yr_\\d+", RegexOption.IGNORE_CASE).replace(stem.lowercase(), "")
    }

    /**
     * The [wanted] layer's `.shp` in [dir], or null. Tries an exact match first, then a
     * case-insensitive one, then a name that matches once "_YR_n" is ignored.
     */
    fun find(dir: File, wanted: String): File? {
        val stem = wanted.substringBeforeLast('.')
        val exact = File(dir, "$stem.shp")
        if (exact.isFile) return exact
        val files = dir.listFiles() ?: return null
        files.firstOrNull { it.name.equals("$stem.shp", true) }?.let { return it }
        val target = normalise(stem)
        return files.firstOrNull {
            it.name.endsWith(".shp", true) && normalise(it.name) == target
        }
    }

    /**
     * The air-calls layer the flight tests are written against.
     *
     * Deliberately `Air_Calls_YR_4` and not a loose name match: the folder also holds a
     * small `air_calls` layer with different fields, and the tests must not silently run
     * against the wrong one. Null when it is absent, so callers can skip.
     */
    fun airCalls(dir: File): File? {
        val files = dir.listFiles() ?: return null
        // Exact stem match only. `find` also matches case-insensitively, which would pick
        // up the unrelated small `air_calls` layer and quietly run the tests on it.
        return files.firstOrNull {
            it.name.substringBeforeLast('.').equals("Air_Calls_YR_4", ignoreCase = true) &&
                it.name.endsWith(".shp", true)
        }
    }

    /** Null when the folder has no Air_Calls_YR_4 layer. */
    fun airCallsDbf(dir: File): File? =
        airCalls(dir)?.let { File(it.parentFile, it.name.replace(".shp", ".dbf")) }
            ?.takeIf { it.isFile }

    /** True when [name] is not one of the app's own working layers. */
    fun isSurveyLayer(name: String): Boolean =
        normalise(name.substringBeforeLast('.')) !in IGNORED
}
