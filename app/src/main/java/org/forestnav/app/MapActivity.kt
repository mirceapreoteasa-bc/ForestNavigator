package org.forestnav.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.forestnav.app.map.LayerStyle
import org.forestnav.app.map.MapCanvasView
import org.forestnav.app.map.SafMapDir
import org.forestnav.app.map.Albers
import org.forestnav.app.map.Utm
import org.gcap.core.ShpFormat
import org.gcap.core.nav.Geodesy
import org.gcap.core.nav.NetworkBuilder
import org.gcap.core.nav.NetworkWriter
import org.gcap.core.nav.OneWay
import org.gcap.core.nav.RouteClass
import org.gcap.core.nav.SourceLine
import org.gcap.core.verify.CrsClassifier
import org.gcap.core.verify.CrsVerdict
import org.gcap.core.verify.LayerSource
import org.gcap.core.verify.LayerVerifier
import org.gcap.core.nav.HeadingEstimator
import org.gcap.core.nav.HeadingState
import org.gcap.core.nav.OrientationController
import org.gcap.core.nav.OrientationMode
import org.gcap.core.nav.OrientationReason
import org.gcap.core.nav.AirCall
import org.gcap.core.nav.FlightPlan
import org.gcap.core.nav.FlightPlanSession
import org.gcap.core.nav.PlanLeg
import org.gcap.core.nav.PlanPhase
import org.gcap.core.nav.MainGrid
import org.forestnav.app.map.NavSettings
import org.forestnav.app.map.OdsTable
import org.forestnav.app.map.SettingsResolver
import org.gcap.core.nav.NavMode
import org.gcap.core.nav.NavState
import org.gcap.core.nav.NavigationSession
import org.gcap.core.nav.SpeedZoom
import java.util.concurrent.Executors

/**
 * Forest Navigator — map screen.
 *
 * Scaffold, not a finished app. What it does today:
 *   - pick the SHP_CAP folder (SAF, user-owned, never created by the app)
 *   - load every .shp as a styled layer, with the proven background viewport
 *     loader, dirty-layer render cache and 1 dp draw-time simplification
 *   - follow the GPS fix, north-up, live overlay readouts
 *   - tap a feature to set it as the active waypoint -> bearing + distance
 *   - persist the last view so a restart resumes where you left off
 *
 * Deliberately NOT carried over from Forest Point Collector: point collection,
 * editing, MOB, layer generation, project-file authoring.
 *
 * The navigation core (map transform, heading, orientation, network graph, routing)
 * now lives in :gcap-core under org.gcap.core.nav and is unit tested there. This view
 * still uses its own north-up, anisotropic affine transform and is not yet wired to
 * MapTransform. docs/NAVIGATION_REQUIREMENTS.md records the decisions, the defects
 * still outstanding (section 5), the build order (section 10) and the open
 * questions (section 11).
 */
class MapActivity : AppCompatActivity() {

    private lateinit var app: NavigatorApp
    private var mapDir: SafMapDir? = null

    /** True while a shapefile write is in flight; blocks a second, interleaved one. */
    private var writeBusy = false
    private lateinit var mapView: MapCanvasView

    private var mapStarted = false
    private var mapLoading = false
    private var loadSeq = 0L
    private var initialLoadSeq = -1L
    private var initialLoadTotal = 0
    private var initialLoadDone = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private val shapeLoader = Executors.newFixedThreadPool(3)
    private var locListener: LocationListener? = null
    private var latestLoc: Location? = null
    private val fixGuard = org.forestnav.app.map.FixGuard()

    /** Active waypoint (working-zone metres), set by tapping a feature. */
    private var wpE = 0.0
    private var wpN = 0.0
    private var wpLabel: String? = null

    private val ui: MapCanvasView by lazy { mapView }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { /* not all providers grant this */ }
        // Everything after this point used to run unguarded, so any failure here - a tree
        // URI the provider will not open, a folder that cannot be listed, an out-of-memory
        // read - closed the app at the moment of picking a folder. Choosing a folder must
        // never be the thing that kills the app, so failures are reported instead.
        val ok = runCatching {
            app.setSafTree(uri)
            initMapDir()
            runCatching { settingsDialog?.dismiss() }
            if (!mapStarted) {
                Toast.makeText(this, "Folder set", Toast.LENGTH_SHORT).show()
                startMap()
            } else if (trackRecorder != null || editSession != null) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Folder set")
                    .setMessage("Stop recording or editing first. The map will use the new folder once you do.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                closeMap()
                startMap()
            }
        }
        if (ok.isFailure) {
            android.util.Log.e("ForestNav", "folder pick failed", ok.exceptionOrNull())
            mapDir = null
            android.app.AlertDialog.Builder(this)
                .setTitle("That folder could not be opened")
                .setMessage(
                    "Android would not let the app read that folder.\n\n" +
                        "Try a folder on the device's internal storage rather than one on a " +
                        "removable card, and check that the app is allowed to access it.",
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startLocationListener()
        } else {
            Toast.makeText(this, "Location permission denied — no GPS", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as NavigatorApp

        // Auto zoom is off unless the user asked for it, and the answer is remembered.
        autoZoom = app.autoZoomEnabled()

        // Navigation_mode is a setting, so it is remembered rather than reset each launch.
        val stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_NAV_MODE, null)
        navMode = runCatching { NavMode.valueOf(stored ?: "") }.getOrDefault(NavMode.GROUND)
        // Map up is a preference too, and should survive a restart like the rest.
        val storedUp = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_MAP_UP, null)
        orientation.mode = runCatching {
            OrientationMode.valueOf(storedUp ?: "")
        }.getOrDefault(OrientationMode.COURSE_UP)
        // The projection limit was held in memory only, so it reverted to the default on
        // every relaunch and a saved change looked like it had been ignored.
        projectLimitM = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(PREF_PROJECT_LIMIT, org.gcap.core.nav.NetworkGraph.PROJECT_LIMIT_M.toFloat())
            .toDouble()

        val root = LinearLayout(this).apply { setPadding(4, 4, 4, 4) }
        rootLayout = root

        // forest_navigator_ODS.ods lays the main page out as four buttons across and
        // six rows down; a single horizontal row cannot hold 24 commands legibly.
        val bar = android.widget.GridLayout(this).apply {
            columnCount = MainGrid.COLUMNS
            rowCount = MainGrid.rowCount
        }
        for ((i, cmd) in commands.withIndex()) {
            bar.addView(commandButton(cmd), gridParams(i))
        }

        mapView = MapCanvasView(this)
        mapView.showP1P2P3 = true
        mapView.onUserPan = {
            // panning to look around means the user is navigating by hand now
            if (follow) { follow = false; relayout() }
        }
        mapView.onPan = { loadViewport(false); updateOverlays() }
        mapView.onMapTap = { e, n -> onMapTap(e, n) }
        mapView.onLongPress = { e, n -> onMapLongPress(e, n) }
        mapView.overlayProject = "Forest Navigator"

        leftPanel = bar

        relayout()
        setContentView(root)

        initMapDir()
        ensureLocation()
    }

    // ---- layout: portrait stacks the bar above the map, landscape beside it --

    private var rootLayout: LinearLayout? = null
    private var leftPanel: android.widget.GridLayout? = null
    private var follow = true

    /** Map orientation: course, waypoint, or north. Cycled by the "Map up" button. */
    private val orientation = OrientationController()
    private val headingEst = HeadingEstimator()

    /** Speed-adaptive scale, per the user's band table. */
    private val speedZoom = SpeedZoom()
    // On by default, but a stored "off" is honoured: the choice is the crew's, not ours.
    private var autoZoom: Boolean = true

    /** Connection tolerance for network building, in metres. */
    private var networkToleranceM = 3.0

    /**
     * Metres of geometric tolerance when simplifying a layer before noding it.
     *
     * A road centreline carries far more vertices than a route needs: at 1:10000,
     * 5 m is a fifth of a pixel. Simplifying to that tolerance cuts the vertex count -
     * and with it the heap the noding pass needs - by roughly two thirds, while
     * leaving the line within 5 m of where it was drawn.
     */
    private var networkSimplifyM = 5.0

    /**
     * One network per routable layer, keyed by the layer's stem.
     *
     * Each layer is noded separately because they are different kinds of ground: an
     * access track, a main road and a walking trail do not join at a crossing, and
     * welding them into one graph would let a route leave the road it was following
     * and cut across a block boundary. Keeping them apart also keeps each network
     * small enough to build on a phone.
     */
    private var networks: Map<String, org.gcap.core.nav.NetworkGraph> = emptyMap()

    /**
     * Saved networks this project has, by layer, whether or not they are loaded.
     *
     * Rebuilding the roads graph costs about 30 s and a couple of hundred megabytes of
     * garbage, and the map only ever routes on one network at a time. So the others are
     * listed here and built only when they are actually chosen.
     */
    private val networkFilesByStem = LinkedHashMap<String, Map<String, String>>()

    /** Stems of saved networks, loaded or not. */
    private val availableStems: Set<String> get() = networkFilesByStem.keys

    /** The network being read right now, so the same one is not started twice. */
    private var networkLoadingStem: String? = null

    /** The raster being used as a basemap, and the reason a candidate was refused. */
    private var rasterName: String? = null
    private var rasterStamp: String = ""

