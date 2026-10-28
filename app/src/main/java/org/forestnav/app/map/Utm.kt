package org.forestnav.app.map

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * WGS84 -> UTM projection (Transverse Mercator), extracted verbatim from the
 * BAF_cap GPS page. Pure math, no Android dependencies.
 */
object Utm {
    const val WGS84_A = 6378137.0
    const val WGS84_F = 1.0 / 298.257223563
    const val K0 = 0.9996
    const val FALSE_E = 500000.0

    fun meridianZone(lon: Double): Int = floor((lon + 180.0) / 6.0).toInt() + 1

    fun zoneLabel(latDeg: Double, lonDeg: Double): String =
        "${meridianZone(lonDeg)}${if (latDeg >= 0) "N" else "S"}"

    fun wgs84ToUtm(latDeg: Double, lonDeg: Double): Pair<Double, Double> =
        wgs84ToUtmZone(latDeg, lonDeg, meridianZone(lonDeg))

    /** Forward projection into an explicit zone (working-CRS conversions). */
    fun wgs84ToUtmZone(latDeg: Double, lonDeg: Double, zone: Int): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val lambda = Math.toRadians(lonDeg - (zone * 6 - 183))
        val e2 = WGS84_F * (2 - WGS84_F)
        val ep2 = e2 / (1 - e2)
        val n = WGS84_A / sqrt(1 - e2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = ep2 * cos(phi) * cos(phi)
        val a = cos(phi) * lambda
        val m = WGS84_A * (
            (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * phi
                - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * phi)
                + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * phi)
                - (35 * e2 * e2 * e2 / 3072) * sin(6 * phi)
            )
        val x = K0 * n * (
            a + (1 - t + c) * a * a * a / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a * a * a * a * a / 120
            )
        val y = K0 * (
            m + n * tan(phi) * (
                a * a / 2
                    + (5 - t + 9 * c + 4 * c * c) * a * a * a * a / 24
                    + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a * a * a * a * a * a / 720
                )
            )
        val easting = FALSE_E + x
        val northing = if (latDeg < 0) 10000000.0 + y else y
        return easting to northing
    }

    fun fmt(v: Double): String =
        String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    /** MOB default point name: DDHHMM (day, 24h hour, minute). Pure for tests. */
    fun mobAutoName(nowMs: Long): String =
        java.text.SimpleDateFormat("ddHHmm", Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(java.util.Date(nowMs))

    /**
     * Inverse Transverse Mercator: UTM -> WGS84 lat/lon. Needed to write back
     * layers whose native CRS is geographic (track.shp) and for GPX export.
     * Returns Pair(latDeg, lonDeg).
     */
    fun utmToLatLon(easting: Double, northing: Double, zone: Int): Pair<Double, Double> {
        val e2 = WGS84_F * (2 - WGS84_F)
        val e4 = e2 * e2
        val e6 = e4 * e2
        val x = easting - FALSE_E
        val m = northing / K0
        val mu = m / (WGS84_A * (1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0))
        val e1 = (1.0 - kotlin.math.sqrt(1.0 - e2)) / (1.0 + kotlin.math.sqrt(1.0 - e2))
        val phi1 = mu + (3.0 * e1 / 2.0 - 27.0 * e1 * e1 * e1 / 32.0) * kotlin.math.sin(2.0 * mu) +
            (21.0 * e1 * e1 / 16.0 - 55.0 * e1 * e1 * e1 * e1 / 32.0) * kotlin.math.sin(4.0 * mu) +
            (151.0 * e1 * e1 * e1 / 96.0) * kotlin.math.sin(6.0 * mu)
        val n1 = WGS84_A / kotlin.math.sqrt(1.0 - e2 * kotlin.math.sin(phi1) * kotlin.math.sin(phi1))
        val t1 = kotlin.math.tan(phi1) * kotlin.math.tan(phi1)
        val c1 = (e2 / (1.0 - e2)) * kotlin.math.cos(phi1) * kotlin.math.cos(phi1)
        val r1base = 1.0 - e2 * kotlin.math.sin(phi1) * kotlin.math.sin(phi1)
        val r1 = WGS84_A * (1.0 - e2) / (r1base * kotlin.math.sqrt(r1base))
        val d = x / (n1 * K0)
        val ep2 = e2 / (1.0 - e2)
        val lat = phi1 - (n1 * kotlin.math.tan(phi1) / r1) * (d * d / 2.0 -
            (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * ep2) * d * d * d * d / 24.0 +
            (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * ep2 - 3.0 * c1 * c1) *
            d * d * d * d * d * d / 720.0)
        val lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val lon = lon0 + (d - (1.0 + 2.0 * t1 + c1) * d * d * d / 6.0 +
            (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * ep2 + 24.0 * t1 * t1) *
            d * d * d * d * d / 120.0) / kotlin.math.cos(phi1)
        return Math.toDegrees(lat) to Math.toDegrees(lon)
    }

    /** Working CRS of a layer, from its .prj sidecar text (null = absent). */
    enum class Crs { UTM_METERS, WGS84_DEGREES, ALBERS, OTHER }

    /**
     * A layer's CRS, plus the UTM zone when it has one.
     *
     * The zone has to travel with the classification. A layer projected in zone 11 and
     * a layer in zone 10 are both "UTM_METERS", but their eastings mean different
     * things, so treating them alike would draw one of them hundreds of kilometres out.
     */
    data class LayerCrs(val kind: Crs, val utmZone: Int = 0)

    /**
     * Classify a .prj and recover its UTM zone.
     *
     * UTM is recognised in any zone and however it is spelled - "UTM_Zone_10N",
     * "UTM zone 33N", "WGS 84 / UTM zone 33N", "EPSG:32610" - because a project may
     * span a zone boundary and users bring layers from their own block.
     */
    fun classify(prjText: String?): LayerCrs {
        if (prjText.isNullOrBlank()) return LayerCrs(Crs.UTM_METERS, ASSUMED_ZONE)
        val t = prjText.trim()
        val flat = t.uppercase().filter { it.isLetterOrDigit() }

        utmZoneIn(flat)?.let { return LayerCrs(Crs.UTM_METERS, it) }

        if (!t.contains("PROJCS") && (t.startsWith("GEOGCS") || t.contains("WGS_1984"))) {
            return LayerCrs(Crs.WGS84_DEGREES)
        }
        // Province-wide conic (VRI): drawn by reprojecting through WGS84.
        if (flat.contains("ALBERS")) return LayerCrs(Crs.ALBERS)

        // A Transverse Mercator in metres with a usable central meridian is a UTM
        // zone even when the name does not say so.
        if (flat.contains("TRANSVERSEMERCATOR") && flat.contains("METER")) {
            val meridian = centralMeridian(t)
            if (meridian != null) {
                val z = utmZoneForMeridian(meridian)
                if (z in 1..60) return LayerCrs(Crs.UTM_METERS, z)
            }
            return LayerCrs(Crs.UTM_METERS, ASSUMED_ZONE)
        }
        return LayerCrs(Crs.OTHER)
    }

    /** Zone assumed for a layer with no readable .prj, matching historic behaviour. */
    const val ASSUMED_ZONE = 10

    /** EPSG code 326xx/327xx (north/south) to a UTM zone, or null. */
    fun zoneFromEpsg(epsg: Int): Int? {
        if (epsg in 32601..32660) return epsg - 32600
        if (epsg in 32701..32760) return epsg - 32700
        return null
    }

    private val ZONE_N = Regex("(?:UTM_?ZONE|UTM|ZONE)(\\d{1,2})N?")
    private val EPSG_UTM = Regex("EPSG:?(\\d{4,5})")

    /** Pull a UTM zone number out of the flattened .prj text, if there is one. */
    private fun utmZoneIn(flat: String): Int? {
        EPSG_UTM.find(flat)?.let { m ->
            zoneFromEpsg(m.groupValues[1].toIntOrNull() ?: 0)?.let { return it }
        }
        val m = ZONE_N.find(flat) ?: return null
        val z = m.groupValues[1].toIntOrNull() ?: return null
        return if (z in 1..60) z else null
    }

    /** Backwards-compatible wrapper for callers that only need the kind. */
    fun classifyCrs(prjText: String?): Crs = classify(prjText).kind

    /** Central meridian degrees from PARAMETER["Central_Meridian",…], if present. */
    private fun centralMeridian(prj: String): Double? {
        val m = Regex("""Central_Meridian"\s*,\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(prj)
        return m?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun utmZoneForMeridian(lon: Double): Int = ((lon + 180.0) / 6.0 + 1.0).toInt()
}

/**
 * Albers Equal-Area Conic (Snyder) on GRS80, for province-wide layers such as
 * BC Albers (VRI). Parameters come from the layer's own .prj
 * (STANDARD_PARALLEL_1/2, CENTRAL_MERIDIAN, LATITUDE_OF_ORIGIN,
 * FALSE_EASTING/NORTHING); NAD83 is treated as WGS84 (1–2 m, the same
 * standing practice as the UTM code). Angles in degrees at the boundary,
 * radians internally — mirroring Utm conventions.
 */
object Albers {

    data class Params(
        val lat0Deg: Double,
        val lon0Deg: Double,
        val lat1Deg: Double,
        val lat2Deg: Double,
        val fe: Double,
        val fn: Double,
    )

    private const val A = 6378137.0
    private const val F = 1.0 / 298.257222101

    /** Parse projection parameters from .prj text; null when incomplete. */
    fun params(prjText: String): Params? {
        fun num(key: String): Double? {
            val m = Regex(
                Regex.escape(key) + "\"\\s*,\\s*(-?\\d+(?:\\.\\d+)?)",
                RegexOption.IGNORE_CASE,
            ).find(prjText)
            return m?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }
        val lat0 = num("LATITUDE_OF_ORIGIN") ?: return null
        val lon0 = num("CENTRAL_MERIDIAN") ?: return null
        val lat1 = num("STANDARD_PARALLEL_1") ?: return null
        val lat2 = num("STANDARD_PARALLEL_2") ?: return null
        val fe = num("FALSE_EASTING") ?: return null
        val fn = num("FALSE_NORTHING") ?: return null
        return Params(lat0, lon0, lat1, lat2, fe, fn)
    }

    private val e2 = F * (2 - F)
    private val e = sqrt(e2)

    private fun m(phi: Double): Double {
        val s = sin(phi)
        return cos(phi) / sqrt(1 - e2 * s * s)
    }

    private fun q(phi: Double): Double {
        val s = sin(phi)
        val eS = e * s
        return (1 - e2) * (s / (1 - e2 * s * s) - (1 / (2 * e)) * ln((1 - eS) / (1 + eS)))
    }

    private data class Consts(val n: Double, val c: Double, val rho0: Double, val lon0: Double)

    private fun consts(p: Params): Consts {
        val lat0 = Math.toRadians(p.lat0Deg)
        val lon0 = Math.toRadians(p.lon0Deg)
        val lat1 = Math.toRadians(p.lat1Deg)
        val lat2 = Math.toRadians(p.lat2Deg)
        val m1 = m(lat1); val m2 = m(lat2)
        val q1 = q(lat1); val q2 = q(lat2)
        val n = if (abs(lat1 - lat2) < 1e-10) sin(lat1) else (m1 * m1 - m2 * m2) / (q2 - q1)
        val c = m1 * m1 + n * q1
        val rho0 = A * sqrt(c - n * q(lat0)) / n
        return Consts(n, c, rho0, lon0)
    }

    /** Albers metres → (lat, lon) degrees. */
    fun toWgs84(e: Double, n: Double, p: Params): Pair<Double, Double> {
        val (nn, c, rho0, lon0) = consts(p)
        var rho = sqrt((e - p.fe) * (e - p.fe) + (rho0 - (n - p.fn)) * (rho0 - (n - p.fn)))
        if (nn < 0) rho = -rho
        val theta = atan2(e - p.fe, rho0 - (n - p.fn))
        val qTarget = (c - (rho * nn / A) * (rho * nn / A)) / nn
        // Invert q(φ) by Newton iteration (dq/dφ closed form below); the
        // textbook series under-corrects by ~0.15° at these latitudes.
        var phi = asin((qTarget / 2).coerceIn(-1.0, 1.0))
        repeat(8) {
            val s = sin(phi)
            val dq = 2 * (1 - e2) * cos(phi) / ((1 - e2 * s * s) * (1 - e2 * s * s))
            phi -= (q(phi) - qTarget) / dq
        }
        val lambda = lon0 + theta / nn
        return Math.toDegrees(phi) to Math.toDegrees(lambda)
    }

    /** (lat, lon) degrees → Albers metres. */
    fun fromWgs84(latDeg: Double, lonDeg: Double, p: Params): Pair<Double, Double> {
        val (nn, c, rho0, lon0) = consts(p)
        val phi = Math.toRadians(latDeg)
        val lambda = Math.toRadians(lonDeg)
        val theta = nn * (lambda - lon0)
        val rho = A * sqrt(c - nn * q(phi)) / nn
        val e = p.fe + rho * sin(theta)
        val n = p.fn + rho0 - rho * cos(theta)
        return e to n
    }
}
