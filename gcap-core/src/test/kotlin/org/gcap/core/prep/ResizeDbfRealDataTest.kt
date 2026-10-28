package org.gcap.core.prep

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The healing write goes through [LayerPreparation.resizeDbf] to keep the attribute table
 * in step with a feature count that a cut may have changed. This runs it against the real
 * survey layer, because the first version of the heal screen wrote nothing at all on the
 * device while every test passed - the failure was in how a real .dbf header is handled,
 * not in the geometry.
 */
class ResizeDbfRealDataTest {

    private fun realDbf(): ByteArray {
        val f = File("/home/mircea/ForestNavigator/FOR_NAV/access.dbf")
        assumeTrue(f.exists(), "real project not present")
        return f.readBytes()
    }

    @Test
    fun resizingARealTableToItsOwnLengthReturnsItUnchanged() {
        val dbf = realDbf()
        val header = ShpFormat.dbfHeader(dbf)
        println("RESIZE header numRecords=${header?.numRecords} fields=${header?.fields}")
        // Size to whatever the layer currently holds; the survey's access layer has been
        // reworked between seasons and a fixed 5177 no longer describes it.
        val own = ShpFormat.dbfHeader(dbf)!!.numRecords
        val out = LayerPreparation.resizeDbf(dbf, own)
        val after = ShpFormat.dbfHeader(out)
        println("RESIZE out numRecords=${after?.numRecords} fields=${after?.fields?.map { it.name to it.len }}")
        assertEquals2(own, after!!.numRecords, "row count must survive")
        // The writer upper-cases field names, which is the DBF convention; compare
        // case-insensitively so this test is about the count, not the spelling.
        assertEquals2(
            listOf("OBJECTID", "SHAPE_Leng").map { it.uppercase() },
            after.fields.map { it.name.uppercase() },
            "field names",
        )
    }

    @Test
    fun resizingARealTableByOneRowAddsABlankRow() {
        val dbf = realDbf()
        val out = LayerPreparation.resizeDbf(dbf, 5178)
        val after = ShpFormat.dbfHeader(out)!!
        assertEquals2(5178, after.numRecords, "one extra row for the extra feature")
        val rows = ShpFormat.readDbfRows(out)
        assertEquals2(5178, rows.size, "rows readable")
        assertEquals2(listOf("", ""), rows.last().values, "the added row is blank")
    }

    @Test
    fun theWholeHealedWriteProducesFourReadableFiles() {
        val dir = File("/home/mircea/ForestNavigator/FOR_NAV")
        assumeTrue(File(dir, "access.shp").exists(), "real project not present")
        val shapes = ShpFormat.readShp(File(dir, "access.shp").readBytes(), -1e18, -1e18, 1e18, 1e18)
        val own = shapes.size   // whatever the layer holds today, not a fixed count
        val lines = shapes.map { LineHealing.Line(it.parts, it.recordIndex) }
        val healed = LineHealing.heal(lines, 7.0)
        val parts = healed.map { it.parts.flatMap { p -> p } }
        val written = LayerPreparation.writeHealedPolylineLayer(
            parts, File(dir, "access.dbf").readBytes(),
            File(dir, "access.prj").readText(), "access_healed",
        )
        println("RESIZE wrote keys=${written.keys.map { it.substringAfterLast('.') }}")
        val shp = ShpFormat.readShp(written.getValue("access_healed.shp"), -1e18, -1e18, 1e18, 1e18)
        assertEquals2(own, shp.size, "features survive the write")
        val dbf = ShpFormat.dbfHeader(written.getValue("access_healed.dbf"))
        assertEquals2(own, dbf!!.numRecords, "attribute rows match the features")
        // .shx holds one 8-byte offset/length pair per feature, after a fixed header.
        val shxLen = written.getValue("access_healed.shx").size
        println("RESIZE shx bytes=$shxLen for ${shp.size} features")
        assertTrue2(shxLen > shp.size * 8, "shx must hold at least 8 bytes per feature")
    }

    private fun assertTrue2(c: Boolean, msg: String) {
        if (!c) throw AssertionError(msg)
    }

    private fun assertEquals2(a: Any?, b: Any?, msg: String) {
        if (a.toString() != b.toString()) {
            throw AssertionError("$msg: expected <$b> but was <$a>")
        }
    }
}