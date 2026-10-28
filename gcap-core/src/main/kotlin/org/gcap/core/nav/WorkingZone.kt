package org.gcap.core.nav

/**
 * Which UTM zone a project is worked in, and how that was decided.
 *
 * The order is deliberate and matters more than it looks. A majority vote across every
 * layer lets one stray `.prj` change the answer for the whole project, and the layer that
 * field work actually runs on has no more say than any other. So the network backbone
 * is asked first: `access` is the layer every route and every field trip depends on, and
 * if it declares a zone then that is the project.
 *
 * Only when there is no such layer does the rest of the project's own declarations count,
 * and only when nothing is projected at all is the zone read from where the coordinates
 * sit, which is better than assuming one - an assumed zone is a guess about where the
 * survey is, and it converts everything wrongly without saying so.
 *
 * The reason is carried with the answer so the app can say where the number came from
 * rather than presenting it as a fact the user has no way to check.
 */
object WorkingZone {

    enum class Source(val label: String) {
        /** The access layer named a zone, so the project is that zone. */
        ACCESS("taken from the access layer"),

        /** Nothing else to go on, but the layers do declare a zone between them. */
        DECLARED("as declared by the project's layers"),

        /** No layer is projected, so the zone came from the data's own longitudes. */
        FROM_DATA("read from where the folder's coordinates sit"),

        /**
         * Nothing could be read at all. Still returned as a number because the rest of
         * the app needs one, but labelled so it is not mistaken for a measurement.
         */
        ASSUMED("assumed: no layer states a zone and none could be read"),
    }

    data class Decision(val zone: Int, val source: Source) {
        /** What to tell a user: the zone and where it came from. */
        fun describe(): String = "UTM $zone, ${source.label}"
    }

    /**
     * @param accessZone zone the access layer declares, or null when there is no access
     *   layer or it does not declare a usable zone.
     * @param declaredZone zone the rest of the project declares, or null if nothing is
     *   projected.
     * @param geographicZone zone implied by the coordinates of degree layers, or 0.
     */
    fun decide(
        accessZone: Int?,
        declaredZone: Int?,
        geographicZone: Int,
        assumedZone: Int,
    ): Decision {
        accessZone?.takeIf { it in 1..60 }?.let { return Decision(it, Source.ACCESS) }
        declaredZone?.takeIf { it in 1..60 }?.let { return Decision(it, Source.DECLARED) }
        geographicZone.takeIf { it in 1..60 }?.let {
            return Decision(it, Source.FROM_DATA)
        }
        return Decision(assumedZone.takeIf { it in 1..60 } ?: 1, Source.ASSUMED)
    }
}