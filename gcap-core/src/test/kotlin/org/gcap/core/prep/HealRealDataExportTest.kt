package org.gcap.core.prep

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Writes healed copies of a real survey layer so the result can be looked at in QGIS.
 *
 * A count cannot answer the only question that matters here - did the tolerance fuse two
 * parallel tracks that should have stayed apart - so this exports the geometry for a human
 * to judge, at a generous tolerance as well as a conservative one.
 *
 * The original layer is never touched. Everything lands in [EXPORT_DIR] as a suffixed copy,
 * which is the same shape the app's Prepare layers action will produce.
 */
class HealRealDataExportTest {

    private val realDir = File(System.getProperty("user.home"), "ForestNavigator/FOR_NAV")
    private val exportDir = File(System.getProperty("user.home"), "ForestNavigator/healing_export")

    /** The four sidecars of a real layer, so the export can keep its attributes. */
    private fun sidecars(stem: String): Map<String, ByteArray> {
        val shp = File(realDir, "$stem.shp")
        assumeTrue(shp.exists(), "real project not present")
        return mapOf(
            "shp" to shp.readBytes(),
            "shx" to File(realDir, "$stem.shx").readBytes(),
            "dbf" to File(realDir, "$stem.dbf").readBytes(),
            "prj" to (File(realDir, "$stem.prj").takeIf { it.exists() }?.readBytes() ?: ByteArray(0)),
        )
    }

    private fun linesFrom(existing: Map<String, ByteArray>): List<LineHealing.Line> {
        val shapes = ShpFormat.readShp(
            existing.getValue("shp"), -1e18, -1e18, 1e18, 1e18,
        )
        return shapes.map { LineHealing.Line(it.parts, it.recordIndex) }
    }

    /**
     * Write [lines] out as a full sidecar set, keeping the source .dbf schema and the
     * source .prj. Reusing the real field names matters: a layer exported with invented
     * field names reads back in QGIS but no longer matches anything else in the project.
     */
    private fun export(name: String, lines: List<LineHealing.Line>, existing: Map<String, ByteArray>) {
        exportDir.mkdirs()
        val parts = lines.map { l -> l.parts.flatMap { it } }
        File(exportDir, "$name.shp").writeBytes(ShpFormat.buildPolylineShp(parts))
        File(exportDir, "$name.shx").writeBytes(ShpFormat.buildPolylineShx(parts))

        val dbf = existing["dbf"]
        if (dbf != null) {
            val header = ShpFormat.dbfHeader(dbf)
            assumeTrue(header != null, "source .dbf unreadable")
            val fields = header!!.fields.map { f -> f.name to f.len }
            val srcRows = ShpFormat.readDbfRows(dbf)
            // healing does not change the feature count or their order, so the source rows
            // travel across unchanged; a cut adds a row of blanks at the end
            val rows = ArrayList<List<String>>(lines.size)
            for (i in lines.indices) {
                rows.add(srcRows.getOrNull(i)?.values ?: List(fields.size) { "" })
            }
            File(exportDir, "$name.dbf").writeBytes(ShpFormat.buildDbfFields(fields, rows))
        }
        val prj = existing["prj"]
        if (prj != null && prj.isNotEmpty()) File(exportDir, "$name.prj").writeBytes(prj)
        // copy the .cpg if the source had one, so QGIS reads the text encoding the same
        sourceCpg()?.let { File(exportDir, "$name.cpg").writeBytes(it) }
        println("HEAL wrote $name: ${lines.size} features")
    }

    private var stem = "access"

    private fun sourceCpg(): ByteArray? {
        val f = File(realDir, "$stem.cpg")
        return if (f.exists()) f.readBytes() else null
    }

    @Test
    fun exportAccessAtConservativeAndWideTolerance() {
        stem = "access"
        val existing = sidecars("access")
        val lines = linesFrom(existing)
        println("HEAL source access: ${lines.size} features")

        export("access_original", lines, existing)

        for (tol in listOf(2.0, 7.0, 15.0)) {
            val plan = LineHealing.plan(lines, tol)
            val healed = LineHealing.heal(lines, tol)
            println(
                "HEAL @${tol.toInt()}m: ${plan.junctionCandidates} junctions, length change " +
                    "%.1f m, components ${plan.componentsBefore} -> ${plan.componentsAfter}"
                        .format(plan.lengthChangeM),
            )
            export("access_healed_${tol.toInt()}m", healed, existing)
            assertEquals(lines.size, healed.size, "healing must not change the feature count")
            assertTrue(plan.healsDisconnect, "healing should join something at ${tol} m")
        }
        println("HEAL inspect in QGIS: $exportDir")
    }

    @Test
    fun exportOnlyTheFeaturesThatTheTwoTolerancesDisagreeAbout() {
        // 1,668 of 5,177 features move differently at 2 m and 7 m. Those are the ones a
        // tolerance can ruin, so write only them: the eye should not have to hunt through
        // 5,000 lines looking for the problem.
        stem = "access"
        val existing = sidecars("access")
        val lines = linesFrom(existing)
        val narrow = LineHealing.heal(lines, 2.0)
        val wide = LineHealing.heal(lines, 7.0)

        val picked = mutableListOf<LineHealing.Line>()
        for (i in lines.indices) {
            if (narrow[i].parts.toString() != wide[i].parts.toString()) picked.add(wide[i])
        }
        println("HEAL features moved differently at 2 m vs 7 m: ${picked.size}")
        assertTrue(picked.size > 10, "expected a meaningful set to inspect")
        export("access_differs_2m_vs_7m", picked, existing)
    }

    @Test
    fun exportACutOnALongAccessLineSoTheGapCanBeSeen() {
        stem = "access"
        val existing = sidecars("access")
        val lines = linesFrom(existing)

        fun lengthOf(l: LineHealing.Line) = l.parts.sumOf { p ->
            (0 until p.size - 1).sumOf { k ->
                Math.hypot(p[k + 1][0] - p[k][0], p[k + 1][1] - p[k][1])
            }
        }
        val target = lines.indices.maxByOrNull { lengthOf(lines[it]) }
        assumeTrue(target != null, "no features")
        val cutAt = target!!
        println(
            "HEAL cutting feature $cutAt, length %.1f m, mid " +
                "(${lines[cutAt].parts[0][lines[cutAt].parts[0].size / 2][0]}, " +
                "${lines[cutAt].parts[0][lines[cutAt].parts[0].size / 2][1]})"
                    .format(lengthOf(lines[cutAt])),
        )

        val cut = LineHealing.cutAtFractions(lines, cutAt, listOf(0.5), 30.0)
        assertEquals(lines.size + 1, cut.size, "a cut makes two features from one")
        val afterHeal = LineHealing.heal(cut, 7.0)
        assertEquals(lines.size + 1, afterHeal.size, "a 30 m cut must survive a 7 m heal")

        // just the neighbourhood of the cut, so it is visible at map scale
        val mid = cut[cutAt].parts[0][0]
        val near = cut.indices.filter { i ->
            val c = cut[i].parts[0]
            (0 until c.size).any { k ->
                Math.hypot(c[k][0] - mid[0], c[k][1] - mid[1]) < 300.0
            }
        }
        println("HEAL features within 300 m of the cut: ${near.size}")
        export("access_cut30m_context", near.map { cut[it] }, existing)
        export("access_cut30m", cut, existing)
    }
}
