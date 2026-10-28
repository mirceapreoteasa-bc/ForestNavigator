package org.gcap.core

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the time actually goes when a saved network is read back.
 *
 * The map start on the BV5200 spent 30 s in here for the roads network, while the same
 * .shp parsed in 130 ms as a display layer. Printed rather than asserted: this exists to
 * be read, and a threshold would only encode one machine's speed.
 */
class DbfReadCostProbeTest {

    private fun dir(): File = File("/tmp/opencode/rd")

    @Test
    fun whereTheRoadsNetworkReadTimeGoes() {
        val shpFile = File(dir(), "ROADS_network.shp")
        val dbfFile = File(dir(), "ROADS_network.dbf")
        assume(shpFile.isFile && dbfFile.isFile)

        val shp = shpFile.readBytes()
        val dbf = dbfFile.readBytes()

        repeat(2) { pass ->
            var t = System.currentTimeMillis()
            val shapes = ShpFormat.readShp(shp, -1e18, -1e18, 1e18, 1e18)
            val msShp = System.currentTimeMillis() - t
            val points = shapes.sumOf { s -> s.parts.sumOf { it.size } }

            t = System.currentTimeMillis()
            val rows = ShpFormat.readDbfRows(dbf)
            val msDbf = System.currentTimeMillis() - t

            t = System.currentTimeMillis()
            val rows2 = ShpFormat.readDbfRows(dbf)
            val msDbf2 = System.currentTimeMillis() - t

            t = System.currentTimeMillis()
            val windowed = ShpFormat.readShp(shp, 503000.0, 5451000.0, 504000.0, 5453000.0)
            val msWindow = System.currentTimeMillis() - t

            println(
                "PROBE pass=$pass shp=${shp.size / 1048576}MB shapes=${shapes.size} points=$points " +
                    "readShpAll=${msShp}ms readShpWindow=${msWindow}ms(${windowed.size} shapes) " +
                    "readDbf=${msDbf}ms readDbfAgain=${msDbf2}ms rows=${rows.size}/${rows2.size}",
            )
        }
    }

    private fun assume(cond: Boolean) =
        org.junit.jupiter.api.Assumptions.assumeTrue(cond, "roads network files not pulled")
}