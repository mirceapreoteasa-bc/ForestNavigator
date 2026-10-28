package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainGridTest {

    @Test
    fun `the sheet's twenty-four commands occupy six rows of four`() {
        val sheet = MainGrid.buttons.filter { !it.cell.startsWith("E") }
        assertEquals(24, sheet.size, "the ODS main page has 24 commands")
        assertEquals(6, sheet.size / MainGrid.COLUMNS)
        for (row in 1..6) {
            val cells = sheet.filter { it.cell.endsWith(row.toString()) }
            assertEquals(4, cells.size, "row $row should hold four buttons")
        }
    }

    @Test
    fun `cells match the sheet`() {
        assertEquals("Start map", MainGrid.byCell("A1")?.ground)
        assertEquals("Settings", MainGrid.byCell("B1")?.ground)
        assertEquals("Navigate", MainGrid.byCell("B3")?.ground)
        assertEquals("Stop_Navigating", MainGrid.byCell("B4")?.ground)
        assertEquals("ROAD", MainGrid.byCell("C4")?.ground)
        assertEquals("Waypoint", MainGrid.byCell("D4")?.ground)
        assertEquals("ACCESS", MainGrid.byCell("B5")?.ground)
        assertEquals("TRAIL", MainGrid.byCell("C5")?.ground)
        assertEquals("MOB", MainGrid.byCell("D6")?.ground)
    }

    @Test
    fun `on_ground is the default layout`() {
        val labels = MainGrid.labels(NavMode.GROUND)
        assertEquals("ROAD", labels[MainGrid.buttons.indexOfFirst { it.cell == "C4" }])
        assertEquals("TRAIL", labels[MainGrid.buttons.indexOfFirst { it.cell == "C5" }])
        assertTrue(
            MainGrid.labels(NavMode.GROUND).none { it == "Flight Path" || it == "FLIGHT PLAN" },
        )
    }

    @Test
    fun `on_air changes exactly the two network buttons`() {
        val ground = MainGrid.buttons.map { it.label(NavMode.GROUND) }
        val air = MainGrid.buttons.map { it.label(NavMode.AIR) }
        val changed = MainGrid.buttons.filter { it.changesWithMode }
        assertEquals(2, changed.size, "only C4 and C5 change with the mode")
        assertEquals(listOf("C4", "C5"), changed.map { it.cell })
        assertEquals(listOf("ROAD", "TRAIL"), changed.map { it.ground })
        assertEquals(listOf("Flight Path", "FLIGHT PLAN"), changed.map { it.air })

        // everything else must be identical, position by position
        for (i in MainGrid.buttons.indices) {
            if (MainGrid.buttons[i].changesWithMode) continue
            assertEquals(ground[i], air[i], "${MainGrid.buttons[i].cell} must not change")
        }
    }

    @Test
    fun `the labels read as specified in the air`() {
        assertEquals("Flight Path", MainGrid.byCell("C4")?.label(NavMode.AIR))
        assertEquals("FLIGHT PLAN", MainGrid.byCell("C5")?.label(NavMode.AIR))
        assertEquals("ACCESS", MainGrid.byCell("B5")?.label(NavMode.AIR), "ACCESS keeps its name")
        assertEquals("Navigate", MainGrid.byCell("B3")?.label(NavMode.AIR))
    }

    @Test
    fun `navigate defaults to access on the ground and the flight path in the air`() {
        assertEquals("net_b5", MainGrid.defaultNetworkId(NavMode.GROUND))
        assertEquals("net_c4", MainGrid.defaultNetworkId(NavMode.AIR))
        assertEquals("ACCESS", MainGrid.byId(MainGrid.defaultNetworkId(NavMode.GROUND))?.label(NavMode.GROUND))
        assertEquals("Flight Path", MainGrid.byId(MainGrid.defaultNetworkId(NavMode.AIR))?.label(NavMode.AIR))
    }

    @Test
    fun `the three network buttons are the ones the sheet names`() {
        assertEquals(listOf("net_c4", "net_b5", "net_c5"), MainGrid.networkIds)
        for (id in MainGrid.networkIds) assertTrue(MainGrid.byId(id) != null, "$id missing")
    }

    @Test
    fun `every button has a unique id and cell`() {
        assertEquals(MainGrid.buttons.size, MainGrid.buttons.map { it.id }.distinct().size, "duplicate id")
        assertEquals(MainGrid.buttons.size, MainGrid.buttons.map { it.cell }.distinct().size, "duplicate cell")
    }

    @Test
    fun `labels line up with buttons`() {
        for (m in listOf(NavMode.GROUND, NavMode.AIR)) {
            assertEquals(MainGrid.buttons.size, MainGrid.labels(m).size)
        }
    }

    @Test
    fun `row count fits the buttons`() {
        // The 24 sheet commands fill six full rows of four. Map up, Auto zoom and Network
        // moved to Settings, so the main page is exactly the sheet's grid again.
        assertEquals(24, MainGrid.buttons.size)
        assertEquals(6, MainGrid.rowCount)
    }
}

class NavigationSessionTest {

