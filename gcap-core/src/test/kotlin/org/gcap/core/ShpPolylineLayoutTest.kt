package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a shapefile the way an external GIS does — straight from the byte layout,
 * not through [ShpFormat]'s own reader — so a writer that is self-consistent but
 * wrong on disk is still caught.
 *
 * This exists because the polyline writer omitted the mandatory Z/M block: its own
 * lengths and offsets agreed with each other, so every in-app test passed, while
 * QGIS read the part and point counts out of the Z field and could not draw or
 * select the features.
 */
class ShpPolylineLayoutTest {

    private fun be(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun le(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun d(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).double

    private fun part(n: Int) = (0 until n).map { doubleArrayOf(100.0 + it, 200.0 + it) }

    @Test
    fun `a polyline record carries the Z and M block the spec requires`() {
        val shp = ShpFormat.buildPolylineShp(listOf(part(18)))
        val shx = ShpFormat.buildPolylineShx(listOf(part(18)))

        // header: declared length must equal the real length
        assertEquals(shp.size.toLong(), be(shp, 24).toLong() * 2, "shp header length")
        assertEquals(shx.size.toLong(), be(shx, 24).toLong() * 2, "shx header length")
        assertEquals(3, le(shp, 32), "file shape type must be PolyLine")

        // index points at the record, and agrees on its length
        val off = be(shx, 100) * 2
        val words = be(shx, 104)
        assertEquals(100, off, "first record must start right after the header")
        assertEquals(1, be(shp, off), "record number")
        assertEquals(words, be(shp, off + 4), "shx and shp must agree on record length")

        // record content, laid out as a GDAL reader walks it
        val c = off + 8
        assertEquals(3, le(shp, c), "record shape type")
        assertEquals(100.0, d(shp, c + 4), "Xmin")
        assertEquals(200.0, d(shp, c + 12), "Ymin")
        assertEquals(117.0, d(shp, c + 20), "Xmax")
        assertEquals(217.0, d(shp, c + 28), "Ymax")
        // Zmin Zmax Mmin Mmax occupy c+36 .. c+68 and are present, not the counts
        for (o in listOf(c + 36, c + 44, c + 52, c + 60)) {
            assertEquals(0.0, d(shp, o), "Z/M block at $o must be written as a double")
        }
        assertEquals(1, le(shp, c + 68), "numParts")
        assertEquals(18, le(shp, c + 72), "numPoints")
        assertEquals(0, le(shp, c + 76), "single part starts at vertex 0")

        // and the vertices start exactly where that says
        val p = c + 80
        assertEquals(100.0, d(shp, p), "first vertex X")
        assertEquals(200.0, d(shp, p + 8), "first vertex Y")
        assertEquals(117.0, d(shp, p + 16 * 17), "last vertex X")
        assertEquals(217.0, d(shp, p + 16 * 17 + 8), "last vertex Y")

        // content length must account for every byte actually written
        assertEquals(80 + 16 * 18, words * 2, "record content length")
    }

    @Test
    fun `every record of a multi-track layer is laid out the same way`() {
        val parts = listOf(part(4), part(9), part(2))
        val shp = ShpFormat.buildPolylineShp(parts)
        val shx = ShpFormat.buildPolylineShx(parts)

        var expectedOffset = 100
        for (i in parts.indices) {
            val off = be(shx, 100 + i * 8) * 2
            assertEquals(expectedOffset, off, "record ${i + 1} offset")
            expectedOffset += 8 + be(shx, 100 + i * 8 + 4) * 2

            val c = off + 8
            assertEquals(3, le(shp, c), "record ${i + 1} type")
            assertEquals(1, le(shp, c + 68), "record ${i + 1} numParts")
            assertEquals(parts[i].size, le(shp, c + 72), "record ${i + 1} numPoints")
        }
        assertEquals(shp.size, expectedOffset, "records must tile the file exactly")
        assertTrue(be(shp, 24) * 2 == shp.size)
    }

    @Test
    fun `the app's own reader still agrees`() {
        val parts = listOf(part(4), part(9))
        val back = ShpFormat.readShp(ShpFormat.buildPolylineShp(parts), 0.0, 0.0, 1000.0, 1000.0)
        assertEquals(2, back.size)
        assertEquals(4, back[0].parts[0].size)
        assertEquals(9, back[1].parts[0].size)
        assertEquals(100.0, back[0].parts[0][0][0])
        assertEquals(108.0, back[1].parts[0][8][0])
    }

    /** A layer recorded before the fix must still open, and must survive being
     *  appended to: the next write rewrites every part in the correct layout. */
    @Test
    fun `a track layer written by the old writer still reads`() {
        val legacy = legacyPolylineBytes(listOf(part(5), part(7)))
        val back = ShpFormat.readShp(legacy, 0.0, 0.0, 1000.0, 1000.0)
        assertEquals(2, back.size, "old records must still be readable")
        assertEquals(5, back[0].parts[0].size)
        assertEquals(7, back[1].parts[0].size)
        assertEquals(100.0, back[0].parts[0][0][0])
        assertEquals(106.0, back[1].parts[0][6][0])
    }

    /** Rebuilds records the way the previous writer did: no Z/M block. */
    private fun legacyPolylineBytes(parts: List<List<DoubleArray>>): ByteArray {
        fun be(v: Int) = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
        )
        fun le(v: Int) = byteArrayOf(
            v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
        )
        fun d(v: Double) = java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(v).array()
        val out = java.io.ByteArrayOutputStream()
        var words = 50
        for (p in parts) words += 4 + (48 + 16 * p.size) / 2
        out.write(be(9994)); out.write(ByteArray(20)); out.write(be(words))
        out.write(le(1000)); out.write(le(3))
        var xmin = Double.MAX_VALUE; var ymin = Double.MAX_VALUE
        var xmax = -Double.MAX_VALUE; var ymax = -Double.MAX_VALUE
        for (p in parts) for (c in p) {
            xmin = minOf(xmin, c[0]); ymin = minOf(ymin, c[1])
            xmax = maxOf(xmax, c[0]); ymax = maxOf(ymax, c[1])
        }
        out.write(d(xmin)); out.write(d(ymin)); out.write(d(xmax)); out.write(d(ymax))
        out.write(d(0.0)); out.write(d(0.0)); out.write(d(0.0)); out.write(d(0.0))
        var rec = 1
        for (p in parts) {
            var a = Double.MAX_VALUE; var b = Double.MAX_VALUE
            var c2 = -Double.MAX_VALUE; var dd = -Double.MAX_VALUE
            for (c in p) {
                a = minOf(a, c[0]); b = minOf(b, c[1]); c2 = maxOf(c2, c[0]); dd = maxOf(dd, c[1])
            }
            out.write(be(rec++))
            out.write(be((48 + 16 * p.size) / 2))
            out.write(le(3))
            out.write(d(a)); out.write(d(b)); out.write(d(c2)); out.write(d(dd))
            out.write(le(1)); out.write(le(p.size)); out.write(le(0))
            for (c in p) { out.write(d(c[0])); out.write(d(c[1])) }
        }
        return out.toByteArray()
    }
}