    /**
     * The orthophoto for the current view, or null when there is none.
     *
     * Decoded on a background thread because reading a raster strip off a SAF stream
     * is slow enough to be felt as a stall; the result is tagged with the view it was
     * decoded for and dropped if the crew has moved on by the time it arrives.
     */
    private var rasterSource: org.gcap.core.raster.GeoTiff? = null
    /** One decode at a time; a pan does not queue up a screen of pixels per frame. */
    private val rasterWorker: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "raster-decode").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    /** Bumped for each request so superseded decodes are discarded. */
    @Volatile private var rasterTicket = 0
    /** What the probe found for each raster, so Layer settings can say what is usable. */
    private var probeEntries: List<RasterEntry> = emptyList()
    /** Magnetic heading for "Map up: compass". Null until the sensors are started. */
    private var compass: org.forestnav.app.map.CompassHeading? = null

    /** Which of [networks] the ACCESS/ROAD/TRAIL buttons have selected. */
    private var selectedNetworkStem: String? = null
    private var networkReport: List<Pair<String, String>> = emptyList()
    private var lastMovingAtMs = 0L
    private var sawFix = false

    private fun lp(w: Int, weight: Float) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, weight)

    /** Conventional scale steps offered by the Zoom_IN / Zoom_OUT commands. */
    private val SCALE_LADDER = intArrayOf(
        500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 7500, 10000, 15000, 20000,
        25000, 40000, 50000, 75000, 100000, 150000, 200000, 250000, 400000, 500000,
        750000, 1000000, 1500000, 2000000, 2500000, 3000000, 4000000,
    )

    private val COMMAND_COLUMNS = MainGrid.COLUMNS
    private val PREFS = "forest_navigator"
    private val AIR_CALLS_STEM = "Air_Calls_YR_4"
    private val AIR_CALLS_ID_FIELD = "CALL_ID"
    private val AIR_CALLS_E_FIELD = "X_Coor"
    private val AIR_CALLS_N_FIELD = "Y_Coor"
    private val FLIGHT_PATH_STEM = "flight_path"
    private val PREF_NAV_MODE = "navigation_mode"
    private val PREF_MAP_UP = "map_up"
    private val PREF_LAST_FLIGHT_LINE = "last_flight_line"

    /** Layer a recorded track is also written to as a new line. "" = only tracks.shp. */
    private val PREF_RECORD_INTO = "record_track_into"
    private val PREF_PROJECT_LIMIT = "project_limit_m"

    /** How wide a cut gap is, in metres. Must clear the healing tolerance. */
    private val PREF_CUT_GAP_M = "cut_gap_m"

    /**
     * `Navigation_mode`: on the ground or in the air. In the air two buttons change
     * meaning, ROAD becoming Flight Path and TRAIL becoming ROUTE, which is why the grid
     * is rebuilt whenever this changes.
     */
    private var navMode: NavMode = NavMode.GROUND
        set(value) {
            field = value
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_NAV_MODE, value.name).apply()
            relayout()
        }

    private val nav = NavigationSession(NavMode.GROUND)

    /** One main-page command. Commands not yet built are shown disabled, not hidden. */
    private data class Command(val label: String, val action: (() -> Unit)?, val command: Boolean = false)

    /**
     * The main page, in the order `forest_navigator_ODS.ods` lays it out and with the
     * labels the current [navMode] calls for.
     *
     * The layout lives in [MainGrid] rather than here so it can be tested against the
     * sheet, and so switching mode is one lookup instead of a second hand-written list
     * that could drift out of step with the first.
     */
    private val commands: List<Command>
        get() = MainGrid.buttons.map { b ->
            val label = if (b.id == "map_up") mapUpLabel() else b.label(navMode)
            Command(label, actionFor(b.id), b.id in COMMAND_IDS)
        }

    /**
     * Buttons that run something (Start/Save/Stop/Exit), as opposed to the ones that open
     * a list. They are coloured differently so the two are not confused under pressure.
     */
    private val COMMAND_IDS = setOf(
        "start_map", "close_map", "exit", "start_edit", "stop_edit", "save_edits",
        "start_track", "stop_track",
    )

    /** What each button does, or null while it is still a placeholder. */
    private fun actionFor(id: String): (() -> Unit)? = when (id) {
        "start_map" -> { { startMap()} }
        "close_map" -> { { closeMap()} }
        "settings" -> { { showSettings() } }
        "layer_settings" -> { { showLayerSettings() } }
        "verify_layers" -> { { verifyLayers() } }
        "centre_me" -> { { centreOnMe() } }
        "zoom_in" -> { { stepZoom(finer = true) } }
        "zoom_out" -> { { stepZoom(finer = false) } }
        "pan" -> { { goToPinned = false; toast("Drag the map to pan") } }
        "map_up" -> { { showMapUpPicker() } }
        "auto_zoom" -> { { toggleAutoZoom() } }
        // The old button built every layer at once and ran out of memory on a large one.
        "network" -> { { showNetworkBuilder() } }
        "mob" -> { { mob() } }
        "record_track" -> { { startRecording() } }
        "start_edit" -> { { startEdit()} }
        "stop_edit" -> { { stopEdit()} }
        "save_edits" -> { { saveEdits()} }
        "add_point" -> { { armAddPoint() } }
        "exit" -> { { exitApp()} }
        "stop_track" -> { { stopRecording()} }
        "go_to" -> { { goTo() } }

        "navigate" -> { { chooseNavigation() } }
        "stop_navigating" -> { { stopNavigation() } }
        "direct_line" -> { { applyNavState(nav.directLine()); navForceDirect = true; plotNavRoute() } }
        "waypoint" -> { { pickWaypoint() } }
        "net_b5", "net_c4" -> { { applyNavState(selectNetwork(id)) } }
        // In the air C5 is ROUTE, which shows the flight plan rather than choosing a network.
        "net_c5" -> {
            {
                if (navMode == NavMode.AIR) showFlightPlan() else applyNavState(selectNetwork(id))
            }
        }

        else -> null
    }

    /**
     * Report what navigation did, and act on the parts that change the map.
     *
     * Stopping puts the map back on the GPS position with course up, which is what the
     * main page specifies for `Stop_Navigating`.
     */
    private fun applyNavState(state: NavState) {
        if (state.recentreOnGps) recentreOnGpsCourseUp()
        toast(state.message)
    }

    private fun recentreOnGpsCourseUp() {
        follow = true
        orientation.mode = OrientationMode.COURSE_UP
        orientation.reset()
        centreOnMe()
        relayout()
    }

    /** `Navigate`: start on the network the current mode calls for. */
    /** The network the ACCESS/ROAD/TRAIL buttons have selected, kept so Navigate can route to a waypoint on it. */
    private var navRouter: org.gcap.core.nav.Router? = null

    /** True once a waypoint has been pinned, so Navigate knows there is a destination. */
    private var hasWaypoint = false

    /**
     * What navigation is being asked to do, and where to.
     *
     * The authority on whether there is a destination at all. `wpE`/`wpN` remain for the
     * on-screen readout; this decides whether Navigate has somewhere to go.
     */
    private val navPlan = org.gcap.core.nav.NavigationPlan()

    /** Waiting for the user to tap a point closer to the road. */
    private var awaitingViaTap = false

    /** Projection limit for waypoints, in metres. Settable through Settings. */
    private var projectLimitM = org.gcap.core.nav.NetworkGraph.PROJECT_LIMIT_M

    /** Where navigation should go, and how far the waypoint sits off the line. */
    private var navTargetE = 0.0
    private var navTargetN = 0.0

    /**
     * The last waypoint destination chosen, so the Navigate dialog can offer it first.
     *
     * Kept apart from [navPlan] because that holds only the single most recent destination,
     * and a Go to... would otherwise overwrite a waypoint the crew still wants.
     */
    private var lastWaypointDest: org.gcap.core.nav.NavigationPlan.Destination? = null

    /** The last Go to... destination chosen, offered first by the Navigate dialog. */
    private var lastGoToDest: org.gcap.core.nav.NavigationPlan.Destination? = null

    /**
     * The Navigate button: choose what to navigate to.
     *
     * The destinations chosen since the map was started are listed first, so the usual
     * answer - the waypoint or Go to... just picked - is one tap away. Only the button
     * asks. A long press on a spot and a tap-to-set-waypoint already name their target,
     * so they go straight to [startNavigation] without this list.
     */
    private fun chooseNavigation() {
        val air = navMode == NavMode.AIR
        val planLayer = if (air) "flight_plan" else "route_plan1"
        val planLabel = if (air) "Flight plan ($planLayer)" else "Route plan ($planLayer)"
        // Remembered destinations come first, waypoint then Go to..., each only when chosen.
        val recent = mutableListOf<Pair<String, () -> Unit>>()
        lastWaypointDest?.let { d ->
            recent.add("${d.label}  \u2026 waypoint" to { navigateToStored(d, waypoint = true) })
        }
        lastGoToDest?.let { d ->
            recent.add("${d.label}  \u2026 Go to\u2026" to { navigateToStored(d, waypoint = false) })
        }
        val fixed = listOf("To waypoint", "Go to\u2026", planLabel)
        val options = (recent.map { it.first } + fixed).toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Navigate to")
            .setItems(options) { _, which ->
                when {
                    which < recent.size -> recent[which].second()
                    which == recent.size -> startNavigation()
                    which == recent.size + 1 -> goTo()
                    else -> startRouteFromLayer(planLayer)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Navigate to a destination the crew already chose, without asking again.
     *
     * Re-selecting it makes it current (a fresh timestamp), then it follows the same path as
     * the original choice: a waypoint projects onto the network, a Go to... heads for the
     * named spot.
     */
    private fun navigateToStored(
        d: org.gcap.core.nav.NavigationPlan.Destination,
        waypoint: Boolean,
    ) {
        navPlan.setDestination(d.label, d.e, d.n, d.origin)
        if (waypoint) {
            wpE = d.e
            wpN = d.n
            hasWaypoint = true
            wpLabel = d.label
            mapView.setP1P2P3(listOf(Triple(d.label, wpE, wpN)))
        } else {
            // A Go to... is a named spot, not a line to walk: clear any waypoint so the
            // projection branch is not taken by mistake.
            awaitingViaTap = false
            clearWaypoint()
        }
        updateOverlays()
        startNavigation()
    }

    /** Load an ordered plan layer (sorted by Call_Num) and start on its first call. */
    private fun startRouteFromLayer(layer: String) {
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        val loaded = loadRoutePlan(dir, layer, "Call_Num")
        if (!loaded) {
            toast("$layer has no Call_Num order to follow. Check the layer and try again.")
            return
        }
        startRoutePlanNavigation()
    }

    private fun startNavigation() {
        goToPinned = false
        // A fresh Navigate is a fresh choice: it follows the network unless the crew
        // asks otherwise here, so a Direct_Line chosen last trip does not persist.
        navForceDirect = false
        navStraightLine = false

        // Decide first whether there is anywhere to go. Announcing "Navigating on ACCESS"
        // and then working out the destination is how a crew ends up being led to a
        // waypoint they chose the day before.
        val choice = navPlan.resolve()
        if (choice is org.gcap.core.nav.NavigationPlan.Resolution.NeedsDestination) {
            askForDestination(choice)
            return
        }
        val ready = choice as org.gcap.core.nav.NavigationPlan.Resolution.Ready
        val d = ready.destination

        navigating = true
        val state = nav.start(d.label)

        // Whether a network exists and whether this destination wants waypoint projection
        // are two separate questions. Conflating them made a Go-to trip announce "no
        // network built" while standing on a network of 93 nodes, and drew a straight line
        // across country when a route along the access was available.
        val graph = selectedNetworkStem?.let { networks[it] }
        val networkReady = graph != null && graph.edges.isNotEmpty()
        if (networkReady && (hasWaypoint || awaitingViaTap)) {
            // How far off the network it lies decides whether to say nothing, say something,
            // or ask: three different situations.
            when (val snap = graph.snapWaypoint(d.e, d.n, waypointLimitM())) {
                is org.gcap.core.nav.WaypointDecision.Direct ->
                    beginNavigationTo(snap.targetE, snap.targetN, null)
                is org.gcap.core.nav.WaypointDecision.Snapped ->
                    beginNavigationTo(
                        snap.targetE, snap.targetN,
                        "Waypoint is ${snap.distanceM.toInt()} m off the line; going to the nearest point on it.",
                    )
                is org.gcap.core.nav.WaypointDecision.TooFar -> {
                    if (snap.distanceM == Double.MAX_VALUE) {
                        beginNavigationTo(snap.targetE, snap.targetN, null)
                    } else {
                        askViaPoint(snap, state.network ?: "the network")
                    }
                }
            }
        } else if (networkReady) {
            // A Go-to destination is a named spot, not a line to walk: go to the spot
            // itself and let plotNavRoute follow the network as far as it can reach.
            beginNavigationTo(d.e, d.n, null)
        } else if (networkLoadingStem != null) {
            // A network is on its way, not absent. Saying "no network built" would be a
            // lie, and drawing a straight line across country when a route along the
            // access was about to be available is the worst of both.
            toast("${networkLoadingStem} network is still loading. Try again in a moment.")
            return
        } else {
            navPlan.useGuidance(org.gcap.core.nav.NavigationPlan.Guidance.DIRECT_LINE)
            beginNavigationTo(d.e, d.n, "Direct line to ${d.label} (no network built)")
        }
        if (!navigating) return
        applyNavState(state)
    }

    /**
     * Navigate was pressed with nowhere current to go.
     *
     * Asks rather than choosing. A stale destination is named and its age given, so the
     * crew can say "yes, that one" instead of wondering why the app refused.
     */
    private fun askForDestination(choice: org.gcap.core.nav.NavigationPlan.Resolution.NeedsDestination) {
        val stale = navPlan.destination
        val age = choice.staleAgeSec
        val msg = when {
            stale == null -> "No destination chosen. Pick a waypoint, or hold the map to navigate from there."
            else -> "\"${stale.label}\" was chosen ${humanAge(age!!)} ago.\nNavigate to something current?"
        }
        optionDialog(
            "Navigate where?",
            msg,
            listOf(
                "Cancel" to {},
                // Navigate on from here. Choosing a waypoint is the answer to this
                // question, so making the user press Navigate a second time afterwards
                // asked the same thing twice and looked, from the outside, like the
                // first answer had not been taken.
                "Choose a waypoint and navigate" to { pickWaypoint(navigateAfter = true) },
            ),
        )
    }

    /** "3 minutes", "2 hours", for the stale-destination message. */
    private fun humanAge(sec: Long): String = when {
        sec < 90 -> "$sec seconds"
        sec < 5400 -> "${sec / 60} minutes"
        sec < 172_800 -> "${sec / 3600} hours"
        else -> "${sec / 86400} days"
    }

    /** Projection limit from the app settings; 1.5 km unless the user changes it. */
    private fun waypointLimitM(): Double = projectLimitM

    /**
 * A dialog whose options each get a full-width row.
 *
 * AlertDialog puts three buttons on one line, and on the 720x1560 BV5200 the labels were
 * squeezed until the third read as a single letter - an option that cannot be read
 * cannot be chosen on purpose. One row each costs a little height and stays legible on
 * every screen this app runs on.
 */
private fun optionDialog(title: String, message: String, options: List<Pair<String, () -> Unit>>) {
        val pad = (12 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (message.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = message
                setPadding(0, 0, 0, pad / 2)
            })
        }
        val dlg = android.app.AlertDialog.Builder(this).setTitle(title).setView(root).create()
        for ((label, action) in options) {
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    dlg.dismiss()
                    action()
                }
            })
        }
        dlg.show()
    }

    /**
     * "Waypoint too far from road - choose via point".
     *
     * Cancel keeps the waypoint but does not navigate to it, Bypass goes to the nearest
     * point on the line, and Tap on screen lets the user pick a point closer to the road.
     */
    private fun askViaPoint(
        d: org.gcap.core.nav.WaypointDecision.TooFar,
        networkLabel: String,
    ) {
        val m = d.distanceM.toInt()
        optionDialog(
            "Waypoint too far from road",
            "Waypoint is $m m from $networkLabel. Choose a point via:",
            listOf(
                "Direct line to it" to {
                    navForceDirect = true
                    // targetE/targetN are the waypoint itself; projectionE/N are the
                    // nearest point on the line, which is what Bypass uses.
                    beginNavigationTo(d.targetE, d.targetN, "Straight line to the waypoint, $m m off the line.")
                },
                "Tap on screen" to {
                    awaitingViaTap = true
                    addArmed = false
                    toast("Tap a point nearer the road")
                },
                "Cancel" to {
                    // the waypoint is left alone and no navigation is started towards it
                    navTargetE = 0.0
                    navTargetN = 0.0
                    toast("Not navigating to that waypoint")
                },
                "Bypass (use nearest point on $networkLabel)" to {
                    beginNavigationTo(
                        d.projectionE, d.projectionN,
                        "Using the nearest point on $networkLabel, $m m away.",
                    )
                },
            ),
        )
    }

    /**
     * Centre on a point and zoom to a scale where point layers are actually drawn.
     *
     * The scale ladder only brings `points` in from 1:100,000, so a MOB saved while the
     * map was fitted to the whole project - typically around 1:400,000 - would be written
     * correctly and still be invisible.
     */
    private fun revealPoint(e: Double, n: Double) {
        // The map centre is always in working-zone metres. The layer may store degrees,
        // but projecting it into the map's own frame is the loader's job, not this one's -
        // assigning degrees here put the view at 0N 125W and blanked the map.
        follow = false
        mapView.centerE = e
        mapView.centerN = n
        if (mapView.scaleDenominator > REVEAL_SCALE) mapView.scaleDenominator = REVEAL_SCALE
        relayout()
        // Load only once the view has its new size. Loading before layout settles
        // computes the window from the old dimensions, so every shape is filtered out
        // and the layer comes back empty - which is why a saved MOB was invisible.
        mapView.post {
            loadViewport(false)
            updateOverlays()
        }
    }

    /** A scale close enough in that point layers are loaded and markers are legible. */
    private val REVEAL_SCALE = 10_000

    /** Set the destination and say what is being done, if anything worth saying. */
    private fun beginNavigationTo(e: Double, n: Double, note: String?) {
        navTargetE = e
        navTargetN = n
        navigating = true
        follow = true
        plotNavRoute()
        note?.let { toast(it) }
    }

    /**
     * Work out and draw the line being driven to the destination.
     *
     * Three outcomes, in the order they matter: follow the network when one is built and
     * the route works; fall back to a straight line when it does not, because refusing to
     * navigate at all would be worse than a line that is merely not optimal; and say which
     * happened, because a crew driving a straight line through a swamp needs to know that
     * is what they are getting rather than believing they are on the access.
     */
    private fun plotNavRoute() {
        val loc = latestLoc
        val graph = navRouter?.graph
        if (loc == null || graph == null || graph.edges.isEmpty() || navForceDirect) {
            // nothing to route along, or a straight line was asked for: draw what can honestly be drawn
            navRoutePoints = if (loc != null) {
                val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
                doubleArrayOf(e, n, navTargetE, navTargetN)
            } else DoubleArray(0)
            navStraightLine = navRoutePoints.isNotEmpty()
            mapView.setNavRoute(navRoutePoints)
            if (navForceDirect && navRoutePoints.isNotEmpty() && graph != null && graph.edges.isNotEmpty()) {
                updateOverlays()
            }
            return
        }
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        val res = navRouter!!.route(e, n, navTargetE, navTargetN, maxSnapM = projectLimitM)
        when (res) {
            is org.gcap.core.nav.RouteResult.Ok -> {
                navRoutePoints = org.gcap.core.nav.RouteGeometry.polyline(graph, res.path)
                navStraightLine = false
                mapView.setNavRoute(navRoutePoints)
            }
            is org.gcap.core.nav.RouteResult.Failed -> {
                // a network exists but will not serve this trip: straight line, and say so
                navRoutePoints = doubleArrayOf(e, n, navTargetE, navTargetN)
                navStraightLine = true
                mapView.setNavRoute(navRoutePoints)
                toast("Not on the built network (${res.reason}); drawing a direct line")
            }
        }
        // Measured now rather than waiting for the next fix: a crew that presses Navigate
        // and stops still needs a distance, and GPS can be minutes between fixes.
        navRemainingM = org.gcap.core.nav.RouteGeometry.remainingM(navRoutePoints, e, n)
        // refresh now: GPS can be minutes between fixes, and the crew pressed a button
        updateOverlays()
    }

    /** The route being followed, so the readout can measure along it rather than as the crow flies. */
    private var navRoutePoints = DoubleArray(0)

    /**
     * True when the drawn line is straight rather than a network route.
     *
     * A network route is correctly fixed: the crew drives the access, so redrawing it
     * from their current position would only rewrite the part already covered. A
     * straight line is drawn from where the crew *was*, so leaving it alone leaves the
     * line pointing at a place they have already left. That is what made the direct
     * line look frozen.
     */
    private var navStraightLine = false

    /** Ask for a straight line to the target whatever the network would prefer. */
    private var navForceDirect = false

    /** Metres left along the drawn route, updated as fixes arrive. */
    private var navRemainingM = Double.NaN

    /**
     * An ordered list of calls to visit in sequence, from a route plan layer.
     *
     * Empty until a plan is loaded. The first entry is the current target; reaching
     * it advances to the next. Routing stays on the selected network throughout.
     */
    private var routePlan: List<Triple<String, Double, Double>> = emptyList()

    /** The whole planned route drawn at once, so the crew sees where they are going. */
    private var routePlanPoints = DoubleArray(0)

    /** `Stop_Navigating`: back to the GPS position with course up. */
    /**
     * Load a route plan layer and prepare to navigate through its calls in order.
     *
     * Reads the layer named by [layerName], sorts by [orderField], and stores the
     * ordered waypoints. If the layer is missing or unreadable the plan stays empty
     * and the caller is told rather than left wondering why nothing happened.
     */
    private fun loadRoutePlan(dir: SafMapDir, layerName: String, orderField: String): Boolean {
        val shpFile = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(layerName, true) && it.endsWith(".shp", true)
        } ?: return false
        val stem = shpFile.substringBeforeLast('.')
        val dbfFile = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
        } ?: return false
        val shpBytes = dir.readFile(shpFile) ?: return false
        val dbfBytes = dir.readFile(dbfFile) ?: return false
        val shapes = try { ShpFormat.readShp(shpBytes, -1e18, -1e18, 1e18, 1e18) } catch (e: Exception) { return false }
        val rows = try { ShpFormat.readDbfRows(dbfBytes) } catch (e: Exception) { return false }
        if (shapes.isEmpty() || rows.isEmpty()) return false

        // Find the order field index from the DBF header
        val d = dbfBytes
        val headerLen = ((d[8].toInt() and 0xFF) or ((d[9].toInt() and 0xFF) shl 8))
        val fieldCount = (headerLen - 32) / 32
        var orderIdx = -1
        var off = 32
        for (i in 0 until fieldCount) {
            if (off + 11 > d.size) break
            val nameBytes = d.copyOfRange(off, off + 11)
            val name = String(nameBytes, Charsets.US_ASCII).split("\u0000").first().trim()
            if (name.equals(orderField, true)) { orderIdx = i; break }
            off += 32
        }
        if (orderIdx < 0) return false

        // Build the plan: label from vals[0], ordered by the order field
        val plan = mutableListOf<Triple<String, Double, Double>>()
        for ((i, sh) in shapes.withIndex()) {
            val row = rows.getOrNull(i) ?: continue
            val vals = row.values
            if (vals.size <= orderIdx) continue
            // The label is the call itself (Call_Num), not OBJECTID: the crew navigates by call.
            val orderVal = vals[orderIdx].trim().toDoubleOrNull() ?: continue
            val label = vals[orderIdx].trim().takeIf { it.isNotBlank() } ?: continue
            val e = sh.parts.firstOrNull()?.firstOrNull()?.getOrNull(0) ?: continue
            val n = sh.parts.firstOrNull()?.firstOrNull()?.getOrNull(1) ?: continue
            if (!e.isFinite() || !n.isFinite()) continue
            plan.add(Triple("$orderVal|$label", e, n))
        }
        if (plan.isEmpty()) return false
        plan.sortBy { it.first.substringBefore("|").toDoubleOrNull() ?: Double.MAX_VALUE }
        routePlan = plan.map { Triple(it.first.substringAfter("|"), it.second, it.third) }
        return true
    }

    /**
     * Start navigating to the first call of the loaded route plan.
     *
     * Goes through the same destination path as Waypoint and Go to..., so the navigation
     * display, the distance and the network choice all see the call as the target. It used
     * to set its own target, which the navigation screen never read.
     */
    private fun startRoutePlanNavigation() {
        if (routePlan.isEmpty()) return
        val first = routePlan.first()
        routePlan = routePlan.drop(1)
        setRouteCallAsTarget(first)
        toast("Route plan: heading for call ${first.first}. ${routePlan.size} more to go.")
        startNavigation()
    }

    private fun setRouteCallAsTarget(call: Triple<String, Double, Double>) {
        wpE = call.second
        wpN = call.third
        hasWaypoint = true
        wpLabel = "Call ${call.first}"
        navPlan.setDestination(wpLabel!!, call.second, call.third,
            org.gcap.core.nav.NavigationPlan.Destination.Origin.ROUTE)
    }

    /** Advance to the next call in the route plan when the current one is reached. */
    private fun advanceRoutePlan() {
        if (routePlan.isEmpty()) return
        val next = routePlan.first()
        routePlan = routePlan.drop(1)
        setRouteCallAsTarget(next)
        toast("Next: call ${next.first}. ${routePlan.size} more to go.")
        startNavigation()
    }

    private fun stopNavigation() {
        navigating = false
        navRoutePoints = DoubleArray(0)
        navRemainingM = Double.NaN
        navStraightLine = false
        navForceDirect = false
        mapView.clearNavRoute()
        updateOverlays()
        applyNavState(nav.stop())
    }

    /** True between Navigate and Stop_Navigating. */
    private var navigating = false

    /**
     * `Waypoint`: choose a target from the waypoints layer named in Settings.
     *
     * There is no picker until that layer is loaded, so this says so plainly rather than
     * offering a list it cannot fill.
     */
    /**
     * `ROUTE`: draw the flight plan on the map.
     *
     * Reads the air calls by `CALL_ID` and lays out every transect and transit in flying
     * order, with the next one picked out. Transits are drawn as plain lines because most
     * of them have no recorded flight-path geometry, and saying so is better than
     * silently omitting them.
     */
    private var flightPlan: FlightPlan? = null
    private var planSession: FlightPlanSession? = null

    /**
     * `FLIGHT PLAN`: build the plan, draw it, then ask the crew where to start.
     *
     * They may start anywhere, so the plan does not assume it begins at `001A`. Once a
     * start call is chosen the app flies a **direct line** to it and only then follows the
     * plan from there.
     */
    private fun showFlightPlan() {
        val dir = mapDir ?: run { toast("Load the project folder first."); return }
        val shpName = dir.listFiles().firstOrNull {
            it.endsWith(".shp", true) && it.substringBeforeLast('.').equals(AIR_CALLS_STEM, true)
        }
        if (shpName == null) {
            toast("No $AIR_CALLS_STEM.shp in the project folder.")
            return
        }
        val names = fieldLabels(dir, shpName, AIR_CALLS_ID_FIELD)
        val xs = fieldLabels(dir, shpName, AIR_CALLS_E_FIELD)
        val ys = fieldLabels(dir, shpName, AIR_CALLS_N_FIELD)
        if (names.size != xs.size || names.size != ys.size || names.isEmpty()) {
            toast("$AIR_CALLS_STEM.dbf has no usable $AIR_CALLS_ID_FIELD / $AIR_CALLS_E_FIELD / $AIR_CALLS_N_FIELD.")
            return
        }
        val calls = ArrayList<AirCall>()
        for (i in names.indices) {
            val e = xs[i].trim().toDoubleOrNull() ?: continue
            val n = ys[i].trim().toDoubleOrNull() ?: continue
            val nm = names[i].trim()
            if (nm.isEmpty()) continue
            calls.add(AirCall(nm, e, n))
        }
        if (calls.isEmpty()) { toast("No air calls found."); return }

        // Assembling the flown line from ~1200 fragments takes a few seconds and
        // would freeze the map, so it runs where layer loading runs. The straight
        // plan stays as the fallback when flight_path.shp is missing or unreadable.
        toast("Assembling the flight plan…")
        val callsSnapshot = ArrayList(calls)
        shapeLoader.execute {
            val path = flightPathLines()
            val plan = if (path != null) {
                runCatching { FlightPlan.buildFromFlownLines(callsSnapshot, path) }
                    .getOrElse { FlightPlan.build(callsSnapshot) }
            } else FlightPlan.build(callsSnapshot)
            runOnUiThread {
                if (!mapStarted) return@runOnUiThread
                renderFlightPlan(plan)
            }
        }
    }

    /**
     * The whole recorded flight path, as flat polylines.
     *
     * Read in full rather than through the viewport loader: the plan needs every
     * fragment, including the ones outside the current window, because the line
     * assembly joins fragments from across the whole survey. Mirrors the CRS
     * handling in `buildLayers`, so a flight path in WGS84 or a foreign UTM zone
     * is still placed correctly in the working zone.
     */
    private fun flightPathLines(fileStem: String = FLIGHT_PATH_STEM): List<DoubleArray>? {
        val dir = mapDir ?: return null
        val f = dir.listFiles().firstOrNull {
            it.endsWith(".shp", true) && it.substringBeforeLast('.').equals(fileStem, true)
        } ?: return null
        val bytes = runCatching { dir.readFile(f) }.getOrNull() ?: return null
        val layerCrs = runCatching { Utm.classify(prjFor(dir, f)) }.getOrNull()
        if (layerCrs == null || layerCrs.kind == Utm.Crs.OTHER) return null
        val raw = try {
            ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
        } catch (e: Exception) { return null }
        val foreignZone = layerCrs.kind == Utm.Crs.UTM_METERS && layerCrs.utmZone != workingZone()
        val geo = layerCrs.kind == Utm.Crs.WGS84_DEGREES || layerCrs.kind == Utm.Crs.ALBERS || foreignZone
        val shapes = if (geo) projectShapes(raw, layerCrs.kind, f, layerCrs.utmZone) else raw
        val out = ArrayList<DoubleArray>()
        for (s in shapes) {
            for (part in s.parts) {
                if (part.size < 2) continue
                val line = DoubleArray(part.size * 2)
                part.forEachIndexed { i, p -> line[i * 2] = p[0]; line[i * 2 + 1] = p[1] }
                out.add(line)
            }
        }
        return out.ifEmpty { null }
    }

    // ---- build_FLIGHT_LINE ---------------------------------------------------

    /** Outcome of building a flight line: either a layer written, or the reason it was not. */
    private sealed class FlightLineOutcome {
        class Built(val stem: String, val lines: Int, val report: String) : FlightLineOutcome()
        class Failed(val reason: String, val report: String = "") : FlightLineOutcome()
    }

    /** Last flight line built on this device, for the display beside the Settings button. */
    private fun lastFlightLineName(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_FLIGHT_LINE, null) ?: "none yet"

    /** Every flight path layer in the folder: `flight_path`, and any `flight_path_2` and so on. */
    private fun flightPathFiles(dir: SafMapDir): List<String> =
        dir.listFiles()
            .filter { f ->
                f.endsWith(".shp", true) &&
                    f.substringBeforeLast('.').startsWith(FLIGHT_PATH_STEM, true)
            }
            .sorted()

    /**
     * The air calls, in the order the layer holds them, as the flight line builder needs
     * them. Returns the calls, or the reason there are none.
     */
    private fun readAirCalls(dir: SafMapDir): Pair<List<AirCall>, String?> {
        val shp = dir.listFiles().firstOrNull {
            it.endsWith(".shp", true) && it.substringBeforeLast('.').equals(AIR_CALLS_STEM, true)
        } ?: return emptyList<AirCall>() to "No $AIR_CALLS_STEM.shp in the project folder."
        val names = fieldLabels(dir, shp, AIR_CALLS_ID_FIELD)
        val xs = fieldLabels(dir, shp, AIR_CALLS_E_FIELD)
        val ys = fieldLabels(dir, shp, AIR_CALLS_N_FIELD)
        if (names.size != xs.size || names.size != ys.size || names.isEmpty()) {
            return emptyList<AirCall>() to
                "$AIR_CALLS_STEM.dbf has no usable $AIR_CALLS_ID_FIELD / $AIR_CALLS_E_FIELD / $AIR_CALLS_N_FIELD."
        }
        val calls = ArrayList<AirCall>()
        for (i in names.indices) {
            val e = xs[i].trim().toDoubleOrNull() ?: continue
            val n = ys[i].trim().toDoubleOrNull() ?: continue
            val nm = names[i].trim()
            if (nm.isEmpty()) continue
            calls.add(AirCall(nm, e, n))
        }
        return if (calls.isEmpty()) emptyList<AirCall>() to "No air calls found in $AIR_CALLS_STEM.shp."
        else calls to null
    }

    /**
     * Build the continuous, filleted flight line from [pathFile] and write it as [stem].shp.
     *
     * Runs off the UI thread. Anything that stops the build is returned as a reason rather
     * than thrown, so the crew sees what went wrong and what was left alone.
     */
    private fun buildFlightLine(dir: SafMapDir, pathFile: String, stem: String): FlightLineOutcome {
        val pathStem = pathFile.substringBeforeLast('.')
        val lines = flightPathLines(pathStem)
            ?: return FlightLineOutcome.Failed(
                "$pathFile could not be read, or it is in a coordinate system this app does not recognise.",
            )
        val (calls, err) = readAirCalls(dir)
        if (err != null) return FlightLineOutcome.Failed(err)

        val res = org.gcap.core.nav.FlightLinePipeline.run(lines, calls)
        val text = res.report.describe()
        if (res.lines.isEmpty()) {
            return FlightLineOutcome.Failed("No continuous line could be built from $pathFile.", text)
        }

        // Lines come out in the working zone. The new layer has to say so in its .prj, which
        // is only possible when the flight path already is in that zone. Refuse otherwise
        // rather than label coordinates with the wrong system.
        val pathPrj = prjFor(dir, pathFile)
        val crs = runCatching { Utm.classify(pathPrj) }.getOrNull()
        if (crs == null || crs.kind != Utm.Crs.UTM_METERS || crs.utmZone != workingZone()) {
            return FlightLineOutcome.Failed(
                "$pathFile is not in UTM zone ${workingZone()}, the project's working zone. " +
                    "Reproject it on a desktop, or set Default CRS, before building.",
            )
        }

        val parts = res.lines.map { flat ->
            (0 until flat.size / 2).map { doubleArrayOf(flat[it * 2], flat[it * 2 + 1]) }
        }
        val names = res.lines.indices.map { "${stem}_${it + 1}" }
        val files = listOf(
            "$stem.shp" to org.gcap.core.ShpFormat.buildPolylineShp(parts),
            "$stem.shx" to org.gcap.core.ShpFormat.buildPolylineShx(parts),
            "$stem.dbf" to org.gcap.core.ShpFormat.buildDbf(ByteArray(0), names, "NAME", 40),
            "$stem.prj" to (pathPrj ?: "").toByteArray(Charsets.UTF_8),
        )
        for ((name, bytes) in files) {
            if (!(dir.overwriteFile(name, bytes) || dir.createFile(name, bytes))) {
                return FlightLineOutcome.Failed(
                    "Could not write $name. The new layer may be incomplete; build it again.",
                    text,
                )
            }
        }
        return FlightLineOutcome.Built(stem, res.lines.size, text)
    }

    /** The Settings button: pick a flight path, confirm, name the new layer, then build. */
    private fun startBuildFlightLine() {
        val dir = mapDir ?: run { toast("Load the project folder first."); return }
        val files = flightPathFiles(dir)
        if (files.isEmpty()) {
            toast("No flight_path .shp in this folder.")
            return
        }
        if (files.size == 1) {
            confirmFlightPath(dir, files[0])
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle("Which flight path?")
                .setItems(files.toTypedArray()) { _, which -> confirmFlightPath(dir, files[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun confirmFlightPath(dir: SafMapDir, pathFile: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Build flight line?")
            .setMessage("Build a continuous, filleted flight line from $pathFile?")
            .setPositiveButton("Yes") { _, _ -> askFlightLineName(dir, pathFile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askFlightLineName(dir: SafMapDir, pathFile: String) {
        val input = android.widget.EditText(this).apply {
            setText("flight_line")
            setSelection(text.length)
            setSingleLine(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Name of the new line layer")
            .setMessage("The .shp extension is added for you.")
            .setView(input)
            .setPositiveButton("Build") { _, _ ->
                val stem = input.text.toString().trim()
                    .ifEmpty { "flight_line" }
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                when {
                    stem.equals(pathFile.substringBeforeLast('.'), true) ->
                        toast("Choose another name: that would overwrite the flight path itself.")
                    dir.listFiles().any {
                        it.endsWith(".shp", true) && it.substringBeforeLast('.').equals(stem, true)
                    } -> android.app.AlertDialog.Builder(this)
                        .setTitle("Replace $stem.shp?")
                        .setMessage("A layer called $stem already exists. Replacing it cannot be undone.")
                        .setPositiveButton("Replace") { _, _ -> runFlightLineBuild(dir, pathFile, stem) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    else -> runFlightLineBuild(dir, pathFile, stem)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runFlightLineBuild(dir: SafMapDir, pathFile: String, stem: String) {
        setBusy("Building flight line")
        Thread {
            val outcome = runCatching { buildFlightLine(dir, pathFile, stem) }
                .getOrElse { e ->
                    android.util.Log.e("ForestNav", "flight line build failed", e)
                    FlightLineOutcome.Failed("Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
                }
            dir.clearCaches()
            runOnUiThread {
                clearBusy()
                when (outcome) {
                    is FlightLineOutcome.Built -> {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_LAST_FLIGHT_LINE, "${outcome.stem}.shp").apply()
                        // Refresh the Settings list first: rebuilding it puts a new dialog on
                        // top, which would hide the report if it were shown before.
                        refreshSettingsIfOpen()
                        // The new layer appears on the map once the map has reloaded its layers.
                        if (mapStarted) { closeMap(); startMap() }
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Flight line built")
                            .setMessage("${outcome.stem}.shp: ${outcome.lines} continuous line(s).\n\n${outcome.report}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is FlightLineOutcome.Failed -> {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Flight line not built")
                            .setMessage(
                                outcome.reason +
                                    (if (outcome.report.isNotBlank()) "\n\n${outcome.report}" else "") +
                                    "\n\nNothing was changed unless a file was named above.",
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    /** Put an assembled plan on the map and offer a start call. */
    private fun renderFlightPlan(plan: FlightPlan) {
        flightPlan = plan
        val nextIndex = nextTransectIndex(plan)
        val lines = ArrayList<MapCanvasView.PlanLine>()
        for (seg in plan.segments) {
            val isNext = plan.transects.indexOf(seg) == nextIndex
            lines += MapCanvasView.PlanLine(
                seg.geometry,
                when {
                    isNext -> MapCanvasView.PlanStyle.NEXT
                    seg.leg == PlanLeg.TRANSECT -> MapCanvasView.PlanStyle.TRANSECT
                    else -> MapCanvasView.PlanStyle.TRANSIT
                },
            )
        }
        val markers = ArrayList<Triple<String, Double, Double>>()
        for (seg in plan.segments) {
            markers.add(Triple(seg.from.name, seg.from.e, seg.from.n))
            markers.add(Triple(seg.to.name, seg.to.e, seg.to.n))
        }
        val next = plan.transectAt(nextIndex)
        val note = next?.let { "Next: ${it.from.name} to ${it.to.name}" } ?: "Plan complete"
        val nextNames = next?.let { setOf(it.from.name, it.to.name) } ?: emptySet()
        mapView.setFlightPlan(lines, markers, note, nextNames)
        val traced = plan.segments.count { it.traced }
        toast(
            "${plan.tripleCount} transects, ${plan.transits.size} transits. " +
                if (traced == plan.segments.size) "Drawn on the flown line."
                else "${plan.segments.size - traced} legs without path geometry.",
        )
        askWhereToStart(plan)
    }

    /**
     * Ask which air call to start from, and start there.
     *
     * A picker rather than assuming the first call: the crew may be starting mid-block, or
     * resuming after the morning's earlier work is already done.
     */
    private fun askWhereToStart(plan: FlightPlan) {
        val names = ArrayList<String>()
        for (seg in plan.segments) {
            names.add(seg.from.name)
            if (seg.to.name !in names) names.add(seg.to.name)
        }
        // a long list is unusable in a dialog, so offer the transect starts in order
        val transectStarts = plan.transects.map { it.from.name }
        val items = (transectStarts + names.filter { it !in transectStarts }.take(0)).toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Start the flight plan at")
            .setItems(items) { _, which ->
                val chosen = items[which]
                val session = FlightPlanSession(plan)
                val started = session.startAt(chosen)
                planSession = session
                toast(started.message)
                updatePlanTarget()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Redraw the plan with the session's current target picked out. */
    private fun updatePlanTarget() {
        val plan = flightPlan ?: return
        val session = planSession ?: return
        val loc = latestLoc
        val progress = if (loc != null) {
            val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
            session.update(e, n)
        } else {
            session.update(Double.NaN, Double.NaN)
        }
        val target = progress.targetName
        val lines = ArrayList<MapCanvasView.PlanLine>()
        for (seg in plan.segments) {
            val isNext = target != null && (seg.to.name == target || seg.from.name == target)
            lines += MapCanvasView.PlanLine(
                seg.geometry,
                when {
                    progress.directLine -> MapCanvasView.PlanStyle.NEXT
                    isNext -> MapCanvasView.PlanStyle.NEXT
                    seg.leg == PlanLeg.TRANSECT -> MapCanvasView.PlanStyle.TRANSECT
                    else -> MapCanvasView.PlanStyle.TRANSIT
                },
            )
        }
        val markers = ArrayList<Triple<String, Double, Double>>()
        for (seg in plan.segments) {
            markers.add(Triple(seg.from.name, seg.from.e, seg.from.n))
            markers.add(Triple(seg.to.name, seg.to.e, seg.to.n))
        }
        val note = when (progress.phase) {
            PlanPhase.IDLE -> "Flight plan: choose a start call"
            PlanPhase.APPROACHING -> "Direct line to ${progress.targetName}"
            PlanPhase.ON_PLAN -> "On plan: next ${progress.targetName}  " +
                "${"%.0f".format(progress.distanceM)} m  " +
                "${progress.callsRemaining} calls left"
            PlanPhase.COMPLETE -> "Flight plan complete"
        }
        mapView.setFlightPlan(lines, markers, note, setOfNotNull(target))
    }

    /** Called on every fix while a flight plan is being flown. */
    private fun advanceFlightPlan() {
        val session = planSession ?: return
        val loc = latestLoc ?: return
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        val before = session.phase
        session.update(e, n)
        if (session.phase != before) updatePlanTarget()
    }

    /**
     * Which transect the crew is on: the nearest one whose A call they have not reached.
     *
     * Judged against the GPS fix when there is one, and simply the first when there is
     * not, so the plan always shows something rather than claiming to know the crew's
     * place.
     */
    private fun nextTransectIndex(plan: FlightPlan): Int {
        val loc = latestLoc ?: return 0
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        if (!e.isFinite() || !n.isFinite()) return 0
        var best = 0
        var bestD = Double.POSITIVE_INFINITY
        plan.transects.forEachIndexed { i, t ->
            val d = Geodesy.distanceProjectedM(e, n, t.from.e, t.from.n)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun pickWaypoint(navigateAfter: Boolean = false) {
        navigateOncePicked = navigateAfter
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        // Prefer what the project chose in Settings; fall back to the built-in default.
        val ps = readProjectSettings(dir)
        val layer = ps.waypointLayer.ifBlank { LayerStyle.Settings().waypointLayer }
        val column = ps.waypointField.ifBlank { LayerStyle.Settings().waypointColumn }
        val names = waypointNames(layer, column)
        if (names.isEmpty()) {
            toast("No waypoints in $layer.$column. Set it in Settings.")
            return
        }
        val labels = names.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Waypoint in $layer")
            .setItems(labels) { _, which -> chooseWaypoint(labels[which], layer, column) }
            .setNeutralButton("Change field") { _, _ ->
                pickLayerThenField("Waypoints", pointLayerNames(dir), dir, layer, column) { l, f ->
                    writeProjectSettings(dir, ps.copy(waypointLayer = l, waypointField = f))
                    toast("Waypoints now $l.$f")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Start navigating as soon as a waypoint has been picked, because the user asked to
     * navigate to one. Cleared again on the way out, so a waypoint later set by tapping
     * the map does not silently launch a trip nobody asked for.
     */
    private var navigateOncePicked = false

    /**
     * Set the chosen waypoint to the point that actually carries that label.
     *
     * Setting only the label left the coordinates at zero, and the readout then measured
     * the distance to UTM 0,0 in this zone - somewhere off West Africa, thousands of
     * kilometres away - and reported it quite confidently.
     */
    private fun chooseWaypoint(
        label: String,
        layerName: String = LayerStyle.Settings().waypointLayer,
        column: String = LayerStyle.Settings().waypointColumn,
    ) {
        applyNavState(nav.setTarget(label))
        // Prefer the layer the label came from, projected; fall back to a drawn layer.
        val dir = mapDir
        val pos = dir?.let { positionInLayer(it, layerName, column, label) } ?: positionOfLabel(label)
        if (pos == null) {
            // the label exists but its position could not be found: say so rather than
            // leaving stale coordinates behind to be measured to
            hasWaypoint = false
            wpLabel = label
            toast("Found \"$label\" but not its position on the map")
            updateOverlays()
            navigateOncePicked = false
            return
        }
        wpE = pos[0]
        wpN = pos[1]
        hasWaypoint = true
        wpLabel = label
        navPlan.setDestination(label, pos[0], pos[1],
            org.gcap.core.nav.NavigationPlan.Destination.Origin.WAYPOINT)
        lastWaypointDest = navPlan.destination
        mapView.setP1P2P3(listOf(Triple(label, wpE, wpN)))
        updateOverlays()
        if (navigateOncePicked) {
            navigateOncePicked = false
            startNavigation()
        }
    }

    /** Working-zone position of the first visible feature labelled [label], or null. */
    private fun positionOfLabel(label: String): DoubleArray? {
        for (layer in mapView.layers.asReversed()) {
            if (!layer.visible) continue
            for (sh in layer.shapes) {
                if (sh.label != label) continue
                // the working-zone position, already projected for display
                val c = sh.parts.firstOrNull()?.firstOrNull() ?: continue
                if (c.size >= 2 && c[0].isFinite() && c[1].isFinite()) {
                    return doubleArrayOf(c[0], c[1])
                }
            }
        }
        return null
    }

    /** Distinct label values from the waypoints layer, or empty when it cannot be read. */
    private fun waypointNames(layerName: String, column: String): List<String> {
        val dir = mapDir ?: return emptyList()
        val col = column.ifBlank { LayerStyle.Settings().waypointColumn }
        return fieldLabels(dir, "$layerName.shp", col)
            .filter { it.isNotEmpty() }.distinct().sorted().take(200)
    }

    /** Position of [label] in [layerName].[column], projected into the working zone. */
    private fun positionInLayer(dir: SafMapDir, layerName: String, column: String, label: String): DoubleArray? {
        fun sidecar(ext: String): ByteArray? {
            val hit = dir.listFiles().firstOrNull {
                it.substringBeforeLast('.').equals(layerName, true) && it.endsWith(".$ext", true)
            } ?: return null
            return dir.readFile(hit)
        }
        val shp = sidecar("shp") ?: return null
        val dbf = sidecar("dbf") ?: return null
        val col = column.ifBlank { LayerStyle.Settings().waypointColumn }
        val idx = org.gcap.core.PointStore.indexOf(dbf, col, label)
        if (idx < 0) return null
        val raw = org.gcap.core.PointStore.coordAt(shp, idx) ?: return null
        val layerCrs = Utm.classify(prjFor(dir, "$layerName.shp"))
        if (layerCrs.kind == Utm.Crs.UTM_METERS) return raw
        val (easting, northing) = Utm.wgs84ToUtmZone(raw[1], raw[0], workingZone())
        return doubleArrayOf(easting, northing)
    }

    /**
     * Settings, currently just the navigation mode.
     *
     * The rest of `settings_1.ods` is not built yet; this is the one setting the main
     * page depends on, since it decides what two of the buttons say.
     */
    /**
     * A list of choices with an explicit Save.
     *
     * These used to apply the moment a row was tapped, which left two problems: there was
     * no way to change your mind before it took effect, and the Settings page behind the
     * dialog kept showing the old value until it was reopened. The list now stays open,
     * Save applies the highlighted row and redraws Settings behind it, and Cancel changes
     * nothing.
     */
    private fun chooseWithSave(
        title: String,
        options: List<String>,
        current: String,
        onSave: (String) -> Unit,
    ) {
        val chosen = intArrayOf(options.indexOf(current).coerceAtLeast(0))
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), chosen[0]) { _, w -> chosen[0] = w }
            .setPositiveButton("Save") { _, _ ->
                if (chosen[0] in options.indices) {
                    onSave(options[chosen[0]])
                    // redraw the page behind so the display field shows the new value
                    showSettings()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readProjectSettings(dir: SafMapDir): org.gcap.core.nav.ProjectSettingsMirror =
        org.gcap.core.nav.ProjectSettingsMirror.parse(dir.readFile("mirshp.json")?.toString(Charsets.UTF_8))

    private fun writeProjectSettings(dir: SafMapDir, value: org.gcap.core.nav.ProjectSettingsMirror) {
        val existing = dir.readFile("mirshp.json")?.toString(Charsets.UTF_8)
        val text = org.gcap.core.nav.ProjectSettingsMirror.writeInto(existing, value)
        if (!(dir.overwriteFile("mirshp.json", text.toByteArray(Charsets.UTF_8)) ||
                dir.createFile("mirshp.json", text.toByteArray(Charsets.UTF_8)))) {
            toast("Could not save settings")
            return
        }
        dir.clearCaches()
        cachedSettings = null
    }

    /** Re-resolve after a settings change, so the next action uses the new choice. */
    private fun applyResolvedSettings() {
        cachedSettings = null
        clearWaypoint()
    }

    /** Point layers in the folder, for the Settings pickers. */
    private fun pointLayerNames(dir: SafMapDir): List<String> =
        dir.listFiles()
            .filter { it.endsWith(".shp", true) }
            .filter { f ->
                val head = runCatching { dir.readHeader(f, 100) }.getOrNull()
                val info = head?.let { runCatching { org.gcap.core.ShpFormat.shpHeader(it) }.getOrNull() }
                org.forestnav.app.map.LayerStyle.kindFor(info?.shapeType) ==
                    MapCanvasView.MapLayer.Kind.POINT
            }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .sorted()

    /** DBF fields of a layer, as "NAME (C)" style labels, for a dropdown. */
    private fun fieldChoices(dir: SafMapDir, layerStem: String): List<String> {
        val stem = layerStem.substringBeforeLast('.')
        val dbf = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
        } ?: return emptyList()
        val bytes = dir.readFile(dbf) ?: return emptyList()
        return runCatching {
            org.gcap.core.PointStore.existingFields(bytes).map { (name, type) -> "$name ($type)" }
        }.getOrDefault(emptyList())
    }

    /**
     * Choose a layer, then a field in it, and only act on Save.
     *
     * Picking used to save immediately and close, so the field could not be checked before
     * it was committed and the Settings page behind kept the old value. Both steps now
     * confirm with Save, and Settings is redrawn once the pair is written.
     */
    private fun pickLayerThenField(
        title: String,
        layers: List<String>,
        dir: SafMapDir,
        currentLayer: String,
        currentField: String,
        onPicked: (layer: String, field: String) -> Unit,
    ) {
        if (layers.isEmpty()) { toast("No point layers in this folder"); return }
        val layerIndex = intArrayOf(layers.indexOf(currentLayer).coerceAtLeast(0))
        android.app.AlertDialog.Builder(this)
            .setTitle("Choose $title layer")
            .setSingleChoiceItems(layers.toTypedArray(), layerIndex[0]) { _, w -> layerIndex[0] = w }
            .setPositiveButton("Next") { _, _ ->
                val layer = layers[layerIndex[0]]
                val fields = fieldChoices(dir, layer)
                if (fields.isEmpty()) {
                    onPicked(layer, currentField)
                    showSettings()
                    return@setPositiveButton
                }
                val start = fields.indexOfFirst { it.startsWith("$currentField (") }
                    .let { if (it >= 0) it else 0 }
                val fieldIndex = intArrayOf(start)
                android.app.AlertDialog.Builder(this)
                    .setTitle("$title field in $layer")
                    .setSingleChoiceItems(fields.toTypedArray(), fieldIndex[0]) { _, w -> fieldIndex[0] = w }
                    .setPositiveButton("Save") { _, _ ->
                        val field = fields[fieldIndex[0]].substringBefore(" (")
                        onPicked(layer, field)
                        showSettings()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Point layers in the folder, for the Settings pickers. */

    private fun showSettings() {
        val dir = mapDir
        val cfg = settings()
        val pad = (10 * density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        /** The one display-field look: yellow on dark blue with a thin green border. */
        fun displayFieldBackground(): android.graphics.drawable.Drawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF10314F.toInt())
                setStroke((2 * density).toInt().coerceAtLeast(2), 0xFF2E7D32.toInt())
            }

        /** Yellow label button with a thin red border: the row's action. */
        fun actionButtonBackground(): android.graphics.drawable.Drawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFD400.toInt())
                setStroke((2 * density).toInt().coerceAtLeast(2), 0xFFD32F2F.toInt())
            }

        fun section(title: String) {
            root.addView(android.widget.TextView(this).apply {
                text = title
                textSize = 12f
                setPadding(0, pad, 0, 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }

        // Read-only values look different from the ones that can be changed, so it is
        // obvious at a glance what this screen will actually let them set.
        /**
         * A row with nothing to set: a plain label and a read-only value. Deliberately not
         * styled as a button, because it does nothing when pressed.
         */
        fun displayRow(label: String, value: String) {
            val line = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            line.addView(android.widget.TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(0xFF10314F.toInt())          // navy on the pale sheet
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            })
            line.addView(android.widget.TextView(this).apply {
                text = value
                textSize = 15f
                // Read-only values: yellow on dark blue, thin green border. Same as the
                // display field beside an action label.
                setTextColor(0xFFFFE45C.toInt())
                background = displayFieldBackground()
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            })
            root.addView(line)
        }

        /**
         * A row whose *label* is the action: the label sits on a yellow button with a red
         * border, and the current value is shown beside it. Used where the label itself is
         * what you act on ("Working folder"), rather than the value.
         */
        fun actionLabelRow(label: String, value: String, onTap: () -> Unit) {
            val line = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            line.addView(android.widget.Button(this).apply {
                text = label
                textSize = 15f
                isAllCaps = false
                setTextColor(0xFF000000.toInt())              // black on yellow
                setBackground(actionButtonBackground())
                setOnClickListener { onTap() }
                layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2)
            })
            // The value beside the action is a read-only display field: yellow on dark
            // blue with a thin green border, so it never looks like something to press.
            line.addView(android.widget.TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(0xFFFFE45C.toInt())
                background = displayFieldBackground()
                setPadding(
                    (10 * density).toInt(), (4 * density).toInt(),
                    (10 * density).toInt(), (4 * density).toInt(),
                )
            })
            root.addView(line)
        }

        /** A row the user can change: the value sits on a black-on-yellow button. */
        fun editableRow(label: String, value: String, onTap: () -> Unit) {
            val line = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            line.addView(android.widget.TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(0xFF10314F.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            })
            line.addView(android.widget.Button(this).apply {
                text = value
                textSize = 14f
                isAllCaps = false
                setTextColor(0xFF000000.toInt())          // black on yellow
                setBackground(actionButtonBackground())
                setOnClickListener { onTap() }
            })
            root.addView(line)
        }

        /** Full-width action button, same yellow-on-red-border look as the main page. */
        fun button(label: String, onTap: () -> Unit) {
            root.addView(android.widget.Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 15f
                setTextColor(0xFF000000.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFFD400.toInt())
                    setStroke((2 * density).toInt().coerceAtLeast(2), 0xFFD32F2F.toInt())
                }
                setOnClickListener { onTap() }
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = (4 * density).toInt()
                }
            })
        }

        /** Start / save / stop / exit: black on orange, unlike the values above. */
        fun commandButton(label: String, onTap: () -> Unit) {
            root.addView(android.widget.Button(this).apply {
                text = label
                isAllCaps = false
                setTextColor(0xFF000000.toInt())
                setBackgroundColor(0xFFFF8C00.toInt())
                setOnClickListener { onTap() }
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = (4 * density).toInt()
                }
            })
        }

        section("PROJECT")
        actionLabelRow(
            "Working folder",
            dir?.let { folderName(it) } ?: "not set",
        ) { pickFolder.launch(null) }

        section("NAVIGATION")
        // Show the mode actually in force, not the stored default: the stored value only
        // changes when Settings is written, so the display used to keep saying on_ground
        // after on_air had been chosen.
        actionLabelRow("Mode", navMode.sheetName()) {
            val modes = NavMode.values().map { it.sheetName() }
            chooseWithSave("Navigation mode", modes, navMode.sheetName()) { picked ->
                applyNavMode(
                    NavMode.values().firstOrNull { it.sheetName() == picked } ?: navMode,
                )
                toast("Mode: $picked")
            }
        }
        actionLabelRow("Waypoint projection limit", "${projectLimitM.toInt()} m") { promptProjectionLimit() }

        // These three are the ones a survey actually changes per project, so each has a
        // button that opens layer-then-field pickers fed from the folder's own DBFs.
        if (dir != null) {
            val layers = pointLayerNames(dir)
            val ps = readProjectSettings(dir)
            actionLabelRow("Go to\u2026", listOf(cfg.goToLayer, cfg.goToField)
                .filter { it.isNotBlank() }.joinToString(" \u00b7 ").ifBlank { "choose" }) {
                pickLayerThenField("Go to", layers, dir, cfg.goToLayer, cfg.goToField) { l, f ->
                    writeProjectSettings(dir, ps.copy(goToLayer = l, goToField = f))
                    applyResolvedSettings()
                }
            }
            actionLabelRow("Waypoints", listOf(ps.waypointLayer, ps.waypointField)
                .filter { it.isNotBlank() }.joinToString(" \u00b7 ").ifBlank { "choose" }) {
                pickLayerThenField("Waypoints", layers, dir, ps.waypointLayer, ps.waypointField) { l, f ->
                    writeProjectSettings(dir, ps.copy(waypointLayer = l, waypointField = f))
                    applyResolvedSettings()
                }
            }
            actionLabelRow("Add point", listOf(cfg.addPointLayer, cfg.addPointNameField)
                .filter { it.isNotBlank() }.joinToString(" \u00b7 ").ifBlank { "choose" }) {
                pickLayerThenField("Add point", layers, dir, cfg.addPointLayer, cfg.addPointNameField) { l, f ->
                    writeProjectSettings(dir, ps.copy(addPointLayer = l, addPointField = f))
                    applyResolvedSettings()
                }
            }
        } else {
            displayRow("Go to\u2026", "${cfg.goToLayer} \u00b7 ${cfg.goToField}")
            displayRow("Waypoints", "choose a folder first")
            displayRow("Add point", "${cfg.addPointLayer} \u00b7 ${cfg.addPointNameField}")
        }
        // These two only report what the app is using; they are not user-settable here.
        displayRow("MOB layer", cfg.mobLayer)
        displayRow("Track layer", cfg.trackLayer)

        section("RECORDING")
        actionLabelRow(
            "Autosave edits",
            cfg.saveEditsEverySec?.let { "every $it s" } ?: "off",
        ) {
            promptProjectNumber(
                "Autosave edits",
                "How often unsaved edits are written. Leave blank to turn autosave off.",
                cfg.saveEditsEverySec?.toString().orEmpty(),
                decimals = false, allowBlank = true,
            ) { ps, v -> ps.copy(saveEditsEverySec = v?.toInt()) }
        }
        actionLabelRow("Track interval", "${cfg.trackIntervalSec} s") {
            promptProjectNumber(
                "Track interval",
                "Seconds between recorded GPS fixes.",
                cfg.trackIntervalSec.toString(), decimals = false,
            ) { ps, v -> ps.copy(trackIntervalSec = v?.toInt()) }
        }
        actionLabelRow("Track minimum distance", "${cfg.trackMinDistanceM.toInt()} m") {
            promptProjectNumber(
                "Track minimum distance",
                "Metres the receiver must move before another point is recorded.",
                cfg.trackMinDistanceM.toInt().toString(), decimals = false,
            ) { ps, v -> ps.copy(trackMinDistanceM = v) }
        }

        section("DISPLAY")
        actionLabelRow("Default scale", "1:${cfg.defaultScaleDenominator}") {
            promptProjectNumber(
                "Default scale",
                "The map scale used when a new map is opened.",
                cfg.defaultScaleDenominator.toString(), decimals = false,
            ) { ps, v -> ps.copy(defaultScaleDenominator = v?.toInt()) }
        }

        button("Layer settings\u2026") { showLayerSettings() }
        button("Verify layers\u2026") { verifyLayers() }
        button("Map report\u2026") { showMapReport() }
        button("Prepare layers\u2026") { showPrepareLayers() }
        button("Heal lines\u2026") { showHealLines() }
        actionLabelRow(
            "Record tracks into a line",
            recordTrackTarget().ifBlank { "off (tracks.shp only)" },
        ) { chooseRecordTrackTarget() }
        // Deliberately not a plain button: cutting deletes a road, so it sits behind a
        // second step that names the layer and the gap rather than being one tap away.
        button("Cut a line\u2026") { showCutLines() }

        // Moved here from the main page. Same handlers, so nothing else changes.
        section("MAP")
        button("Map up: ${mapUpLabel()}") { showMapUpPicker() }
        button("Auto zoom: ${if (autoZoom) "on" else "off"}") { toggleAutoZoom() }
        commandButton("Build a network") { showNetworkBuilder() }
        button("Default CRS: ${defaultCrsLabel()}") { showDefaultCrsPicker() }
        section("FLIGHT")
        actionLabelRow("build_FLIGHT_LINE", lastFlightLineName()) { startBuildFlightLine() }
        if (cutArmed) button("Cancel cut") { cancelCut() }

        // Kept so a folder change can close it: a Settings panel naming the old folder
        // while the map draws the new one is the exact disagreement worth removing.
        settingsDialog?.dismiss()
        // One scroll container, with a fixed window height. The dialog used to wrap this
        // ScrollView in its own, and on the 1200x2000 tablet the two disagreed about the
        // height: the list stopped after "Map report" and the MAP and FLIGHT sections
        // could not be reached. Giving the window a height makes this the only scroller.
        val scroller = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        settingsDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroller)
            .setPositiveButton("Close", null)
            .create()
        // Come back to where the user was: rebuilding the list after a change used to
        // throw them back to the top of a long screen.
        val keep = settingsScrollY
        val dialog = settingsDialog
        dialog?.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                (resources.displayMetrics.heightPixels * 0.82).toInt(),
            )
            if (keep > 0) scroller.post { scroller.scrollTo(0, keep) }
        }
        dialog?.show()
    }

    private var settingsDialog: android.app.AlertDialog? = null

    /** Vertical position of the Settings list, kept across a rebuild. */
    private var settingsScrollY = 0

    /**
     * The Settings list's scroll view, found by walking the dialog's view tree.
     *
     * Looking it up by a fixed id is not safe: the id resolves to a FrameLayout that
     * wraps the list, and casting that to a ScrollView throws on every setting change.
     */
    private fun findSettingsScroller(): android.widget.ScrollView? {
        val root = settingsDialog?.window?.decorView ?: return null
        val queue = ArrayDeque<android.view.View>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is android.widget.ScrollView) return v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
        }
        return null
    }

    /** Set while a cut is armed, so a map tap severs a line instead of dropping a waypoint. */
    private var cutArmed = false

    /** Layer the armed cut will edit. */
    private var cutLayer: String? = null

    /**
     * Cuts made this session, not yet written.
     *
     * Starting one is deliberately awkward - it needs Settings, a layer and a gap. Saving
     * and stopping it use the ordinary main-page buttons, which carry no risk of their own.
     */
    private var lineSession: org.forestnav.app.map.LineEditSession? = null

    /**
     * The layer settings page: which layers exist, and whether each is drawn.
     *
     * Reads the same `layer_settings.ods` the app already uses for styling, so a layer
     * switched off here stays off when the project is reopened.
     */
    /**
     * Layer settings: one row per shapefile in the folder, saved to the folder's mirshp.json.
     *
     * Lists the files on disk rather than the loaded layers, because a layer hidden here is
     * not loaded at all - listing only what is loaded would make it impossible to show again.
     * Modelled on ShpPointCap's LayerSettingsActivity; it reads and writes the same JSON model,
     * so no spreadsheet is needed.
     */
    /** Layer settings being edited. It stays open across edits until Save & exit or Exit. */
    private var layerDraft: MutableList<org.forestnav.app.map.LayerStyle.LayerSpec>? = null
    private var layerDraftDir: SafMapDir? = null
    private var layerDraftDirty = false

    private fun showLayerSettings() {
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        val rows = loadLayerRows(dir)
        if (rows.isEmpty()) { toast("No shapefiles in this folder"); return }
        layerDraft = rows
        layerDraftDir = dir
        layerDraftDirty = false
        showLayerList()
    }

    /** The list of layers. Shown again after every edit, so the screen never closes by itself. */
    private fun showLayerList() {
        val dir = layerDraftDir ?: return
        val rows = layerDraft ?: return
        // Layer settings can be reached before the map has finished probing rasters,
        // so make sure the ON/OFF marks mean something rather than all reading "off".
        if (probeEntries.isEmpty()) {
            Thread {
                val p = probeRaster(dir, readProjectSettings(dir).basemapRaster)
                runOnUiThread { probeEntries = p.entries }
            }.start()
        }
        val labels = rows.map {
            "${if (it.visible) "ON " else "off"}  ${it.name}" +
                (if (it.colorRaw.isNotBlank()) "  ${it.colorRaw}" else "") +
                (if (it.symbolRaw.isNotBlank()) "  ${it.symbolRaw}" else "")
        } + rasterStems(dir).map { stem ->
            val chosen = readProjectSettings(dir).basemapRaster.equals(stem, true)
            val canDraw = probeEntries.any { it.stem.equals(stem, true) && it.renderable }
            (if (chosen) "ON " else "off") + "  $stem" +
                (if (canDraw) "  (raster)" else "  (cannot be drawn)")
        }
        val labelsTyped = labels.toTypedArray()
        val title = "Layer settings" + if (layerDraftDirty) " (unsaved changes)" else ""
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labelsTyped) { _, which ->
                if (which < rows.size) editOneLayer(dir, rows, which)
                else toggleRaster(dir, rasterStems(dir)[which - rows.size])
            }
            .setPositiveButton("Save & exit") { _, _ ->
                saveLayerRows(dir, rows)
                layerDraft = null
                layerDraftDirty = false
            }
            .setNegativeButton("Exit") { _, _ -> exitLayerSettings() }
            .setCancelable(false)
            .show()
    }

    /** Leave without saving. Asks first when there is something to lose. */
    private fun exitLayerSettings() {
        if (!layerDraftDirty) {
            layerDraft = null
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Discard changes?")
            .setMessage("The layer changes have not been saved.")
            .setPositiveButton("Discard") { _, _ -> layerDraft = null; layerDraftDirty = false }
            .setNegativeButton("Keep editing") { _, _ -> showLayerList() }
            .setCancelable(false)
            .show()
    }

    /** Rasters in the folder, renderable or not, for Layer settings. */
    private fun rasterStems(dir: SafMapDir): List<String> =
        dir.listFiles()
            .filter { org.forestnav.app.map.ScaleLayerPolicy.isUnsupportedRaster(it) }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .sorted()

    /**
     * Turn a raster on, or off if it is already the one showing.
     *
     * Only one basemap is drawn at a time. Two rasters of the same ground stacked on
     * each other hide whichever was meant to show through, so choosing one turns off
     * the other rather than adding a second.
     */
    private fun toggleRaster(dir: SafMapDir, stem: String) {
        val ps = readProjectSettings(dir)
        val on = if (ps.basemapRaster.equals(stem, true)) "" else stem
        writeProjectSettings(dir, ps.copy(basemapRaster = on))
        layerDraftDirty = false
        showLayerList()
        applyBasemapChoice(dir)
        toast(if (on.isBlank()) "Basemap off" else "Basemap: $on")
    }

    /** Point the map at the basemap the project settings name, or at none. */
    private fun applyBasemapChoice(dir: SafMapDir) {
        val wanted = readProjectSettings(dir).basemapRaster
        val p = probeRaster(dir, wanted)
        probeEntries = p.entries
        if (wanted.isNotBlank() && p.name == null) {
            toast("$wanted cannot be drawn, so no basemap is shown")
        }
        applyRasterProbe(p)
    }

    /** Say what the app can do with a basemap raster, instead of leaving it unlisted. */
    private fun showRasterInfo(dir: SafMapDir, stem: String) {
        val file = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) &&
                org.forestnav.app.map.ScaleLayerPolicy.isUnsupportedRaster(it)
        } ?: return
        val size = dir.sizeOf(file)
        Thread {
            val pfd = dir.openRandomAccess(file)
            val info = if (pfd == null || size == null) "could not be opened" else {
                val bytes = org.forestnav.app.map.PfdByteSource(pfd, size)
                val g = org.gcap.core.raster.GeoTiff.open(bytes, org.forestnav.app.map.AndroidJpegDecoder())
                when {
                    g == null -> "not a TIFF this app can read"
                    !g.georeferenced -> "no georeferencing, so it cannot be placed on the map"
                    g.unsupported() != null -> g.unsupported()!!.reason
                    else -> "supported, ${g.width} x ${g.height}, compression ${g.compression}"
                }
            }
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle(stem)
                    .setMessage("$file\n${size?.let { "${it / 1024 / 1024} MB" } ?: ""}\n$info")
                    .setPositiveButton("Close", null)
                    .show()
            }
        }.start()
    }

    /**
     * The layer list for this project, creating the file if it is not there yet.
     *
     * A folder with no settings file used to keep working only by accident, because
     * every lookup fell back to a default. The file is now the one place settings live,
     * so it is written on first open: the crew can see and edit what the app decided,
     * rather than having to trust it.
     */
    private fun loadLayerRows(dir: SafMapDir): MutableList<org.forestnav.app.map.LayerStyle.LayerSpec> {
        val raw = dir.readFile("mirshp.json")?.toString(Charsets.UTF_8)
        val existing = raw?.takeIf { it.isNotBlank() }
        val saved = runCatching { org.forestnav.app.map.LayerStyle.parseProject(existing.orEmpty()) }
            .getOrElse { emptyList() }
        val stems = dir.listFiles()
            .filter { it.endsWith(".shp", true) }
            .map { it.substringBeforeLast('.') }
            .filter { st -> !st.startsWith("network_", true) && !st.endsWith("_network", true) }
            .distinct()
        val rows = stems.map { stem ->
            saved.firstOrNull { it.name.equals(stem, true) }
                ?: org.forestnav.app.map.LayerStyle.LayerSpec(
                    name = stem,
                    visible = org.forestnav.app.map.LayerStyle.defaultVisible(stem),
                )
        }.toMutableList()

        if (existing == null) {
            val text = org.forestnav.app.map.LayerStyle.docWithLayers(null, rows)
            val ok = dir.overwriteFile("mirshp.json", text.toByteArray(Charsets.UTF_8)) ||
                dir.createFile("mirshp.json", text.toByteArray(Charsets.UTF_8))
            if (ok) {
                cachedSettings = null
                android.util.Log.i("ForestNav", "created mirshp.json for ${rows.size} layers")
            } else {
                android.util.Log.w("ForestNav", "could not create mirshp.json")
            }
        }
        return rows
    }

    private fun saveLayerRows(dir: SafMapDir, rows: List<org.forestnav.app.map.LayerStyle.LayerSpec>) {
        val existing = dir.readFile("mirshp.json")?.toString(Charsets.UTF_8)
        val text = org.forestnav.app.map.LayerStyle.docWithLayers(existing, rows)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (!(dir.overwriteFile("mirshp.json", bytes) || dir.createFile("mirshp.json", bytes))) {
            toast("Could not save mirshp.json")
            return
        }
        dir.clearCaches()
        toast("Layer settings saved")
        // startMap() ignores a map that is already open, so restart it to pick up the styles.
        if (mapStarted) {
            closeMap()
            startMap()
        }
    }

    /**
     * Edit one layer's settings.
     *
     * Only one layer's controls exist at a time. Building every row at once meant 21 rows
     * with two spinners each, which is a lot of views for a phone to hold, and picking a
     * folder is exactly when a phone is most likely to run out of memory.
     */
    private fun editOneLayer(
        dir: SafMapDir,
        rows: MutableList<org.forestnav.app.map.LayerStyle.LayerSpec>,
        index: Int,
    ) {
        val spec = rows[index]
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val kind = run {
            val shp = dir.listFiles().firstOrNull {
                it.substringBeforeLast('.').equals(spec.name, true) && it.endsWith(".shp", true)
            } ?: return@run MapCanvasView.MapLayer.Kind.POINT
            val head = runCatching { dir.readHeader(shp, 100) }.getOrNull()
            val info = head?.let { runCatching { org.gcap.core.ShpFormat.shpHeader(it) }.getOrNull() }
            org.forestnav.app.map.LayerStyle.kindFor(info?.shapeType)
        }
        val editor = LayerRowEditor(spec, dir, kind)
        content.addView(android.widget.TextView(this).apply { text = spec.name; textSize = 17f })
        content.addView(editor.controls())
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Layer: ${spec.name}")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                rows[index] = editor.toSpec()
                layerDraftDirty = true
                showLayerList()
            }
            .setNeutralButton("Move up") { _, _ ->
                if (index > 0) {
                    val t = rows[index]; rows[index] = rows[index - 1]; rows[index - 1] = t
                    layerDraftDirty = true
                }
                showLayerList()
            }
            .setNegativeButton("Back") { _, _ -> showLayerList() }
            .setCancelable(false)
            .show()
    }

    /** One layer's controls on the Layer settings screen. */
    private inner class LayerRowEditor(
        private val spec: org.forestnav.app.map.LayerStyle.LayerSpec,
        private val dir: SafMapDir,
        private val kind: MapCanvasView.MapLayer.Kind,
    ) {
        private val show = android.widget.CheckBox(this@MapActivity).apply { text = "Show"; isChecked = spec.visible }
        private val edit = android.widget.CheckBox(this@MapActivity).apply { text = "Editable"; isChecked = spec.editable }
        private val symbol = spinnerOf(
            org.forestnav.app.map.LayerStyle.symbolNamesFor(kind), spec.symbolRaw,
        )
        // Real swatches rather than a list of words: "blue" on a white dialog tells you
        // nothing about what the layer will look like on the map.
        private val colour = ColourChooser(spec.colorRaw)
        private val size = android.widget.EditText(this@MapActivity).apply {
            hint = "default"; setText(spec.sizeDp?.toString().orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        // Label field is chosen from the layer's own DBF, not typed: guessing a field name
        // and getting it wrong silently draws no labels at all.
        private val labelChoices: List<String> = run {
            val stem = spec.name
            val dbf = dir.listFiles().firstOrNull {
                it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
            }
            val bytes = dbf?.let { dir.readFile(it) }
            val fields = if (bytes == null) emptyList() else runCatching {
                org.gcap.core.PointStore.existingFields(bytes).map { it.first }
            }.getOrDefault(emptyList())
            listOf("") + fields
        }
        private var chosenLabel: String = spec.labelField.orEmpty().let {
            if (it.isNotEmpty() && labelChoices.none { f -> f == it }) labelChoices.firstOrNull() ?: "" else it
        }
        private val labelSize = android.widget.EditText(this@MapActivity).apply {
            hint = "default"; setText(spec.labelSizeSp?.toString().orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        private val labelSpinner = android.widget.Spinner(this@MapActivity).apply {
            adapter = android.widget.ArrayAdapter(
                this@MapActivity, android.R.layout.simple_spinner_dropdown_item, labelChoices,
            )
            setSelection(labelChoices.indexOf(chosenLabel).coerceAtLeast(0))
        }

        /**
     * A row of tappable colour swatches for one layer.
     *
     * Replaces a spinner full of colour names. The names were there but easy to miss,
     * and "yellow" or "white" gives no idea how a layer will actually look drawn over
     * the basemap, so the swatch shows the colour itself. The current colour is marked,
     * and tapping it again clears the override so the layer goes back to its default.
     */
    private inner class ColourChooser(current: String) {
        var selected: String = current.trim()

        private val chosen = current.trim()

        fun controls(): android.view.View {
            val wrap = android.widget.LinearLayout(this@MapActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
            }
            val row = android.widget.LinearLayout(this@MapActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            wrap.addView(android.widget.TextView(this@MapActivity).apply {
                text = "Colour"
                textSize = 14f
            })
            val caption = android.widget.TextView(this@MapActivity).apply {
                textSize = 12f
            }
            val names = org.forestnav.app.map.LayerStyle.colourNames()
            for (name in names) {
                val b = android.widget.Button(this@MapActivity)
                val size = (26 * density).toInt().coerceAtLeast(26)
                b.layoutParams = android.widget.LinearLayout.LayoutParams(
                    size + (6 * density).toInt(), size,
                ).apply { marginEnd = (4 * density).toInt() }
                b.background = org.forestnav.app.map.LayerStyle.swatchDrawable(
                    org.forestnav.app.map.LayerStyle.colorOf(name),
                    isSelected = name.equals(chosen, true),
                )
                b.setOnClickListener {
                    // tapping the colour already set clears it, so a layer can go back
                    // to whatever the app would choose by itself
                    selected = if (name.equals(selected, true)) "" else name
                    caption.text = captionFor(selected)
                    for (child in row.children()) {
                        val idx = row.indexOfChild(child)
                        val nm = names.getOrNull(idx)
                        child.background = org.forestnav.app.map.LayerStyle.swatchDrawable(
                            org.forestnav.app.map.LayerStyle.colorOf(nm ?: ""),
                            isSelected = nm.equals(selected, true),
                        )
                    }
                }
                row.addView(b)
            }
            wrap.addView(android.widget.ScrollView(this@MapActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
            caption.text = captionFor(selected)
            wrap.addView(caption)
            return wrap
        }

        private fun captionFor(value: String): String =
            if (value.isBlank()) "default" else value

        private fun android.view.ViewGroup.children(): List<android.view.View> {
            val out = ArrayList<android.view.View>()
            for (i in 0 until childCount) out.add(getChildAt(i))
            return out
        }
    }

    private fun spinnerOf(options: List<String>, current: String): android.widget.Spinner =
            android.widget.Spinner(this@MapActivity).apply {
                adapter = android.widget.ArrayAdapter(
                    this@MapActivity, android.R.layout.simple_spinner_dropdown_item, options,
                )
                setSelection(options.indexOf(current).coerceAtLeast(0))
            }

        fun controls(): android.view.View =
            android.widget.LinearLayout(this@MapActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, pad4(), 0, pad4())
                addView(android.widget.LinearLayout(this@MapActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    addView(show); addView(edit)
                })
                addView(captionRow("Symbol", symbol))
                // The swatches need a row of their own: crammed in beside the symbol
                // spinner they were squeezed to two chips wide and overlapped.
                addView(colour.controls())
                // One row per setting, each with its own caption. These three used to
                // share a single line whose only labels were the words "label" and a
                // truncated "label s", so working out which box was the symbol size and
                // which was the label size was pure guesswork for anyone new to it.
                addView(captionRow("Symbol size (dp)", size))
                addView(captionRow("Label field", labelSpinner))
                addView(captionRow("Label size (sp)", labelSize))
            }

        /** A captioned setting on its own row, the caption always visible. */
        private fun captionRow(caption: String, control: android.view.View): android.view.View =
            android.widget.LinearLayout(this@MapActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad4(), 0, pad4())
                addView(android.widget.TextView(this@MapActivity).apply {
                    text = caption
                    textSize = 14f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (118 * resources.displayMetrics.density).toInt(),
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                })
                addView(
                    control,
                    android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ),
                )
            }

        private fun pad4() = (4 * resources.displayMetrics.density).toInt()

        fun toSpec(): org.forestnav.app.map.LayerStyle.LayerSpec =
            org.forestnav.app.map.LayerStyle.LayerSpec(
                name = spec.name,
                visible = show.isChecked,
                editable = edit.isChecked,
                symbolRaw = symbol.selectedItem as String,
                colorRaw = colour.selected,
                sizeDp = size.text.toString().toFloatOrNull(),
                labelField = (labelSpinner.selectedItem as? String)?.trim()?.takeIf { it.isNotEmpty() },
                labelSizeSp = labelSize.text.toString().toFloatOrNull(),
            )
    }

    private val density: Float get() = resources.displayMetrics.density

    /** Human name of the folder SAF granted, so the user can see which project is open. */
    /**
     * The folder's name, or a plain statement that it has gone.
     *
     * "selected" told the crew nothing: a folder that has been deleted, renamed, or is on
     * an SD card that was pulled out still holds a saved URI, so the app believes it has a
     * project and every layer read fails quietly. Saying so is the difference between a
     * crew noticing and a crew driving blind.
     */
    private fun folderName(dir: SafMapDir): String {
        val doc = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(this, dir.treeUri)
        }.getOrNull()
        val name = runCatching { doc?.name }.getOrNull()
        return when {
            doc == null || !doc.exists() || doc.isDirectory.not() -> "no longer available - tap to choose again"
            name.isNullOrBlank() -> "selected"
            else -> name
        }
    }

    /** B25: how far off the network a waypoint may be and still be projected onto it. */
    /**
     * Edit one number, with Save and Cancel, and write it into the project's own settings.
     *
     * Nothing is committed until Save, and the Settings page behind is redrawn so the new
     * value is visible at once. These live in the project file rather than device
     * preferences, so they travel with the folder.
     */
    private fun promptProjectNumber(
        title: String,
        hint: String,
        initial: String,
        decimals: Boolean,
        allowBlank: Boolean = false,
        apply: (org.gcap.core.nav.ProjectSettingsMirror, Double?) -> org.gcap.core.nav.ProjectSettingsMirror,
    ) {
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        val input = android.widget.EditText(this).apply {
            setText(initial)
            inputType = if (decimals) {
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                android.text.InputType.TYPE_CLASS_NUMBER
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(hint)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text.toString().trim()
                val v = if (raw.isEmpty()) {
                    if (!allowBlank) { toast("Enter a value"); return@setPositiveButton }
                    null
                } else {
                    val d = raw.toDoubleOrNull()
                    if (d == null || d < 0) { toast("Enter a number"); return@setPositiveButton }
                    // A number too large for the field silently became Int.MAX_VALUE and
                    // read as a ridiculous interval, so reject it instead.
                    if (d > 1_000_000.0) { toast("That value is too large"); return@setPositiveButton }
                    d
                }
                val ps = readProjectSettings(dir)
                writeProjectSettings(dir, apply(ps, v))
                applyResolvedSettings()
                toast("$title ${v?.let { fmtNumber(it) } ?: "off"}")
                showSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Whole numbers read better without a trailing ".0". */
    private fun fmtNumber(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    private fun promptProjectionLimit() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(projectLimitM.toInt().toString())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Waypoint projection limit")
            .setMessage(
                "A waypoint within this distance of the chosen network is delivered on the " +
                    "line itself. Beyond it you are asked what to do."
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim().toDoubleOrNull()
                if (v == null || v < 0) {
                    toast("Enter a distance in metres")
                } else {
                    projectLimitM = v
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putFloat(PREF_PROJECT_LIMIT, v.toFloat()).apply()
                    toast("Projection limit ${v.toInt()} m")
                    showSettings()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyNavMode(mode: NavMode) {
        val state = nav.setMode(mode)
        navMode = mode                        // rebuilds the grid with the new labels
        applyModeVisibility()
        applyNavState(state)
        // remembered, so the choice survives leaving and reopening the app
        runCatching {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_NAV_MODE, mode.name).apply()
        }
    }

    /** Layers the current mode shows: the saved or default choice, plus the air layers in AIR. */
    private fun applyModeVisibility() {
        for (l in mapView.layers) {
            l.visible = l.baseVisible || (navMode == NavMode.AIR &&
                org.forestnav.app.map.LayerStyle.airModeAdds(l.name))
        }
        mapView.requestDraw()
        // a layer that was hidden has no shapes loaded; load them now that it is shown
        if (mapStarted) loadViewport(false)
    }

    private fun relayout() {
        val root = rootLayout ?: return
        val bar = leftPanel ?: return
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        (bar.parent as? ViewGroup)?.removeView(bar)
        (mapView.parent as? ViewGroup)?.removeView(mapView)
        root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        root.addView(
            bar,
            if (landscape) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f)
                    .apply { gravity = android.view.Gravity.TOP }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )
        root.addView(
            mapView,
            if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        refreshCommands()
    }

    /**
     * Rebuild the command buttons so state-dependent labels (Follow, Map up,
     * Auto zoom) reflect what is actually happening. The button set is fixed by the
     * ODS layout, only the labels move.
     */
    private fun refreshCommands() {
        val bar = leftPanel ?: return
        bar.removeAllViews()
        for ((i, cmd) in commands.withIndex()) {
            bar.addView(commandButton(cmd), gridParams(i))
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // configChanges keeps the activity alive across rotation, so the view
        // tree has to be re-flowed by hand.
        super.onConfigurationChanged(newConfig)
        relayout()
    }

    private fun followLabel(): String =
        if (follow) "Follow: on" else "Follow: off"

    /**
     * Build one network per routable layer, from the whole project folder.
     *
     * Reads the shapefiles directly rather than reusing what the map has loaded, so the
     * network covers the project rather than the current viewport: a crew that drives
     * ten kilometres from where they built it must still be routed, not quietly dropped
     * onto a straight line. Each layer is noded separately - see [networks] - and the
     * result is written out so the build can be inspected rather than trusted.
     */

    /** What [buildNetworks] hands back: the networks, their report, and what to say. */
    private class NetworkBuildResult(
        networks: Map<String, org.gcap.core.nav.NetworkGraph> = emptyMap(),
        val report: List<Pair<String, String>> = emptyList(),
        val message: String = "",
    ) {
        /** Filled in by a build so the map can use the graph without a restart. */
        var networks: Map<String, org.gcap.core.nav.NetworkGraph> = networks
    }

    /**
     * The work itself, off the UI thread.
     *
     * Reads every routable layer, nodes it on its own and writes the result out. Runs
     * in the background because the whole project takes seconds to node, and blocking
     * the map for that long reads as a hang rather than as work being done.
     */
    private fun buildNetworks(dir: SafMapDir): NetworkBuildResult {
        val tl = System.currentTimeMillis()
        val allFiles = dir.listFiles()
        android.util.Log.i("NavDiag", "loadNetworks: dir.listFiles() ${System.currentTimeMillis() - tl}ms n=${allFiles.size}")
        val routable = allFiles.filter { f ->
            f.endsWith(".shp", true) && isRoutableLayer(f.substringBeforeLast('.')) &&
                !f.startsWith("network_", true) &&
                !Regex("^[A-Z]+_network$", RegexOption.IGNORE_CASE).matches(f.substringBeforeLast('.'))
        }
        if (routable.isEmpty()) {
            return NetworkBuildResult(
                emptyMap(), emptyList(), "No routable layers (road/access/trail) in this project",
            )
        }

        val t0 = System.currentTimeMillis()
        val built = LinkedHashMap<String, org.gcap.core.nav.NetworkGraph>()
        val reports = LinkedHashMap<String, org.gcap.core.nav.BuildReport>()

        for (f in routable) {
            val stem = f.substringBeforeLast('.')
            val prj = prjFor(dir, f)
            val layerCrs = runCatching { Utm.classify(prj) }.getOrNull()
            if (layerCrs == null || layerCrs.kind == Utm.Crs.OTHER) continue
            val bytes = runCatching { dir.readFile(f) }.getOrNull() ?: continue
            val raw = try {
                ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
            } catch (e: Exception) { continue }
            val foreignZone = layerCrs.kind == Utm.Crs.UTM_METERS && layerCrs.utmZone != workingZone()
            val geo = layerCrs.kind == Utm.Crs.WGS84_DEGREES || layerCrs.kind == Utm.Crs.ALBERS || foreignZone
            val shapes = if (geo) projectShapes(raw, layerCrs.kind, f, layerCrs.utmZone) else raw

            val cls = routeClassFor(stem)
            val speed = when (cls) {
                RouteClass.ROAD -> 40.0
                RouteClass.ACCESS -> 15.0
                RouteClass.TRAIL -> 3.0
                RouteClass.BIKE -> 12.0
                RouteClass.AIR -> 120.0
            }
            val sources = ArrayList<SourceLine>()
            for (sh in shapes) {
                for (part in sh.parts) {
                    if (part.size < 2) continue
                    val pts = simplify(part, networkSimplifyM)
                    if (pts.size < 2) continue
                    val flat = DoubleArray(pts.size * 2)
                    var k = 0
                    for (p in pts) {
                        if (p.size < 2 || !p[0].isFinite() || !p[1].isFinite()) continue
                        flat[k * 2] = p[0]; flat[k * 2 + 1] = p[1]; k++
                    }
                    if (flat.size < 4) continue
                    sources.add(SourceLine(flat, stem, cls, OneWay.BOTH, speed))
                }
            }
            if (sources.isEmpty()) continue

            val (graph, report) = try {
                NetworkBuilder(networkToleranceM).build(sources)
            } catch (e: Exception) {
                return NetworkBuildResult(
                    emptyMap(), emptyList(),
                    "$stem: build failed (${e.message ?: e.javaClass.simpleName})",
                )
            }
            if (graph.edges.isEmpty()) continue
            built[stem] = graph
            reports[stem] = report
        }

        if (built.isEmpty()) {
            return NetworkBuildResult(emptyMap(), emptyList(), "No usable routable line geometry")
        }

        var written = 0
        var failed: String? = null
        for ((stem, graph) in built) {
            val out = NetworkWriter.write(
                graph, zone = workingZone(), datum = NetworkWriter.Datum.NAD83,
                baseName = networkFileName(stem).removeSuffix(".shp"),
            )
            for ((name, bytes) in out) {
                if (writeShapefilePart(dir, name, bytes)) written++ else failed = name
            }
        }

        val ms = System.currentTimeMillis() - t0
        val selStem = defaultNetworkStem()
        val sel = selStem?.let { reports[it] }
        val report = listOf(
            "Networks" to "${built.size}",
            "Selected" to (selStem ?: "-"),
            "Nodes" to "${sel?.nodeCount ?: 0}",
            "Segments" to "${sel?.edgeCount ?: 0}",
            "Regions" to "${sel?.regionCount ?: 0}",
            "Files" to "$written" + (failed?.let { " (failed: $it)" } ?: ""),
            "Time" to "${ms}ms",
        )
        val message = if (failed == null) {
            "Built ${built.size} network(s) in ${ms}ms: " +
                reports.entries.joinToString { "${it.key} ${it.value.edgeCount} seg" }
        } else {
            "Built ${built.size} network(s), $failed failed"
        }
        return NetworkBuildResult(built, report, message)
    }

    /**
     * Write one part of a shapefile, creating it when it is not there yet.
     *
     * A network writes .shp/.shx/.dbf/.prj in turn, and the .prj was arriving without the
     * others: a provider that reports failure for a not-yet-existing file made the caller
     * skip its createFile fallback. Both routes are tried, then the folder is re-read to
     * confirm the bytes really landed.
     */
    private fun writeShapefilePart(dir: SafMapDir, name: String, bytes: ByteArray): Boolean {
        if (dir.childExists(name)) {
            if (dir.overwriteFile(name, bytes)) return true
        } else if (dir.createFile(name, bytes)) {
            return true
        }
        // One retry: the first createFile may have been refused because a listing was stale.
        return dir.overwriteFile(name, bytes) || dir.createFile(name, bytes)
    }

    // ---- build a network, one layer at a time -------------------------------

    /** Line layers that could become a network, with why they might not be worth it. */
    private data class NetworkCandidate(
        val stem: String,
        val cls: org.gcap.core.nav.RouteClass,
        val features: Int,
        val vertices: Int,
        val alreadyBuilt: Boolean,
    )

    private fun networkCandidates(dir: SafMapDir): List<NetworkCandidate> {
        val built = dir.listFiles()
            .filter { it.endsWith(".shp", true) }
            .map { it.substringBeforeLast('.').lowercase() }
            .filter { it.endsWith("_network") || it.startsWith("network_") }
            .toSet()
        return dir.listFiles()
            .filter { it.endsWith(".shp", true) }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .filter { isRoutableLayer(it) }
            .filterNot { it.lowercase() in built }
            .sorted()
            .map { stem ->
                var feats = 0
                var verts = 0
                runCatching {
                    val head = dir.readHeader("$stem.shp", 100)
                    val info = head?.let { runCatching { org.gcap.core.ShpFormat.shpHeader(it) }.getOrNull() }
                    if (info != null) {
                        val bytes = dir.readFile("$stem.shp")
                        if (bytes != null) {
                            val shapes = org.gcap.core.ShpFormat.readShp(
                                bytes, info.xmin, info.ymin, info.xmax, info.ymax,
                            )
                            feats = shapes.size
                            verts = shapes.sumOf { sh -> sh.parts.sumOf { part -> part.size } }
                        }
                    }
                }
                NetworkCandidate(stem, routeClassFor(stem), feats, verts, false)
            }
    }

    /**
     * Choose one layer and build its network.
     *
     * This replaces a single button that built everything at once, which gave no clue what
     * was built or what failed. Each layer now reports its own tolerances, is confirmed
     * before any writing, and produces its own report.
     */
    private fun showNetworkBuilder() {
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        val cands = networkCandidates(dir)
        if (cands.isEmpty()) {
            toast("No line layers left to build. Every routable layer already has a network.")
            return
        }
        val labels = cands.map {
            "${it.cls.name.lowercase().replaceFirstChar { c -> c.uppercase() }}  ${it.stem}  " +
                "(${it.features} lines, ${it.vertices} points)"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Build a network")
            .setItems(labels) { _, which -> confirmNetworkBuild(dir, cands[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Confirm before building: the write replaces the layer's saved network, and the
     * tolerances change the shape of the result, so both are shown before anything happens.
     */
    private fun confirmNetworkBuild(dir: SafMapDir, cand: NetworkCandidate) {
        val pad = (10 * density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        /** A dialog field whose value can be read after the dialog is dismissed. */
        class NumField(val view: android.widget.EditText, val initial: Double) {
            fun get(): Double = view.text.toString().trim().toDoubleOrNull() ?: initial
        }
        fun field(label: String, value: Double, hintText: String): NumField {
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            box.addView(android.widget.TextView(this).apply { text = "$label: ${value.toInt()} m" })
            val e = android.widget.EditText(this).apply {
                setText(value.toInt().toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            box.addView(e)
            box.addView(android.widget.TextView(this).apply { text = hintText; textSize = 11f })
            root.addView(box)
            return NumField(e, value)
        }
        val sField = field("Simplify", 7.0, "Thins points along each line. Fewer points, faster build.")
        val wField = field("Weld", 7.0, "Joins line ends that stop this close together.")
        // Only roads carry near-parallel duplicates worth folding together.
        val mergeEnabled = cand.cls == org.gcap.core.nav.RouteClass.ROAD
        var mField: NumField? = null
        if (mergeEnabled) {
            mField = field(
                "Merge parallel", 5.0,
                "Joins lines running within this distance of each other. " +
                    "For roads copied from several sources.",
            )
        }
        root.addView(TextView(this).apply {
            text =
                "${cand.features} lines, ${cand.vertices} points.\n\n" +
                    "This writes ${networkFileName(cand.stem)}.shp and its sidecars, " +
                    "replacing any network already saved for this layer.\n\n" +
                    "Your line layer is not changed."
        })

        android.app.AlertDialog.Builder(this)
            .setTitle("Build ${cand.stem} network?")
            // Scrollable, and the explanation moved inside it. A custom view plus a
            // setMessage stack into one fixed-height panel, which on the 720x1560 BV5200
            // squeezed the button row down to a couple of pixels of CANCEL and BUILD.
            // One bounded body scrolls; the buttons stay laid out and visible.
            .setView(ScrollView(this).apply {
                isFillViewport = true
                addView(root)
            })
            .setPositiveButton("BUILD") { _, _ ->
                val s = sField.get()
                val w = wField.get()
                val m = mField?.get()
                buildOneNetwork(dir, cand, s, w, m)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** How often the map view is written to preferences; not worth doing per fix. */
    private val VIEW_SAVE_INTERVAL_MS = 5000L
    private var lastViewSavedAtMs = 0L

    private fun buildOneNetwork(
        dir: SafMapDir,
        cand: NetworkCandidate,
        simplifyM: Double,
        weldM: Double,
        mergeM: Double?,
    ) {
        val body = android.widget.TextView(this).apply {
            text = "Building ${cand.stem}..."
            textSize = 15f
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), 0)
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("Network")
            .setView(body)
            .setNegativeButton("Close", null)
            .setCancelable(false)
            .create()
        dlg.show()
        Thread {
            // A build over a large layer takes minutes; say which stage it is on, so a
            // working build does not look like a frozen app.
            val stage = java.util.concurrent.atomic.AtomicReference("Reading layer")
            val ticker = Thread {
                val startedAt = System.currentTimeMillis()
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(1000)
                        val secs = (System.currentTimeMillis() - startedAt) / 1000
                        runOnUiThread {
                            if (dlg.isShowing) {
                                body.text = "Building ${cand.stem}...\n${stage.get()}\n${secs}s"
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    // normal: the build finished and stopped the ticker
                }
            }
            ticker.isDaemon = true
            ticker.start()
            var built: NetworkBuildResult? = null
            val text = runCatching {
                buildNetworkText(dir, cand, simplifyM, weldM, mergeM, stage, built)
            }.getOrElse { "Failed: ${it.javaClass.simpleName}: ${it.message}" }
            ticker.interrupt()
            runOnUiThread {
                // Register what was just built. Without this the files are on disk but
                // the map still routes on nothing, and Navigate reports no network.
                built?.let { r ->
                    if (r.networks.isNotEmpty()) {
                        networks = networks + r.networks
                        selectedNetworkStem = r.networks.keys.firstOrNull()
                        selectedNetworkStem?.let {
                            val tr = System.currentTimeMillis()
            navRouter = org.gcap.core.nav.Router(r.networks.getValue(it))
            android.util.Log.i("NavDiag", "Router($it) built in ${System.currentTimeMillis() - tr}ms")
                            if (navigating) plotNavRoute()
                        }
                        updateNetworkReport()
                    }
                }
            }
            runOnUiThread {
                if (dlg.isShowing) dlg.dismiss()
                android.app.AlertDialog.Builder(this)
                    .setTitle("${cand.stem} network")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    /** Do the work off the UI thread and describe what came out. */
    private fun buildNetworkText(
        dir: SafMapDir,
        cand: NetworkCandidate,
        simplifyM: Double,
        weldM: Double,
        mergeM: Double?,
        stage: java.util.concurrent.atomic.AtomicReference<String>,
        sink: NetworkBuildResult?,
    ): String {
        val bytes = dir.readFile("${cand.stem}.shp") ?: return "Could not read ${cand.stem}.shp"
        val info = org.gcap.core.ShpFormat.shpHeader(bytes) ?: return "Could not read the header"
        val raw = org.gcap.core.ShpFormat.readShp(bytes, info.xmin, info.ymin, info.xmax, info.ymax)
            .map { org.gcap.core.prep.LineHealing.Line(it.parts, it.recordIndex) }

        var lines = raw
        val notes = ArrayList<String>()
        stage.set("Simplifying")
        if (simplifyM > 0) {
            val before = lines.sumOf { l -> l.parts.sumOf { p -> p.size } }
            lines = org.gcap.core.prep.LineHealing.simplifyCollinear(lines, simplifyM).lines
            val after = lines.sumOf { l -> l.parts.sumOf { p -> p.size } }
            notes += "simplify ${simplifyM.toInt()} m: $before -> $after points"
        }
        stage.set("Merging parallel lines")
        if (mergeM != null && mergeM > 0) {
            val before = lines.size
            lines = org.gcap.core.prep.ParallelMerge.apply(lines, mergeM)
            notes += "merge ${mergeM.toInt()} m: $before -> ${lines.size} lines"
        }

        val sources = lines.map { l ->
            val flat = DoubleArray(l.parts.sumOf { p -> p.size } * 2)
            var k = 0
            for (p in l.parts) for (xy in p) { flat[k++] = xy[0]; flat[k++] = xy[1] }
            org.gcap.core.nav.SourceLine(flat, cand.stem, cand.cls)
        }
        stage.set("Welding and building graph")
        val tBuild = System.currentTimeMillis()
        val (graph, report) = org.gcap.core.nav.NetworkBuilder(
            toleranceM = weldM,
            allowSupervisedGaps = true,
        ).build(sources)
        val buildMs = System.currentTimeMillis() - tBuild
        if (report.edgeCount == 0) return "No usable lines: the network came out empty."

        // Hand the graph back so the map can route on it straight away, rather than
        // only after a restart.
        @Suppress("UNUSED_EXPRESSION")
        run {
            if (sink != null) sink.networks = mapOf(cand.stem to graph)
        }

        stage.set("Writing files")
        val base = networkFileName(cand.stem).removeSuffix(".shp")
        val written = org.gcap.core.nav.NetworkWriter.write(
            graph, zone = workingZone(), datum = org.gcap.core.nav.NetworkWriter.Datum.NAD83,
            baseName = base,
        )
        val missing = ArrayList<String>()
        for ((name, content) in written) {
            if (!(dir.overwriteFile(name, content) || dir.createFile(name, content))) {
                missing += name
            }
        }
        dir.clearCaches()

        val totalKm = graph.edges.sumOf { it.lengthM } / 1000.0
        val sb = StringBuilder()
        sb.append("Network: ").append(base).append("\n")
        sb.append("Length: ").append("%.1f".format(totalKm)).append(" km\n")
        sb.append("Nodes: ").append(report.nodeCount).append("\n")
        sb.append("Branches: ").append(report.edgeCount).append(" edges\n")
        sb.append("Regions: ").append(report.regionCount).append("\n")
        sb.append("Welded ends: ").append(report.snappedEndpointCount).append("\n")
        sb.append("Split at crossings: ").append(report.intersectionSplitCount).append("\n")
        sb.append("Build time: ").append(buildMs / 1000.0).append(" s\n")
        sb.append("Largest region: ").append(report.maxRegionEdgeCount)
            .append(" edges, ").append("%.1f".format(report.maxRegionLengthKm)).append(" km\n")
        sb.append("\nPrepared:\n")
        for (n in notes) sb.append("- ").append(n).append("\n")
        sb.append("\nFiles written: ").append(written.keys.joinToString(", ") {
            it.substringAfterLast('.')
        }).append("\n")
        if (missing.isNotEmpty()) {
            sb.append("NOT written: ").append(missing.joinToString(", "))
        } else {
            val check = written.keys.map { it.substringAfterLast('.') }
            val present = check.filter { dir.childExists("${base}.$it") }
            sb.append("Verified on disk: ").append(present.joinToString(", "))
        }
        if (report.regionCount > 1) {
            sb.append("\n\nThe network has ").append(report.regionCount)
                .append(" separate areas. A route is only possible inside one of them;")
                .append(" across a gap the app says the two points are not connected.")
        }
        return sb.toString()
    }

    /**
     * Find a raster in the folder that can be drawn, and start decoding the view.
     *
     * The first supported one wins. Which that is matters: a project may hold both an
     * orthophoto and a lower-resolution mosaic, and picking the wrong one puts blurry
     * imagery under a detailed survey. Rasters that cannot be decoded are named, so a
     * folder that looks blank says why instead of just being blank.
     */
    /** Outcome of looking for a drawable raster. Pure I/O, so it runs off the UI thread. */
    /** One raster in the folder, and whether this app can draw it. */
    private data class RasterEntry(
        val stem: String,
        val renderable: Boolean,
        val reason: String,
        val geo: org.gcap.core.raster.GeoTiff? = null,
        val bytes: org.forestnav.app.map.PfdByteSource? = null,
    )

    private class RasterProbe(
        val candidatesPresent: Boolean,
        val name: String?,
        val geo: org.gcap.core.raster.GeoTiff?,
        val bytes: org.forestnav.app.map.PfdByteSource?,
        val rejected: List<String>,
    ) {
        /** Every raster found, renderable or not, for Layer settings and Verify layers. */
        var entries: List<RasterEntry> = emptyList()
    }

    /**
     * Look at every raster in the folder and work out which can be drawn.
     *
     * This used to stop at the first one that opened, so a second, better basemap in
     * the folder was invisible and could not be chosen. Now each is examined and
     * reported on, and [preferred] decides which one is actually used.
     */
    private fun probeRaster(dir: SafMapDir, preferred: String = ""): RasterProbe {
        val candidates = dir.listFiles().filter { org.forestnav.app.map.ScaleLayerPolicy.isUnsupportedRaster(it) }
        if (candidates.isEmpty()) return RasterProbe(false, null, null, null, emptyList())

        val entries = ArrayList<RasterEntry>()
        for (f in candidates) {
            val stem = f.substringBeforeLast('.')
            if (entries.any { it.stem.equals(stem, true) }) continue
            val size = dir.sizeOf(f)
            if (size == null) {
                entries += RasterEntry(stem, false, "size could not be read")
                continue
            }
            val pfd = dir.openRandomAccess(f)
            if (pfd == null) {
                entries += RasterEntry(stem, false, "could not be opened")
                continue
            }
            val bytes = org.forestnav.app.map.PfdByteSource(pfd, size)
            val g = org.gcap.core.raster.GeoTiff.open(bytes, org.forestnav.app.map.AndroidJpegDecoder())
            when {
                g == null -> {
                    bytes.close()
                    entries += RasterEntry(stem, false, "not a TIFF this app can read")
                }
                !g.georeferenced -> {
                    bytes.close()
                    entries += RasterEntry(stem, false, "no georeferencing, so it cannot be placed on the map")
                }
                g.unsupported() != null -> {
                    val why = g.unsupported()!!.reason
                    bytes.close()
                    entries += RasterEntry(stem, false, why.substringBefore('.'))
                }
                else -> entries += RasterEntry(
                    stem, true, "${g.width} x ${g.height}, compression ${g.compression}", g, bytes,
                )
            }
        }

        val rejected = entries.filter { !it.renderable }.map { "${it.stem} (${it.reason})" }
        // The chosen basemap wins; otherwise the first that can be drawn.
        val chosen = entries.firstOrNull { it.renderable && it.stem.equals(preferred, true) }
            ?: entries.firstOrNull { it.renderable }
        val p = RasterProbe(true, chosen?.stem, chosen?.geo, chosen?.bytes, rejected)
        p.entries = entries
        return p
    }

    /** Runs on the UI thread: swap in the probed raster and close the one it replaces. */
    private fun applyRasterProbe(p: RasterProbe) {
        if (!p.candidatesPresent) {
            rasterSource = null
            rasterName = null
            mapView.setRaster(null, null, null)
            return
        }
        rasterBytes?.close()
        rasterBytes = p.bytes
        rasterSource = p.geo
        rasterName = p.name
        if (p.geo == null) {
            // Nothing drawable was chosen. The bitmap already decoded for a previous
            // choice stays on the map unless it is explicitly dropped here, so switching
            // the basemap off left it drawn and the layer list said otherwise.
            mapView.setRaster(null, null, null)
            toast("No drawable raster: ${p.rejected.joinToString(", ")}")
        }
        refreshRaster()
    }

    private var rasterBytes: org.forestnav.app.map.PfdByteSource? = null

    /**
     * Decode the visible window of the basemap, off the UI thread.
     *
     * Re-decoded when the view has moved outside what is held, so panning fetches new
     * ground rather than stretching the old picture.
     */
    private fun refreshRaster() {
        val g = rasterSource ?: return
        if (!mapStarted) return
        if (mapView.width <= 0) {
            // the view has not been laid out yet; a raster cannot be decoded for a
            // window of nothing, so try again once it has a size
            mapView.postDelayed({ refreshRaster() }, 250)
            return
        }
        val vb = mapView.viewportBounds()
        val stamp = "%.0f,%.0f,%.0f,%.0f,%d".format(
            vb[0], vb[1], vb[2], vb[3], mapView.scaleDenominator,
        )
        if (stamp == rasterStamp && mapView.rasterCovers(vb)) return
        rasterStamp = stamp
        val w = mapView.width
        val h = mapView.height
        val world = vb.copyOf()
        // Every decode holds a full screen of pixels twice over (an IntArray and a
        // Bitmap). Panning used to start a new thread each time and keep them all, which
        // ran the phone out of memory. Decode one screen at a time, and if the user has
        // moved on again, throw the result away rather than showing it late.
        val ticket = ++rasterTicket
        rasterWorker.execute {
            val px = IntArray(w * h)
            val ok = runCatching { g.decodeWindow(world, w, h, px) }.getOrDefault(false)
            if (!ok) return@execute
            if (ticket != rasterTicket) return@execute
            val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            runOnUiThread {
                if (ticket != rasterTicket) {
                    bmp.recycle()
                    return@runOnUiThread
                }
                mapView.setRaster(bmp, world, stamp)
            }
        }
    }

    /**
     * `Prepare layers`: what is costing this folder speed, and what can be done about it.
     *
     * Audit first, action second, and never silently. It reads only file sizes and .prj
     * headers, so opening it costs nothing even in a 600 MB folder, and every proposed
     * write goes to a new name with the original left alone.
     */
    private fun showPrepareLayers() {
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        val files = dir.listFiles()
        // The app's own network output is not a layer the user prepared, so auditing it
        // would offer to "fix" files this app wrote minutes ago.
        val shp = files.filter {
            it.endsWith(".shp", true) &&
                !it.startsWith("network_", true) &&
                !it.substringBeforeLast('.').endsWith("_network", true)
        }
        if (shp.isEmpty()) { toast("No shapefiles in this folder"); return }

        val zone = workingZone()
        // "Is this project in degrees" is decided by counting the layers that are, not
        // by asking whether any layer is projected. The old test asked the second
        // question, so a folder whose .prj files could not be read or were in a
        // projection this app does not recognise was reported as being in degrees.
        // That is the wrong answer stated confidently, on a folder that is fine.
        val recognised = shp.mapNotNull { f ->
            runCatching { Utm.classify(prjFor(dir, f)).kind }.getOrNull()
        }
        val projectedCount = recognised.count { it == Utm.Crs.UTM_METERS }
        val geographicCount = recognised.count { it == Utm.Crs.WGS84_DEGREES }
        val projectGeo = recognised.isNotEmpty() && geographicCount > projectedCount

        val facts = shp.map { f ->
            val stem = f.substringBeforeLast('.')
            val size = dir.sizeOf(f) ?: 0L
            val c = runCatching { Utm.classify(prjFor(dir, f)) }.getOrNull()
            val deliberate = stem.equals("points", true) || stem.equals("tracks", true)
            org.gcap.core.prep.LayerPreparation.LayerFacts(
                stem = stem,
                fileBytes = size,
                crsLabel = when (c?.kind) {
                    Utm.Crs.UTM_METERS -> "UTM ${c.utmZone}N"
                    Utm.Crs.WGS84_DEGREES -> "WGS84 degrees"
                    Utm.Crs.ALBERS -> "Albers"
                    Utm.Crs.OTHER -> "unrecognised"
                    null -> "no .prj"
                },
                crsZone = c?.utmZone ?: 0,
                geographic = c?.kind == Utm.Crs.WGS84_DEGREES,
                deliberateDegrees = deliberate,
                routable = isRoutableLayer(stem),
            )
        }
        val advice = org.gcap.core.prep.LayerPreparation.audit(facts, zone, projectGeo)
        val actionable = advice.filter { it.action != null }

        val body = StringBuilder()
        body.append("Working zone: ").append(
            if (projectGeo) "degrees" else "UTM ${zone}N",
        ).append("\n")
        body.append("Estimated in memory: ")
            .append("%.0f".format(facts.sumOf { org.gcap.core.prep.LayerPreparation.estimatedHeapMiB(it.fileBytes) }))
            .append(" MiB across ${facts.size} layers\n\n")
        if (actionable.isEmpty()) {
            body.append("Nothing to do: no layer is off-CRS or heavy enough to matter.")
        } else {
            body.append("Worth a look:\n")
            for (a in actionable) {
                val verb = if (a.action == org.gcap.core.prep.LayerPreparation.Action.REPROJECT) {
                    "reproject to ${a.outputStem}"
                } else {
                    "write ${a.outputStem}"
                }
                body.append("  ").append(a.stem).append(" - ").append(a.detail).append("\n")
                body.append("      would ").append(verb).append("\n")
            }
        }

        val scroll = android.widget.ScrollView(this)
        scroll.addView(android.widget.TextView(this).apply {
            text = body.toString()
            setPadding(24, 24, 24, 24)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Prepare layers")
            .setView(scroll)
            .setPositiveButton("Leave as is", null)
            .apply {
                if (actionable.isNotEmpty()) {
                    setNeutralButton("Act on one") { _, _ ->
                        chooseLayerToPrepare(advice)
                    }
                }
            }
            .show()
    }

    /** Step through the layers worth acting on, one at a time. */
    private fun chooseLayerToPrepare(advice: List<org.gcap.core.prep.LayerPreparation.Advice>) {
        val actionable = advice.filter { it.action != null }
        val labels = actionable.map { it.stem }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Which layer?")
            .setItems(labels) { _, which ->
                val a = actionable[which]
                android.app.AlertDialog.Builder(this)
                    .setTitle(a.stem)
                    .setMessage(a.detail + "\n\nThe original file is kept. A new layer is written beside it.")
                    .setPositiveButton("Do it") { _, _ -> applyPreparation(a) }
                    .setNegativeButton("Leave it", null)
                    .show()
            }
            .setNegativeButton("Back", null)
            .show()
    }

    /** Read one layer, apply the chosen transform, and write the result under a new name. */
    private fun applyPreparation(a: org.gcap.core.prep.LayerPreparation.Advice) {
        val dir = mapDir ?: return
        val out = a.outputStem ?: return
        val action = a.action ?: return
        Thread {
            val msg = runCatching { prepareOne(dir, a.stem, out, action) }.getOrElse {
                "Could not write $out: ${it.message ?: it.javaClass.simpleName}"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    /**
     * `Heal lines`: repair a digitised line layer before building a network from it.
     *
     * Audit first, then act, on one layer at a time. The ladder is shown as numbers rather
     * than adjectives because the choice is a distance judgement the user has to make on
     * their own ground: 7 m joins 2,243 junctions on the survey access layer and moves 437 m
     * of geometry, while 15 m moves 3,737 m, which is where genuinely separate lines start
     * fusing. Nothing is written until a tolerance is chosen, and the original is kept.
     */
    private fun showHealLines() {
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        // A dialog opened while Settings is still up goes behind it, and the taps meant for
        // the new dialog land on Settings instead - which silently does nothing visible.
        settingsDialog?.dismiss()
        val candidates = dir.listFiles()
            .filter { it.endsWith(".shp", true) }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .filter { stem -> !stem.startsWith("network_", true) && !stem.endsWith("_network", true) }
            .filter { stem -> lineShapeType(dir, stem) == 3 }
            .sorted()
        if (candidates.isEmpty()) {
            toast("No line shapefiles in this folder")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Heal which line layer?")
            .setItems(candidates.toTypedArray()) { _, which ->
                planHealing(dir, candidates[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** 3 = polyline. Anything else cannot be healed as a line. */
    private fun lineShapeType(dir: SafMapDir, stem: String): Int {
        val f = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".shp", true)
        } ?: return -1
        val head = dir.readHeader(f, 100) ?: return -1
        return runCatching { ShpFormat.shpHeader(head)?.shapeType ?: -1 }.getOrElse { -1 }
    }

    private fun readHealingLines(dir: SafMapDir, stem: String): List<org.gcap.core.prep.LineHealing.Line>? {
        val f = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".shp", true)
        } ?: return null
        val bytes = dir.readFile(f) ?: return null
        val shapes = runCatching {
            ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
        }.getOrElse { return null }
        return shapes.map { org.gcap.core.prep.LineHealing.Line(it.parts, it.recordIndex) }
    }

    /**
     * Show what each tolerance would do to one layer, then heal at whichever is chosen.
     *
     * Every number comes from [org.gcap.core.prep.LineHealing.plan] on this layer's real
     * geometry. The plan runs off the UI thread: on the 5,177-feature access layer it
     * takes about a second per tolerance, which would drop frames if done inline.
     */
    /** Show progress in the corner while a background read or write runs. */
    private fun setBusy(label: String) {
        mapView.loadProgress = label
        mapView.invalidate()
    }

    private fun clearBusy() {
        mapView.loadProgress = ""
        mapView.invalidate()
    }

    private fun planHealing(dir: SafMapDir, stem: String) {
        setBusy("Reading $stem")
        Thread {
            val outcome = runCatching {
                val lines = readHealingLines(dir, stem)
                if (lines == null || lines.isEmpty()) {
                    null
                } else {
                    // read once: over SAF a second read of a 1.3 MB layer is not free, and
                    // the table below needs the same geometry the plan was measured on
                    Triple(lines, org.gcap.core.prep.LineHealing.summariseLadder(lines), 0)
                }
            }.getOrElse { e ->
                android.util.Log.e("ForestNav", "healing plan failed", e)
                null
            }
            if (outcome == null) {
                runOnUiThread {
                    clearBusy()
                    toast("$stem could not be read. Try again, or check the file in QGIS.")
                }
                return@Thread
            }
            val srcLines = outcome.first
            val ladder = outcome.second
            val body = StringBuilder()
            val verts = srcLines.sumOf { l -> l.parts.sumOf { it.size } }
            body.append("$stem: ${srcLines.size} lines, $verts vertices\n")
            body.append("  tol   junctions   geometry moved\n")
            for (p in ladder) {
                val mark = if (p.toleranceM == org.gcap.core.prep.LineHealing.DEFAULT_TOLERANCE_M) "*" else " "
                body.append("$mark ").append("%.1f".format(p.toleranceM)).append(" m")
                    .append("   ").append(p.junctionCandidates.toString().padStart(6))
                    .append("   ").append("%.0f".format(p.lengthChangeM)).append(" m\n")
            }
            body.append("\n* = the default.\n\n")
            body.append("Junctions are endpoints that would gain a connection.\n")
            body.append("Geometry moved is how far line ends shift to meet - the honest\n")
            body.append("check that a tolerance has not fused two separate roads. A jump from\n")
            body.append("hundreds of metres to kilometres means it has.\n")
            runOnUiThread {
                    clearBusy()
                // Two dialogs, not one. An AlertDialog carrying both a message and an item
                // list does not reliably render the list on Android, so the table is read
                // first and the choice offered afterwards.
                android.app.AlertDialog.Builder(this)
                    .setTitle("Heal $stem")
                    .setMessage(body.toString())
                    .setPositiveButton("Choose a tolerance") { _, _ -> chooseTolerance(dir, stem, ladder) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    /** Second step: the ladder is on screen, now offer the choices. */
    private fun chooseTolerance(
        dir: SafMapDir,
        stem: String,
        ladder: List<org.gcap.core.prep.LineHealing.Summary>,
    ) {
        val labels = ladder.map { p ->
            val mark = if (p.toleranceM == org.gcap.core.prep.LineHealing.DEFAULT_TOLERANCE_M) " * " else "   "
            "%.1f m   %d junctions   %.0f m moved".format(p.toleranceM, p.junctionCandidates, p.lengthChangeM) + mark
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Tolerance for $stem")
            .setItems(labels) { _, which -> confirmHeal(dir, stem, ladder[which].toleranceM) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmHeal(dir: SafMapDir, stem: String, toleranceM: Double) {
        val out = "${stem}_healed"
        android.app.AlertDialog.Builder(this)
            .setTitle("Heal at ${"%.1f".format(toleranceM)} m?")
            .setMessage(
                "Writes $out.shp beside the original.\n\n" +
                    "$stem is left exactly as it is. Switch it off in Layer settings, or " +
                    "the map draws both layers.",
            )
            .setPositiveButton("Heal it") { _, _ -> applyHealing(dir, stem, out, toleranceM) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyHealing(dir: SafMapDir, stem: String, out: String, toleranceM: Double) {
        setBusy("Healing $stem")
        Thread {
            val msg = runCatching { healOne(dir, stem, out, toleranceM) }.getOrElse {
                "Could not write $out: ${it.message ?: it.javaClass.simpleName}"
            }
            runOnUiThread {
                clearBusy()
                toast(msg)
                loadViewport(true)
            }
        }.start()
    }

    private fun healOne(dir: SafMapDir, stem: String, out: String, toleranceM: Double): String {
        val files = dir.listFiles()
        fun sidecar(ext: String): ByteArray? {
            val f = files.firstOrNull {
                it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".$ext", true)
            }
            return if (f == null) null else dir.readFile(f)
        }
        val lines = readHealingLines(dir, stem) ?: return "$stem.shp could not be read"
        if (lines.isEmpty()) return "$stem holds no line features"
        val dbf = sidecar("dbf") ?: ByteArray(0)
        val prjText = prjFor(dir, files.first {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".shp", true)
        }) ?: org.gcap.core.nav.NetworkWriter.prjText(
            workingZone() ?: 0, org.gcap.core.nav.NetworkWriter.Datum.NAD83,
        )

        val plan = org.gcap.core.prep.LineHealing.plan(lines, toleranceM)
        val healed = org.gcap.core.prep.LineHealing.heal(lines, toleranceM)
        val parts = healed.map { it.parts.flatMap { p -> p } }
        val written = org.gcap.core.prep.LayerPreparation.writeHealedPolylineLayer(
            parts, dbf, prjText, out,
        )
        val moved = "%.0f".format(plan.lengthChangeM)
        val groups = "${plan.componentsBefore} to ${plan.componentsAfter}"
        return "${writeAll(dir, written)}: ${plan.junctionCandidates} junctions joined, " +
            "$moved m of geometry moved, separate groups $groups. " +
            "Switch $stem off in Layer settings, or the map draws both."
    }

    // ---- GPX recorded track into a line layer -------------------------------

    private fun recordTrackTarget(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_RECORD_INTO, "") ?: ""

    /**
     * Choose which line layer a finished track is also written into as a new line.
     *
     * Off by default: recording into `access` puts the line into the very layer a network
     * is built from, so it has to be a decision rather than a default nobody made.
     */
    private fun chooseRecordTrackTarget() {
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        settingsDialog?.dismiss()
        val lines = dir.listFiles()
            .filter { it.endsWith(".shp", true) }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .filter { st -> !st.startsWith("network_", true) && !st.endsWith("_network", true) }
            .filter { lineShapeType(dir, it) == 3 }
            .sorted()
        val options = mutableListOf("off (tracks.shp only)")
        options.addAll(lines)
        // Save, not tap-to-apply: an accidental tap used to change where recordings go.
        chooseWithSave("Record tracks into a line", options, recordTrackTarget()) { picked ->
            val target = if (picked == options.first()) "" else lines[options.indexOf(picked) - 1]
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_RECORD_INTO, target).apply()
            toast(
                if (target.isBlank()) "Tracks go to tracks.shp only"
                else "Tracks will also be written to $target.shp",
            )
        }
    }

    // ---- cut mode ------------------------------------------------------------

    private fun cutGapM(): Double =
        getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(PREF_CUT_GAP_M, 20f).toDouble()

    /**
     * Pick a line layer, then arm a cut.
     *
     * Cutting edits the layer in place - there is no separate output to keep, because a cut
     * that lives in a copy would not change the map or the network, and the point is to
     * stop a route crossing ground nobody can cross. The saved network for the layer is
     * removed afterwards, because it still holds the unbroken road and would quietly route
     * straight over the gap.
     */
    private fun showCutLines() {
        val dir = mapDir ?: run { toast("Pick a folder first"); return }
        settingsDialog?.dismiss()
        val lines = dir.listFiles()
            .filter { it.endsWith(".shp", true) }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .filter { st -> !st.startsWith("network_", true) && !st.endsWith("_network", true) }
            .filter { lineShapeType(dir, it) == 3 }
            .sorted()
        if (lines.isEmpty()) { toast("No line shapefiles in this folder"); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cut which line layer?")
            .setItems(lines.toTypedArray()) { _, which -> armCut(lines[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun armCut(stem: String) {
        val gap = cutGapM()
        android.app.AlertDialog.Builder(this)
            .setTitle("Cut $stem?")
            .setMessage(
                "Tap the line on the map where it should be broken.\n\n" +
                    "A ${"%.0f".format(gap)} m gap is left, so a network built afterwards " +
                    "will not connect across it.\n\n" +
                    "$stem is edited in place - there is no undo. Healing $stem afterwards " +
                    "will keep the gap, because it is wider than the healing tolerance.",
            )
            .setPositiveButton("Arm the cut") { _, _ ->
                when (val d = org.forestnav.app.map.EditMode.canStartCutting(
                    pointSessionOpen = editSession != null,
                    lineSessionExists = lineSession != null,
                )) {
                    is org.forestnav.app.map.EditMode.Decision.No -> {
                        toast(d.why)
                        return@setPositiveButton
                    }
                    else -> Unit
                }
                cutArmed = true
                cutLayer = stem
                lineSession = org.forestnav.app.map.LineEditSession()
                mapView.cutCursor = true
                updateOverlays()
                toast("Tap the line to cut. Save edits writes it; Stop edit discards.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Leave cut mode. [discard] is for Stop edit; arming again keeps staged cuts. */
    private fun cancelCut(discard: Boolean = false) {
        cutArmed = false
        cutLayer = null
        if (discard) lineSession = null
        mapView.cutCursor = false
        mapView.cutPending = lineSession?.count ?: 0
        updateOverlays()
    }

    /** A tap in cut mode: find the nearest line, show where, and ask before breaking it. */
    private fun onCutTap(e: Double, n: Double) {
        val stem = cutLayer ?: return
        val dir = mapDir ?: return
        val gap = cutGapM()
        setBusy("Looking for the line")
        Thread {
            val outcome = runCatching {
                val lines = readHealingLines(dir, stem).orEmpty()
                if (lines.isEmpty()) null
                else {
                    val at = projectTapToLayer(stem, e, n)
                    val res = org.gcap.core.prep.LineCutTarget.find(
                        lines, at[0], at[1], maxOffsetM = CUT_REACH_M, gapM = gap,
                    )
                    // nearest vertex, to compare the tap against the geometry directly
                    var nearest = Double.MAX_VALUE
                    for (l in lines) for (pt in l.parts) for (v in pt) {
                        val d = kotlin.math.hypot(at[0] - v[0], at[1] - v[1])
                        if (d < nearest) nearest = d
                    }
                    android.util.Log.i(
                        "ForestNav",
                        "CUTDBG tap=(${at[0]}, ${at[1]}) nearestVertex=${"%.1f".format(nearest)}m res=$res",
                    )
                    res to lines
                }
            }.getOrElse { err ->
                android.util.Log.e("ForestNav", "cut lookup failed", err)
                null
            }
            runOnUiThread {
                clearBusy()
                if (outcome == null) {
                    toast("$stem could not be read.")
                    return@runOnUiThread
                }
                val (result, _) = outcome
                when (result) {
                    is org.gcap.core.prep.LineCutTarget.Result.Found ->
                        confirmCut(stem, result.target, gap)
                    is org.gcap.core.prep.LineCutTarget.Result.Refused ->
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Cannot cut here")
                            .setMessage(cutRefusalText(result.reason, gap))
                            .setPositiveButton("OK", null)
                            .show()
                    org.gcap.core.prep.LineCutTarget.Result.NothingClose ->
                        android.app.AlertDialog.Builder(this)
                            .setTitle("No line to cut here")
                            .setMessage(
                                org.gcap.core.prep.LineCutTarget.explainNoCut(
                                    lines = readHealingLines(dir, stem).orEmpty(),
                                    x = projectTapToLayer(stem, e, n)[0],
                                    y = projectTapToLayer(stem, e, n)[1],
                                    maxOffsetM = CUT_REACH_M,
                                    gapM = gap,
                                ),
                            )
                            .setPositiveButton("OK", null)
                            .show()
                }
            }
        }.start()
    }

    private fun cutRefusalText(
        reason: org.gcap.core.prep.LineCutTarget.BlockReason,
        gap: Double,
    ): String = when (reason) {
        org.gcap.core.prep.LineCutTarget.BlockReason.GAP_TOO_SMALL ->
            "A ${"%.0f".format(gap)} m gap is too small to hold. Use at least " +
                "${"%.0f".format(org.gcap.core.prep.LineCutTarget.MIN_GAP_M)} m, or healing " +
                "this layer may close it again."
        org.gcap.core.prep.LineCutTarget.BlockReason.GAP_TOO_LARGE ->
            "A ${"%.0f".format(gap)} m gap is too wide. Use at most " +
                "${"%.0f".format(org.gcap.core.prep.LineCutTarget.MAX_GAP_M)} m."
        org.gcap.core.prep.LineCutTarget.BlockReason.TOO_CLOSE_TO_HEAL_TOLERANCE ->
            "A ${"%.0f".format(gap)} m gap is no wider than the healing tolerance, so " +
                "healing this layer would weld the road straight back together and the cut " +
                "would stop working. Use a wider gap."
        org.gcap.core.prep.LineCutTarget.BlockReason.NOTHING_CLOSE ->
            "No line was found under the tap."
    }

    /**
     * Stage one cut.
     *
     * Nothing is written here. The cut joins the session and is written when the user
     * presses Save edits, so a mistap costs one tap on Stop edit rather than a redrawn
     * road - and the layer keeps its saved network right up until the moment it changes.
     */
    private fun confirmCut(stem: String, target: org.gcap.core.prep.LineCutTarget.Target, gap: Double) {
        val session = lineSession
        if (session == null) { cancelCut(); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cut $stem here?")
            .setMessage(
                "${"%.1f".format(target.offsetM)} m from the line you tapped.\n" +
                    "Line ${target.recordIndex + 1}, ${"%.0f".format(target.fraction * 100)}% " +
                    "along.\n\n" +
                    "A ${"%.0f".format(gap)} m gap will be written into $stem.shp when you " +
                    "press Save edits. Stop edit discards it.",
            )
            .setPositiveButton("Stage the cut") { _, _ ->
                val problem = session.stage(stem, target, gap)
                if (problem != null) {
                    toast(problem)
                } else {
                    toast(
                        "${session.count} cut(s) staged. Save edits to write, " +
                            "Stop edit to discard.",
                    )
                }
                updateCutBanner()
            }
            .setNegativeButton("Keep it", null)
            .show()
    }

    private fun updateCutBanner() {
        val n = lineSession?.count ?: 0
        mapView.cutPending = n
        mapView.cutCursor = cutArmed
        updateOverlays()
    }

    private fun sidecarBytes(dir: SafMapDir, stem: String, ext: String): ByteArray? {
        val f = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".$ext", true)
        }
        return if (f == null) null else dir.readFile(f)
    }

    /** The stem this layer actually has on disk, matched without regard to case. */
    private fun onDiskStem(dir: SafMapDir, stem: String): String? =
        dir.listFiles().firstOrNull {
            it.endsWith(".shp", true) && it.substringBeforeLast('.').equals(stem, true)
        }?.substringBeforeLast('.')

    /** How far from a line a cut tap may land and still count. */
    private val CUT_REACH_M = 40.0

    private fun projectTapToLayer(stem: String, e: Double, n: Double): DoubleArray {
        val dir = mapDir
        val prj = dir?.let {
            val f = it.listFiles().firstOrNull { f ->
                f.substringBeforeLast('.').equals(stem, true) && f.endsWith(".shp", true)
            }
            if (f == null) null else prjFor(it, f)
        }
        // Utm.classify already yields the layer CRS; projectCoords converts working-zone
        // metres into whatever this layer stores, which is the same direction prepareOne
        // uses for a reprojected vertex.
        val layerCrs = runCatching { Utm.classify(prj) }.getOrNull()
        return projectCoords(e, n, layerCrs, prj)
    }

    private fun prepareOne(
        dir: SafMapDir,
        stem: String,
        out: String,
        action: org.gcap.core.prep.LayerPreparation.Action,
    ): String {
        val files = dir.listFiles()
        val shpFile = files.firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".shp", true)
        } ?: return "$stem.shp not found"
        val dbfFile = files.firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
        } ?: return "$stem.dbf not found"
        val bytes = dir.readFile(shpFile) ?: return "$stem.shp could not be read"
        val dbf = dir.readFile(dbfFile) ?: ByteArray(0)
        val header = dir.readHeader(shpFile, 100)
        val info = runCatching { ShpFormat.shpHeader(header ?: ByteArray(0)) }.getOrNull()
        val shapeType = info?.shapeType ?: 1
        val shapes = ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
        if (shapes.isEmpty()) return "$stem holds no features"

        val prj = prjFor(dir, shpFile)
        val src = runCatching { Utm.classify(prj) }.getOrNull()
        val prjText = org.gcap.core.nav.NetworkWriter.prjText(workingZone(), org.gcap.core.nav.NetworkWriter.Datum.NAD83)
        val tol = org.gcap.core.prep.LayerPreparation.SIMPLIFY_TOLERANCE_M

        val written: Map<String, ByteArray>
        val geomMiB: Double
        if (shapeType == 1) {
            val coords = shapes.map { sh -> sh.parts.firstOrNull()?.firstOrNull() ?: doubleArrayOf(0.0, 0.0) }
                .map { c ->
                    if (action == org.gcap.core.prep.LayerPreparation.Action.REPROJECT) {
                        projectCoords(c[0], c[1], src, prj)
                    } else {
                        c
                    }
                }
            geomMiB = coords.size * 16.0 / (1024 * 1024)
            written = org.gcap.core.prep.LayerPreparation.writePointLayer(coords, dbf, prjText, out)
        } else {
            val parts = ArrayList<List<DoubleArray>>()
            var before = 0
            var after = 0
            for (sh in shapes) {
                for (part in sh.parts) {
                    if (part.size < 2) continue
                    val moved = when (action) {
                        org.gcap.core.prep.LayerPreparation.Action.REPROJECT ->
                            part.map { projectCoords(it[0], it[1], src, prj) }
                        org.gcap.core.prep.LayerPreparation.Action.SIMPLIFY ->
                            org.gcap.core.prep.LayerPreparation.simplifyPart(part, tol)
                    }
                    if (moved.size < 2) continue
                    before += part.size
                    after += moved.size
                    parts.add(moved)
                }
            }
            geomMiB = after * 16.0 / (1024 * 1024)
            written = org.gcap.core.prep.LayerPreparation.writePolylineLayer(parts, dbf, prjText, out)
            if (action == org.gcap.core.prep.LayerPreparation.Action.SIMPLIFY && before > 0) {
                val pct = if (before > 0) (100 - after * 100 / before) else 0
                return "${writeAll(dir, written)}: $before -> $after vertices ($pct% fewer). " +
                    "Switch $stem off in Layer settings, or the map draws both."
            }
        }
        val warn = if (geomMiB >= org.gcap.core.prep.LayerPreparation.WARN_HEAP_MIB) {
            " - still heavy at ${"%.0f".format(geomMiB)} MiB"
        } else {
            ""
        }
        return "${writeAll(dir, written)}$warn"
    }

    /**
     * Convert one point from its own CRS into the working zone.
     *
     * The same three cases [projectShapes] handles, for a single vertex: a foreign UTM
     * zone is moved through latitude and longitude, degrees are projected, and anything
     * already in the working zone is passed through untouched so a missing declaration
     * never silently moves data.
     */
    private fun projectCoords(x: Double, y: Double, src: Utm.LayerCrs?, prj: String?): DoubleArray {
        val zone = workingZone() ?: return doubleArrayOf(x, y)
        val kind = src?.kind ?: return doubleArrayOf(x, y)
        return when {
            kind == Utm.Crs.UTM_METERS && src.utmZone == zone -> doubleArrayOf(x, y)
            kind == Utm.Crs.UTM_METERS -> {
                val (lat, lon) = Utm.utmToLatLon(x, y, src.utmZone)
                val (e, n) = Utm.wgs84ToUtmZone(lat, lon, zone)
                doubleArrayOf(e, n)
            }
            kind == Utm.Crs.WGS84_DEGREES -> {
                val (e, n) = Utm.wgs84ToUtmZone(y, x, zone)
                doubleArrayOf(e, n)
            }
            kind == Utm.Crs.ALBERS -> {
                val params = prj?.let { Albers.params(it) }
                if (params == null) doubleArrayOf(x, y)
                else {
                    val (ae, an) = Albers.toWgs84(x, y, params)
                    val (e, n) = Utm.wgs84ToUtmZone(ae, an, zone)
                    doubleArrayOf(e, n)
                }
            }
            else -> doubleArrayOf(x, y)
        }
    }

    private fun writeAll(dir: SafMapDir, files: Map<String, ByteArray>): String {
        var written = 0
        var failed: String? = null
        for ((name, bytes) in files) {
            val ok = dir.createFile(name, bytes) || dir.overwriteFile(name, bytes)
            if (ok) written++ else failed = name
        }
        return if (failed == null) "Wrote $written files" else "Wrote $written, failed on $failed"
    }


    /**
     * Note which layers have a saved network, without reading any of them.
     *
     * Only file names are involved, so this costs a few directory listings rather than
     * the tens of seconds it takes to rebuild a graph.
     */
    private fun discoverNetworks(dir: SafMapDir) {
        networkFilesByStem.clear()
        for (layer in dir.listFiles().filter { f ->
            f.endsWith(".shp", true) && isRoutableLayer(f.substringBeforeLast('.'))
        }) {
            val stem = layer.substringBeforeLast('.')
            networkFilesIn(dir, stem)?.let { networkFilesByStem[stem] = it }
        }
    }

    /**
     * Rebuild one saved network into a graph, or null when it cannot be read.
     * Blocking: callers run this off the UI thread.
     */
    private fun buildNetworkGraph(dir: SafMapDir, stem: String): org.gcap.core.nav.NetworkGraph? {
        val files = networkFilesByStem[stem] ?: networkFilesIn(dir, stem)?.also {
            networkFilesByStem[stem] = it
        } ?: return null
        val tn = System.currentTimeMillis()
        val shp = dir.readFile(files["shp"]!!) ?: return null
        val dbf = dir.readFile(files["dbf"]!!) ?: return null
        val prj = files["prj"]?.takeIf { it.isNotBlank() }?.let { dir.readFile(it) }
        val tRead = System.currentTimeMillis()
        android.util.Log.i("NavDiag", "net $stem: read ${System.currentTimeMillis() - tn}ms shpBytes=${shp.size}")
        return when (val r = org.gcap.core.nav.NetworkReader.read(shp, dbf, prj)) {
            is org.gcap.core.nav.NetworkReader.Result.Ok -> {
                android.util.Log.i(
                    "NavDiag",
                    "net $stem: build ${System.currentTimeMillis() - tRead}ms " +
                        "nodes=${r.graph.nodeCount} edges=${r.graph.edges.size}",
                )
                r.graph
            }
            is org.gcap.core.nav.NetworkReader.Result.Failed -> null
        }
    }

    /** The Networks readout: what is loaded, and what is merely available to load. */
    private fun networkReportLines(
        loaded: Map<String, org.gcap.core.nav.NetworkGraph>,
        selected: String?,
    ): List<Pair<String, String>> {
        val g = selected?.let { loaded[it] }
        return listOf(
            "Networks" to "${loaded.size} of ${availableStems.size} loaded",
            "Available" to availableStems.joinToString(", ").ifBlank { "-" },
            "Selected" to (selected ?: "-"),
            "Nodes" to "${g?.nodeCount ?: 0}",
            "Segments" to "${g?.edges?.size ?: 0}",
            "Regions" to "${g?.regionCount ?: 0}",
        )
    }

    /** Which travel class a source layer belongs to, from its name. */
    private fun routeClassFor(name: String): RouteClass {
        val n = name.lowercase()
        return when {
            n.contains("bike") -> RouteClass.BIKE
            n.contains("trail") -> RouteClass.TRAIL
            n.contains("road") || n.contains("highway") -> RouteClass.ROAD
            n.contains("access") || n.contains("track") -> RouteClass.ACCESS
            else -> RouteClass.ACCESS
        }
    }

    /**
     * Whether a layer is one the crew travels along, and so gets its own network.
     *
     * Deliberately name-based and deliberately narrow: a block boundary, a lake
     * shoreline or a grid is drawn for orientation, not driven along, and letting it
     * into the network is how a route ends up cutting across country on a line that
     * is not a road.
     */
    private fun isRoutableLayer(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("road") || n.contains("highway") || n.contains("access") ||
            n.contains("trail") || n.contains("track") || n.contains("transect") ||
            n.contains("flight_path") || n.contains("bike")
    }

    /**
     * The network file name for a layer, in the form `ACCESS_network`.
     *
     * Upper-cased so it sorts with the other network layers in a file listing and
     * reads as a name rather than a prefix, and stable so a network written by one
     * run is found by the next without the folder being searched by guesswork.
     */
    private fun networkFileName(layerStem: String): String =
        "${layerStem.uppercase()}_network"

    /**
     * Find a network layer's files in the folder, case-insensitively.
     *
     * SAF is case-sensitive on some providers and not others, and a folder that has
     * been through a desktop rename can hold `ACCESS_network.shp` next to
     * `access_network.dbf`. Matching on the stem and comparing case-insensitively
     * means a network is found however its files were cased.
     */
    private fun networkFilesIn(dir: SafMapDir, layerStem: String): Map<String, String>? {
        val stem = networkFileName(layerStem)
        val wanted = listOf(".shp", ".shx", ".dbf", ".prj")
        val found = dir.listFiles().filter { f ->
            val dot = f.lastIndexOf('.')
            if (dot <= 0) return@filter false
            val ext = f.substring(dot).lowercase()
            ext in wanted && f.substring(0, dot).equals(stem, true)
        }
        if (found.isEmpty()) return null
        val byExt = found.map { f ->
            val dot = f.lastIndexOf('.')
            f.substring(dot).lowercase() to f
        }.toMap()
        // .shp and .dbf are the minimum; the others are written but not read back
        val shp = byExt[".shp"] ?: return null
        val dbf = byExt[".dbf"] ?: return null
        return mapOf("shp" to shp, "dbf" to dbf, "prj" to (byExt[".prj"] ?: ""))
    }

    /** Which layer a network button routes on, or null when the project has none. */
    private fun networkStemForButton(buttonId: String): String? {
        val stems = availableStems.ifEmpty { networks.keys }
        return when (buttonId) {
            "net_b5" -> stems.firstOrNull { it.contains("access") || it.contains("track") }
            "net_c4" -> stems.firstOrNull { it.contains("road") || it.contains("highway") }
            "net_c5" -> stems.firstOrNull { it.contains("trail") }
            else -> null
        }
    }

    /** The layer Navigate starts on for the current mode. */
    private fun defaultNetworkStem(): String? {
        val available = availableStems.ifEmpty { networks.keys }
        return when (navMode) {
            NavMode.AIR -> available.firstOrNull { it.contains("flight") || it.contains("air") }
                ?: available.firstOrNull()
            else -> available.firstOrNull { it.contains("access") }
                ?: available.firstOrNull { it.contains("road") }
                ?: available.firstOrNull()
        }
    }

    /**
     * One of the ACCESS/ROAD/TRAIL buttons.
     *
     * Chooses which layer's network is followed and points the router at it, so the
     * button and the routing cannot drift apart. A project without that kind of layer
     * says so rather than silently following a different one.
     */
    private fun selectNetwork(buttonId: String): NavState {
        val stem = networkStemForButton(buttonId)
        val state = nav.chooseNetwork(buttonId)
        if (stem == null) {
            // Cleared rather than left at the previous layer: a button that names a
            // network this project does not have must not leave the last one selected,
            // or Navigate quietly routes on a network the crew did not choose.
            selectedNetworkStem = null
            navRouter = null
            updateNetworkReport()
            val label = org.gcap.core.nav.MainGrid.byId(buttonId)?.label(navMode) ?: buttonId
            return state.copy(message = "No $label network in this project. Press Network to build one.")
        }

        networks[stem]?.let { useLoadedNetwork(it, stem); return state }

        // Not loaded yet. Build it off the UI thread: the roads graph takes about 30 s
        // and doing that on the main thread freezes the map for the whole of it.
        val dir = mapDir
        if (dir == null) {
            selectedNetworkStem = null
            navRouter = null
            updateNetworkReport()
            return state.copy(message = "Pick a folder first.")
        }
        val label = org.gcap.core.nav.MainGrid.byId(buttonId)?.label(navMode) ?: stem
        // One build per network at a time. Tapping the button again while it is still
        // being read used to start a second copy, doubling the memory and the wait.
        if (networkLoadingStem == stem) {
            return state.copy(message = "The $label network is still loading.")
        }
        requestNetwork(dir, stem, quiet = true)
        return state.copy(message = "Loading the $label network...")
    }

    /**
     * Build one saved network off the UI thread and adopt it when it arrives.
     *
     * Shared by the network buttons and by map start, so a network is only ever built
     * once and never on the main thread. Quiet when the load was not asked for: at map
     * start nobody pressed anything, so no toast interrupts the map coming up.
     */
    private fun requestNetwork(dir: SafMapDir, stem: String, quiet: Boolean) {
        networks[stem]?.let { useLoadedNetwork(it, stem); return }
        if (networkLoadingStem == stem) return
        networkLoadingStem = stem
        val label = org.gcap.core.nav.MainGrid.byId(networkButtonForStem(stem))?.label(navMode) ?: stem
        setBusy("Loading $label network")
        Thread {
            val graph = buildNetworkGraph(dir, stem)
            runOnUiThread {
                clearBusy()
                if (networkLoadingStem == stem) networkLoadingStem = null
                if (graph == null) {
                    if (!quiet) toast("Could not read the $label network")
                    updateNetworkReport()
                    return@runOnUiThread
                }
                networks = networks + (stem to graph)
                useLoadedNetwork(graph, stem)
                if (!quiet && !navigating) toast("$label network loaded")
            }
        }.start()
    }

    /** The network button a layer belongs to, so its own name is used in messages. */
    private fun networkButtonForStem(stem: String): String = when {
        stem.contains("access") || stem.contains("track") -> "net_b5"
        stem.contains("road") || stem.contains("highway") -> "net_c4"
        stem.contains("trail") -> "net_c5"
        else -> ""
    }

    /** Point the router at an already-built graph and adopt it as the selected network. */
    private fun useLoadedNetwork(graph: org.gcap.core.nav.NetworkGraph, stem: String) {
        selectedNetworkStem = stem
        navRouter = org.gcap.core.nav.Router(graph)
        if (navigating) plotNavRoute()
        updateNetworkReport()
    }

    /**
     * Load networks already built in this project, so a crew does not have to rebuild
     * after every restart.
     *
     * Reading four small files per network is cheap next to building one, so this is
     * only done on the first load rather than on every pan.
     */
    private fun loadSavedNetworks(dir: SafMapDir) {
        if (networks.isNotEmpty() || networkLoadingStem != null) return
        // Only the file names, which costs a few directory lookups. Nothing is rebuilt
        // here: the map goes up straight away and the network it will route on arrives
        // in the background. Loading one here froze the map for as long as the graph
        // took, which on a large roads network was the whole startup.
        discoverNetworks(dir)
        updateNetworkReport()
        if (availableStems.isEmpty()) return
        val stem = defaultNetworkStem() ?: return
        requestNetwork(dir, stem, quiet = true)
    }

    /** Refresh the network readout after the selected network changes. */
    private fun updateNetworkReport() {
        networkReport = networkReportLines(networks, selectedNetworkStem)
        updateOverlays()
    }

    /**
     * Douglas-Peucker simplification.
     *
     * A road centreline carries far more vertices than a route needs: the shape is
     * drawn at 1:10000, where 5 m is a fifth of a pixel. Simplifying to that tolerance
     * cuts the vertex count - and with it the heap the noding pass needs - by roughly
     * two thirds, while leaving the line within 5 m of where it was drawn.
     */
    private fun simplify(points: List<DoubleArray>, toleranceM: Double): List<DoubleArray> {
        if (points.size < 3 || toleranceM <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            val ax = points[a][0]; val ay = points[a][1]
            val bx = points[b][0]; val by = points[b][1]
            val dx = bx - ax; val dy = by - ay
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            var maxD = 0.0
            var maxI = -1
            for (i in a + 1 until b) {
                val px = points[i][0]; val py = points[i][1]
                val d = if (len < 1e-9) {
                    kotlin.math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
                } else {
                    kotlin.math.abs(dy * px - dx * py + bx * ay - by * ax) / len
                }
                if (d > maxD) { maxD = d; maxI = i }
            }
            if (maxD > toleranceM && maxI > 0) {
                keep[maxI] = true
                stack.addLast(intArrayOf(a, maxI))
                stack.addLast(intArrayOf(maxI, b))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Read-only health check over the project folder, reported per layer in the same
     * shape Forest Point Collector uses so the two apps read alike.
     *
     * Runs on a background thread: a block with tens of thousands of features can take
     * a while to walk, and the app is used on phones in the field where a frozen UI is
     * worse than a slow report.
     */
    /**
 * The zone this folder would be worked in, and whether it was derived rather than
 * declared. Read-only: Verify layers may run while a map is live, so this must not
 * change the state the map is using.
 */
private fun verifyZone(dir: SafMapDir): org.gcap.core.nav.WorkingZone.Decision? =
        runCatching { decideZone(dir) }.getOrNull()

private fun verifyLayers() {
        val dir = mapDir
        if (dir == null) { toast("Pick a folder first"); return }
        toast("Checking layers\u2026")
        Thread {
            val source = object : LayerSource {
                override fun listFiles(): List<String> = dir.listFiles()
                override fun readHeader(name: String, maxBytes: Int): ByteArray? =
                    dir.readHeader(name, maxBytes)
                override fun readFile(name: String): ByteArray? = dir.readFile(name)
            }
            val report = try {
                LayerVerifier(source, CrsClassifier { prj -> crsVerdict(prj) }).verify()
            } catch (e: Exception) {
                null
            }
            val zone = try { verifyZone(dir) } catch (e: Exception) { null }
            runOnUiThread {
                if (report == null) { toast("Layer check failed"); return@runOnUiThread }
                val notes = ArrayList(report.lines)
                // The zone every coordinate is being put through, and where it came from.
                // A zone assumed rather than read is the one thing that silently puts a
                // whole project in the wrong place, so it is stated here and not left to
                // be inferred from the absence of a complaint.
                if (zone != null) {
                    notes.add("")
                    notes.add("Working zone: ${zone.describe()}")
                }
                // Mixed CRS in one folder is worth saying out loud, but it is not an
                // error and nothing is changed: reprojecting belongs in QGIS on a desktop.
                // name every system present, not just the first: "UTM 10N and WGS84"
                val systems = Regex("""\d+ layer\(s\) ([^;\n]+)""")
                    .findAll(report.crsNote).map { it.groupValues[1].trim() }
                    .filter { it.isNotEmpty() }.distinct().toList()
                if (systems.size > 1) {
                    notes.add("")
                    notes.add("Heads up: this folder holds more than one coordinate system (${systems.joinToString(" and ")}).")
                    notes.add("Layers kept in degrees on purpose are used as they are.")
                    notes.add("To change one, reproject it in QGIS on a desktop.")
                }
                // Rasters the app cannot draw. Saying so is the whole point: an
                // orthoimagery folder otherwise loads looking simply empty, and the
                // natural conclusion is that the project is wrong rather than the format.
                // Every raster in the folder, and what this app can do with it. A raster
                // that cannot be drawn used to go unmentioned, so the map simply looked
                // empty with no explanation.
                val rasterEntries = probeEntries.ifEmpty {
                    probeRaster(dir, readProjectSettings(dir).basemapRaster).entries
                }
                if (rasterEntries.isNotEmpty()) {
                    notes.add("")
                    notes.add("Rasters in this folder:")
                    // Report the one actually being drawn, not the one named in the
                    // settings: if nothing was chosen the app still draws the first it
                    // can, and saying "available" for that one would be untrue.
                    val on = rasterName ?: readProjectSettings(dir).basemapRaster
                    for (e in rasterEntries) {
                        val mark = when {
                            !e.renderable -> "not drawn"
                            e.stem.equals(on, true) -> "shown as the basemap"
                            else -> "available"
                        }
                        notes.add("  ${e.stem}: $mark, ${e.reason}")
                    }
                    val unusable = rasterEntries.filter { !it.renderable }
                    if (unusable.isNotEmpty()) {
                        notes.add("")
                        notes.add(
                            "${unusable.size} raster(s) cannot be drawn by this app. " +
                                "The files are untouched and open normally in QGIS.",
                        )
                        notes.add("Choose a different basemap in Layer settings.")
                    }
                    // Say what to do about it on a desktop, since that is the only place
                    // a raster can be changed. Written as steps, not as jargon.
                    notes.add("")
                    notes.add("Preparing a raster for this app in QGIS:")
                    notes.add("  1. GeoTIFF only (.tif). Other formats are ignored.")
                    notes.add("  2. It must carry a coordinate system. In QGIS: Layer >")
                    notes.add("     Properties > Source > Assign or Generate Spatial Reference,")
                    notes.add("     choosing the same one as the rest of the project")
                    notes.add("     (NAD83 / UTM zone 10N for this project). A file with no")
                    notes.add("     coordinate system cannot be placed on the map.")
                    notes.add("  3. Compression must be None, Deflate or LZW. JPEG is read,")
                    notes.add("     but old-style JPEG (compression 6) and JPEG 2000")
                    notes.add("     (34712) are not. To change it, convert once on a desktop:")
                    notes.add("       gdal_translate -co COMPRESS=DEFLATE in.tif out.tif")
                    notes.add("  4. Overlapping rasters are not stacked: one basemap is shown")
                    notes.add("     at a time. Turn one on in Layer settings.")
                    notes.add("  5. Keep the resolution sensible. A 18121 x 13145 image at")
                    notes.add("     1 m covers a large area and is slow to draw on a phone;")
                    notes.add("     2-5 m is usually enough for fieldwork.")
                }
                // What Start map stays quiet about, listed here so it can actually be read:
                // which layers are in degrees on purpose, and any zone that differs.
                scanProject(dir)?.takeIf { it.messages().isNotEmpty() }?.let { scan ->
                    notes.add("")
                    notes.add("Coordinate systems, layer by layer:")
                    notes.addAll(scan.messages().map { "  " + it })
                }
                val cfg = settings()
                // The spreadsheet sheets are gone; the project's own file is the
                // single source of truth. Name it so it is obvious where a setting
                // came from and what to edit.
                val ps = readProjectSettings(dir)
                notes.add("")
                notes.add("Settings come from this project's mirshp.json. There is no spreadsheet.")
                if (ps.basemapRaster.isNotBlank()) {
                    notes.add("Basemap raster: ${ps.basemapRaster}")
                }
                showVerifyReport(notes, report.summary, report.extentNote, report.crsNote)
            }
        }.start()
    }

    /** Bridge the app's CRS classifier to the verifier's shape. */
    private fun crsVerdict(prj: String?): CrsVerdict {
        val c = Utm.classify(prj)
        return when (c.kind) {
            Utm.Crs.UTM_METERS -> CrsVerdict("UTM", c.utmZone, recognised = true)
            Utm.Crs.WGS84_DEGREES -> CrsVerdict("WGS84", 0, recognised = true, geographic = true)
            Utm.Crs.ALBERS -> CrsVerdict("Albers", 0, recognised = true)
            Utm.Crs.OTHER -> CrsVerdict("?", 0, recognised = false)
        }
    }

    private fun showVerifyReport(lines: List<String>, summary: String, extent: String, crs: String) {
        val body = StringBuilder()
        if (lines.isNotEmpty()) {
            body.append(lines.joinToString("\n")).append("\n\n")
        }
        body.append(summary).append("\n")
        if (crs.isNotBlank()) body.append(crs).append("\n")
        if (extent.isNotBlank()) body.append(extent)

        val scroll = android.widget.ScrollView(this)
        scroll.addView(android.widget.TextView(this).apply {
            text = body.toString()
            setPadding(24, 24, 24, 24)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Verify layers")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun mapUpLabel(): String = when (orientation.mode) {
        OrientationMode.COURSE_UP -> "Up: course"
        OrientationMode.WAYPOINT_UP -> "Up: waypoint"
        OrientationMode.NORTH_UP -> "Up: north"
        OrientationMode.DIRECT_LINE_UP -> "Up: bearing to target"
        OrientationMode.COMPASS_UP -> "Up: compass"
    }

    /**
     * Choose how the map is oriented, rather than cycling through them blind.
     *
     * Cycling was three taps with no way to see what the choices were, and the answer
     * only appeared on the main page after leaving Settings.
     */
    /** Turn speed-based zooming on or off and remember the choice. */
    private fun toggleAutoZoom() {
        autoZoom = !autoZoom
        app.setAutoZoomEnabled(autoZoom)
        relayout()
        refreshSettingsIfOpen()
        toast("Auto zoom ${if (autoZoom) "on" else "off"}")
    }

    /**
     * Rebuild the Settings dialog so a button shows the value just chosen.
     *
     * Its rows are built once when it opens, so without this the label kept claiming
     * the old value and it looked as though the tap had done nothing.
     */
    private fun refreshSettingsIfOpen() {
        if (settingsDialog?.isShowing != true) return
        // Cosmetic refresh only. If anything here goes wrong the setting has already
        // been applied, so losing the screen rebuild is far better than a crash.
        runCatching {
            settingsScrollY = findSettingsScroller()?.scrollY ?: 0
            settingsDialog?.dismiss()
            showSettings()
        }.onFailure {
            android.util.Log.w("ForestNav", "settings refresh failed", it)
            settingsDialog?.dismiss()
        }
    }

    /**
     * Choose which layer's coordinate system the project is treated as being in.
     *
     * Most folders hold one coordinate system and never need this. It matters for the
     * ones that do not: the app otherwise takes the most common one, which is a guess,
     * and a guess about coordinates moves the map by kilometres without looking wrong.
     */
    private fun showDefaultCrsPicker() {
        val dir = mapDir ?: run { toast("Load the project folder first."); return }
        val chosen = readProjectSettings(dir).defaultCrsLayer
        val options = dir.listFiles()
            .filter { it.endsWith(".shp", true) }
            .map { it.substringBeforeLast('.') }
            .filter { st -> !st.startsWith("network_", true) && !st.endsWith("_network", true) }
            .distinct()
            .sorted()
        if (options.isEmpty()) { toast("No layers in this folder"); return }

        // Name each layer by the coordinate system it actually carries, so the choice is
        // made on evidence rather than on a guess from the file name.
        val labels = options.map { st ->
            val c = runCatching { Utm.classify(prjFor(dir, "$st.shp")) }.getOrNull()
            val what = when (c?.kind) {
                Utm.Crs.UTM_METERS -> "UTM ${c.utmZone}N"
                Utm.Crs.WGS84_DEGREES -> "WGS84 degrees"
                Utm.Crs.ALBERS -> "Albers"
                Utm.Crs.OTHER -> "unrecognised"
                null -> "no .prj"
            }
            val tick = if (st.equals(chosen, true)) "* " else "  "
            "$tick$st  ($what)"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Default CRS (project coordinate system)")
            .setItems(labels) { d, which ->
                val picked = options[which]
                writeProjectSettings(dir, readProjectSettings(dir).copy(defaultCrsLayer = picked))
                applyDefaultCrs(dir, picked)
                d.dismiss()
                refreshSettingsIfOpen()
                val c = runCatching { Utm.classify(prjFor(dir, "$picked.shp")) }.getOrNull()
                toast("Default CRS: $picked" + when (c?.kind) {
                    Utm.Crs.UTM_METERS -> " (UTM ${c.utmZone}N)"
                    Utm.Crs.WGS84_DEGREES -> " (WGS84 degrees)"
                    else -> ""
                })
            }
            .setNeutralButton("Automatic") { _, _ ->
                writeProjectSettings(dir, readProjectSettings(dir).copy(defaultCrsLayer = ""))
                applyDefaultCrs(dir, "")
                refreshSettingsIfOpen()
                toast("Default CRS: worked out from the layers")
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    /** What the Default CRS button shows. */
    private fun defaultCrsLabel(): String {
        val dir = mapDir ?: return "automatic"
        val chosen = readProjectSettings(dir).defaultCrsLayer
        if (chosen.isBlank()) return "automatic"
        val c = runCatching { Utm.classify(prjFor(dir, "$chosen.shp")) }.getOrNull()
        return when (c?.kind) {
            Utm.Crs.UTM_METERS -> "$chosen (UTM ${c.utmZone}N)"
            Utm.Crs.WGS84_DEGREES -> "$chosen (degrees)"
            else -> "$chosen (unrecognised)"
        }
    }

    /** Re-decide the working zone from a chosen layer, and redraw. */
    private fun applyDefaultCrs(dir: SafMapDir, layer: String) {
        if (layer.isBlank()) {
            workingZone = null
        } else {
            val c = runCatching { Utm.classify(prjFor(dir, "$layer.shp")) }.getOrNull()
            if (c?.kind == Utm.Crs.UTM_METERS && c.utmZone in 1..60) {
                workingZone = c.utmZone
            } else {
                toast("$layer is not in a UTM zone, so the zone was left alone")
                return
            }
        }
        // The zone feeds every projection on the screen, so the layers have to be read
        // again against it rather than left drawn at the old scale.
        cachedSettings = null
        if (mapStarted) {
            closeMap()
            startMap()
        }
    }

    private fun showMapUpPicker() {
        val modes = listOf(
            OrientationMode.NORTH_UP,
            OrientationMode.COURSE_UP,
            OrientationMode.DIRECT_LINE_UP,
            OrientationMode.COMPASS_UP,
            OrientationMode.WAYPOINT_UP,
        )
        val checked = modes.indexOf(orientation.mode)
        android.app.AlertDialog.Builder(this)
            .setTitle("Map up")
            .setSingleChoiceItems(modes.map { mapUpName(it) }.toTypedArray(), checked) { d, which ->
                orientation.mode = modes[which]
                // Remembered, like every other setting on this screen. It used to live
                // only in memory, so the choice was silently thrown away on the next
                // launch and the crew had to pick it again every morning.
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(PREF_MAP_UP, modes[which].name).apply()
                orientation.reset()
                d.dismiss()
                relayout()
                refreshSettingsIfOpen()
                toast("Map up: ${mapUpName(modes[which])}")
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    /** Name shown in the picker and on the button. */
    private fun mapUpName(mode: OrientationMode): String = when (mode) {
        OrientationMode.NORTH_UP -> "North"
        OrientationMode.COURSE_UP -> "Course (direction of travel)"
        OrientationMode.DIRECT_LINE_UP -> "Bearing to target"
        OrientationMode.COMPASS_UP -> "Compass"
        OrientationMode.WAYPOINT_UP -> "Waypoint"
    }


    /**
     * Main-page buttons are this many times their natural height. Phones use 1.7; tablets
     * (smallest width 600 dp or more) use 1.7 increased by a further 1.3, because the same
     * buttons were too thin to hit on the larger screen.
     */
    private val MAIN_BUTTON_HEIGHT_SCALE: Float
        get() = if (resources.configuration.smallestScreenWidthDp >= 600) 1.7f * 1.3f else 1.7f

    private fun commandButton(cmd: Command) = android.widget.Button(this).apply {
        text = cmd.label
        isAllCaps = false
        textSize = 9f
        minHeight = 0
        minimumHeight = 0
        setPadding(1, 1, 1, 1)
        isEnabled = cmd.action != null
        alpha = if (isEnabled) 1f else 0.35f
        // Three kinds of button, told apart by colour so the right one is easy to hit:
        //   commands (start/save/stop/exit) : black on orange
        //   Navigate                          : black on light green, blue border
        //   everything else that runs something: black on yellow, red border
        val fill: Int
        val border: Int
        when {
            cmd.command -> { fill = 0xFFFF8C00.toInt(); border = 0xFF8A3D00.toInt() }
            cmd.label.equals("Navigate", true) -> { fill = 0xFFA5D6A7.toInt(); border = 0xFF1565C0.toInt() }
            cmd.action != null -> { fill = 0xFFFFD400.toInt(); border = 0xFFD32F2F.toInt() }
            else -> { fill = 0xFF6B7280.toInt(); border = 0xFF374151.toInt() }
        }
        setTextColor(0xFF000000.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            setStroke((2 * density).toInt().coerceAtLeast(2), border)
        }
        setOnClickListener { cmd.action?.invoke() }
        // Main-page buttons were only one text line tall, which made the right one hard to
        // hit on a phone. Make them 1.7 times their natural height.
        measure(
            android.view.View.MeasureSpec.UNSPECIFIED,
            android.view.View.MeasureSpec.UNSPECIFIED,
        )
        minimumHeight = (measuredHeight * MAIN_BUTTON_HEIGHT_SCALE).toInt()
    }

    private fun gridParams(index: Int) = android.widget.GridLayout.LayoutParams().apply {
        width = 0
        height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
        columnSpec = android.widget.GridLayout.spec(index % MainGrid.COLUMNS, 1f)
        rowSpec = android.widget.GridLayout.spec(index / MainGrid.COLUMNS)
        setMargins(1, 1, 1, 1)
    }

    /** Step to the next finer or coarser conventional scale. */
    private fun stepZoom(finer: Boolean) {
        val ladder = SCALE_LADDER
        val i = ladder.indexOfFirst { it >= mapView.scaleDenominator }
            .let { if (it < 0) ladder.size - 1 else it }
        val j = (i + if (finer) -1 else 1).coerceIn(0, ladder.size - 1)
        mapView.scaleDenominator = ladder[j]
        loadViewport(false)
        updateOverlays()
    }

    private fun centreOnMe() {
        goToPinned = false
        val loc = latestLoc
        if (loc == null) { toast("No GPS fix"); return }
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        // Centring on the fix and then following it are the same request. This cleared
        // follow, so "Center me" snapped the view once and the map then stayed behind as
        // the phone moved.
        follow = true
        mapView.centerE = e
        mapView.centerN = n
        loadViewport(false)
        relayout()
        updateOverlays()
    }



    // ---- Go to… ------------------------------------------------------------

    /** Set by Go to… so the map stays put until the user takes over. */
    private var goToPinned = false

    /**
     * `Go to…`: list the values of the chosen field and centre the map on one.
     *
     * Layer and field come from the spreadsheet, so the same button searches Ground_Call
     * in one project and something else in the next.
     */
    private fun goTo() {
        val dir = mapDir ?: run { toast("Load the project folder first."); return }
        val cfg = settings()
        // A layer chosen in Settings wins over the spreadsheet default.
        val ps = readProjectSettings(dir)
        val goToLayer = ps.goToLayer.ifBlank { cfg.goToLayer }
        val goToField = ps.goToField.ifBlank { cfg.goToField }
        val shp = org.forestnav.app.map.MobWriter.shpName(dir, goToLayer)
        if (shp == null) {
            toast("$goToLayer.shp is not in this project. Set the Go to… layer in Settings.")
            return
        }
        val labels = fieldLabels(dir, shp, goToField)
        if (labels.isEmpty()) {
            // Either the layer is empty or the field is the wrong one. Both are fixed the
            // same way, so offer the picker instead of a dead end.
            android.app.AlertDialog.Builder(this)
                .setTitle("Nothing to go to")
                .setMessage(
                    "$goToLayer.shp has no values in \"$goToField\". " +
                        "Choose a different layer and field?"
                )
                .setPositiveButton("Choose") { _, _ ->
                    pickLayerThenField("Go to", pointLayerNames(dir), dir, goToLayer, goToField) { l, f ->
                        writeProjectSettings(dir, ps.copy(goToLayer = l, goToField = f))
                        toast("Go to now $l.$f")
                        goTo()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Go to… $goToLayer")
            .setItems(labels.toTypedArray()) { _, which ->
                centreOnEntry(dir, cfg.copy(goToLayer = goToLayer, goToField = goToField), shp, labels[which])
            }
            .setNeutralButton("Change field") { _, _ ->
                pickLayerThenField("Go to", pointLayerNames(dir), dir, goToLayer, goToField) { l, f ->
                    writeProjectSettings(dir, ps.copy(goToLayer = l, goToField = f))
                    toast("Go to now $l.$f")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun centreOnEntry(
        dir: SafMapDir, cfg: NavSettings, shp: String, label: String,
    ) {
        val stem = shp.substringBeforeLast('.')
        fun sidecar(ext: String): ByteArray? {
            val hit = dir.listFiles().firstOrNull {
                it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".$ext", true)
            } ?: return null
            return dir.readFile(hit)
        }
        val shpBytes = sidecar("shp")
        val dbfBytes = sidecar("dbf")
        if (shpBytes == null || dbfBytes == null) {
            toast("${shp} could not be read.")
            return
        }
        val idx = org.gcap.core.PointStore.indexOf(dbfBytes, cfg.goToField, label)
        val raw = if (idx >= 0) org.gcap.core.PointStore.coordAt(shpBytes, idx) else null
        if (raw == null) {
            toast("$label was not found in ${cfg.goToLayer}.shp.")
            return
        }
        // A layer may store degrees while the map is in working-zone metres. Centring on
        // the raw numbers put the map thousands of kilometres away, so project first.
        val layerCrs = Utm.classify(prjFor(dir, shp))
        val c = if (layerCrs.kind == Utm.Crs.UTM_METERS) {
            raw
        } else {
            // shapefile order is x=longitude, y=latitude
            val (easting, northing) = Utm.wgs84ToUtmZone(raw[1], raw[0], workingZone())
            doubleArrayOf(easting, northing)
        }
        if (!c.all { it.isFinite() }) {
            toast("$label is not at a usable position.")
            return
        }
        follow = false
        mapView.centerE = c[0]
        mapView.centerN = c[1]
        // fixed on purpose: the user picks a scale they know, so it is not carried over
        mapView.scaleDenominator = GO_TO_SCALE
        goToPinned = true
        navPlan.setDestination(label, c[0], c[1],
            org.gcap.core.nav.NavigationPlan.Destination.Origin.GO_TO)
        lastGoToDest = navPlan.destination
        loadViewport(false)
        relayout()
        updateOverlays()
        toast("Centred on $label at 1:${GO_TO_SCALE}")
    }

    /** The scale Go to… always uses. */
    private val GO_TO_SCALE = 10_000



    // ---- editing -----------------------------------------------------------

    /** The edit in progress, or null. */
    private var editSession: org.forestnav.app.map.EditSession? = null

    /** True once Add point has been pressed and the next map tap is a new point. */
    private var addArmed = false

    /** Writes out unsaved edits on the interval from A8; disabled when that is blank. */
    private var autoSaver: org.forestnav.app.map.AutoSaver? = null

    private fun startAutosave() {
        autoSaver = org.forestnav.app.map.AutoSaver(settings().saveEditsEverySec)
            .also { if (it.enabled) toast("Autosave every ${it.everySec}s") }
    }

    /** Called after any change that leaves edits unsaved. */
    private fun editsChanged() {
        if (autoSaver?.markDirty() == true) commitEdits()
    }

    /**
     * Write pending edits without the usual confirmation, for autosave.
     *
     * Never touches [editSession]: a failed autosave has to leave the edits pending so
     * D3 or EXIT can still save them, rather than dropping work nobody was told about.
     */
    private fun commitEdits() {
        val s = editSession ?: return
        val dir = mapDir ?: return
        runWrite("Autosave") {
            val c = s.commit(dir, projectCrsText(dir), workingZone())
            if (c is org.forestnav.app.map.EditSession.Commit.Written) {
                autoSaver?.markClean()
                updateOverlays()
            }
            commitMessage(c)
        }
    }

    /**
     * D1: begin editing the configured point layer.
     *
     * Refused when the sheet marks that layer not editable, because writing to it would
     * quietly change a survey the user was told was fixed.
     */
    private fun startEdit() {
        // Start edit edits points and nothing else. The rules live in EditMode so they are
        // tested rather than merely intended - cutting a road is not something a thumb on
        // the main page should ever be able to start.
        when (val d = org.forestnav.app.map.EditMode.canStartPoints(
            pointSessionOpen = editSession != null,
            lineSessionExists = lineSession != null,
        )) {
            is org.forestnav.app.map.EditMode.Decision.No -> { toast(d.why); return }
            else -> Unit
        }
        val dir = mapDir ?: run { toast("Load the project folder first."); return }
        val cfg = settings()
        val layer = cfg.addPointLayer
        val spec = loadSpecs(dir).firstOrNull { it.name.equals(layer, true) }
        if (spec != null && !spec.editable) {
            toast("$layer is marked not editable in this project's settings")
            return
        }
        val editField = org.forestnav.app.map.MobWriter.resolveField(dir, layer, cfg.nameFieldFor(layer))
        val existing = org.forestnav.app.map.MobWriter.existingNames(dir, layer, editField)
        editSession = org.forestnav.app.map.EditSession(layer, editField, existing)
        addArmed = false
        startAutosave()
        val every = autoSaver?.everySec
        toast("Editing $layer.shp - ${existing.size} points" +
            if (every != null) ", autosave every ${every}s" else "")
    }

    /** D5: arm the next map tap as a new point. */
    private fun armAddPoint() {
        if (editSession == null) { toast("Start edit first"); return }
        addArmed = true
        toast("Tap the map to place the point")
    }

    private fun promptPointName(e: Double, n: Double) {
        val s = editSession ?: return
        addArmed = false
        val field = s.field
        val input = android.widget.EditText(this)
        android.app.AlertDialog.Builder(this)
            .setTitle("Name this point")
            .setMessage("Writes to $field in ${s.layer}.shp")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString()
                if (s.add(e, n, name)) {
                    editsChanged()
                    val secs = autoSaver?.remainingSec()
                    toast("Added ${name.trim()}" + if (secs != null && secs > 0) ", saving in ${secs}s" else "")
                } else {
                    toast(s.lastError ?: "Not added")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** D3: write everything staged. */
    /**
     * Save whatever is staged, points or cuts.
     *
     * One button for both because both are edits the user has made deliberately, and
     * having to hunt for a second save control is how unsaved work gets lost.
     */
    /** Staged cuts: save them, throw them away, or carry on cutting. */
    private fun askAboutStagedCuts(cuts: org.forestnav.app.map.LineEditSession) {
        val which = cuts.layers.joinToString(", ")
        optionDialog(
            "${cuts.count} cut(s) not saved",
            "${cuts.count} cut(s) staged on $which.\n\nSaving writes the gaps into the layer. Its saved " +
                "network is then out of date: build the network again to route on it. " +
                "Discarding leaves the layer exactly as it is.",
            listOf(
                "Keep cutting" to {},
                "Discard" to {
                    cuts.discard()
                    cancelCut(discard = true)
                    toast("Cuts discarded, no layer changed")
                },
                "Save" to { saveLineCuts(cuts) },
            ),
        )
    }

    private fun saveLineCuts(cuts: org.forestnav.app.map.LineEditSession) {
        val dir = mapDir ?: run { toast("Load the project folder first."); return }
        setBusy("Cutting")
        Thread {
            val outcome = runCatching { cuts.commit(dir) }
                .getOrElse { e ->
                    runOnUiThread { clearBusy(); toast("Could not cut: ${e.message}") }
                    return@Thread
                }
            // Logged as well as shown: a toast is gone before anyone can read it, and a cut
            // that fails to write while clearing its banner is the failure most likely to
            // cost a road in the field.
            android.util.Log.i(
                "ForestNav",
                "CUTSAVE written=${outcome.written}/${outcome.attempted} " +
                    "failed=${outcome.failed} :: ${outcome.messages.joinToString("; ")}",
            )
            runOnUiThread {
                clearBusy()
                cancelCut(discard = true)
                // A dialog, not a toast: a failed cut must stay on screen until read, because
                // the layer is the one thing that cannot be recovered from a mistake here.
                android.app.AlertDialog.Builder(this)
                    .setTitle(
                        if (outcome.failed == 0) "Cuts saved" else "Some cuts were NOT saved",
                    )
                    .setMessage(
                        "${outcome.written} of ${outcome.attempted} cut(s) written.\n\n" +
                            outcome.messages.joinToString("\n"),
                    )
                    .setPositiveButton("OK", null)
                    .show()
                loadViewport(true)
            }
        }.start()
    }

    private fun saveEdits() {
        val cuts = lineSession
        if (cuts != null && cuts.dirty) { saveLineCuts(cuts); return }
        val s = editSession ?: run { toast("Not editing"); return }
        if (!s.dirty) { toast("Nothing to save"); return }
        val dir = mapDir ?: run { toast("Load the project folder first."); return }
        runWrite("Saving edits") {
            val c = s.commit(dir, projectCrsText(dir), workingZone())
            if (c is org.forestnav.app.map.EditSession.Commit.Written) autoSaver?.markClean()
            commitMessage(c) + if (c is org.forestnav.app.map.EditSession.Commit.Written && c.created) {
                " - new layer ${c.layer}.shp created"
            } else ""
        }
    }

    /** D2: stop editing, offering to save first when there is anything to save. */
    private fun stopEdit() {
        val cuts = lineSession
        if (cuts != null && cuts.dirty) { askAboutStagedCuts(cuts); return }
        if (cuts != null) {
            cancelCut(discard = true)
            toast("Line editing stopped, nothing to save")
            return
        }
        val s = editSession ?: run { toast("Not editing"); return }
        if (!s.dirty) {
            editSession = null
            addArmed = false
            autoSaver = null
            toast("Editing stopped")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Unsaved points")
            .setMessage("${s.count} point(s) not yet written to ${s.layer}.shp")
            .setPositiveButton("Save and stop") { _, _ -> saveEditsThenStop() }
            .setNegativeButton("Discard") { _, _ ->
                s.discard()
                editSession = null
                addArmed = false
                autoSaver = null
                toast("Edits discarded")
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun saveEditsThenStop() {
        val s = editSession ?: return
        val dir = mapDir ?: return
        runWrite("Saving edits") {
            val c = s.commit(dir, projectCrsText(dir), workingZone())
            if (c !is org.forestnav.app.map.EditSession.Commit.Failed) {
                editSession = null
                addArmed = false
                autoSaver = null
            }
            commitMessage(c)
        }
    }

    private fun commitMessage(c: org.forestnav.app.map.EditSession.Commit): String = when (c) {
        is org.forestnav.app.map.EditSession.Commit.Written ->
            "${c.points} point(s) saved to ${c.layer}.shp"
        is org.forestnav.app.map.EditSession.Commit.Failed -> c.reason
        org.forestnav.app.map.EditSession.Commit.Nothing -> "Nothing to save"
    }

    /**
     * A3: stop everything and close.
     *
     * Unsaved points are offered first. Closing on top of them would lose them silently,
     * which is the one outcome an edit mode must never produce.
     */
    private fun exitApp() {
        val s = editSession
        if (s != null && s.dirty) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Unsaved points")
                .setMessage("${s.count} point(s) not yet written to ${s.layer}.shp. Save before closing?")
                .setPositiveButton("Save and exit") { _, _ -> saveEditsThen { stopEverything(); finish() } }
                .setNegativeButton("Discard") { _, _ -> s.discard(); stopEverything(); finish() }
                .setNeutralButton("Cancel", null)
                .show()
            return
        }
        stopEverything()
        finish()
    }

    private fun saveEditsThen(after: () -> Unit) {
        val s = editSession ?: run { after(); return }
        val dir = mapDir ?: run { after(); return }
        runWrite("Saving edits") {
            val c = s.commit(dir, projectCrsText(dir), workingZone())
            if (c !is org.forestnav.app.map.EditSession.Commit.Failed) {
                editSession = null
                addArmed = false
                autoSaver = null
                after()
            }
            commitMessage(c)
        }
    }

    /** Stop recording, navigation and GPS before the activity goes away. */
    private fun stopEverything() {
        if (trackRecorder?.recording == true) {
            val n = trackRecorder!!.count
            trackRecorder!!.stop()
            toast("Recording stopped with $n point(s) - not saved")
        }
        trackRecorder?.stop()
        trackRecorder = null
        editSession = null
        addArmed = false
        autoSaver = null
        goToPinned = false
        if (navigating) runCatching { stopNavigation() }
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        val listener = locListener
        if (lm != null && listener != null) {
            runCatching { lm.removeUpdates(listener) }
        }
        locListener = null
    }


    private fun lastModified(dir: SafMapDir, name: String): Long =
        dir.listFiles().firstOrNull { it.equals(name, true) }?.let { dir.lastModified(it) } ?: 0L

    // ---- track recording ---------------------------------------------------

    /** Null unless a track is being recorded. */
    private var trackRecorder: org.forestnav.app.map.TrackRecorder? = null

    /** Layer the finished track is written to, so a settings change mid-track is ignored. */
    private var trackTargetLayer: String = "tracks"

    /** C1: begin a track from the present position and keep feeding it. */
    private fun startRecording() {
        if (mapDir == null) { toast("Load the project folder first."); return }
        val loc = latestLoc ?: run { toast("No GPS fix"); return }
        val cfg = settings()
        val r = org.forestnav.app.map.TrackRecorder(
            cfg.trackIntervalSec, cfg.trackMinDistanceM, cfg.trackMaxDistanceM, workingZone())
        r.start()
        // Seed with the present position so a short track is still recordable.
        // The stamp must come from the same clock the location listener uses -
        // elapsedRealtime, not currentTimeMillis - or every later fix looks like it
        // arrived before this one and is thrown away as out of order.
        val (e0, n0) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        r.addFix(e0, n0, android.os.SystemClock.elapsedRealtime())
        trackRecorder = r
        trackTargetLayer = cfg.trackLayer
        toast("Recording into ${cfg.trackLayer}.shp every ${cfg.trackIntervalSec}s")
    }

    /** C2: stop, name, and write the line plus its GPX. */
    private fun stopRecording() {
        val r = trackRecorder ?: run { toast("Not recording"); return }
        r.stop()
        trackRecorder = null
        if (r.count < 2) {
            toast("Track stopped with ${r.count} point - nothing to save")
            return
        }
        val dir = mapDir ?: return
        val layer = trackTargetLayer
        val c = java.util.Calendar.getInstance()
        val suggested = org.gcap.core.LineStore.defaultName(c)
        promptTrackName(dir, layer, r, suggested, org.gcap.core.LineStore.defaultDate(c))
    }

    private fun promptTrackName(
        dir: SafMapDir, layer: String, r: org.forestnav.app.map.TrackRecorder,
        suggested: String, date: String,
    ) {
        val input = android.widget.EditText(this).apply {
            setText(suggested)
            setSelection(suggested.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Save track to $layer.shp")
            .setMessage("${r.count} points, ${"%.0f".format(r.lengthM())} m\n${r.skipped} readings skipped\nName")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                writeTrack(dir, layer, input.text.toString().trim(), date, r)
            }
            .setNegativeButton("Discard", null)
            .show()
    }

    private fun writeTrack(
        dir: SafMapDir, layer: String, name: String, date: String,
        r: org.forestnav.app.map.TrackRecorder,
    ) {
        if (name.isEmpty()) { toast("Track needs a name"); return }
        runWrite("Saving track") {
            // the project's own coordinate text, so a new tracks.shp is labelled correctly
            val crs = projectCrsText(dir)
            val existing = readSidecars(dir, layer)
            // the finished track is stored in the layer's own coordinate system, so a
            // tracks.shp kept in degrees receives degrees
            val prj = org.forestnav.app.map.LayerCrs.prjOf(dir, layer)
            val pts = r.points.map {
                org.forestnav.app.map.LayerCrs.toLayerCoords(it[0], it[1], workingZone(), prj)
            }
            val out = org.gcap.core.LineStore.append(existing, pts, name, date, crs)
            // The chosen line layer gets the same track as a new feature of its own, so a
            // walked or driven line joins the network data rather than living only in
            // tracks.shp. Written after tracks, and reported separately, because a failure
            // here must not lose the track itself.
            val into = recordTrackTarget()
            var intoMsg = ""
            if (into.isNotBlank() && !into.equals(layer, true)) {
                intoMsg = runCatching { appendTrackToLineLayer(dir, into, name, date, r) }
                    .getOrElse { " ; $into not written: ${it.message ?: it.javaClass.simpleName}" }
            }
            var ok = true
            val stem = layer.substringBeforeLast('.')
            // Write back under the layer's existing on-disk name. `tracks` and `TRACKS`
            // must not end up as two files holding half a track each.
            val onDiskShp = dir.listFiles().firstOrNull {
                it.endsWith(".shp", true) && it.substringBeforeLast('.').equals(stem, true)
            }
            val writeStem = onDiskShp?.substringBeforeLast('.') ?: stem
            for ((ext, bytes) in out) {
                val fname = "$writeStem.$ext"
                val done = if (dir.listFiles().any { it.equals(fname, true) }) {
                    dir.overwriteFile(fname, bytes)
                } else {
                    dir.createFile(fname, bytes)
                }
                if (!done) ok = false
            }
            // the GPX keeps the native WGS84 fixes, whichever CRS the shapefile uses
            val gpxName = "$writeStem-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.gpx"
            if (!dir.createFile(gpxName, r.gpx(name)) && !dir.overwriteFile(gpxName, r.gpx(name))) {
                ok = false
            }
            if (ok) "Track $name saved: ${r.count} points, ${"%.0f".format(r.lengthM())} m" +
                " + $gpxName$intoMsg"
            else "Track saved but not every file could be written$intoMsg"
        }
    }

    /**
     * Write a finished track into [stem] as a new line feature.
     *
     * The track is stored in that layer's own coordinate system, so a layer kept in degrees
     * receives degrees - the same rule MOB and Add point follow.
     */
    private fun appendTrackToLineLayer(
        dir: SafMapDir,
        stem: String,
        name: String,
        date: String,
        r: org.forestnav.app.map.TrackRecorder,
    ): String {
        val existing = readSidecars(dir, stem)
        if (existing["shp"] == null) return "; $stem.shp could not be read"
        val crs = projectCrsText(dir)
        val prj = org.forestnav.app.map.LayerCrs.prjOf(dir, stem)
        val pts = r.points.map {
            org.forestnav.app.map.LayerCrs.toLayerCoords(it[0], it[1], workingZone(), prj)
        }
        if (pts.size < 2) return "; track too short to add to $stem"
        val out = org.gcap.core.LineStore.append(existing, pts, name, date, crs)
        val writeStem = onDiskStem(dir, stem) ?: stem
        var ok = true
        for ((ext, bytes) in out) {
            val fname = "$writeStem.$ext"
            val done = if (dir.listFiles().any { it.equals(fname, true) }) {
                dir.overwriteFile(fname, bytes)
            } else {
                dir.createFile(fname, bytes)
            }
            if (!done) ok = false
        }
        // A new line changes the layer, so its saved network is now out of date. The user
        // rebuilds it when they choose; the app does not do that or delete the old one.
        return if (ok) {
            "; also added to $stem.shp. Build its network again to route on the new line"
        } else {
            "; $stem not written"
        }
    }

    /** Coordinate text used for a newly created layer, taken from the project's own layers. */
    private fun projectCrsText(dir: SafMapDir): String? {
        for (name in dir.listFiles().filter { it.endsWith(".prj", true) }) {
            val text = dir.readFile(name)?.let { String(it).trim() }
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    /** Existing sidecars of a line layer, keyed by extension. */
    private fun readSidecars(dir: SafMapDir, layer: String): Map<String, ByteArray> {
        val stem = layer.substringBeforeLast('.')
        val out = HashMap<String, ByteArray>()
        for (ext in org.gcap.core.LineStore.SIDECARS) {
            val hit = dir.listFiles().firstOrNull {
                it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".$ext", true)
            } ?: continue
            dir.readFile(hit)?.let { out[ext] = it }
        }
        return out
    }

    // ---- MOB ---------------------------------------------------------------

    /** Resolved settings for the open project, re-read whenever the folder changes. */
    @Volatile
    private var cachedSettings: NavSettings? = null

    private fun settings(): NavSettings {
        cachedSettings?.let { return it }
        val dir = mapDir
        // No spreadsheet: the app's own defaults, overridden by this project's file.
        val fromSheet = NavSettings()
        // Values the user changed in Settings win over the spreadsheet: they were set
        // deliberately on this device, and a sheet copied from elsewhere must not undo it.
        val ps = dir?.let { readProjectSettings(it) }
        val loaded = if (ps == null) fromSheet else fromSheet.copy(
            // The layer the picker saved, not the sheet's name: Settings wrote xgv_mp but
            // the row still read Ground_Call, so Go to kept using the spreadsheet layer.
            goToLayer = ps.goToLayer.ifBlank { fromSheet.goToLayer },
            goToField = ps.goToField.ifBlank { fromSheet.goToField },
            addPointLayer = ps.addPointLayer.ifBlank { fromSheet.addPointLayer },
            addPointNameField = ps.addPointField.ifBlank { fromSheet.addPointNameField },
            trackIntervalSec = ps.trackIntervalSec ?: fromSheet.trackIntervalSec,
            trackMinDistanceM = ps.trackMinDistanceM ?: fromSheet.trackMinDistanceM,
            saveEditsEverySec = ps.saveEditsEverySec ?: fromSheet.saveEditsEverySec,
            defaultScaleDenominator = ps.defaultScaleDenominator ?: fromSheet.defaultScaleDenominator,
        )
        cachedSettings = loaded
        return loaded
    }

    /**
     * `MOB`: drop a point at the present position and name it by the clock.
     *
     * Deliberately not gated on edit mode - MOB is for when something has just gone wrong
     * and the fastest thing that works is the one button that works.
     */
    private fun mob() {
        val loc = latestLoc
        if (loc == null) { toast("No GPS fix"); return }
        val dir = mapDir ?: run { toast("Load the project folder first."); return }
        val cfg = settings()
        val layer = cfg.mobLayer
        val c = java.util.Calendar.getInstance()
        val suggested = org.gcap.core.PointStore.defaultName(
            c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
        )
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        // each layer keeps its own name field: points.shp holds NAME, not GCALL_ID
        val field = org.forestnav.app.map.MobWriter.resolveField(dir, layer, cfg.nameFieldFor(layer))

        val existing = mobExisting(dir, layer, field)
        if (existing.contains(suggested)) {
            promptMobOverwrite(dir, layer, field, suggested, existing, e, n)
        } else {
            promptMobName(dir, layer, field, suggested, existing, e, n)
        }
    }

    private fun mobShpName(dir: SafMapDir, layer: String): String? {
        val stem = layer.substringBeforeLast('.')
        return dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".shp", true)
        }
    }

    /** Names already in the MOB layer, so a collision can be caught before writing. */
    private fun mobExisting(dir: SafMapDir, layer: String, field: String): List<String> {
        val shp = mobShpName(dir, layer) ?: return emptyList()
        val stem = shp.substringBeforeLast('.')
        val dbf = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
        } ?: return emptyList()
        val bytes = dir.readFile(dbf) ?: return emptyList()
        return org.gcap.core.PointStore.names(bytes, field)
    }

    private fun promptMobName(
        dir: SafMapDir, layer: String, field: String, suggested: String,
        existing: List<String>, e: Double, n: Double,
    ) {
        val input = android.widget.EditText(this).apply {
            setText(suggested)
            setSelection(suggested.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("MOB at present position")
            .setMessage("Layer $layer.shp\nName")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                writeMob(dir, layer, field, input.text.toString().trim(), existing, e, n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptMobOverwrite(
        dir: SafMapDir, layer: String, field: String, name: String,
        existing: List<String>, e: Double, n: Double,
    ) {
        optionDialog(
            "Name already used",
            "$name is already in $layer.shp. Overwrite it, or keep it for this MOB?",
            listOf(
                "Cancel" to {},
                "Keep both" to {
                    var alt = name
                    var n2 = 2
                    while (existing.contains(alt)) { alt = "$name-$n2"; n2++ }
                    writeMob(dir, layer, field, alt, existing, e, n)
                },
                "Overwrite" to { writeMob(dir, layer, field, name, existing, e, n) },
            ),
        )
    }

    private fun writeMob(
        dir: SafMapDir, layer: String, field: String, name: String,
        existing: List<String>, e: Double, n: Double,
    ) {
        runWrite("Saving MOB") {
            when (val out = org.forestnav.app.map.MobWriter.write(
                dir, layer, field, name, e, n, null, workingZone())) {
                is org.forestnav.app.map.MobWriter.Outcome.Written -> {
                    // Show it. A MOB saved off the edge of the current view, or beyond the
                    // scale where point layers load, gave no sign of having worked at all.
                    // This runs on the write thread, so the view work has to be posted.
                    runOnUiThread { revealPoint(e, n) }
                    if (out.created) "New layer ${out.layer}.shp created with ${out.name}"
                    else "MOB ${out.name} saved to ${out.layer}.shp"
                }
                is org.forestnav.app.map.MobWriter.Outcome.Failed -> out.reason
            }
        }
    }

    /**
     * Shapefile writes run off the UI thread: a four-file rewrite through SAF took about
     * 20 s on a slow phone and froze the activity when done inline.
     */
    private fun runWrite(label: String, work: () -> String) {
        if (writeBusy) {
            toast("Still saving the previous change - nothing written, try again")
            return
        }
        writeBusy = true
        mapView.loadProgress = label
        mapView.invalidate()
        Thread {
            val msg = try {
                work()
            } catch (e: Throwable) {
                android.util.Log.e("ForestNav", "write failed", e)
                "Save failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            mapDir?.clearCaches()
            runOnUiThread {
                writeBusy = false
                mapView.loadProgress = ""
                mapView.invalidate()
                toast(msg)
                loadViewport(true)
            }
        }.start()
    }


    // ---- folder ------------------------------------------------------------

    private fun initMapDir() {
        val tree = app.safTreeUri ?: return
        mapDir = SafMapDir(this, tree)
    }

    // ---- location ----------------------------------------------------------

    private fun ensureLocation() {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) startLocationListener()
        else requestLocation.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startLocationListener() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locListener?.let { runCatching { lm.removeUpdates(it) } }
        // All abstract methods implemented: on API 28 LocationListener also
        // declares onProviderEnabled/onProviderDisabled/onStatusChanged, and a
        // class omitting them throws AbstractMethodError as soon as the platform
        // calls one (e.g. the user toggles GPS).
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                // Android can replay a cached fix after a newer one. Taking it would move
                // the map and the flight plan's next-call choice to an old position, which
                // is exactly the 1300 km jump seen on the emulator.
                val elapsedMs = loc.elapsedRealtimeNanos / 1_000_000L
                if (!fixGuard.accept(elapsedMs)) {
                    android.util.Log.w("ForestNav", "refused stale fix: " + loc.latitude + "," + loc.longitude +
                        " (elapsedRealtime=" + elapsedMs + ")")
                    return
                }
                latestLoc = loc
                if (!mapStarted) return
                val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
                mapView.setGpsMarker(e, n, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0, true)

                val now = System.currentTimeMillis()
                // the map's own fixGuard has already vetted this fix, so the recorder
                // only has to decide on interval, distance and jump size
                trackRecorder?.addFix(e, n, elapsedMs)
                headingEst.add(e, n, now)
                val hs = headingEst.estimate(now)
                // The two arrows: where the phone is going, and where it is being sent.
                // A coherent course is enough to point the travel arrow - a slow walk
                // counts - while map rotation still waits for real movement ([hs.moving]),
                // so a parked phone drifts without spinning the map.
                mapView.gpsHeadingDeg = if (hs.hasCourse) hs.bearingDeg else Double.NaN
                mapView.targetBearingDeg = navigationTargetBearing(e, n)
                // a flight plan in progress steps to its next point as calls are reached
                advanceFlightPlan()
                if (!sawFix) { sawFix = true; lastMovingAtMs = now }
                if (hs.moving) lastMovingAtMs = now
                val stillForMs = if (hs.moving) 0L else now - lastMovingAtMs
                mapView.bearingDeg = orientation.bearingFor(
                    now, hs, waypointBearingFrom(e, n), stillForMs,
                    compass?.bearingDeg?.takeIf { !it.isNaN() },
                )

                // speed-adaptive scale: walking pace shows detail, transit shows reach
                if (autoZoom) {
                    val want = speedZoom.update(hs.speedMps * 3.6, now)
                    if (want != mapView.scaleDenominator) {
                        mapView.scaleDenominator = want
                        loadViewport(false)
                    }
                }

                if (navigating) {
                    // A straight line is drawn from where the crew was when it was last
                    // plotted, so it has to be re-drawn as they move or it points at a
                    // place they have already left. A network route is left alone.
                    if (navStraightLine) {
                        navRoutePoints = doubleArrayOf(e, n, navTargetE, navTargetN)
                        mapView.setNavRoute(navRoutePoints)
                    }
                    // measured forward along the drawn line, so the figure shrinks as the
                    // crew covers ground instead of growing as they drive
                    navRemainingM = org.gcap.core.nav.RouteGeometry.remainingM(navRoutePoints, e, n)
                    if (!navRemainingM.isNaN() && navRemainingM < 50.0 && routePlan.isNotEmpty()) {
                        advanceRoutePlan()
                    }
                }
                if (follow) {
                    mapView.centerE = e
                    mapView.centerN = n
                    loadViewport(false)
                }
                updateOverlays()
            }

            @Deprecated("Required on API < 29; the platform no longer calls it.")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {
                latestLoc = null
                fixGuard.reset()
                if (::mapView.isInitialized) {
                    mapView.setGpsMarker(0.0, 0.0, 0.0, false)
                    updateOverlays()
                }
            }
        }
        locListener = listener
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, listener)
        }
    }

    // ---- map lifecycle -----------------------------------------------------

    /**
     * UTM zone the project is displayed in. Taken from the first layer that declares
     * one, because settings_1.ods makes that the default coordinate system, and it
     * must be a var now: a project may legitimately sit either side of a zone
     * boundary, and layers brought in from elsewhere still reproject into it.
     */
    private var workingZone: Int? = null

    private fun workingZone(): Int = workingZone ?: Utm.ASSUMED_ZONE

    /**
     * What the project turned out to be made of, decided once per map start.
     *
     * Layers arrive later, so this is re-run rather than cached for the session.
     */
    @Volatile
    private var projectScan: org.gcap.core.nav.ProjectScan.Result? = null

    /**
     * Look at every `.shp` in the folder and decide the project's working zone.
     *
     * Silent when nothing is wrong: a consistent folder says nothing at all. A folder with
     * a switched-on layer that has no `.prj`, or one in a different UTM zone, says so -
     * those are the layers the crew is about to rely on.
     */
    /**
     * What the folder's coordinate systems look like, or null when it cannot be read.
     *
     * Separate from [runProjectScan] so Verify layers can show the same findings on
     * demand: they are deliberately quiet at map start, which would otherwise mean the
     * crew had no way to check that the app read their folder the way they meant it.
     */
    private fun scanProject(dir: SafMapDir): org.gcap.core.nav.ProjectScan.Result? {
        return try {
            val rows = loadSpecs(dir)
            val known = rows.map { it.name }
            val enabled = rows.filter { it.visible }.map { it.name }.toSet()
            val files = dir.listFiles()
            // With no saved layer list there is nothing saying what is switched on, so
            // "enabled" comes out empty and the scan would examine nothing at all, which
            // meant a folder whose layers are fine and one whose layers are broken both
            // stayed silent. Absent a list, treat every shapefile as in use: that is what
            // the map draws anyway.
            val noLayerSheet = known.isEmpty()
            val enabledNames = if (noLayerSheet) {
                files.filter { it.endsWith(".shp", true) }.map { it.substringBeforeLast('.') }.toSet()
            } else enabled
            val probes = files.filter { it.endsWith(".shp", true) }.map { f ->
                val stem = f.substringBeforeLast('.')
                val prj = files.firstOrNull {
                    it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".prj", true)
                }
                    org.gcap.core.nav.ProjectScan.LayerProbe(
                    stem = stem,
                    prj = prj?.let { dir.readFile(it)?.toString(Charsets.UTF_8) },
                    inLayerSettings = noLayerSheet || known.any { it.equals(stem, true) },
                )
            }
            org.gcap.core.nav.ProjectScan.scan(probes, enabledNames)
        } catch (e: Exception) {
            // a scan that cannot run must never stop the map from loading
            android.util.Log.w("ForestNav", "project scan failed", e)
            null
        }
    }

    /**
 * Drop everything that was decided about the project rather than about the map, so the
 * next start reads the folder that is actually there.
 *
 * The zone in particular has to be re-derived: a user who replaces or exchanges the
 * project folder in the field gets a different CRS, and reusing the previous zone is the
 * same fault as assuming one in the first place.
 */
private fun forgetProject() {
        // Networks too: they were read out of the folder being replaced, so keeping them
        // would route on a graph belonging to files that are no longer there.
        networks = emptyMap()
        networkFilesByStem.clear()
        navRouter = null
        workingZone = null
        projectScan = null
        probeEntries = emptyList()
        layerDraftDirty = false
    }

    /** Reported once per map start, so a reload does not nag. */
    private var scanReported = false

    private fun startMap() {
        val dir = mapDir
        if (dir == null) {
            Toast.makeText(this, "Pick a folder first", Toast.LENGTH_LONG).show()
            pickFolder.launch(null)
            return
        }
        if (mapStarted) { Toast.makeText(this, "Map already started", Toast.LENGTH_SHORT).show(); return }
        mapStarted = true
        mapLoading = true
        scanReported = false
        // Everything decided from the folder's contents is decided again from scratch.
        // These outlive a single map, so without this a second Start_MAP reused the first
        // project's zone and scan: replacing the folder on disk, or opening a different
        // project, silently carried the old CRS forward and every conversion was wrong.
        forgetProject()
        // The scan reads the folder's settings and every .prj through SAF, which takes
        // seconds on a phone. Doing it on the UI thread stalled input and the first draw,
        // so the whole load runs on a worker and only the results are applied here.
        Thread {
            val p0 = System.currentTimeMillis()
            val scan = runCatching { scanProject(dir) }.getOrNull()
            val p1 = System.currentTimeMillis()
            val build = runCatching { buildLayers() }.getOrElse {
                android.util.Log.e("ForestNav", "layer build failed", it)
                LayerBuild(emptyList(), emptyList(), null)
            }
            val p2 = System.currentTimeMillis()
            val raster = runCatching {
                probeRaster(dir, readProjectSettings(dir).basemapRaster)
            }.getOrElse {
                android.util.Log.e("ForestNav", "raster probe failed", it)
                null
            }
            val p3 = System.currentTimeMillis()
            android.util.Log.i(
                "NavDiag",
                "startMap phases: scan=${p1 - p0}ms buildLayers=${p2 - p1}ms rasterProbe=${p3 - p2}ms",
            )
            runOnUiThread { finishStartMap(dir, scan, build, raster) }
        }.start()
    }

    private fun finishStartMap(
        dir: SafMapDir,
        scan: org.gcap.core.nav.ProjectScan.Result?,
        build: LayerBuild,
        raster: RasterProbe?,
    ) {
        if (!mapStarted || isDestroyed) return
        projectScan = scan
        mapLoading = false
        for (l in build.layers) mapView.addLayer(l)
        mapView.started = true
        lastMapReport = "Last load: ${build.layers.size} layer(s), " +
            "${build.skipped.size} skipped, zone ${workingZone() ?: "?"}"
        reportIfNothingDrawn(build)
        // Silent when the folder is consistent; otherwise say what is switched on and
        // does not fit, before anyone relies on it.
        projectScan?.takeIf { !scanReported && it.warnings().isNotEmpty() }?.let { s ->
            scanReported = true
            android.app.AlertDialog.Builder(this)
                .setTitle("Layers need attention")
                .setMessage(s.warnings().joinToString("\n\n"))
                .setPositiveButton("OK", null)
                .setNeutralButton("Layer settings") { _, _ -> showLayerSettings() }
                .show()
        }

        if (build.skipped.isNotEmpty()) {
            Toast.makeText(
                this,
                "Skipped (unsupported .prj): ${build.skipped.joinToString()}",
                Toast.LENGTH_LONG,
            ).show()
        }

        // Resume the last view if we have one, else fit the data.
        val last = app.lastView()
        if (last != null) {
            mapView.centerE = last.first
            mapView.centerN = last.second
            mapView.scaleDenominator = last.third
        } else if (build.dataBbox != null) {
            fitPending = build.dataBbox
        }

        // Fit before the first load, so the load is not run against a centre of 0,0.
        if (fitPending != null) fitToBbox(fitPending!!)
        if (mapView.width <= 0 && fitPending == null) {
            mapView.post { loadViewport(true) }
        } else {
            loadViewport(true)
        }
        raster?.let { applyRasterProbe(it) }
        // Overlays depend on the view size, so paint them once the layout is in.
        mapView.post {
            fitPending?.let { fitToBbox(it) }
            updateOverlays()
            refreshRaster()
        }
    }

    private var fitPending: DoubleArray? = null

    private fun closeMap() {
        mapStarted = false
        mapLoading = false
        loadSeq++
        mapView.started = false
        mapView.layers.clear()
        mapView.selected = null
        runCatching { mapView.releaseCaches() }
        runCatching { mapDir?.clearCaches() }
        clearWaypoint()
        latestLoc = null
        fixGuard.reset()
        mapView.setGpsMarker(0.0, 0.0, 0.0, false)
        rasterSource = null
        rasterStamp = ""
        mapView.setRaster(null, null, null)
        updateOverlays()
    }

    private fun fitToBbox(b: DoubleArray) {
        if (mapView.width <= 0 || mapView.height <= 0) {
            // No size yet. Retry until the view has been laid out, otherwise the fit is
            // dropped and the map stays centred on 0,0 with every layer filtered away.
            fitPending = b
            mapView.postDelayed({ fitPending?.let { fitToBbox(it) } }, 120)
            return
        }
        val (x0, y0, x1, y1) = b
        mapView.centerE = (x0 + x1) / 2
        mapView.centerN = (y0 + y1) / 2
        fitPending = null
        val spanX = (x1 - x0).coerceAtLeast(1.0)
        val spanY = (y1 - y0).coerceAtLeast(1.0)
        val px = mapView.width.coerceAtLeast(1)
        // 0.00028 m/px is the metric ground resolution of a 1:10000 map.
        mapView.scaleDenominator =
            ((spanX / px) / 0.00028).toInt().coerceIn(500, 400000)
    }

    private data class LayerBuild(
        val layers: List<MapCanvasView.MapLayer>,
        val skipped: List<String>,
        val dataBbox: DoubleArray?,
    )

    /** What the last map load produced, shown by Settings -> Map report. */
    private var lastMapReport: String = "No map loaded yet."

    /**
     * Settings -> Map report: what the app can actually see in the chosen folder.
     *
     * Written for a phone in the field with no cable attached. A blank map used to be
     * silent, so there was nothing to report even in the log.
     */
    private fun showMapReport() {
        val dir = mapDir
        if (dir == null) { toast("Pick a folder first"); return }
        setBusy("Checking folder")
        Thread {
            val report = StringBuilder()
            val listing = runCatching { dir.listFiles() }
            val files: List<String> = listing.getOrElse { emptyList() }
            report.append("Folder: ").append(app.safTreeUri?.lastPathSegment ?: "?").append("\n")
            report.append("Files in folder: ")
                .append(if (listing.isSuccess) files.size.toString() else "could not list")
                .append("\n")
            val shp = files.filter { it.endsWith(".shp", true) }
            report.append("Shapefiles: ").append(shp.size).append("\n\n")
            val built = runCatching { buildLayers() }.getOrElse {
                report.append("Reading layers failed: ").append(it.javaClass.simpleName)
                    .append(": ").append(it.message).append("\n")
                null
            }
            if (built != null) {
                report.append("Layers drawn: ").append(built.layers.size).append("\n")
                report.append("Skipped: ").append(
                    if (built.skipped.isEmpty()) "none" else built.skipped.joinToString(", ")
                ).append("\n")
                report.append("Working zone: ").append(workingZone() ?: "unknown").append("\n\n")
                if (built.layers.isEmpty()) {
                    report.append("Nothing was drawn. Each .shp needs its .shx and .dbf beside it.\n")
                    report.append("First 12 shapefiles and their sidecars:\n")
                    for (f in shp.take(12)) {
                        val stem = f.substringBeforeLast('.')
                        val hasShx = files.any { it.equals("$stem.shx", true) }
                        val hasDbf = files.any { it.equals("$stem.dbf", true) }
                        val hasPrj = files.any { it.equals("$stem.prj", true) }
                        report.append("  ").append(stem)
                            .append(if (hasShx) "" else "  NO .shx")
                            .append(if (hasDbf) "" else "  NO .dbf")
                            .append(if (hasPrj) "" else "  no .prj")
                            .append("\n")
                    }
                }
            }
            runOnUiThread {
                clearBusy()
                val body = report.toString()
                val tv = android.widget.TextView(this@MapActivity).apply {
                    text = body
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(24, 24, 24, 24)
                }
                val sv = android.widget.ScrollView(this@MapActivity)
                sv.addView(tv)
                android.app.AlertDialog.Builder(this@MapActivity)
                    .setTitle("Map report")
                    // setView, not setMessage: this builder's setMessage takes text only.
                    .setView(sv)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }.start()
    }

    /** Report a map that loaded but drew nothing, rather than leaving a blank screen. */
    private fun reportIfNothingDrawn(built: LayerBuild) {
        android.util.Log.i(
            "ForestNav",
            "MAPDBG layers=${built.layers.size} skipped=${built.skipped.joinToString(",")} " +
                "zone=${workingZone()} scan=${projectScan?.workingZone}",
        )
        if (built.layers.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("No layers could be drawn")
                .setMessage(
                    "The folder opened, but none of its shapefiles could be read.\n\n" +
                        "Check that Android has granted access to the folder, and that the " +
                        "shapefiles have their .shx and .dbf beside the .shp.",
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun buildLayers(): LayerBuild {
        val dir = mapDir ?: return LayerBuild(emptyList(), emptyList(), null)
        val files = dir.listFiles().filter { it.endsWith(".shp", true) }
        val specs = loadSpecs()
        val match = LayerStyle.match(files, specs)
        val skipped = mutableListOf<String>()
        val out = mutableListOf<MapCanvasView.MapLayer>()
        var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE
        var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
        var have = false

        // Later entries draw on top, so reverse the sheet order.
        for (entry in match.ordered.reversed()) {
            val f = entry.file
            val spec = entry.spec
            val bytes = runCatching { dir.readHeader(f, 100) }.getOrNull() ?: continue
            val info = runCatching { ShpFormat.shpHeader(bytes) }.getOrNull() ?: continue
            val layerCrs = Utm.classify(prjFor(dir, f))
            if (layerCrs.kind == Utm.Crs.OTHER) { skipped.add(f.substringBeforeLast('.')); continue }
            // the project's working zone follows the first layer that declares one,
            // matching "default coordinate system = first layer" in settings_1.ods
            // the project's zone is decided by majority across the switched-on layers, so
            // a single stray layer cannot choose it; this per-layer value is only a
            // fallback for a project the scan could not read
            if (workingZone == null) workingZone = decideZone(dir).zone
            val kind = LayerStyle.kindFor(info?.shapeType)
            val layer = MapCanvasView.MapLayer(
                name = f.substringBeforeLast('.'),
                color = spec?.colorRaw?.let { LayerStyle.borderOf(it) }
                    ?: org.forestnav.app.map.LayerStyle.defaultColorFor(f.substringBeforeLast('.')),
                widthPx = 3f,
                kind = kind,
                editable = false,
            )
            layer.file = f
            layer.crs = layerCrs.kind
            layer.crsZone = layerCrs.utmZone
            // emergency layers draw over the block polygons, whatever the sheet says
            layer.alwaysOnTop = org.forestnav.app.map.ScaleLayerPolicy.isEmergency(f)
            applySpec(layer, spec, kind)
            out.add(layer)
            val b = info?.let { doubleArrayOf(it.xmin, it.ymin, it.xmax, it.ymax) }
            // only same-zone geometry may seed the overall extent; a foreign zone's
            // numbers are in its own frame and would wreck the fit
            if (b != null && b.all { it.isFinite() } &&
                layerCrs.kind == Utm.Crs.UTM_METERS &&
                (layerCrs.utmZone == 0 || layerCrs.utmZone == workingZone())
            ) {
                bx0 = minOf(bx0, b[0]); by0 = minOf(by0, b[1])
                bx1 = maxOf(bx1, b[2]); by1 = maxOf(by1, b[3])
                have = true
            }
        }
        android.util.Log.i(
            "NavDiag", "buildLayers: files=${files.size} built=${out.size} skipped=$skipped haveBbox=$have"
        )
        return LayerBuild(out, skipped, if (have) doubleArrayOf(bx0, by0, bx1, by1) else null)
    }

    private fun loadSpecs(): List<LayerStyle.LayerSpec> =
        mapDir?.let { loadSpecs(it) } ?: emptyList()

    /** The layer list saved in this project's own file. */
    private fun loadSpecs(dir: SafMapDir): List<LayerStyle.LayerSpec> {
        val text = runCatching {
            dir.readFile("mirshp.json")?.toString(Charsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        return runCatching { LayerStyle.parseProject(text) }.getOrElse { emptyList() }
    }

    private fun prjFor(dir: SafMapDir, shpName: String): String? {
        val stem = shpName.substringBeforeLast('.')
        val prj = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) &&
                (it.endsWith(".prj", true) || it.endsWith(".PRJ", true))
        } ?: return null
        return runCatching { org.forestnav.app.map.LayerImport.decodePrj(dir.readFile(prj)) }
            .getOrNull()
    }

    private fun applySpec(
        layer: MapCanvasView.MapLayer,
        spec: LayerStyle.LayerSpec?,
        kind: MapCanvasView.MapLayer.Kind,
    ) {
        // Visibility comes from the folder's mirshp.json, edited in Layer settings.
        layer.baseVisible = spec?.visible ?: org.forestnav.app.map.LayerStyle.defaultVisible(layer.name)
        layer.visible = layer.baseVisible || (navMode == NavMode.AIR &&
            org.forestnav.app.map.LayerStyle.airModeAdds(layer.name))
        when (kind) {
            MapCanvasView.MapLayer.Kind.POINT ->
                layer.pointSymbol = LayerStyle.pointSymbolOf(spec?.symbolRaw.orEmpty())
            MapCanvasView.MapLayer.Kind.LINE ->
                layer.lineSymbol = LayerStyle.lineSymbolOf(spec?.symbolRaw.orEmpty())
            MapCanvasView.MapLayer.Kind.POLYGON ->
                // A polygon layer with no chosen symbol is drawn as an outline. A 50% fill of
                // every polygon layer covered the project area in one colour.
                layer.polySymbol = if (spec?.symbolRaw.isNullOrBlank()) MapCanvasView.PolySymbol.NOFILL
                else LayerStyle.polySymbolOf(spec.symbolRaw)
        }
        layer.symbolSizeDp = spec?.sizeDp ?: 2f
        layer.labelField = spec?.labelField
        layer.labelSizeSp = spec?.labelSizeSp ?: 7f
        layer.borderColor = spec?.colorRaw?.let { LayerStyle.borderOf(it) } ?: layer.color
    }

    /**
     * Background viewport load, carried over from Forest Point Collector: only
     * layers intersecting the current window are parsed, a seq guard discards
     * stale results after a zoom or Close_MAP, and the working set is loaded
     * smallest-first so navigation detail appears before bulk layers.
     */
    private fun loadViewport(initial: Boolean) {
        // a panned view needs fresh imagery; the first one is covered by startMap
        if (!initial) refreshRaster()
        val dir = mapDir ?: return
        if (!mapStarted) return
        val snapshot = mapView.layers.filter { it.visible }
        if (snapshot.isEmpty()) return
        val seq = ++loadSeq
        if (initial) {
            val tn = System.currentTimeMillis()
            loadSavedNetworks(dir)
            android.util.Log.i("NavDiag", "loadSavedNetworks took ${System.currentTimeMillis() - tn}ms")
            initialLoadSeq = seq
            initialLoadTotal = snapshot.size
            initialLoadDone = 0
        }
        val (we, hn) = mapView.windowMeters()
        val x0 = mapView.centerE - we / 2 - 10.0
        val x1 = mapView.centerE + we / 2 + 10.0
        val y0 = mapView.centerN - hn / 2 - 10.0
        val y1 = mapView.centerN + hn / 2 + 10.0

        // Only load what is worth seeing at this scale: at 1:400,000 a few hundred
        // metres of track is a fraction of a pixel, and drawing all nineteen layers
        // just makes a smear while costing the time to read them.
        val scale = mapView.scaleDenominator
        val onAir = navMode == NavMode.AIR
        val ordered = snapshot
            .filter { org.forestnav.app.map.ScaleLayerPolicy.loadsOrEmergency(it.file, scale, onAir) }
            // emergency layers first, and among the rest the cheapest to draw first
            .sortedWith(compareByDescending<MapCanvasView.MapLayer> {
                org.forestnav.app.map.ScaleLayerPolicy.isEmergency(it.file)
            }.thenBy { it.shapes.size })
        for (layer in snapshot) {
            if (!org.forestnav.app.map.ScaleLayerPolicy.loadsOrEmergency(layer.file, scale, onAir)) {
                mapView.layers.firstOrNull { it.name == layer.name }?.shapes = emptyList()
            }
        }
        if (ordered.isEmpty()) return
        if (initial) {
            initialLoadTotal = ordered.size
            initialLoadDone = 0
        }
        android.util.Log.i(
            "NavDiag",
            "loadViewport initial=$initial layers=${snapshot.size} win=($x0,$y0,$x1,$y1) centre=(${mapView.centerE},${mapView.centerN}) scale=${mapView.scaleDenominator}"
        )
        for (layer in ordered) {
            shapeLoader.execute {
                if (!mapStarted || seq != loadSeq) return@execute
                val t0 = System.currentTimeMillis()
                try {
                    val bytes = dir.readFile(layer.file) ?: return@execute
                    val tRead = System.currentTimeMillis()
                    val foreignZone = layer.crs == Utm.Crs.UTM_METERS && layer.crsZone != workingZone()
                    val geo = layer.crs == Utm.Crs.WGS84_DEGREES ||
                        layer.crs == Utm.Crs.ALBERS || foreignZone
                    val raw = try {
                        if (geo) ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
                        else ShpFormat.readShp(bytes, x0, y0, x1, y1)
                    } catch (e: Exception) { emptyList() }
                    val shapes = if (geo) {
                        val conv = projectShapes(raw, layer.crs, layer.file, layer.crsZone)
                        conv.filterNot { it.xmax < x0 || it.xmin > x1 || it.ymax < y0 || it.ymin > y1 }
                    } else raw
                    val labelled = if (layer.labelField != null) {
                        val labels = fieldLabels(dir, layer.file, layer.labelField!!)
                        if (labels.isEmpty()) shapes
                        else shapes.map { it.copy(label = labels.getOrNull(it.recordIndex).orEmpty()) }
                    } else shapes
                    android.util.Log.i(
                        "NavDiag",
                        "parsed ${layer.file}: ${raw.size} -> ${shapes.size} shapes (crs=${layer.crs}) " +
                            "readMs=${tRead - t0} totalMs=${System.currentTimeMillis() - t0} " +
                            "bytes=${bytes.size}",
                    )
                    runOnUiThread {
                        if (!mapStarted || seq != loadSeq) return@runOnUiThread
                        mapView.layers.firstOrNull { it.name == layer.name }?.shapes = labelled
                        mapView.invalidate()
                        if (seq == initialLoadSeq) {
                            initialLoadDone++
                            mapView.loadProgress =
                                "Loading… $initialLoadDone/$initialLoadTotal"
                            if (initialLoadDone >= initialLoadTotal) {
                                initialLoadSeq = -1L
                                mapView.loadProgress = ""
                            }
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    android.util.Log.e("Nav", "OOM loading ${layer.file}", e)
                } catch (e: Exception) {
                    android.util.Log.e("Nav", "failed ${layer.file}", e)
                }
            }
        }
    }

    /** Geographic / conic layers are converted to working-zone metres. */
    /**
     * [s] with reprojected [parts] **and a matching bounding box**.
     *
     * The box has to be recomputed: a geographic shape's bbox is in degrees, so copying
     * the parts while leaving the box behind leaves a shape whose parts are metres and
     * whose extent is -125 degrees. The viewport filter then compares degrees against
     * metres, rejects every reprojected shape, and the layer silently renders empty -
     * which is why points.shp read 7 records and kept 0.
     */
    private fun withParts(s: ShpFormat.Shape, parts: List<List<DoubleArray>>): ShpFormat.Shape {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (part in parts) for (c in part) {
            if (c.size < 2) continue
            if (c[0] < x0) x0 = c[0]
            if (c[0] > x1) x1 = c[0]
            if (c[1] < y0) y0 = c[1]
            if (c[1] > y1) y1 = c[1]
        }
        if (x0 > x1 || y0 > y1) return s.copy(parts = parts)
        return s.copy(parts = parts, xmin = x0, ymin = y0, xmax = x1, ymax = y1)
    }

    /**
     * The zone this project is worked in, and why. One decision, used both by the map and
     * by Verify layers, so the number being converted with and the number reported can
     * never disagree.
     */
    private fun decideZone(dir: SafMapDir): org.gcap.core.nav.WorkingZone.Decision {
        val files = runCatching { dir.listFiles().filter { it.endsWith(".shp", true) } }
            .getOrElse { emptyList() }
        val scan = projectScan ?: runCatching { scanProject(dir) }.getOrNull()

        // The backbone first. Field work navigates on access, so if access says a zone,
        // that is the project - and it cannot be overturned by one stray layer elsewhere
        // in the folder, which is what a majority vote allows.
        val accessZone = files.firstOrNull { it.substringBeforeLast('.').equals("access", true) }
            ?.let { runCatching { Utm.classify(prjFor(dir, it)) }.getOrNull() }
            ?.takeIf { it.kind == Utm.Crs.UTM_METERS && it.utmZone in 1..60 }
            ?.utmZone

        return org.gcap.core.nav.WorkingZone.decide(
            accessZone = accessZone,
            declaredZone = scan?.workingZone?.takeIf { scan.projectProjected },
            geographicZone = geographicZoneOf(dir, files),
            assumedZone = Utm.ASSUMED_ZONE,
        )
    }

    /**
     * The UTM zone implied by where the geographic layers actually sit, from the
     * longitudes in their shape headers. 0 when no layer is in degrees.
     *
     * Read from the 100-byte header only, so it costs one small read per layer and
     * needs no geometry.
     */
    private fun geographicZoneOf(dir: SafMapDir, files: List<String>): Int {
        var sum = 0.0
        var n = 0
        for (f in files) {
            val head = runCatching { dir.readHeader(f, 100) }.getOrNull() ?: continue
            val info = runCatching { ShpFormat.shpHeader(head) }.getOrNull() ?: continue
            if (!info.xmin.isFinite() || !info.xmax.isFinite()) continue
            if (Math.abs(info.xmin) > 180.0 || Math.abs(info.xmax) > 180.0) continue
            if (Utm.classify(prjFor(dir, f)).kind != Utm.Crs.WGS84_DEGREES) continue
            sum += (info.xmin + info.xmax) / 2.0
            n++
        }
        return if (n == 0) 0 else Utm.meridianZone(sum / n)
    }

    private fun projectShapes(
        shapes: List<ShpFormat.Shape>,
        crs: Utm.Crs,
        file: String,
        layerZone: Int = Utm.ASSUMED_ZONE,
    ): List<ShpFormat.Shape> {
        val zone = workingZone()
        // Already in the working zone: nothing to do. A layer from another zone has to
        // be moved, or its eastings are interpreted as if they were local and it lands
        // hundreds of kilometres out.
        if (crs == Utm.Crs.UTM_METERS && layerZone == zone) return shapes
        if (crs == Utm.Crs.UTM_METERS) {
            return shapes.mapNotNull { s ->
                val parts = s.parts.map { part ->
                    part.mapNotNull { c ->
                        val (lat, lon) = Utm.utmToLatLon(c[0], c[1], layerZone)
                        val (e, n) = Utm.wgs84ToUtmZone(lat, lon, zone)
                        doubleArrayOf(e, n)
                    }
                }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) null else withParts(s, parts)
            }
        }
        return shapes.mapNotNull { s ->
            val parts = s.parts.map { part ->
                part.mapNotNull { c ->
                    if (crs == Utm.Crs.WGS84_DEGREES) {
                        val (e, n) = Utm.wgs84ToUtmZone(c[1], c[0], zone)
                        doubleArrayOf(e, n)
                    } else {
                        val prj = mapDir?.let { prjFor(it, file) }
                        val params = prj?.let { Albers.params(it) }
                        if (params == null) null
                        else {
                            val (ae, an) = Albers.toWgs84(c[0], c[1], params)
                            val (e2, n2) = Utm.wgs84ToUtmZone(ae, an, zone)
                            doubleArrayOf(e2, n2)
                        }
                    }
                }.filter { it.isNotEmpty() }
            }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) null
            else withParts(s, parts)
        }
    }

    private fun fieldLabels(dir: SafMapDir, shpName: String, field: String): List<String> {
        val stem = shpName.substringBeforeLast('.')
        val dbfName = dir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
        } ?: return emptyList()
        val bytes = runCatching { dir.readFile(dbfName) }.getOrNull() ?: return emptyList()
        return runCatching {
            val fields = ShpFormat.dbfHeader(bytes).fields
            val idx = fields.indexOfFirst { it.name.equals(field, ignoreCase = true) }
            if (idx < 0) emptyList()
            else ShpFormat.readDbfRows(bytes).map { it.values.getOrNull(idx)?.trim().orEmpty() }
        }.getOrElse { emptyList() }
    }

    // ---- waypoint ----------------------------------------------------------

    private fun onMapTap(e: Double, n: Double) {
        if (org.forestnav.app.map.EditMode.tapCutsLine(cutArmed)) {
            onCutTap(e, n)
            return
        }
        if (awaitingViaTap) {
            awaitingViaTap = false
            wpE = e
            wpN = n
            hasWaypoint = true
            startNavigation()
            return
        }
        if (addArmed) { promptPointName(e, n); return }
        if (!mapStarted) return
        // Prefer a tapped feature; fall back to the raw coordinate.
        val hit = mapView.layers.asReversed().firstOrNull { layer ->
            layer.visible && layer.shapes.any { s ->
                e >= s.xmin - 5 && e <= s.xmax + 5 && n >= s.ymin - 5 && n <= s.ymax + 5
            }
        }
        wpE = e
        wpN = n
        hasWaypoint = true
        wpLabel = hit?.name ?: "Pinned"
        navPlan.setDestination(wpLabel!!, e, n,
            org.gcap.core.nav.NavigationPlan.Destination.Origin.WAYPOINT)
        lastWaypointDest = navPlan.destination
        mapView.setP1P2P3(listOf(Triple(wpLabel!!, wpE, wpN)))
        Toast.makeText(this, "Waypoint: $wpLabel", Toast.LENGTH_SHORT).show()
        updateOverlays()
    }

    /**
     * A long press on the map: "take me to this spot".
     *
     * Asks rather than assuming, because a hold is easy to make by accident — a crew
     * steadying a phone on a dashboard can hold a finger down without meaning to. The
     * spot is shown so the answer is about a place the crew can see, not a coordinate
     * they cannot check.
     */
    private fun onMapLongPress(e: Double, n: Double) {
        if (cutArmed) {
            toast("Cut mode: tap the line to cut it")
            return
        }
        if (!mapStarted) return
        if (addArmed) { promptPointName(e, n); return }
        wpE = e
        wpN = n
        hasWaypoint = true
        wpLabel = "Here"
        navPlan.setDestination("Here", e, n,
            org.gcap.core.nav.NavigationPlan.Destination.Origin.WAYPOINT)
        lastWaypointDest = navPlan.destination
        mapView.setP1P2P3(listOf(Triple("Here", e, n)))
        updateOverlays()
        android.app.AlertDialog.Builder(this)
            .setTitle("Navigate here?")
            .setMessage("Hold was on a spot ${wpE.toInt()} E, ${wpN.toInt()} N. Start navigating to it?")
            .setPositiveButton("Navigate") { _, _ -> startNavigation() }
            .setNegativeButton("Cancel") { _, _ -> clearWaypoint() }
            .show()
    }

    private fun clearWaypoint() {
        wpLabel = null
        hasWaypoint = false
        mapView.setP1P2P3(emptyList())
        updateOverlays()
    }

    // ---- overlays ----------------------------------------------------------

    private fun updateOverlays() {
        if (!mapStarted) {
            mapView.overlayLeft = emptyList()
            mapView.overlayRight = emptyList()
            mapView.invalidate()
            return
        }
        val zone = workingZone()
        val loc = latestLoc
        val e = mapView.centerE
        val n = mapView.centerN
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Longitude" to String.format("%.5f", Utm.utmToLatLon(e, n, zone).second)
        rows += "Latitude" to String.format("%.5f", Utm.utmToLatLon(e, n, zone).first)
        rows += "UTM Zone" to "${zone}N"
        rows += "Easting" to String.format("%.3f", e)
        rows += "Northing" to String.format("%.3f", n)
        rows += "Scale" to "1:${mapView.scaleDenominator}"
        rows += "Map up" to "${orientation.reason.name.lowercase()} ${mapView.bearingDeg.toInt()}\u00b0"
        if (loc != null) rows += "Speed" to "%.0f km/h".format(headingEst.lastSpeedMps * 3.6)
        if (loc != null && loc.hasAccuracy()) rows += "Precision" to "${loc.accuracy.toInt()} m"
        rows += "Heading" to if (loc != null && loc.hasBearing()) "${loc.bearing.toInt()}°" else "-"
        mapView.overlayLeft = rows

        val right = mutableListOf<Pair<String, String>>()
        val loc2 = latestLoc
        if (wpLabel != null && loc2 != null) {
            // latestLoc is already latitude/longitude in degrees: it must not be put
            // through the inverse projection, which expects metres and produced a
            // distance of about 5000 km. The waypoint is (easting, northing).
            val d = haversineM(loc2.latitude, loc2.longitude, wpE, wpN)
            val bearing = bearingDeg(loc2.latitude, loc2.longitude, wpE, wpN)
            right += "Waypoint" to wpLabel!!
            right += "Distance" to if (d < 1000) "%.0f m".format(d) else "%.2f km".format(d / 1000)
            right += "Bearing" to "${bearing.toInt()}°"
        }
        if (navigating && navRoutePoints.size >= 4) {
            right += "On route" to if (!navRemainingM.isNaN()) {
                if (navRemainingM < 1000) {
                    "%.0f m left".format(navRemainingM)
                } else {
                    "%.2f km left".format(navRemainingM / 1000)
                }
            } else {
                // refused rather than shown: the route and the fix are in different
                // coordinate systems, which is a CRS to fix, not a distance to read
                "route and position disagree"
            }
        }
        if (networkReport.isNotEmpty()) right += networkReport
        mapView.overlayRight = right
        mapView.requestDraw()
        // Writing the view on every GPS fix cost memory and blocked on a busy screen.
        // It only matters when the user next opens the map, so save it occasionally.
        val now = System.currentTimeMillis()
        if (now - lastViewSavedAtMs > VIEW_SAVE_INTERVAL_MS) {
            lastViewSavedAtMs = now
            app.saveView(mapView.centerE, mapView.centerN, mapView.scaleDenominator)
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, e2: Double, n2: Double): Double {
        val (lat2, lon2) = Utm.utmToLatLon(e2, n2, workingZone())
        return Geodesy.distanceM(lat1, lon1, lat2, lon2)
    }

    private fun bearingDeg(lat1: Double, lon1: Double, e2: Double, n2: Double): Double {
        val (lat2, lon2) = Utm.utmToLatLon(e2, n2, workingZone())
        return Geodesy.bearingDeg(lat1, lon1, lat2, lon2)
    }

    /** Bearing from a projected position to the active waypoint, or null if unset. */
    /**
 * Bearing from here to whatever is being navigated to, or null when there is nothing to
 * navigate to. The navigation target wins over a plain waypoint, because that is the one
 * the crew is actually heading for.
 */
private fun navigationTargetBearing(e: Double, n: Double): Double? {
        val te = when {
            navigating && (navTargetE != 0.0 || navTargetN != 0.0) -> navTargetE to navTargetN
            wpLabel != null -> wpE to wpN
            else -> return null
        }
        if (e == 0.0 && n == 0.0) return null
        val (lat, lon) = Utm.utmToLatLon(e, n, workingZone())
        val (tlat, tlon) = Utm.utmToLatLon(te.first, te.second, workingZone())
        return Geodesy.bearingDeg(lat, lon, tlat, tlon)
    }

private fun waypointBearingFrom(e: Double, n: Double): Double? {
        if (wpLabel == null) return null
        val (lat, lon) = Utm.utmToLatLon(e, n, workingZone())
        val (wlat, wlon) = Utm.utmToLatLon(wpE, wpN, workingZone())
        return Geodesy.bearingDeg(lat, lon, wlat, wlon)
    }

    override fun onResume() {
        super.onResume()
        // The compass only runs while the map is on screen: it is a sensor, and a
        // background one drains the battery for nothing.
        if (compass == null) compass = org.forestnav.app.map.CompassHeading(this)
        compass?.start()
    }

    override fun onPause() {
        compass?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locListener?.let { runCatching { lm.removeUpdates(it) } }
        locListener = null
        shapeLoader.shutdownNow()
    }
}
