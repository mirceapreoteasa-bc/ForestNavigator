package org.gcap.core.raster

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A JPEG decoder that records what it was given and returns a known image. */
private class FakeJpeg(
    private val w: Int,
    private val h: Int,
    private val pixel: (Int, Int) -> Triple<Int, Int, Int>,
) : JpegDecoder {
    var seen: ByteArray? = null

    override fun decode(jpeg: ByteArray, sample: Int): JpegImage? {
        seen = jpeg
        val rgb = IntArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val (r, g, b) = pixel(x, y)
            val i = (y * w + x) * 3
            rgb[i] = r; rgb[i + 1] = g; rgb[i + 2] = b
        }
        return JpegImage(w, h, rgb)
    }
}

/**
 * Builds a little-endian striped TIFF with one strip, compression 7, and optional
 * JPEGTables, so each case controls exactly the bytes the reader sees.
 */
private fun buildJpegTiff(
    w: Int,
    h: Int,
    spp: Int,
    strip: ByteArray,
    tables: ByteArray?,
    compression: Int = 7,
    originE: Double = 1000.0,
    originN: Double = 2000.0,
): ByteArray {
    val body = ByteArrayOutputStream()
    fun pos(): Int = 8 + body.size()
    fun le(vararg v: Int, bytes: Int) {
        val b = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when (bytes) { 2 -> b.putShort(v[0].toShort()); 4 -> b.putInt(v[0]) }
        body.write(b.array())
    }

    val tablesOff = pos()
    tables?.let { body.write(it) }
    val stripOff = pos()
    body.write(strip)
    if (body.size() % 2 == 1) body.write(0)
    val scaleOff = pos()
    for (d in doubleArrayOf(1.0, 1.0, 0.0)) body.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(d).array())
    val tieOff = pos()
    for (d in doubleArrayOf(0.0, 0.0, 0.0, originE, originN, 0.0)) body.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(d).array())
    val ifdOff = pos()

    // tag, type, count, value-or-offset (SHORT=3, LONG=4, UNDEFINED=7, DOUBLE=12)
    val entries = mutableListOf(
        intArrayOf(256, 4, 1, w),
        intArrayOf(257, 4, 1, h),
        intArrayOf(259, 3, 1, compression),
        intArrayOf(262, 3, 1, 2),
        intArrayOf(273, 4, 1, stripOff),
        intArrayOf(277, 3, 1, spp),
        intArrayOf(278, 4, 1, h),
        intArrayOf(279, 4, 1, strip.size),
        intArrayOf(33550, 12, 3, scaleOff),
        intArrayOf(33922, 12, 6, tieOff),
    )
    if (tables != null) entries += intArrayOf(347, 7, tables.size, tablesOff)
    entries.sortBy { it[0] }

    val ifd = ByteArrayOutputStream()
    ifd.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(entries.size.toShort()).array())
    for (e in entries) {
        val f = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        f.putShort(e[0].toShort()); f.putShort(e[1].toShort()); f.putInt(e[2]); f.putInt(e[3])
        ifd.write(f.array())
    }
    ifd.write(ByteArray(4))

    val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    header.put('I'.code.toByte()); header.put('I'.code.toByte()); header.putShort(42); header.putInt(ifdOff)
    return header.array() + body.toByteArray() + ifd.toByteArray()
}

class GeoTiffJpegTest {

    private val ff = 0xFF.toByte()
    private val d8 = 0xD8.toByte()
    private val d9 = 0xD9.toByte()

    @Test
    fun jpegStripsDecodeThroughTheSuppliedDecoder() {
        val strip = byteArrayOf(ff, d8, 0x11, 0x22, ff, d9)
        val tiff = buildJpegTiff(4, 4, 3, strip, tables = null)
        val fake = FakeJpeg(4, 4) { x, y -> Triple(x * 40, y * 40, 100) }
        val g = GeoTiff.open(ArrayByteSource(tiff), fake)
        assertNotNull(g)
        assertEquals(null, g!!.unsupported(), "a JPEG raster with a decoder is drawable")
        assertTrue(g.decodable)

        val out = IntArray(16)
        assertTrue(g.decodeWindow(g.worldBounds()!!, 4, 4, out))
        for (y in 0 until 4) for (x in 0 until 4) {
            val want = (0xFF shl 24) or ((x * 40) shl 16) or ((y * 40) shl 8) or 100
            assertEquals(want, out[y * 4 + x], "pixel ($x,$y)")
        }
    }

    @Test
    fun sharedJpegTablesAreSplicedIntoTheStrip() {
        // JPEGTables carries its own SOI/EOI; the strip omits its tables entirely.
        val tables = byteArrayOf(ff, d8, 0xAA.toByte(), 0xBB.toByte(), ff, d9)
        val strip = byteArrayOf(ff, d8, 0x11, 0x22, ff, d9)
        val fake = FakeJpeg(4, 4) { _, _ -> Triple(0, 0, 0) }
        val g = GeoTiff.open(ArrayByteSource(buildJpegTiff(4, 4, 3, strip, tables)), fake)!!
        g.decodeWindow(g.worldBounds()!!, 4, 4, IntArray(16))

        // SOI + tables without their SOI/EOI + strip without its SOI, EOI kept from the strip
        assertArrayEquals(
            byteArrayOf(ff, d8, 0xAA.toByte(), 0xBB.toByte(), 0x11, 0x22, ff, d9),
            fake.seen,
        )
    }

    @Test
    fun fourSampleJpegRastersGetOpaqueAlpha() {
        val strip = byteArrayOf(ff, d8, ff, d9)
        val fake = FakeJpeg(4, 4) { _, _ -> Triple(10, 20, 30) }
        val g = GeoTiff.open(ArrayByteSource(buildJpegTiff(4, 4, 4, strip, null)), fake)!!
        val out = IntArray(16 * 4)
        assertTrue(g.decodeWindow(g.worldBounds()!!, 4, 4, out))
        // JPEG has no alpha; the RGBA reader must report every pixel opaque
        val argb = (0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30
        assertEquals(argb, out[0] and 0xFFFFFFFF.toInt())
    }

    @Test
    fun withoutADecoderTheRasterSaysSoRatherThanFailingSilently() {
        val strip = byteArrayOf(ff, d8, ff, d9)
        val g = GeoTiff.open(ArrayByteSource(buildJpegTiff(4, 4, 3, strip, null)))!!
        assertFalse(g.decodable)
        assertTrue(g.unsupported()!!.reason.contains("no JPEG decoder"))
    }

    @Test
    fun jpeg2000IsNamedAsUnsupported() {
        val g = GeoTiff.open(ArrayByteSource(buildJpegTiff(4, 4, 3, byteArrayOf(0, 0), null, compression = 34712)))!!
        assertFalse(g.decodable)
        assertTrue(g.unsupported()!!.reason.contains("JPEG 2000"))
    }
}
