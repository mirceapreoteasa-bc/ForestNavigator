package org.forestnav.app.map

import kotlin.math.abs
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.gcap.core.ShpFormat
import org.gcap.core.nav.MapTransform
import org.gcap.core.nav.ZoomScale

/**
 * North-oriented UTM map canvas. The viewport always shows a fixed
 * ground rectangle whose extent doubles per zoom step:
 * scale 1:10000 -> 500m x 400m (default); 1:5000/2500 zoom in (halves),
 * 1:20000/40000 zoom out (doubles). Centred on the live GPS fix unless
 * [panning] is armed (finger drag translates the map, north stays up).
 *
 * Draws the 17 readout overlays of point_collector_ODS.ods over the map.
 */
class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    class MapLayer(
        val name: String,
        val color: Int,
        val widthPx: Float,
        val kind: Kind,
        val editable: Boolean,
    ) {
        /** Working CRS; WGS84 layers are converted to UTM metres at load. */
        var crs: Utm.Crs = Utm.Crs.UTM_METERS

        /**
         * The UTM zone this layer is authored in. A layer from a neighbouring zone is
         * still "UTM_METERS" but its eastings mean something else, so it has to be
         * reprojected rather than dropped straight into the working zone.
         */
        var crsZone: Int = Utm.ASSUMED_ZONE
        enum class Kind { LINE, POINT, POLYGON }
        var file: String = name
        private var _visible: Boolean = true
        var visible: Boolean
            get() = _visible
            set(v) { if (_visible != v) { _visible = v; dirty = true } }
        private var _shapes: List<ShpFormat.Shape> = emptyList()
        var shapes: List<ShpFormat.Shape>
            get() = _shapes
            set(v) { _shapes = v; dirty = true }
        var showAsBusy: Boolean = false

        /**
         * Drawn last, over everything else.
         *
         * Set for the emergency layers - a MOB has to be visible the moment it is
         * saved, and in a survey folder it would otherwise be buried under the block
         * polygons simply by appearing early in the layer sheet.
         */
        var alwaysOnTop: Boolean = false
        var labelField: String? = null
        var labelColor: Int = 0xFF1B1B1B.toInt()
        // Layer-settings styling (see LayerStyle): symbols, sizes in dp
        // (points: radius; lines/polygons: stroke width), label size in sp.
        var pointSymbol: PointSymbol = PointSymbol.CIRCLE
        var lineSymbol: LineSymbol = LineSymbol.SIMPLE
        /** Visibility from the project file or the default; AIR mode can add to it. */
        var baseVisible: Boolean = true
        var polySymbol: PolySymbol = PolySymbol.FILL
        var symbolSizeDp: Float = 2f
        var labelSizeSp: Float = 7f
        var borderColor: Int = color
        // Dirty-layer render cache (QField-style): only layers whose shapes,
        // style or viewport changed re-render; the rest blit their bitmap.
        var dirty: Boolean = true
        var cache: Bitmap? = null
        /** LRU stamp for [CACHE_BYTE_BUDGET] eviction. */
        var lastDrawn: Long = 0L
    }

    enum class PointSymbol { CIRCLE, TRIANGLE, SQUARE, DIAMOND }
    enum class LineSymbol { SIMPLE, DASH, DOT, DOUBLE_ROAD }
    enum class PolySymbol { FILL, HATCH_45, HATCH_NEG45, HATCH_V, HATCH_H, DOT, NOFILL }

    private val bgPaint = Paint().apply { color = 0xFFF7F5F0.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val polyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF444444.toInt(); strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textSize = 26f }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF444444.toInt(); textSize = 18f }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt(); textSize = 7f * resources.displayMetrics.scaledDensity
    }
    /** Per-frame allocations hoisted: onDraw runs at 1 Hz plus every fix and
     *  command, and allocating Paints/Paths there churns the GC on slow phones. */
    private val northStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt(); style = Paint.Style.STROKE
    }
    private val northFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1B1B.toInt() }
    private val northTri = Path()
    private val hlStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val hlFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD700.toInt(); style = Paint.Style.FILL
    }
    private val markerPaint = Paint().apply { color = Color.BLUE }
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE }
    private val gpsOvalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2200CC00; style = Paint.Style.FILL
    }
    /** Go-to target marker (blue ring + label, distinct from the red fix cross). */
    private val goToPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val goToFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x220000FF; style = Paint.Style.FILL
    }
    var goToShow: Boolean = false
    var goToE: Double = 0.0
    var goToN: Double = 0.0
    var goToText: String = ""

    fun setGoToMarker(e: Double, n: Double, label: String, show: Boolean) {
        goToE = e; goToN = n; goToText = label; goToShow = show; invalidate()
    }
    /** Point symbols: circle (dot), triangle, square, diamond. Size = radius dp. */
    private fun drawPointSymbol(canvas: Canvas, x: Float, y: Float, layer: MapLayer) {
        val r = maxOf(2f, layer.symbolSizeDp * resources.displayMetrics.density)
        when (layer.pointSymbol) {
            PointSymbol.CIRCLE -> canvas.drawCircle(x, y, r, pointPaint)
            PointSymbol.SQUARE -> canvas.drawRect(x - r, y - r, x + r, y + r, pointPaint)
            PointSymbol.DIAMOND -> {
                val path = Path().apply {
                    moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close()
                }
                canvas.drawPath(path, pointPaint)
            }
            PointSymbol.TRIANGLE -> {
                val path = Path().apply {
                    moveTo(x, y - r); lineTo(x + r, y + r); lineTo(x - r, y + r); close()
                }
                canvas.drawPath(path, pointPaint)
            }
        }
    }
    private val shaderCache = mutableMapOf<String, Shader>()

    /** Repeating hatch/dot tile shader, cached per (pattern, color, width). */
    private fun hatchShader(pattern: PolySymbol, color: Int, widthPx: Float): Shader {
        val d = resources.displayMetrics.density
        val key = "$pattern|$color|$widthPx|$d"
        return shaderCache.getOrPut(key) {
            val s = (8 * d).toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1f, widthPx)
                strokeCap = Paint.Cap.ROUND
            }
            val f = s.toFloat()
            when (pattern) {
                PolySymbol.HATCH_45 -> c.drawLine(0f, f, f, 0f, p)
                PolySymbol.HATCH_NEG45 -> c.drawLine(0f, 0f, f, f, p)
                PolySymbol.HATCH_V -> c.drawLine(f / 2f, 0f, f / 2f, f, p)
                PolySymbol.HATCH_H -> c.drawLine(0f, f / 2f, f, f / 2f, p)
                PolySymbol.DOT -> {
                    p.style = Paint.Style.FILL
                    c.drawCircle(f / 2f, f / 2f, maxOf(1.5f, widthPx), p)
                }
                else -> { }
            }
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
    private val gpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }

    /** Short red arrow: direction of travel. Filled so the head reads at map scales. */
    private val gpsHeadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt(); strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }

    /** Longer green arrow: direction to the navigation target. */
    private val gpsTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt(); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
    }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }

    /**
     * Coalesce redraw requests.
     *
     * The GPS callback fires repeatedly and each fix used to ask for an immediate
     * redraw. With the map drawn on the software canvas every frame is far more
     * expensive, so that saturated the UI thread and buttons stopped responding.
     * Requests now collapse into at most one frame per [REDRAW_INTERVAL_MS].
     */
    private var drawPending = false
    private var lastDrawAtMs = 0L

    fun requestDraw() {
        val now = android.os.SystemClock.uptimeMillis()
        val waited = now - lastDrawAtMs
        if (drawPending) return
        if (waited < REDRAW_INTERVAL_MS) {
            drawPending = true
            postDelayed({
                drawPending = false
                lastDrawAtMs = android.os.SystemClock.uptimeMillis()
                invalidate()
            }, REDRAW_INTERVAL_MS - waited)
            return
        }
        lastDrawAtMs = now
        invalidate()
    }

    /** Replace a plain [invalidate] in per-fix and per-sensor paths with this. */
    fun redrawSoon() = requestDraw()

    var started: Boolean = false

        set(v) { field = v; invalidate() }
    var editing: Boolean = false
    var editableLayerName: String? = null
        set(v) { field = v; invalidate() }

    companion object {
        /** Opens at the helicopter transit scale, per the user's spec. */
        val DEFAULT_SCALE_DENOMINATOR = org.gcap.core.nav.SpeedZoom.DEFAULT_SCALE

        /** Ceiling on redraw rate from GPS and sensor updates. */
        const val REDRAW_INTERVAL_MS = 120L
    }

    var centerE: Double = 0.0
    var centerN: Double = 0.0
    /**
     * Nominal zoom index. Normally driven by [org.gcap.core.nav.SpeedZoom] from GPS
     * speed; set directly when the user chooses a scale by hand.
     */
    var scaleDenominator: Int = DEFAULT_SCALE_DENOMINATOR
        set(v) { field = v; invalidate() }

    /**
     * Compass bearing held up the screen: 0 = north up, 90 = east up.
     *
     * Driven by [org.gcap.core.nav.OrientationController]. Rotation is applied about
     * the view centre by [org.gcap.core.nav.MapTransform], so the centre point does
     * not drift while the map turns.
     */
    var bearingDeg: Double = 0.0
        set(v) {
            val n = org.gcap.core.nav.MapTransform.normaliseBearing(v)
            if (field != n) { field = n; requestDraw() }
        }

    var gpsE: Double = 0.0
    var gpsN: Double = 0.0
    var gpsAcc: Double = 0.0
    var haveGps: Boolean = false

    private val pMarkers = mutableListOf<Triple<String, Double, Double>>()
    var showP1P2P3: Boolean = true

    var selected: Pair<Int, ShpFormat.Shape>? = null

    /**
     * Map readouts drawn over the canvas (point_collector_ODS.ods rows 7-18).
     * [overlayLeft] runs down the left edge, [overlayRight] down the right edge
     * under [overlayProject]; both are label/value pairs.
     */
    var overlayLeft: List<Pair<String, String>> = emptyList()
    var overlayRight: List<Pair<String, String>> = emptyList()
    var overlayProject: String = ""
    var showNorthArrow: Boolean = true

    /**
     * Pan mode. When false the host is following a position and a drag is ignored.
     * [MapActivity] clears this as soon as the user starts dragging, so panning works
     * without arming anything when not following.
     */
    var panning: Boolean = false
    private var deliveredLongPress = false

    /**
     * Fired on the first pixel of a drag, so the host can suspend map-following.
     * A block overview spans tens of kilometres; without this the view snaps back to
     * the GPS fix every couple of seconds and panning is impossible.
     */
    var onUserPan: (() -> Unit)? = null

    /**
     * Keep the view centre inside the loaded data.
     *
     * Panning is unbounded otherwise, and drifting off into blank space is how a user
     * ends up convinced they are lost. The centre is held within the data extent plus
     * a margin, so the edge of the block is reachable but the map cannot vanish.
     */
    var clampToDataExtent: Boolean = true

    /** Ground extent of all visible layers, or null if none are loaded. */
    fun dataExtent(): DoubleArray? {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        var any = false
        for (l in layers) {
            if (!l.visible) continue
            for (sh in l.shapes) {
                if (!sh.xmin.isFinite() || !sh.ymin.isFinite()) continue
                x0 = minOf(x0, sh.xmin); y0 = minOf(y0, sh.ymin)
                x1 = maxOf(x1, sh.xmax); y1 = maxOf(y1, sh.ymax)
                any = true
            }
        }
        if (!any) return null
        return doubleArrayOf(x0, y0, x1, y1)
    }

    /** Pull the centre back over the data if it has drifted away from it. */
    fun clampCentreToData() {
        if (!clampToDataExtent) return
        val ext = dataExtent() ?: return
        if (width <= 0 || height <= 0) return
        // allow half a viewport beyond the data so edge features stay reachable
        val vb = viewportBounds()
        val halfE = (vb[2] - vb[0]) / 2.0
        val halfN = (vb[3] - vb[1]) / 2.0
        val margin = maxOf(halfE, halfN)
        val minE = ext[0] - margin; val maxE = ext[2] + margin
        val minN = ext[1] - margin; val maxN = ext[3] + margin
        var changed = false
        if (centerE < minE) { centerE = minE; changed = true }
        if (centerE > maxE) { centerE = maxE; changed = true }
        if (centerN < minN) { centerN = minN; changed = true }
        if (centerN > maxN) { centerN = maxN; changed = true }
        if (changed) requestDraw()
    }

    /** "Loading layers… 7/23" during the initial Start_MAP batch, else "". */
    var loadProgress: String = ""

    /** Fired after each pan step so the host can reload the viewport. */
    var onPan: (() -> Unit)? = null
        set(v) { field = v; invalidate() }

    var onMapTap: ((e: Double, n: Double) -> Unit)? = null

    /**
     * Draw the map in cut mode.
     *
     * A visible change of state matters here more than usual: the next tap removes a road,
     * so the screen has to say so before the tap rather than after.
     */
    var cutCursor: Boolean = false

    /** How many cuts are staged and not yet written. Shown so none is forgotten. */
    var cutPending: Int = 0

    /**
     * Fired when the user holds still on one spot for [longPressMs].
     *
     * A crew on the ground sets a destination by pointing at where they want to go,
     * and the way to point at a spot on a map is to hold a finger on it. Tapping is
     * for choosing things that are already there; holding is for saying "here".
     */
    var onLongPress: ((e: Double, n: Double) -> Unit)? = null

    /** How long a finger must hold still before it counts as "here". */
    var longPressMs: Long = 6_000L

    val layers = mutableListOf<MapLayer>()

    /**
     * The layers in the order they should be drawn.
     *
     * Rebuilt only when a layer is added or its [MapLayer.alwaysOnTop] changes, so the
     * per-frame cost is a list read rather than a sort.
     */
    private var drawOrder: List<MapLayer> = emptyList()

    private fun refreshDrawOrder() {
        drawOrder = layers.sortedBy { if (it.alwaysOnTop) 1 else 0 }
    }

    /** Layers with at least this many shapes get a cached bitmap (bounds memory). */
    private val CACHE_MIN_SHAPES = 30

    /**
     * Longest a single frame may spend re-rasterising layers. Android declares the app
     * unresponsive if input waits about five seconds, so a frame is capped well below
     * that and the remainder is finished on the next one.
     */
    private val FRAME_BUDGET_MS = 90L
    private var renderDeferred = false


    /**
     * Global cap on cached layer bitmaps. A full-screen ARGB_8888 layer is
     * ~5 MB at 1080x1200, so an unbounded per-layer cache OOM-kills a 3 GB
     * phone mid-pan (the process dies and the map comes back "not started").
     * Over budget, the least-recently-drawn caches are recycled; they simply
     * re-render on the next frame.
     */
    private val CACHE_BYTE_BUDGET = 40L * 1024 * 1024
    private var drawTick = 0L

    private fun cachedBytes(): Long =
        layers.sumOf { (it.cache?.allocationByteCount ?: 0).toLong() }

    private fun recycleLayer(l: MapLayer) {
        l.cache?.recycle()
        l.cache = null
    }

    /** Free cached bitmaps until the budget is met; never touches [keep]. */
    private fun trimCaches(keep: MapLayer? = null) {
        var total = cachedBytes()
        if (total <= CACHE_BYTE_BUDGET) return
        for (l in layers.sortedBy { it.lastDrawn }) {
            if (total <= CACHE_BYTE_BUDGET) break
            if (l === keep) continue
            val b = l.cache ?: continue
            total -= b.allocationByteCount.toLong()
            recycleLayer(l)
        }
    }

    /** Release every cached bitmap (Close_MAP, or leaving the map). */
    fun releaseCaches() {
        for (l in layers) recycleLayer(l)
    }

    private var vpE = Double.NaN
    private var vpN = Double.NaN
    private var vpScale = 0
    private var vpBearing = Double.NaN

    fun addLayer(layer: MapLayer) {
        layers.add(layer)
        refreshDrawOrder()
        invalidate()
    }
    fun removeLayer(name: String) {
        val doomed = layers.filter { it.name == name }
        layers.removeAll(doomed)
        for (l in doomed) recycleLayer(l)
        invalidate()
    }
    fun setP1P2P3(markers: List<Triple<String, Double, Double>>) { pMarkers.clear(); pMarkers.addAll(markers); invalidate() }

    // ---- raster basemap ----

    /**
     * The decoded orthophoto for the current view, and the ground area it covers.
     *
     * Held as a screen-sized bitmap rather than an image pyramid: the decode is done
     * by the host on a background thread, and keeping one screenful is what allows a
     * raster hundreds of megabytes on disk to be drawn at all.
     */
    var rasterBitmap: android.graphics.Bitmap? = null
        set(v) { field = v; invalidate() }
    private var rasterBounds: DoubleArray? = null
    private var rasterStamp: String? = null

    /**
     * Set the decoded basemap, tagged with what produced it.
     *
     * The stamp is what stops a decode that finished after the crew had already moved
     * on from being drawn in the wrong place: a stale tile is worse than none, because
     * it looks like real ground.
     */
    fun setRaster(bitmap: android.graphics.Bitmap?, bounds: DoubleArray?, stamp: String?) {
        if (stamp != rasterStamp && rasterStamp != null && stamp != null) {
            // the view has moved on since this was decoded; refuse it
            return
        }
        rasterBitmap = bitmap
        rasterBounds = bounds
        rasterStamp = stamp
        invalidate()
    }

    /** True when the cached basemap covers [world]. */
    fun rasterCovers(world: DoubleArray): Boolean {
        val b = rasterBounds ?: return false
        return world[0] >= b[0] && world[2] <= b[2] && world[1] >= b[1] && world[3] <= b[3]
    }

    private fun drawRaster(canvas: Canvas) {
        val bmp = rasterBitmap ?: return
        val b = rasterBounds ?: return
        val t = transform()
        val p0 = t.worldToScreen(b[0], b[3])
        val p1 = t.worldToScreen(b[2], b[1])
        if (!p0[0].isFinite() || !p1[0].isFinite()) return
        // the bitmap was decoded for a sub-window of the screen; scale it into place
        val dest = android.graphics.RectF(
            p0[0].toFloat(), p0[1].toFloat(), p1[0].toFloat(), p1[1].toFloat(),
        )
        canvas.drawBitmap(bmp, null, dest, bgPaint.apply { isFilterBitmap = true })
    }

    // ---- navigation route overlay ----

    /**
     * The line being driven towards the destination, in world coordinates as
     * `e0,n0,e1,n1,...`, or empty when not navigating.
     *
     * Drawn in its own right rather than left to the crew's memory: on the ground the
     * route is the thing being followed, and it has to stay legible over any layer
     * underneath it, which is why it carries a pale casing under the colour.
     */
    private var navRoutePoints = DoubleArray(0)

    /** Why the route looks the way it does, e.g. that the destination was snapped onto it. */
    private var navRouteNote = ""

    private fun drawNavRoute(canvas: Canvas) {
        if (navRoutePoints.size < 4) return
        val d = resources.displayMetrics.density
        val t = transform()
        val path = android.graphics.Path()
        var started = false
        for (i in 0 until navRoutePoints.size / 2) {
            val s = t.worldToScreen(navRoutePoints[i * 2], navRoutePoints[i * 2 + 1])
            if (!s[0].isFinite() || !s[1].isFinite()) continue
            if (!started) { path.moveTo(s[0].toFloat(), s[1].toFloat()); started = true }
            else path.lineTo(s[0].toFloat(), s[1].toFloat())
        }
        if (!started) return
        // Widths in pixels rather than density units: the layers underneath are 3 px, so
        // the route has to be plainly heavier than them on every screen. At 3.4 dp it
        // was about the same weight as the road it follows and disappeared into it,
        // which is the opposite of what a crew is looking for. Now a 8 px line inside a
        // 13 px pale casing - wider than any layer, and legible over any of them.
        // The casing first, so the line survives being drawn over a same-coloured layer.
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 13f
                color = 0xF2FFFFFF.toInt()
            },
        )
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 8f
                // amber, not the magenta of the flight plan's next transect: a driver
                // following one must never mistake the other for the line they are on
                color = 0xFFFF6D00.toInt()
            },
        )
    }

    /** Show the route being followed. Pass an empty array to clear it. */
    fun setNavRoute(points: DoubleArray, note: String = "") {
        navRoutePoints = points
        navRouteNote = note
        invalidate()
    }

    fun clearNavRoute() = setNavRoute(DoubleArray(0))

    // ---- flight plan overlay ----

    /** How a plan line is drawn. */
    enum class PlanStyle {
        /** Repositioning between triples: a plain line, because no path is recorded. */
        TRANSIT,

        /** An observation transect, drawn along the flight path. */
        TRANSECT,

        /** The transect the crew is on or heading for, picked out. */
        NEXT,
    }

    /** One polyline of the flight plan, in world coordinates as `e0,n0,e1,n1,...`. */
    data class PlanLine(val points: DoubleArray, val style: PlanStyle)

    private var planLines: List<PlanLine> = emptyList()
    private var planMarkers: List<Triple<String, Double, Double>> = emptyList()
    private var planNote: String = ""

    /** Call names belonging to the transect being flown; always labelled. */
    private var planNextNames: Set<String> = emptySet()

    /**
     * Show the flight plan: the transects to fly, the transits between them, and the next
     * one highlighted.
     *
     * This is drawn rather than left to the crew's memory because a helicopter that has
     * just circled a transect two or three times has lost its bearings, and the plan is
     * what orients the pilot and passengers to the next point.
     */
    fun setFlightPlan(
        lines: List<PlanLine>,
        markers: List<Triple<String, Double, Double>> = emptyList(),
        note: String = "",
        nextNames: Set<String> = emptySet(),
    ) {
        planLines = lines
        planMarkers = markers
        planNote = note
        planNextNames = nextNames
        invalidate()
    }

    fun clearFlightPlan() = setFlightPlan(emptyList())

    private fun drawFlightPlan(canvas: Canvas) {
        if (planLines.isEmpty()) return
        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        val transitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
            color = 0xFF7A8699.toInt()          // muted: shown, not followed
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * d, 5f * d), 0f)
        }
        val transectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * d
            color = 0xFF1E88E5.toInt()
        }
        val nextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * d
            color = 0xFFD81B60.toInt()
        }
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * sp; color = 0xFF1B1B1B.toInt(); textAlign = Paint.Align.CENTER
        }
        val path = android.graphics.Path()

        for (line in planLines) {
            if (line.points.size < 4) continue
            path.rewind()
            val t = transform()
            for (i in 0 until line.points.size / 2) {
                val s = t.worldToScreen(line.points[i * 2], line.points[i * 2 + 1])
                // the plan is world data, so drop anything behind the camera
                if (!s[0].isFinite() || !s[1].isFinite()) continue
                if (i == 0) path.moveTo(s[0].toFloat(), s[1].toFloat())
                else path.lineTo(s[0].toFloat(), s[1].toFloat())
            }
            canvas.drawPath(
                path,
                when (line.style) {
                    PlanStyle.TRANSIT -> transitPaint
                    PlanStyle.TRANSECT -> transectPaint
                    PlanStyle.NEXT -> nextPaint
                },
            )
        }

        val t = transform()
        // Declutter the labels. At a zoomed-out scale the A and B calls of a transect sit
        // almost on top of each other and their labels overprint into nonsense like
        // "148AB", which is worse than no label at all. Labels are dropped when they would
        // collide, except for the next transect, which is always labelled because that is
        // the one the crew is looking for.
        val placed = ArrayList<FloatArray>()
        val nextNames = planNextNames
        for ((name, e, n) in planMarkers) {
            val s = t.worldToScreen(e, n)
            if (!s[0].isFinite() || !s[1].isFinite()) continue
            // markers well outside the viewport are not worth drawing at all
            if (s[0] < -40 || s[0] > width + 40 || s[1] < -40 || s[1] > height + 40) continue
            val isNext = name in nextNames
            var collides = false
            for (p in placed) {
                if (abs(p[0] - s[0]) < 44f * d && abs(p[1] - s[1]) < 15f * d) { collides = true; break }
            }
            markerPaint.color = if (isNext) 0xFFD81B60.toInt() else 0xFF1E88E5.toInt()
            canvas.drawCircle(s[0].toFloat(), s[1].toFloat(), 3.5f * d, markerPaint)
            if (isNext || !collides) {
                canvas.drawText(name, s[0].toFloat(), s[1].toFloat() - 6f * d, labelPaint)
                placed.add(floatArrayOf(s[0].toFloat(), s[1].toFloat()))
            }
        }

        if (planNote.isNotEmpty()) {
            val np = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 13f * sp
                color = 0xFF1B1B1B.toInt()
                textAlign = Paint.Align.CENTER
                style = Paint.Style.FILL
            }
            val tw = np.measureText(planNote)
            val bx = (width - tw) / 2f - 10f * d
            canvas.drawRoundRect(bx, 8f * d, bx + tw + 20f * d, 34f * d, 6f * d, 6f * d, barFill)
            canvas.drawText(planNote, width / 2f, 26f * d, np)
        }
    }
    fun setGpsMarker(e: Double, n: Double, acc: Double, have: Boolean) {
        gpsE = e; gpsN = n; gpsAcc = acc; haveGps = have; requestDraw()
    }

    /**
     * Bearing of travel, or NaN when there is not enough movement to judge it.
     * Drawn as the short red arrow: which way the phone is going, which is the thing a
     * crew checks when the map stops agreeing with them.
     */
    var gpsHeadingDeg: Double? = null

    /**
     * Bearing to whatever is being navigated to, or NaN when there is no target.
     * Drawn as the longer green arrow, so the two directions can be read apart at a
     * glance instead of being inferred from one ambiguous marker.
     */
    var targetBearingDeg: Double? = null

    /**
     * An arrow from the fix along a world bearing, drawn in screen space.
     *
     * The bearing is measured from north clockwise, while the screen measures from the
     * +x axis with y downward, and the map itself may be rotated by compass mode. Taking
     * the bearing off [MapTransform.screenAxisBearing] gets all three right at once.
     */
    private fun drawArrow(
        canvas: Canvas,
        x: Float,
        y: Float,
        bearingDeg: Double?,
        lengthPx: Float,
        paint: Paint,
    ) {
        if (bearingDeg == null || !bearingDeg.isFinite()) return
        val t = transform()
        val rel = Math.toRadians(
            org.gcap.core.nav.MapTransform.normaliseBearing(bearingDeg - t.screenAxisBearing()),
        )
        val dx = kotlin.math.sin(rel).toFloat()
        val dy = -kotlin.math.cos(rel).toFloat()
        val tipX = x + dx * lengthPx
        val tipY = y + dy * lengthPx
        canvas.drawLine(x, y, tipX, tipY, paint)
        // a head, so the direction is unambiguous at a glance rather than only in motion
        val head = lengthPx * 0.34f
        val back = head * 0.8f
        val path = android.graphics.Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - (dx * head + dy * back), tipY - (dy * head - dx * back))
            lineTo(tipX - (dx * head - dy * back), tipY - (dy * head + dx * back))
            close()
        }
        canvas.drawPath(path, paint)
    }

    /**
     * Ground metres spanned by the view's width at the nominal scale.
     *
     * The nominal convention is kept deliberately: 500 m across at 1:10,000, so the
     * scale list in settings behaves exactly as before. What changed is that this is
     * now a *width*, and the vertical extent follows from the view's real aspect
     * ratio. Previously east and north had independent metres-per-pixel, which
     * stretched the map whenever the view was not 500x400 m and would have sheared
     * it entirely once rotation was introduced.
     */
    private fun groundWidthM(): Double = ZoomScale.groundWidthM(scaleDenominator)

    /** Ground rectangle currently visible, isotropic. */
    fun windowMeters(): Pair<Double, Double> {
        return ZoomScale.windowMeters(scaleDenominator, width, height)
    }

    /**
     * The rotation-capable transform. Rebuilt per call rather than cached because it
     * depends on the measured view size, which is not known until layout.
     */
    fun transform(): MapTransform {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val mpp = ZoomScale.metresPerPixel(scaleDenominator, w)
        return MapTransform(centerE, centerN, mpp, bearingDeg, w, h)
    }

    /** True physical scale denominator for the current viewport and screen density. */
    fun physicalScaleDenominator(): Double =
        ZoomScale.physicalScaleDenominator(
            ZoomScale.metresPerPixel(scaleDenominator, width.coerceAtLeast(1)),
            resources.displayMetrics.densityDpi.toDouble(),
        )

    private fun worldToScreen(e: Double, n: Double, out: FloatArray) {
        val p = transform().worldToScreen(e, n)
        out[0] = p[0].toFloat()
        out[1] = p[1].toFloat()
    }

    fun screenToWorld(x: Float, y: Float): DoubleArray =
        transform().screenToWorld(x.toDouble(), y.toDouble())

    /**
     * Ground bounding box of the visible area, accounting for rotation.
     *
     * Rotating the view exposes corners that a naive axis-aligned rectangle around the
     * centre would miss, so the four screen corners are mapped back through the
     * inverse transform.
     */
    fun viewportBounds(): DoubleArray = transform().viewportBounds()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        if (!started) {
            canvas.drawText("  GPS_MAP  (Start_MAP)", 16f, h / 2 - 30f, textPaint)
            canvas.drawText("  Press Start_MAP to load the layers", 16f, h / 2 + 20f, smallText)
            return
        }

        // north-oriented frame
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, framePaint)

        // Shared screen-point scratch for the overlay sections below.
        val p1 = FloatArray(2)

        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        // View rect in ground metres (+10 m for stroke widths); shapes fully
        // outside are skipped — at 1:10000 the viewport holds a fraction of a
        // big layer, and pathing everything every second saturates a slow phone.
        val vb = viewportBounds()
        val vx0 = vb[0] - 10.0; val vx1 = vb[2] + 10.0
        val vy0 = vb[1] - 10.0; val vy1 = vb[3] + 10.0
        // QField-style dirty-layer cache: only layers whose shapes, style or
        // viewport changed re-render; the rest blit their bitmap. A viewport
        // change invalidates all. Small layers (< CACHE_MIN_SHAPES) skip the
        // cache (cheap to redraw) to bound memory on 3 GB phones.
        if (centerE != vpE || centerN != vpN || scaleDenominator != vpScale || bearingDeg != vpBearing) {
            vpE = centerE; vpN = centerN; vpScale = scaleDenominator; vpBearing = bearingDeg
            for (l in layers) l.dirty = true
        }
        if (drawOrder.size != layers.size) refreshDrawOrder()
        drawRaster(canvas)

        // Rasterising every layer in one pass can take longer than the input timeout on a
        // slow phone drawing on the software canvas, which shows up as "app isn't
        // responding". So each frame spends at most [FRAME_BUDGET_MS] re-rendering and then
        // asks for another frame to finish the rest. A stale cache is blitted in the
        // meantime, so the map still appears immediately and sharpens over a few frames.
        val frameDeadline = android.os.SystemClock.uptimeMillis() + FRAME_BUDGET_MS
        renderDeferred = false

        for (layer in drawOrder) {
            if (!layer.visible) continue
            val bmp = layer.cache
            val big = layer.shapes.size >= CACHE_MIN_SHAPES
            val needsRender = !big || layer.dirty || bmp == null || bmp.width != width || bmp.height != height
            if (big && needsRender) {
                // out of time: blit whatever is cached and come back next frame
                if (android.os.SystemClock.uptimeMillis() > frameDeadline) {
                    bmp?.takeIf { it.width == width && it.height == height }
                        ?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                    if (renderDeferred) return
                    renderDeferred = true
                    postInvalidateDelayed(FRAME_BUDGET_MS)
                    return
                }
                // Free room before allocating a new full-screen bitmap.
                trimCaches(keep = layer)
                val b = if (bmp != null && bmp.width == width && bmp.height == height) bmp
                else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val bc = Canvas(b)
                // Clear the cache bitmap before re-rendering: otherwise a
                // pan/zoom draws the new shapes over the old ones and they
                // trail across the screen. Only ever on the cache bitmap —
                // clearing the real canvas would erase the whole map.
                bc.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                renderLayer(bc, layer)
                layer.cache = b
                layer.dirty = false
                canvas.drawBitmap(b, 0f, 0f, null)
            } else if (needsRender) {
                renderLayer(canvas, layer)
            } else {
                layer.lastDrawn = ++drawTick
                canvas.drawBitmap(bmp, 0f, 0f, null)
            }
        }

        // selected feature highlight
        selected?.let { (li, s) ->
            if (li in layers.indices) {
                for (part in s.parts) for (pt in part) {
                    worldToScreen(pt[0], pt[1], p1)
                    canvas.drawCircle(p1[0], p1[1], 8f, hlFill)
                    canvas.drawCircle(p1[0], p1[1], 8f, hlStroke)
                }
            }
        }

        // P1/P2/P3 markers
        if (showP1P2P3) {
            for ((label, e, n) in pMarkers) {
                worldToScreen(e, n, p1)
                canvas.drawCircle(p1[0], p1[1], 10f, markerPaint)
                canvas.drawText(label, p1[0] + 14f, p1[1] - 14f, markerText)
            }
        }

        // GPS marker + accuracy
        if (haveGps) {
            worldToScreen(gpsE, gpsN, p1)
            if (gpsAcc > 0) {
                val (we, hn) = windowMeters()
                val rE = ((gpsAcc) / (we / width)).toFloat()
                val rN = ((gpsAcc) / (hn / height)).toFloat()
                canvas.drawOval(RectF(p1[0] - rE, p1[1] - rN, p1[0] + rE, p1[1] + rN), gpsOvalPaint)
            }
            canvas.drawLine(p1[0] - 16f, p1[1], p1[0] + 16f, p1[1], gpsPaint)
            canvas.drawLine(p1[0], p1[1] - 16f, p1[0], p1[1] + 16f, gpsPaint)
            // Two arrows, because "where am I" and "which way am I going" were one
            // crosshair and neither could be read off it. Red is the short one, the
            // direction the phone is travelling; green is the longer one, towards the
            // navigation target. Lengths are in pixels, not density units, because these
            // have to be legible on a small screen as well as a large one.
            drawArrow(canvas, p1[0], p1[1], gpsHeadingDeg, 28f, gpsHeadingPaint)
            drawArrow(canvas, p1[0], p1[1], targetBearingDeg, 52f, gpsTargetPaint)
        }

        // Go-to target marker
        if (goToShow) {
            worldToScreen(goToE, goToN, p1)
            canvas.drawCircle(p1[0], p1[1], 12f, goToFill)
            canvas.drawCircle(p1[0], p1[1], 12f, goToPaint)
            if (goToText.isNotEmpty()) {
                canvas.drawText(goToText, p1[0] + 16f, p1[1] - 12f, labelHalo)
                canvas.drawText(goToText, p1[0] + 16f, p1[1] - 12f, labelPaint)
            }
        }

        drawNavRoute(canvas)
        drawFlightPlan(canvas)
        drawOverlays(canvas)
    }

    /**
     * Render one layer's shapes onto [c] — draw-time simplification (sub-pixel
     * vertices dropped) and label gating (on-screen only). Called for dirty
     * layers; clean cached layers blit their bitmap instead.
     */
    private fun renderLayer(c: Canvas, layer: MapLayer) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        val vb = viewportBounds()
        val vx0 = vb[0] - 10.0; val vx1 = vb[2] + 10.0
        val vy0 = vb[1] - 10.0; val vy1 = vb[3] + 10.0
        val dashFx = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
        val dotFx = DashPathEffect(floatArrayOf(1f, 7 * d), 0f)
        val path = Path()
        val p1 = FloatArray(2); val p2 = FloatArray(2)
        val simplifyPx = 1.0f * d
        val strokePx = maxOf(1f, layer.symbolSizeDp * d)
        linePaint.color = layer.borderColor
        linePaint.strokeWidth = strokePx
        linePaint.pathEffect = when (layer.lineSymbol) {
            LineSymbol.DASH -> dashFx
            LineSymbol.DOT -> dotFx
            else -> null
        }
        linePaint.strokeCap = Paint.Cap.ROUND
        pointPaint.color = layer.color
        labelPaint.color = layer.labelColor
        labelPaint.textSize = layer.labelSizeSp * sp
        for (s in layer.shapes) {
            if (s.xmax < vx0 || s.xmin > vx1 || s.ymax < vy0 || s.ymin > vy1) continue
            for (part in s.parts) {
                path.rewind()
                var first = true
                var prevX = 0f; var prevY = 0f
                for (i in part.indices) {
                    worldToScreen(part[i][0], part[i][1], if (first) p1 else p2)
                    val px = if (first) p1[0] else p2[0]
                    val py = if (first) p1[1] else p2[1]
                    if (first) {
                        path.moveTo(px, py)
                        prevX = px; prevY = py
                        first = false
                    } else {
                        val dx = px - prevX; val dy = py - prevY
                        if (dx * dx + dy * dy >= simplifyPx * simplifyPx) {
                            path.lineTo(px, py)
                            prevX = px; prevY = py
                        }
                    }
                }
                if (layer.kind == MapLayer.Kind.LINE) {
                    if (layer.lineSymbol == LineSymbol.DOUBLE_ROAD) {
                        linePaint.color = layer.borderColor
                        linePaint.strokeWidth = strokePx + 2.5f * d
                        linePaint.pathEffect = null
                        c.drawPath(path, linePaint)
                        linePaint.color = 0xFFF7F5F0.toInt()
                        linePaint.strokeWidth = strokePx
                        c.drawPath(path, linePaint)
                    } else {
                        c.drawPath(path, linePaint)
                    }
                } else if (layer.kind == MapLayer.Kind.POLYGON) {
                    path.close()
                    when (layer.polySymbol) {
                        PolySymbol.NOFILL -> { /* border only below */ }
                        PolySymbol.FILL -> {
                            polyFillPaint.shader = null
                            polyFillPaint.color = (layer.color and 0x00FFFFFF.toInt()) or 0x80000000.toInt()
                            c.drawPath(path, polyFillPaint)
                        }
                        else -> {
                            polyFillPaint.shader = hatchShader(layer.polySymbol, layer.color, strokePx)
                            c.drawPath(path, polyFillPaint)
                            polyFillPaint.shader = null
                        }
                    }
                    linePaint.color = layer.borderColor
                    linePaint.strokeWidth = strokePx
                    linePaint.pathEffect = null
                    c.drawPath(path, linePaint)
                } else {
                    if (part.isNotEmpty()) {
                        worldToScreen(part[0][0], part[0][1], p1)
                        drawPointSymbol(c, p1[0], p1[1], layer)
                        if (s.label.isNotEmpty() && p1[0] in 0f..w && p1[1] in 0f..h) {
                            labelPaint.color = layer.labelColor
                            c.drawText(s.label, p1[0] + 7f, p1[1] - 9f, labelHalo)
                            c.drawText(s.label, p1[0] + 7f, p1[1] - 9f, labelPaint)
                        }
                    }
                }
            }
            if (layer.kind == MapLayer.Kind.POLYGON && s.label.isNotEmpty()) {
                worldToScreen((s.xmin + s.xmax) / 2.0, (s.ymin + s.ymax) / 2.0, p1)
                if (p1[0] in 0f..w && p1[1] in 0f..h) {
                    labelPaint.color = layer.labelColor
                    c.drawText(s.label, p1[0] + 7f, p1[1] - 9f, labelHalo)
                    c.drawText(s.label, p1[0] + 7f, p1[1] - 9f, labelPaint)
                }
            }
        }
    }

    /** Right-aligned twin of [smallText] for the right-hand readout column. */
    private val rightText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt()
        textAlign = Paint.Align.RIGHT
    }

    /** Label/value columns + north arrow (the 17 map texts of the spec sheet). */

    private fun drawOverlays(canvas: Canvas) {
        if (!started) return
        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        smallText.textSize = 11f * sp
        smallText.color = 0xFF1B1B1B.toInt()
        rightText.textSize = 11f * sp
        rightText.color = 0xFF1B1B1B.toInt()
        val pad = 6f * d
        val lineH = smallText.textSize * 1.3f
        val w = width.toFloat()

        // Load progress banner, centred near the top — a 60 s load with no
        // feedback looks like a hang.
        if (loadProgress.isNotEmpty()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 15f * sp; color = 0xFF1B1B1B.toInt()
                textAlign = Paint.Align.CENTER
            }
            val tw = tp.measureText(loadProgress)
            val th = 22f * d
            val bx = (w - tw) / 2f - 8f * d
            val by = 26f * d
            canvas.drawRoundRect(bx, by, bx + tw + 16f * d, by + th, 6f * d, 6f * d, barFill)
            canvas.drawText(loadProgress, w / 2f, by + th * 0.72f, tp)
        }

        // Cut mode banner. The next tap removes a stretch of road, so the screen has to
        // say that before the tap happens, not report it afterwards.
        if (cutCursor) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 15f * sp; color = 0xFF7A1010.toInt()
                textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
            // Wording comes from EditMode so the tested rule and the drawn banner cannot
            // drift apart - a banner promising something the guard does not enforce is
            // worse than no banner.
            val msg = org.forestnav.app.map.EditMode.lineBanner(true, cutPending) ?: return
            val tw = tp.measureText(msg)
            val th = 22f * d
            val bx = (w - tw) / 2f - 10f * d
            val by = 54f * d
            val fill = Paint().apply { color = 0xFFFDE8E8.toInt() }
            val edge = Paint().apply {
                color = 0xFFC0392B.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2f * d
            }
            canvas.drawRoundRect(bx, by, bx + tw + 20f * d, by + th, 6f * d, 6f * d, fill)
            canvas.drawRoundRect(bx, by, bx + tw + 20f * d, by + th, 6f * d, 6f * d, edge)
            canvas.drawText(msg, w / 2f, by + th * 0.72f, tp)
        }

        var y = pad + smallText.textSize
        for ((k, v) in overlayLeft) {
            canvas.drawText("$k  $v", pad, y, smallText)
            y += lineH
        }

        var yr = pad + smallText.textSize
        if (overlayProject.isNotEmpty()) {
            canvas.drawText(overlayProject, w - pad, yr, rightText)
            yr += lineH
        }
        if (showNorthArrow) {
            drawNorthArrow(canvas, w - pad - 12f * d, yr + 14f * d, 11f * d)
            // Keep Go to / Bearing / Distance clear of the arrow graphic.
            yr += 34f * d + 2 * lineH
        }
        for ((k, v) in overlayRight) {
            canvas.drawText("$k  $v", w - pad, yr, rightText)
            yr += lineH
        }

        // Editing state only while an edit session is open (not one of the 17).
        if (editing) {
            smallText.color = 0xFF1B6B1B.toInt()
            canvas.drawText("EDIT ${editableLayerName ?: "none"}", pad, height - 10f * d, smallText)
            smallText.color = 0xFF1B1B1B.toInt()
        }
    }

    /**
     * North arrow under the project name. It points wherever north actually is, which
     * is only "up" while the map is north-oriented; once the map rotates this is the
     * only cue telling the user which way is north.
     */
    private fun drawNorthArrow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        northStroke.strokeWidth = 2f * resources.displayMetrics.density
        val save = canvas.save()
        canvas.rotate(bearingDeg.toFloat(), cx, cy)
        rightText.textAlign = Paint.Align.CENTER
        canvas.drawText("N", cx, cy - r - 6f, rightText)
        rightText.textAlign = Paint.Align.RIGHT
        northTri.rewind()
        northTri.moveTo(cx, cy - r)
        northTri.lineTo(cx - r * 0.55f, cy + r * 0.35f)
        northTri.lineTo(cx + r * 0.55f, cy + r * 0.35f)
        northTri.close()
        canvas.drawPath(northTri, northFill)
        canvas.drawLine(cx, cy + r * 0.45f, cx, cy + r * 1.5f, northStroke)
        canvas.restoreToCount(save)
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /**
     * Tap (quick touch, no pan mode) selects/adds; in [panning] mode a drag
     * translates the map and never taps. Follow mode ignores drags — the map
     * stays centred on the fix / Go-to point until Pan is armed.
     *
     * A hold that does not move for [longPressMs] is offered to the host as a
     * long press, which is how a crew says "take me to this spot" without having
     * to find it in a layer first.
     */
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!started) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                panning = false
                if (onLongPress != null) {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    val r = Runnable {
                        val w = screenToWorld(downX, downY)
                        onLongPress?.invoke(w[0], w[1])
                    }
                    longPressRunnable = r
                    longPressHandler.postDelayed(r, longPressMs)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // a drag means the user wants to look somewhere; tell the host once
                // so it stops snapping the view back to the GPS fix
                if (!panning &&
                    kotlin.math.hypot(event.x - downX, event.y - downY) >
                    android.view.ViewConfiguration.get(context).scaledTouchSlop
                ) {
                    panning = true
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    onUserPan?.invoke()
                }
                if (!panning) return true
                // Drag the ground under the finger. Going through the inverse
                // transform keeps panning correct at any bearing, where a raw
                // pixel-to-metre step would drift as the map turns.
                val t = transform()
                val mpp = t.metresPerPixel
                val a = t.screenToWorld(lastX.toDouble(), lastY.toDouble())
                val b = t.screenToWorld(event.x.toDouble(), event.y.toDouble())
                centerE += a[0] - b[0]
                centerN += a[1] - b[1]
                clampCentreToData()
                lastX = event.x; lastY = event.y
                invalidate()
                onPan?.invoke()
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                longPressRunnable = null
                if (!panning) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.hypot(event.x - downX, event.y - downY) <= slop) {
                        val w = screenToWorld(event.x, event.y)
                        onMapTap?.invoke(w[0], w[1])
                    }
                }
                return true
            }
        }
        return true
    }
}