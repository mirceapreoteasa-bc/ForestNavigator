package org.gcap.core.nav

/**
 * Whether the crew is navigating on the ground or in the air.
 *
 * This is the `Navigation_mode` setting. It is deliberately just the two values the
 * sheet asks for, because the whole point of it is that the main page already works for
 * [GROUND] and only two buttons change for [AIR].
 */
enum class NavMode {
    GROUND,
    AIR;

    fun toggled(): NavMode = if (this == GROUND) AIR else GROUND

    /** Name as `settings_1.ods` writes it. */
    fun sheetName(): String = if (this == GROUND) "on_ground" else "on_air"
}

/**
 * One button on the main page.
 *
 * [ground] and [air] are the two labels; most buttons are the same in both modes, and
 * only the network buttons change. Keeping both labels on the button makes the
 * `on_air` behaviour explicit rather than scattered through the UI code.
 */
data class GridButton(
    val id: String,
    /** Cell in `forest_navigator_ODS.ods`, e.g. `C4`. */
    val cell: String,
    val ground: String,
    val air: String = ground,
    val implemented: Boolean = false,
) {
    fun label(mode: NavMode): String = if (mode == NavMode.AIR) air else ground

    /** True when this button means something different in the air. */
    val changesWithMode: Boolean get() = ground != air
}

/**
 * The main page, four across, in the order `forest_navigator_ODS.ods` lays it out.
 *
 * The first six rows are the twenty-four commands of the sheet, named by their cell so
 * the sheet and the app cannot drift apart silently. The last three are the controls
 * added since the sheet was written: map up, auto zoom and network.
 *
 * In `on_air` mode two buttons change meaning: `C4` ROAD becomes **Flight Path**, and
 * `C5` TRAIL becomes **FLIGHT PLAN**, which opens the flight plan rather than choosing a
 * network.
 */
object MainGrid {
    const val COLUMNS = 4

    val buttons: List<GridButton> = listOf(
        GridButton("start_map", "A1", "Start map", implemented = true),
        GridButton("settings", "B1", "Settings", implemented = true),
        GridButton("record_track", "C1", "Record track"),
        GridButton("start_edit", "D1", "Start edit"),

        GridButton("close_map", "A2", "Close map", implemented = true),
        GridButton("layer_settings", "B2", "Layer settings", implemented = true),
        GridButton("stop_track", "C2", "Stop track"),
        GridButton("stop_edit", "D2", "Stop edit"),

        GridButton("exit", "A3", "EXIT"),
        GridButton("navigate", "B3", "Navigate", implemented = true),
        GridButton("direct_line", "C3", "Direct_Line", implemented = true),
        GridButton("save_edits", "D3", "Save edits"),

        GridButton("centre_me", "A4", "Centre me", implemented = true),
        GridButton("stop_navigating", "B4", "Stop_Navigating", implemented = true),
        GridButton("net_c4", "C4", "ROAD", "Flight Path", implemented = true),
        GridButton("waypoint", "D4", "Waypoint", implemented = true),

        GridButton("go_to", "A5", "Go to\u2026"),
        GridButton("net_b5", "B5", "ACCESS", implemented = true),
        // In the air this is not a network at all: it opens the flight plan.
        GridButton("net_c5", "C5", "TRAIL", "FLIGHT PLAN", implemented = true),
        GridButton("add_point", "D5", "Add point"),

        GridButton("pan", "A6", "Pan", implemented = true),
        GridButton("zoom_in", "B6", "Zoom +", implemented = true),
        GridButton("zoom_out", "C6", "Zoom \u2212", implemented = true),
        GridButton("mob", "D6", "MOB"),

        // Map up, Auto zoom and Network moved to the end of Settings: they are used
        // occasionally, and keeping them on the main page crowded the buttons crews reach
        // for while walking.
    )

    /** Row count needed to hold every button at [COLUMNS] across. */
    val rowCount: Int get() = (buttons.size + COLUMNS - 1) / COLUMNS

    fun labels(mode: NavMode): List<String> = buttons.map { it.label(mode) }

    fun byId(id: String): GridButton? = buttons.firstOrNull { it.id == id }

    fun byCell(cell: String): GridButton? = buttons.firstOrNull { it.cell == cell }

    /**
     * Which network [Navigate] follows: ACCESS on the ground, Flight Path in the air.
     *
     * On the ground the crew's normal work is the access network. In the air the
     * equivalent is the flight path, which is a network that can actually be followed -
     * `C5` is the **FLIGHT PLAN** button there, and that opens the plan with a chosen
     * start call rather than being a network to route along.
     */
    fun defaultNetworkId(mode: NavMode): String =
        if (mode == NavMode.AIR) "net_c4" else "net_b5"

    /** The three network buttons, which are the only ones that change with the mode. */
    val networkIds: List<String> = listOf("net_c4", "net_b5", "net_c5")
}