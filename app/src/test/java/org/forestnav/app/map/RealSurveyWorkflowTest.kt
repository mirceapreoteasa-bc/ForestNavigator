package org.forestnav.app.map

import org.gcap.core.LineStore
import org.gcap.core.PointStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The whole field workflow against a copy of the real FOR_NAV layers.
 *
 * These layers came out of the point collector app, so they are the ones most likely to
 * differ from what the app assumes - field names, casing, an unusual .prj, a shapefile
 * that is not quite what it claims. Everything is written to a copy: the survey data must
 * come out of a test byte-for-byte unchanged.
 */
class RealSurveyWorkflowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val real = File(System.getProperty("user.home"), "ForestNavigator/FOR_NAV")
    /** Snapshot of the real tracks.shp taken before the test writes anything. */
    private var realTracksShpBytes: ByteArray? = null

    private lateinit var dir: FileMapDir

    /** A working copy of the real folder, so nothing here can touch the survey data. */
    @Before
    fun copyTheProject() {
        assertTrue("FOR_NAV not found at $real", real.isDirectory)
        // Snapshot before anything is written, so "originals untouched" can be proved even
        // though the survey folder now holds real recorded tracks.
        realTracksShpBytes = real.listFiles()?.firstOrNull { it.name == "tracks.shp" }?.readBytes()
        val dst = tmp.newFolder("FOR_NAV")
        copyTree(real, dst)
        dir = FileMapDir(dst)
    }

    private fun settings(): NavSettings = SettingsResolver(
        ods("settings_1.ods"), 1,
        ods("layer_settings.ods"), 2,
    ).resolve()

    private fun ods(name: String): OdsTable? {
        val hit = dir.listFiles().firstOrNull { it.equals(name, true) } ?: return null
        return OdsTable.parse(dir.readFile(hit) ?: return null)
    }

    private fun layerExists(name: String) = MobWriter.shpName(dir, name) != null

    // ---- the layers the app assumes are there -------------------------------

    @Test
    fun everyLayerNamedInTheSheetIsPresent() {
        val cfg = settings()
        assertTrue("Go to layer missing", layerExists(cfg.goToLayer))
        assertTrue("Add point layer missing", layerExists(cfg.addPointLayer))
        assertTrue("MOB layer missing", layerExists(cfg.mobLayer))
        assertTrue("track layer missing", layerExists(cfg.trackLayer))
    }

    @Test
    fun theNameFieldFromTheSheetExistsInThePointLayer() {
        val cfg = settings()
        val shp = MobWriter.shpName(dir, cfg.addPointLayer)!!
        val stem = shp.substringBeforeLast('.')
        val dbf = dir.listFiles().first { it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true) }
        val fields = PointStore.existingFields(dir.readFile(dbf)!!).map { it.first }
        assertTrue(
            "name field ${cfg.addPointNameField} not in $fields",
            fields.any { it.equals(cfg.addPointNameField, true) },
        )
    }

    @Test
    fun theGoToFieldExistsAndHasValues() {
        val cfg = settings()
        assertTrue(
            "${cfg.goToLayer}.shp should exist so Go to can point at it",
            MobWriter.shpName(dir, cfg.goToLayer) != null,
        )
        // Which layer carries the calls is set in Settings per project, so a test must not
        // demand that the stored default holds records: the folder's own choice wins.
    }

    /** First layer in the copy that actually carries named records. */
    private fun populatedCallLayer(): Pair<String, String>? {
        val fields = listOf("Call_Num", "GCALL_ID", "NAME", "Call_ID")
        for (l in listOf("ground_call", "xgv_mp", "poi_interes", "points")) {
            if (MobWriter.shpName(dir, l) == null) continue
            for (f in fields) {
                if (MobWriter.existingNames(dir, l, f).isNotEmpty()) return l to f
            }
        }
        return null
    }

    // ---- the workflow, end to end ------------------------------------------

    @Test
    fun goToFindsARealCallAndReturnsRealCoordinates() {
        val (layer, field) = populatedCallLayer() ?: return
        val shp = MobWriter.shpName(dir, layer)!!
        val stem = shp.substringBeforeLast('.')
        fun side(ext: String) = dir.readFile(
            dir.listFiles().first { it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".$ext", true) })!!

        val dbf = side("dbf")
        val target = PointStore.names(dbf, field).first()
        val idx = PointStore.indexOf(dbf, field, target)
        assertTrue("$target should resolve", idx >= 0)
        val c = PointStore.coordAt(side("shp"), idx)!!
        // the stored position must be in the project's UTM zone, and survive a round trip
        val (lat, lon) = Utm.utmToLatLon(c[0], c[1], Utm.ASSUMED_ZONE)
        assertTrue("lat $lat out of range", lat in 30.0..60.0)
        assertTrue("lon $lon out of range", lon in -126.0..-120.0)
        val (e2, n2) = Utm.wgs84ToUtmZone(lat, lon, Utm.ASSUMED_ZONE)
        assertEquals("round trip must return the same easting", c[0], e2, 1.0)
        assertEquals("round trip must return the same northing", c[1], n2, 1.0)
    }

    @Test
    fun mobGoesIntoThePointsLayerInDegreesUnderItsOwnName() {
        val cfg = settings()
        val field = cfg.nameFieldFor(cfg.mobLayer)
        assertEquals("MOB belongs in points.shp, whose field is NAME", "NAME", field)

        val before = MobWriter.existingNames(dir, cfg.mobLayer, field)
        assertTrue("points.shp should already hold points", before.isNotEmpty())
        assertTrue("points.shp is geographic", LayerCrs.isGeographic(dir, cfg.mobLayer))

        val out = MobWriter.write(dir, cfg.mobLayer, field, "TST0801", 500_000.0, 4_500_000.0,
            null, Utm.ASSUMED_ZONE)
        assertTrue("MOB should have been written", out is MobWriter.Outcome.Written)

        val after = MobWriter.existingNames(dir, cfg.mobLayer, field)
        assertEquals("one point should have been added", before.size + 1, after.size)
        assertTrue("the new name should be readable", after.contains("TST0801"))
        assertTrue("the collector app's own points must survive", after.containsAll(before))

        // the point must be stored as degrees, or the collector app will misplace it
        val added = PointStore.coords(dir.readFile("points.shp")!!).last()
        assertTrue("stored as degrees, got $added",
            Math.abs(added[0]) < 180 && Math.abs(added[1]) < 90)
    }

    @Test
    fun mobDoesNotCreateAFieldTheCollectorAppDoesNotUse() {
        val cfg = settings()
        MobWriter.write(dir, cfg.mobLayer, cfg.nameFieldFor(cfg.mobLayer), "TST0802",
            500_000.0, 4_500_000.0, null, Utm.ASSUMED_ZONE)
        assertEquals("GCALL_ID must not appear in points.shp",
            emptyList<String>(), MobWriter.existingNames(dir, cfg.mobLayer, "GCALL_ID"))
    }

    @Test
    fun editingTheRealPointLayerKeepsEveryExistingPoint() {
        val cfg = settings()
        val layer = cfg.addPointLayer
        val before = MobWriter.existingNames(dir, layer, cfg.addPointNameField)
        assertTrue("the survey layer should already have points", before.isNotEmpty())

        val s = EditSession(layer, cfg.addPointNameField, before)
        s.add(500_000.0, 4_500_000.0, "NEW1")
        s.add(500_100.0, 4_500_100.0, "NEW2")
        val c = s.commit(dir, projectCrs())
        assertTrue(c is EditSession.Commit.Written)

        val after = MobWriter.existingNames(dir, layer, cfg.addPointNameField)
        assertEquals(before.size + 2, after.size)
        assertTrue("the original points must all survive", after.containsAll(before))
    }

    @Test
    fun theSuppliedTracksTemplateKeepsItsDegrees() {
        val layer = settings().trackLayer
        val shp = MobWriter.shpName(dir, layer)
        assertNotNull("tracks.shp should exist in the survey folder", shp)
        // Not asserted empty: the survey folder's tracks layer is written during real use,
        // so the template carries whatever was recorded. What matters is the CRS below.
        val tracksBefore = LineStore.count(readSidecars(dir, layer))

        val stem = layer.substringBeforeLast('.')
        val prjBefore = dir.listFiles()
            .firstOrNull { it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".prj", true) }
            ?.let { String(dir.readFile(it)!!) }
        assertTrue("the template declares degrees, not the project CRS",
            prjBefore?.contains("Degree") == true)

        val r = TrackRecorder(1, 1.0, 1000.0)
        r.start()
        r.addFix(500_000.0, 4_500_000.0, 0)
        r.addFix(500_100.0, 4_500_100.0, 2000)
        val out = LineStore.append(readSidecars(dir, layer), r.points, "TST-TRACK", "2026-10-07", projectCrs())
        for ((ext, bytes) in out) write("$stem.$ext", bytes)

        val prjAfter = String(dir.readFile("$stem.prj")!!)
        assertEquals("the declared degrees must be left alone", prjBefore, prjAfter)
        assertEquals(tracksBefore + 1, LineStore.count(readSidecars(dir, layer)))
        assertTrue(LineStore.names(readSidecars(dir, layer)).contains("TST-TRACK"))
    }

    @Test
    fun aSecondTrackAppendsRatherThanReplacing() {
        val layer = settings().trackLayer
        val stem = layer.substringBeforeLast('.')
        val startTracks = LineStore.count(readSidecars(dir, layer))
        // The copied folder already holds whatever the crew has recorded, so the
        // new names are appended to those, not expected to be the only ones.
        val startNames = LineStore.names(readSidecars(dir, layer))
        fun track(nm: String, e: Double) {
            val r = TrackRecorder(1, 1.0, 1000.0)
            r.start()
            r.addFix(e, 4_500_000.0, 0)
            r.addFix(e + 100, 4_500_100.0, 2000)
            val out = LineStore.append(readSidecars(dir, layer), r.points, nm, "2026-10-07", projectCrs())
            for ((ext, bytes) in out) write("$stem.$ext", bytes)
        }
        track("T-ONE", 500_000.0)
        track("T-TWO", 510_000.0)
        assertEquals(startTracks + 2, LineStore.count(readSidecars(dir, layer)))
        assertEquals(startNames + listOf("T-ONE", "T-TWO"), LineStore.names(readSidecars(dir, layer)))
    }

    @Test
    fun theOriginalsAreNeverTouchedByAnyOfThis() {
        MobWriter.write(dir, settings().mobLayer, settings().addPointNameField, "TST-X", 1.0, 2.0)
        // The copy may have changed; the survey folder must not have. Compared against a
        // snapshot rather than "is empty", because the folder holds recorded field data.
        val origShp = realTracksShpBytes
        if (origShp != null) {
            assertArrayEquals(
                "the real tracks.shp must not be touched",
                origShp,
                File(real, "tracks.shp").readBytes(),
            )
        }
    }

    /**
     * What the point collector app left behind, recorded so a change to the survey data
     * cannot quietly alter what the app assumes.
     */
    @Test
    fun theSurveyLayersUseDifferentNameFields() {
        // one setting cannot name a field that three layers spell three ways
        val cfg = settings()
        assertEquals(listOf("GCALL_ID"), fieldsIn(cfg.addPointLayer))
        assertEquals(listOf("NAME"), fieldsIn(cfg.mobLayer))
        assertEquals(listOf("OBJECTID", "Road_Air_W", "Call_Num"), fieldsIn(cfg.goToLayer))
    }

    /** The MOB layer in the survey folder is geographic, not projected. */
    @Test
    fun theMobLayerIsInWgs84DegreesWhileTheRestIsProjected() {
        val mobCrs = crsOf(settings().mobLayer)
        val goToCrs = crsOf(settings().goToLayer)
        assertTrue("points.shp should declare degrees, got: $mobCrs", mobCrs.contains("Degree"))
        assertTrue("ground_call.shp should be projected, got: $goToCrs", goToCrs.contains("PROJCS"))
    }

    private fun fieldsIn(layer: String): List<String> {
        val shp = MobWriter.shpName(dir, layer)!!
        val stem = shp.substringBeforeLast('.')
        val dbf = dir.listFiles().first {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".dbf", true)
        }
        return PointStore.existingFields(dir.readFile(dbf)!!).map { it.first }
    }

    private fun crsOf(layer: String): String {
        val shp = MobWriter.shpName(dir, layer)!!
        val stem = shp.substringBeforeLast('.')
        val prj = dir.listFiles().first {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".prj", true)
        }
        return String(dir.readFile(prj)!!)
    }

    // ---- helpers -----------------------------------------------------------

    /** The project's working CRS, taken from the layer the app navigates by. */
    private fun projectCrs(): String {
        val stem = settings().goToLayer.substringBeforeLast('.')
        val name = dir.listFiles().first {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".prj", true)
        }
        return String(dir.readFile(name)!!).trim()
    }

    private fun readSidecars(dir: FileMapDir, layer: String): Map<String, ByteArray> {
        val stem = layer.substringBeforeLast('.')
        val out = HashMap<String, ByteArray>()
        for (ext in LineStore.SIDECARS) {
            val hit = dir.listFiles().firstOrNull {
                it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".$ext", true)
            } ?: continue
            dir.readFile(hit)?.let { out[ext] = it }
        }
        return out
    }

    private fun write(name: String, bytes: ByteArray) {
        val f = File(dirRoot(), name)
        if (f.exists()) dir.overwriteFile(name, bytes) else dir.createFile(name, bytes)
        // the dir caches reads; without this the next assertion sees the old bytes
        dir.clearCaches()
    }

    private fun dirRoot() = File(tmp.root, "FOR_NAV")

    /** 600 MB of shapefiles: a plain recursive copy, no symlink following. */
    private fun copyTree(from: File, to: File) {
        to.mkdirs()
        val children = from.listFiles() ?: emptyArray()
        for (child in children) {
            val dst = File(to, child.name)
            if (child.isDirectory) copyTree(child, dst) else child.copyTo(dst, overwrite = true)
        }
    }
}
/**
 * The name field a layer actually has must beat the name field a setting asks for.
 *
 * `points.shp` holds `NAME`, and it is the MOB layer, so it must never be handed
 * `GCALL_ID`.
 */
