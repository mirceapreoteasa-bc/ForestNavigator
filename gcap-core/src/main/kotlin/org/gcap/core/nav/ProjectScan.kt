package org.gcap.core.nav

/**
 * What the project is made of, and whether it hangs together.
 *
 * Run when a project folder is chosen and again on Start map, because layers arrive
 * later. Its job is to settle one question the rest of the app keeps asking - *which
 * coordinate system is this project in* - instead of each layer deciding for itself.
 *
 * Layers that are present but switched off in `layer_settings.ods` are skipped: they are
 * not drawn, not edited and not navigated on, so a problem in one is not the user's
 * problem today. Layers that are switched on and are wrong **are** reported, because the
 * crew is about to rely on them.
 */
object ProjectScan {

    /** One `.shp` in the folder and whatever its `.prj` says, if anything. */
    data class LayerProbe(val stem: String, val prj: String?, val inLayerSettings: Boolean = true)

    enum class Kind {
        /** Switched on, but with no coordinate declaration at all. */
        NO_CRS,

        /** Switched on, with a declaration the app cannot interpret. */
        UNKNOWN_CRS,

        /** Switched on, projected, but into a different UTM zone from the project. */
        ZONE_MISMATCH,

        /** Switched on and geographic, while the project is projected. Legitimate,
         *  but worth naming so nobody wonders why two layers disagree. */
        GEOGRAPHIC_IN_PROJECTED,
    }

    data class Finding(val stem: String, val kind: Kind, val detail: String) {
        /** Words fit to show the user, naming the layer and what to do about it. */
        fun message(): String = when (kind) {
            Kind.NO_CRS -> "$stem is switched on but has no .prj, so its coordinates cannot be " +
                "interpreted. Either add a .prj or turn the layer off."
            Kind.UNKNOWN_CRS -> "$stem has a coordinate system the app does not recognise: $detail"
            Kind.ZONE_MISMATCH -> "$stem is in UTM zone $detail while the rest of the project " +
                "uses a different zone. Reprojenting is safer done in QGIS on a desktop."
            Kind.GEOGRAPHIC_IN_PROJECTED -> "$stem is in degrees while the project is projected " +
                "($detail). It is reprojected for display; distances to it are approximate."
        }

        fun severity(): String = when (kind) {
            Kind.NO_CRS, Kind.UNKNOWN_CRS -> "warning"
            Kind.ZONE_MISMATCH, Kind.GEOGRAPHIC_IN_PROJECTED -> "note"
        }
    }

    data class Result(
        /** The zone the project is treated as being in. */
        val workingZone: Int,
        /** Layers actually examined. */
        val scanned: List<String>,
        /** Present but switched off, so not examined. */
        val skipped: List<String>,
        val findings: List<Finding>,
        /**
         * False when no layer declares a projected zone, so [workingZone] is only the
         * assumed fallback and says nothing about where the data actually is. The app
         * then has to derive the zone from the geographic layers' own coordinates.
         */
        val projectProjected: Boolean = true,
    ) {
        /** True when nothing needs telling the user. */
        val clean: Boolean get() = findings.isEmpty()

        /** Every line, for somewhere the user chooses to read. */
        fun messages(): List<String> = findings.map { it.message() }

        /**
         * Only the findings worth interrupting for.
         *
         * A folder that deliberately keeps some layers in degrees - which is exactly what
         * the survey folders do - would otherwise raise a dialog on every single map start
         * about a choice the user has already made and cannot act on from a phone.
         */
        fun warnings(): List<String> = findings.filter { it.severity() == "warning" }.map { it.message() }
    }

    /**
     * Decide the project's working zone and report what does not fit.
     *
     * The zone is the one used by most switched-on layers, so a single stray layer from
     * another zone cannot drag the whole project with it. Ties go to the lower zone,
     * which is the more conservative choice.
     */
    fun scan(
        layers: List<LayerProbe>,
        enabled: Set<String>,
        assumedZone: Int = UtmZoneFallback,
    ): Result {
        // switched on, whatever the spelling of the name
        val on = layers.filter { l -> l.inLayerSettings && enabled.any { name -> name.equals(l.stem, true) } }
        val skipped = layers.filterNot { it in on }.map { it.stem }

        // geographic classification needs the shared CRS classifier
        val classified = on.map { it to classifyCrs(it.prj) }
        val zoneCounts = HashMap<Int, Int>()
        for ((_, c) in classified) {
            if (c.projected) {
                val z = c.zone.takeIf { it in 1..60 } ?: assumedZone
                zoneCounts[z] = (zoneCounts[z] ?: 0) + 1
            }
        }
        val working = zoneCounts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key ?: assumedZone
        val projectProjected = zoneCounts.isNotEmpty()

        val findings = ArrayList<Finding>()
        for ((probe, c) in classified) {
            when {
                c.kind == CrsKind.NONE || probe.prj.isNullOrBlank() ->
                    findings.add(Finding(probe.stem, Kind.NO_CRS, "none"))

                c.kind == CrsKind.UNKNOWN ->
                    findings.add(Finding(probe.stem, Kind.UNKNOWN_CRS, c.note))

                c.projected && c.zone in 1..60 && c.zone != working ->
                    findings.add(Finding(probe.stem, Kind.ZONE_MISMATCH, "${c.zone}"))

                !c.projected && projectProjected ->
                    findings.add(Finding(probe.stem, Kind.GEOGRAPHIC_IN_PROJECTED, "WGS84 degrees"))
            }
        }
        return Result(
            workingZone = working,
            scanned = on.map { it.stem },
            skipped = skipped,
            findings = findings,
            projectProjected = projectProjected,
        )
    }

    // ---- minimal CRS classification, so this stays free of Android and of the app --

    internal enum class CrsKind { NONE, GEOGRAPHIC, UTM, OTHER, UNKNOWN }

    internal data class Crs(val kind: CrsKind, val zone: Int = 0, val note: String = "") {
        val projected: Boolean get() = kind == CrsKind.UTM
    }

    internal fun classifyCrs(prj: String?): Crs {
        val t = prj?.trim().orEmpty()
        if (t.isEmpty()) return Crs(CrsKind.NONE)
        val hasProjection = t.contains("PROJECTION[", true)
        if (!hasProjection && (t.contains("GEOGCS", true) || t.contains("WGS_1984", true))) {
            return Crs(CrsKind.GEOGRAPHIC, 0, "WGS84 degrees")
        }
        val zone = UTM_ZONE.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: CENTRAL_MERIDIAN.find(t)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { meridianToZone(it) }
        if (hasProjection && zone != null) return Crs(CrsKind.UTM, zone, "UTM $zone")
        if (t.contains("ALBERS", true)) return Crs(CrsKind.OTHER, 0, "Albers")
        if (hasProjection) return Crs(CrsKind.OTHER, 0, "projected, zone unknown")
        return Crs(CrsKind.UNKNOWN, 0, t.take(40))
    }

    private val UTM_ZONE = Regex("""UTM[_ ]?ZONE[ _]?(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val CENTRAL_MERIDIAN = Regex("""CENTRAL_MERIDIAN["']?,\s*(-?[\d.]+)""", RegexOption.IGNORE_CASE)

    private fun meridianToZone(meridian: Double): Int? {
        if (meridian !in -180.0..180.0) return null
        val z = Math.floor((meridian + 183.0) / 6.0).toInt()
        return if (z in 1..60) z else null
    }

    /** Kept explicit so the fallback zone is one place. */
    const val UtmZoneFallback = 10
}