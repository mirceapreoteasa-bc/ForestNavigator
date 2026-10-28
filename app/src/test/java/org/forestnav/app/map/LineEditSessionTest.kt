package org.forestnav.app.map

import org.gcap.core.ShpFormat
import org.gcap.core.prep.LineCutTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Cuts are staged, not written.
 *
 * That is the whole safety argument for starting line editing from Settings and saving it
 * from the main page: nothing on disk changes until one deliberate save, so a mistap costs
 * a tap on Stop edit rather than a redrawn road.
 */
class LineEditSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A folder with one access line plus a legacy-named saved network. */
    private fun folderWithRoad(): FileMapDir {
        val d = tmp.newFolder("FOR_NAV")
        val parts = listOf(
            listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(500.0, 0.0), doubleArrayOf(1000.0, 0.0)),
        )
        File(d, "access.shp").writeBytes(ShpFormat.buildPolylineShp(parts))
        File(d, "access.shx").writeBytes(ShpFormat.buildPolylineShx(parts))
        File(d, "access.dbf").writeBytes(
            ShpFormat.buildDbfFields(
                listOf("OBJECTID" to 10, "SHAPE_LENG" to 19),
                listOf(listOf("1", "1000")),
            ),
        )
        File(d, "access.prj").writeText("PROJCS[\"x\",UNIT[\"Meter\",1.0]]")
        for (e in listOf("shp", "shx", "dbf", "prj")) {
            File(d, "network_access.$e").writeText("stale")
        }
        return FileMapDir(d)
    }

    @Test
    fun stagingWritesNothingUntilCommit() {
        val dir = folderWithRoad()
        val before = dir.readFile("access.shp")!!
        val s = LineEditSession()
        val t = LineCutTarget.find(
            listOf(
                org.gcap.core.prep.LineHealing.Line(
                    listOf(
                        listOf(
                            doubleArrayOf(0.0, 0.0), doubleArrayOf(500.0, 0.0),
                            doubleArrayOf(1000.0, 0.0),
                        ),
                    ),
                ),
            ),
            500.0, 0.0, maxOffsetM = 20.0, gapM = 20.0,
        ).targetOrNull!!

        assertNull(s.stage("access", t, 20.0))
        assertTrue(s.dirty)
        assertEquals(1, s.count)
        assertTrue("staging must not touch the file", before.contentEquals(dir.readFile("access.shp")!!))
        assertTrue("staging must not remove the network", File(tmp.root, "FOR_NAV/network_access.shp").exists())
    }

    @Test
    fun committingCutsTheLineAndLeavesTheNetworkForTheUserToRebuild() {
        val dir = folderWithRoad()
        val s = LineEditSession()
        val t = LineCutTarget.find(
            listOf(
                org.gcap.core.prep.LineHealing.Line(
                    listOf(
                        listOf(
                            doubleArrayOf(0.0, 0.0), doubleArrayOf(500.0, 0.0),
                            doubleArrayOf(1000.0, 0.0),
                        ),
                    ),
                ),
            ),
            500.0, 0.0, maxOffsetM = 20.0, gapM = 20.0,
        ).targetOrNull!!
        s.stage("access", t, 20.0)

        val r = s.commit(dir)
        if (r.written != 1) throw AssertionError("commit said: ${r.messages}")
        assertEquals(0, r.failed)

        val shapes = ShpFormat.readShp(dir.readFile("access.shp")!!, -1e18, -1e18, 1e18, 1e18)
        assertEquals("one line became two", 2, shapes.size)
        val gap = kotlin.math.abs(shapes[0].parts[0].last()[0] - shapes[1].parts[0].first()[0])
        assertEquals(20.0, gap, 1e-6)

        // The saved network is left alone: rebuilding it is the user's step.
        assertTrue(
            "the saved network must be left for the user to rebuild",
            File(tmp.root, "FOR_NAV/network_access.shp").exists(),
        )
        assertTrue(
            "the save message must tell the user to rebuild the network",
            r.messages.single().contains("build the network again"),
        )
        assertNotNull("the dbf must grow to match", dir.readFile("access.dbf"))
        assertEquals(2, ShpFormat.dbfHeader(dir.readFile("access.dbf")!!)!!.numRecords)
    }

    @Test
    fun discardingLeavesEverythingAlone() {
        val dir = folderWithRoad()
        val before = dir.readFile("access.shp")!!
        val s = LineEditSession()
        val t = LineCutTarget.find(
            listOf(
                org.gcap.core.prep.LineHealing.Line(
                    listOf(
                        listOf(
                            doubleArrayOf(0.0, 0.0), doubleArrayOf(500.0, 0.0),
                            doubleArrayOf(1000.0, 0.0),
                        ),
                    ),
                ),
            ),
            500.0, 0.0, maxOffsetM = 20.0, gapM = 20.0,
        ).targetOrNull!!
        s.stage("access", t, 20.0)
        s.discard()

        assertTrue(!s.dirty)
        assertTrue(before.contentEquals(dir.readFile("access.shp")!!))
        assertTrue(File(tmp.root, "FOR_NAV/network_access.shp").exists())
    }

    @Test
    fun theSameSpotCannotBeCutTwice() {
        val s = LineEditSession()
        val t = LineCutTarget.Target(0, 0, 0.5, 500.0, 0.0, 0.1, 1000.0)
        assertNull(s.stage("access", t, 20.0))
        assertNotNull("a second tap on the same spot must be refused", s.stage("access", t, 20.0))
        assertEquals(1, s.count)
    }

    @Test
    fun twoCutsOnOneLineBothLand() {
        val dir = folderWithRoad()
        val s = LineEditSession()
        val mk = { at: Double ->
            LineCutTarget.find(
                listOf(
                    org.gcap.core.prep.LineHealing.Line(
                        listOf(
                            listOf(
                                doubleArrayOf(0.0, 0.0), doubleArrayOf(500.0, 0.0),
                                doubleArrayOf(1000.0, 0.0),
                            ),
                        ),
                    ),
                ),
                at, 0.0, maxOffsetM = 20.0, gapM = 20.0,
            ).targetOrNull!!
        }
        s.stage("access", mk(300.0), 20.0)
        s.stage("access", mk(700.0), 20.0)
        assertEquals(2, s.count)
        assertEquals(2, s.commit(dir).written)

        val shapes = ShpFormat.readShp(dir.readFile("access.shp")!!, -1e18, -1e18, 1e18, 1e18)
        assertEquals("two cuts make three pieces", 3, shapes.size)
    }

    @Test
    fun anEmptySessionSavesNothing() {
        val dir = folderWithRoad()
        val r = LineEditSession().commit(dir)
        assertEquals(0, r.written)
        assertEquals(0, r.attempted)
    }

    @Test
    fun aMissingLayerIsReportedRatherThanThrown() {
        val dir = folderWithRoad()
        val s = LineEditSession()
        s.stage("nosuchlayer", LineCutTarget.Target(0, 0, 0.5, 1.0, 1.0, 0.0, 10.0), 20.0)
        val r = s.commit(dir)
        assertEquals(1, r.attempted)
        assertEquals(0, r.written)
        assertEquals(1, r.failed)
        assertTrue(r.messages.any { it.contains("nosuchlayer") })
    }
}