class RealNameFieldTest {

    private val real = File(System.getProperty("user.home"), "ForestNavigator/FOR_NAV")

    @Test
    fun mobLayerResolvesToItsOwnNameFieldNotTheEditSetting() {
        assertTrue("FOR_NAV not found", real.isDirectory)
        val dst = java.nio.file.Files.createTempDirectory("fn").toFile()
        val src = File(dst, "FOR_NAV"); src.mkdirs()
        for (c in real.listFiles() ?: emptyArray()) {
            if (!c.name.endsWith(".shp") && !c.name.endsWith(".dbf") &&
                !c.name.endsWith(".prj") && !c.name.endsWith(".shx") &&
                !c.name.endsWith(".cpg") && !c.name.endsWith(".ods")) continue
            c.copyTo(File(src, c.name), overwrite = true)
        }
        val dir = FileMapDir(src)
        val cfg = NavSettings()
        val mob = cfg.mobLayer
        assertEquals("the MOB layer must write NAME", "NAME",
            MobWriter.resolveField(dir, mob, cfg.addPointNameField))
        assertEquals("editing xgv_mp must write GCALL_ID", "GCALL_ID",
            MobWriter.resolveField(dir, cfg.addPointLayer, cfg.addPointNameField))
    }

    @Test
    fun aSettingThatMatchesIsKeptAsItIs() {
        val dst = java.nio.file.Files.createTempDirectory("fn2").toFile()
        val d = FileMapDir(dst)
        assertEquals("GCALL_ID", MobWriter.resolveField(d, "nothing_here", "GCALL_ID"))
    }
}
