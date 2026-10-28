package org.forestnav.app.map

/**
 * Which layers are worth loading at a given map scale.
 *
 * Drawing every layer in a 130-file survey folder at 1:400,000 is wasted work and looks
 * like a solid smear: at that zoom a few hundred metres of track is a fraction of a pixel.
 * The rule is that detail arrives as you zoom in.
 */
object ScaleLayerPolicy {

    /** Loaded at 1:400,000 and further out: the skeleton of the survey. */
    val WIDEST = setOf("access", "ground_call", "xgv_mp")

    /** Added by 1:200,000. */
    val MID = WIDEST + setOf("grid", "lakes", "roads")

    /**
     * Added by 1:100,000: the recorded and operational layers.
     *
     * `points` is deliberately absent - it is an emergency layer and is always loaded.
     */
    val NEAR = MID + setOf(
        "tracks", "no_work", "air_calls", "flight_path", "Air_Calls_YR_4",
    )

    /** Everything, once close enough for the fine detail to matter. */
    val ALL: Set<String>? = null

    /**
     * Layer names to load at [denominator] (1:[denominator]).
     *
     * Matches on the file stem with or without its extension, and ignores case, because
     * the sheet and the survey folder disagree on both.
     */
    fun layersAt(denominator: Int, onAir: Boolean = false): Set<String>? = when {
        denominator >= 400_000 -> WIDEST
        denominator >= 200_000 -> MID
        denominator >= 100_000 -> NEAR
        else -> ALL
    }

    /** True when [layer] should be loaded at [denominator]. */
    fun loads(layer: String, denominator: Int, onAir: Boolean = false): Boolean {
        val stem = layer.substringBeforeLast('.').lowercase()
        // The close-in band is the other way round from the ladder: a layer can be
        // wanted when zoomed in and pointless when zoomed out.
        if (CLOSE_ONLY.any { it == stem }) return denominator in CLOSE_NEAR_M..CLOSE_FAR_M
        val set = layersAt(denominator, onAir) ?: return true
        return set.any { it.lowercase() == stem }
    }

    /**
     * The MOB layer, which is loaded whatever the zoom.
     *
     * A MOB is an emergency record: it must be on the map the moment it is saved, not
     * only once the crew zooms in past 1:100,000. It therefore sits ahead of the ladder
     * rather than in one of its rungs.
     */
    fun isEmergency(layer: String): Boolean {
        val stem = layer.substringBeforeLast('.').lowercase()
        return EMERGENCY.any { it == stem }
    }

    /** True when [layer] should be loaded at [denominator], emergency layers always are. */
    fun loadsOrEmergency(layer: String, denominator: Int, onAir: Boolean = false): Boolean =
        isEmergency(layer) || loads(layer, denominator, onAir)

    /** Emergency layers, outside the zoom ladder. */
    val EMERGENCY = setOf("points")

    /**
     * Layers shown only between 1:[CLOSE_FAR_M] and 1:[CLOSE_NEAR_M].
     *
     * A band rather than a rung. Block delineation is a boundary, so it is noise at
     * 1:50,000 and unreadable at 1:500, and it is the heaviest layer in a survey
     * folder by a wide margin - so it earns its memory only in between.
     */
    val CLOSE_ONLY = setOf("delineation")

    /** Outer edge of the close-in band: visible at 1:10,000, not at 1:20,000. */
    const val CLOSE_FAR_M = 10_000

    /** Inner edge: not visible past 1:500, where a boundary is finer than a pixel. */
    const val CLOSE_NEAR_M = 500

    /**
     * Raster extensions this app cannot draw.
     *
     * There is no GeoTIFF decoder here, so a survey folder carrying orthoimagery
     * would load with no photography and no explanation - which reads as a broken
     * folder rather than an unsupported format. They are detected by name so they can
     * be reported instead of vanishing.
     */
    val RASTER_EXTENSIONS = setOf("tif", "tiff", "vrt", "img", "jp2", "ecw", "sid")

    /** True when [file] is a raster the app cannot display. */
    fun isUnsupportedRaster(file: String): Boolean =
        file.substringAfterLast('.', "").lowercase() in RASTER_EXTENSIONS

    /** The raster files in [present] the app cannot display, in words fit for the user. */
    fun rastersPresent(present: Collection<String>): List<String> =
        present.filter { isUnsupportedRaster(it) }
            .map { it.substringBeforeLast('.') }
            .distinct()

    /**
     * Layers dropped at [denominator], in words fit for the user.
     *
     * Empty when nothing is being held back, so the caller can stay quiet rather than
     * reporting a non-problem.
     */
    fun dropped(present: Collection<String>, denominator: Int, onAir: Boolean = false): List<String> {
        val set = layersAt(denominator, onAir) ?: return emptyList()
        return present.filterNot { loads(it, denominator, onAir) }
    }
}