    @Test
    fun `navigate starts on access by default`() {
        val s = NavigationSession()
        val st = s.start()
        assertTrue(st.active)
        assertEquals("ACCESS", st.network)
        assertEquals(NavKind.NETWORK, st.kind)
    }

    @Test
    fun `navigate in the air starts on the route`() {
        val s = NavigationSession(NavMode.AIR)
        val st = s.start()
        assertEquals("Flight Path", st.network)
    }

    @Test
    fun `stopping asks for the map to go back to the gps position`() {
        val s = NavigationSession()
        s.start()
        val st = s.stop()
        assertFalse(st.active)
        assertTrue(st.recentreOnGps, "the map should re-centre on the GPS position")
        assertNull(st.target)
        assertNull(st.network)
    }

    @Test
    fun `direct line only applies while navigating`() {
        val s = NavigationSession()
        assertEquals("Start navigating first.", s.directLine().message)
        assertFalse(s.directLine().kind == NavKind.DIRECT_LINE)

        s.start("Ground_1")
        val st = s.directLine()
        assertEquals(NavKind.DIRECT_LINE, st.kind)
        assertEquals("Ground_1", st.target)
    }

    @Test
    fun `the network buttons choose the network before and during navigation`() {
        val s = NavigationSession()
        // chosen first, remembered
        assertTrue(s.chooseNetwork("net_c4").message.contains("ROAD"))
        assertEquals("ROAD", s.start().network)

        // switched mid-flight
        val st = s.chooseNetwork("net_b5")
        assertEquals("ACCESS", st.network)
        assertEquals(NavKind.NETWORK, st.kind, "choosing a network leaves direct line")
    }

    @Test
    fun `in the air the network buttons read as flight path and flight plan`() {
        val s = NavigationSession(NavMode.AIR)
        assertEquals(listOf("Flight Path", "ACCESS", "FLIGHT PLAN"), s.networkLabels())
        assertEquals("Flight Path", s.chooseNetwork("net_c4").network ?: s.activeNetworkLabel())
    }

    @Test
    fun `a non-network button is refused`() {
        val s = NavigationSession()
        assertEquals("That is not a network button.", s.chooseNetwork("navigate").message)
    }

    @Test
    fun `starting twice keeps the crew's place`() {
        val s = NavigationSession()
        s.start()
        val st = s.chooseNetwork("net_c4")
        val again = s.start()
        assertTrue(again.active)
        assertEquals("ROAD", again.network, "the network chosen in between is kept")
        assertTrue(again.message.startsWith("Already navigating"))
    }

    @Test
    fun `stopping when idle says so`() {
        assertEquals("Not navigating.", NavigationSession().stop().message)
    }

    @Test
    fun `a waypoint can be chosen before starting`() {
        val s = NavigationSession()
        val st = s.setTarget("Ground_7")
        assertEquals("Ground_7", st.target)
        assertFalse(st.active)
        val started = s.start()
        assertEquals("Ground_7", started.target)
    }

    @Test
    fun `advancing past the last call says so`() {
        val s = NavigationSession(NavMode.AIR)
        s.start()
        assertEquals("Next: 102A.", s.advanceTo("102A").message)
        val last = s.advanceTo(null)
        assertNull(last.target)
        assertEquals("Last call reached.", last.message)
    }

    @Test
    fun `changing mode stops navigation rather than carrying it over`() {
        val s = NavigationSession()
        s.start("Ground_3")
        val st = s.setMode(NavMode.AIR)
        assertFalse(st.active, "a route is not a road network")
        assertTrue(st.recentreOnGps)
        assertTrue(st.message.contains("on_air"))

        // and the new mode's default applies from scratch
        assertEquals("Flight Path", s.start().network)
    }

    @Test
    fun `setting the same mode changes nothing`() {
        val s = NavigationSession()
        s.start()
        val st = s.setMode(NavMode.GROUND)
        assertTrue(st.active, "re-selecting the same mode must not stop navigation")
    }

    @Test
    fun `switching mode resets a network chosen under the old one`() {
        val s = NavigationSession()
        s.chooseNetwork("net_c4")
        s.setMode(NavMode.AIR)
        assertEquals("Flight Path", s.activeNetworkLabel(), "ROAD does not exist in the air")
    }

    @Test
    fun `following the network again leaves the direct line`() {
        val s = NavigationSession()
        s.start()
        s.directLine()
        val st = s.followNetwork()
        assertEquals(NavKind.NETWORK, st.kind)
        assertEquals("Following ACCESS.", st.message)
    }

    @Test
    fun `the messages say what happened`() {
        val s = NavigationSession()
        s.start("Ground_1")
        assertTrue(s.state.message.contains("ACCESS"))
        assertTrue(s.state.message.contains("Ground_1"))
    }

    @Test
    fun `modes are named as the settings sheet writes them`() {
        assertEquals("on_ground", NavMode.GROUND.sheetName())
        assertEquals("on_air", NavMode.AIR.sheetName())
    }

    @Test
    fun `toggling mode flips between the two`() {
        assertEquals(NavMode.AIR, NavMode.GROUND.toggled())
        assertEquals(NavMode.GROUND, NavMode.AIR.toggled())
    }
}