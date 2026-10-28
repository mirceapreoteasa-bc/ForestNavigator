package org.forestnav.app.map

/**
 * Collects the fixes that make up a recorded track.
 *
 * Fixed positions are kept in the project's working zone, because that is what the
 * surrounding layers use and what `LineStore` writes. The `.gpx` written beside them is
 * WGS84, as the format requires, so the GPS's own coordinates are always recoverable
 * without a projection being involved.
 *
 * Free of Android so the filtering can be tested directly. Three different reasons make a
 * fix unwanted, and all three have bitten real surveys:
 *
 *  - too soon: fixes arrive every couple of seconds and the interval is what the user set
 *  - too close: standing still would otherwise produce a very long line of duplicates
 *  - too far: a single wild jump would draw a line across the map that was never driven
 */
class TrackRecorder(
    private val intervalSec: Int = 10,
    private val minDistanceM: Double = 5.0,
    private val maxJumpM: Double = 1000.0,
    private val zone: Int = Utm.ASSUMED_ZONE,
    /**
     * Wall clock, used only for the timestamps written into the GPX.
     *
     * Kept separate from the stamp used for filtering because that one is monotonic
     * (elapsedRealtime) while a GPX time has to be a real date. Mixing the two is what
     * produced 1970 timestamps in an otherwise correct track.
     */
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
    private val kept = ArrayList<DoubleArray>()
    private var times = ArrayList<Long>()
    private var stamps = ArrayList<Long>()
    private var on = false

    var lastFixMs: Long = 0L
        private set

    /** True while recording. */
    val recording: Boolean get() = on

    /** Positions kept so far, in order. */
    val points: List<DoubleArray> get() = kept.toList()

    /** Fixes taken but not kept, for the summary shown when recording stops. */
    var skipped: Int = 0
        private set

    val count: Int get() = kept.size

    /** Begin a new track. Anything recorded before is discarded. */
    fun start() {
        kept.clear()
        times.clear()
        stamps.clear()
        skipped = 0
        lastFixMs = 0L
        on = true
    }

    fun stop() {
        on = false
    }

    /**
     * Offer one fix. Returns true when it was kept.
     *
     * The jump test runs before the distance test on purpose: a fix a kilometre away is a
     * bad reading, not a long leg, and letting it through would corrupt the line.
     */
    fun addFix(e: Double, n: Double, atMs: Long): Boolean {
        if (!on) return false
        if (e.isNaN() || n.isNaN() || e.isInfinite() || n.isInfinite()) { skipped++; return false }

        if (kept.isEmpty()) {
            accept(e, n, atMs)
            return true
        }
        if (atMs < lastFixMs) { skipped++; return false }
        if (atMs - lastFixMs < intervalSec * 1000L) { skipped++; return false }

        val d = distance(kept.last(), e, n)
        if (d > maxJumpM) { skipped++; return false }
        if (d < minDistanceM) { skipped++; return false }

        accept(e, n, atMs)
        return true
    }

    private fun accept(e: Double, n: Double, atMs: Long) {
        kept.add(doubleArrayOf(e, n))
        times.add(atMs)
        stamps.add(wallClock())
        lastFixMs = atMs
    }

    /** Recorded length in metres. */
    fun lengthM(): Double {
        var total = 0.0
        for (i in 1 until kept.size) total += distance(kept[i - 1], kept[i][0], kept[i][1])
        return total
    }

    /**
     * A GPX 1.1 file holding this track, in WGS84 degrees.
     *
     * Written whatever the project CRS, so the native fix is never lost: it is the one
     * artefact that does not depend on a projection being right.
     */
    fun gpx(name: String): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="Forest Navigator" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("  <metadata><name>").append(esc(name)).append("</name></metadata>").append('\n')
        sb.append("  <trk>").append('\n')
        sb.append("    <name>").append(esc(name)).append("</name>").append('\n')
        sb.append("    <trkseg>").append('\n')
        for (i in kept.indices) {
            val (lat, lon) = Utm.utmToLatLon(kept[i][0], kept[i][1], zone)
            sb.append("      <trkpt lat=\"").append(f(lat)).append("\" lon=\"").append(f(lon))
                .append("\"><ele>0</ele><time>")
                .append(isoUtc(stamps[i])).append("</time></trkpt>").append('\n')
        }
        sb.append("    </trkseg>").append('\n')
        sb.append("  </trk>").append('\n')
        sb.append("</gpx>").append('\n')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun f(v: Double) = "%.7f".format(v)

    private fun isoUtc(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(ms))

    companion object {
        /** Straight-line metres between two working-zone positions. */
        fun distance(a: DoubleArray, e: Double, n: Double): Double {
            val de = e - a[0]
            val dn = n - a[1]
            return Math.sqrt(de * de + dn * dn)
        }
    }
}