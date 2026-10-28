package org.forestnav.app.map

import org.gcap.core.LineStore
import org.gcap.core.PointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The end-to-end track save: recorder output to a real tracks.shp on disk. */
class TrackWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun forNav(): FileMapDir = FileMapDir(tmp.newFolder("FOR_NAV"))

    private fun recorder(n: Int): TrackRecorder {
        val r = TrackRecorder(intervalSec = 1, minDistanceM = 1.0, maxJumpM = 1000.0)
        r.start()
        for (i in 0 until n) r.addFix(500_000.0 + i * 50.0, 4_500_000.0, i * 2000L)
        return r
    }

    @Test
    fun aTrackInADegreesLayerIsStoredInDegrees() {
        val d = forNav()
        File(tmp.root, "FOR_NAV").mkdirs()
        File(tmp.root, "FOR_NAV/tracks.prj").writeText(
            "GEOGCS[\"GCS_WGS_1984\",UNIT[\"Degree\",0.0174532925199433]]")
        // the same two steps the activity performs: convert to the layer's own CRS, then save
        val r = recorder(4)
        val prj = "GEOGCS[\"GCS_WGS_1984\",UNIT[\"Degree\",0.0174532925199433]]"
        val pts = r.points.map { LayerCrs.toLayerCoords(it[0], it[1], Utm.ASSUMED_ZONE, prj) }
        val out = LineStore.append(emptyMap(), pts, "T", "2026-10-07", "UTM")
        for ((ext, bytes) in out) File(tmp.root, "FOR_NAV/tracks.$ext").writeBytes(bytes)
        val part = LineStore.existingParts(mapOf("shp" to d.readFile("tracks.shp")!!)).single()
        assertTrue("stored as degrees, got $part",
            part.all { Math.abs(it[0]) < 180 && Math.abs(it[1]) < 90 })
    }

    @Test
    fun aRecordedTrackBecomesOnePolyline() {
        val d = forNav()
        val r = recorder(5)
        val out = LineStore.append(emptyMap(), r.points, "2026-10-07 1432", "2026-10-07", "UTM-TEXT")
        for ((ext, bytes) in out) File(tmp.root, "FOR_NAV/tracks.$ext").writeBytes(bytes)

        val parts = LineStore.existingParts(
            mapOf("shp" to d.readFile("tracks.shp")!!, "dbf" to d.readFile("tracks.dbf")!!))
        assertEquals(1, parts.size)
        assertEquals(5, parts[0].size)
    }

    @Test
    fun theSuppliedTemplateKeepsItsDeclaredDegrees() {
        val d = forNav()
        // tracks.shp as it ships: empty, and declaring degrees while the project is UTM
        File(tmp.root, "FOR_NAV/tracks.prj").writeText(
            "GEOGCS[\"GCS_WGS_1984\",UNIT[\"Degree\",0.0174532925199433]]")
        File(tmp.root, "FOR_NAV/ground_call.prj").writeText("PROJCS[\"NAD_1983_UTM_Zone_10N\"]")

        val r = recorder(3)
        val crs = "PROJCS[\"NAD_1983_UTM_Zone_10N\"]"
        val out = LineStore.append(mapOf("prj" to File(tmp.root, "FOR_NAV/tracks.prj").readBytes()),
            r.points, "T1", "2026-10-07", crs)
        for ((ext, bytes) in out) File(tmp.root, "FOR_NAV/tracks.$ext").writeBytes(bytes)

        // the .prj is left exactly as the user keeps it
        assertEquals(
            "GEOGCS[\"GCS_WGS_1984\",UNIT[\"Degree\",0.0174532925199433]]",
            File(tmp.root, "FOR_NAV/tracks.prj").readText(),
        )
        assertTrue("NAME should now exist",
            PointStore.existingFields(d.readFile("tracks.dbf")!!).any { it.first == "NAME" })
        assertEquals(1, LineStore.count(mapOf("shp" to d.readFile("tracks.shp")!!)))
    }

    @Test
    fun twoTracksAccumulateInTheSameLayer() {
        val d = forNav()
        var l = LineStore.append(emptyMap(), recorder(4).points, "T1", "2026-10-07", "UTM")
        l = LineStore.append(l, recorder(6).points, "T2", "2026-10-07", "UTM")
        for ((ext, bytes) in l) File(tmp.root, "FOR_NAV/tracks.$ext").writeBytes(bytes)

        assertEquals(2, LineStore.count(mapOf("shp" to d.readFile("tracks.shp")!!)))
        assertEquals(listOf("T1", "T2"), LineStore.names(mapOf("dbf" to d.readFile("tracks.dbf")!!)))
    }

    @Test
    fun theGpxIsWgs84WhileTheShapefileKeepsProjectCoordinates() {
        val r = recorder(4)
        val gpx = String(r.gpx("T1"))
        val lat = Regex("""lat="([-\d.]+)"""").find(gpx)!!.groupValues[1].toDouble()
        val lon = Regex("""lon="([-\d.]+)"""").find(gpx)!!.groupValues[1].toDouble()

        // gpx in degrees
        assertTrue(lon in -126.0..-120.0)
        // shapefile in metres
        assertEquals(500_000.0, r.points.first()[0], 1e-6)
    }

    @Test
    fun aTrackTooShortToBeALineIsNotWritten() {
        val r = TrackRecorder(intervalSec = 1)
        r.start()
        r.addFix(500_000.0, 4_500_000.0, 0)
        assertEquals(1, r.count)
        var refused = false
        try { LineStore.append(emptyMap(), r.points, "T", "D", "UTM") } catch (e: IllegalArgumentException) { refused = true }
        assertTrue("a single point must not be saved as a line", refused)
    }
}
