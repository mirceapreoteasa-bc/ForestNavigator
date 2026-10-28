package org.gcap.core.raster

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Builds small TIFFs with known pixel values, so the decoder can be checked against
 * an answer rather than against whatever it happens to produce.
 */
class GeoTiffTest {

    private fun load(name: String): ByteArray =
        GeoTiffTest::class.java.getResourceAsStream("/raster/$name")
            ?.readBytes() ?: error("missing test raster $name")

    /** w*h*3 RGB bytes where pixel (x,y) is (x*16, y*16, 128). */
    private fun expected(x: Int, y: Int): Int =
        (0xFF shl 24) or ((x * 16).coerceIn(0, 255) shl 16) or
            ((y * 16).coerceIn(0, 255) shl 8) or 128

    @Test
    fun readsHeaderAndPlacesTheRaster() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_4x4.tif")))
        assertNotNull(g)
        assertEquals(4, g!!.width)
        assertEquals(4, g.height)
        assertEquals(3, g.samplesPerPixel)
        assertTrue(g.georeferenced)
        assertNull(g.unsupported())
        // 1 m pixels, top-left pixel centred at 10E 20N
        assertArrayEquals(doubleArrayOf(9.5, 16.5, 13.5, 20.5), g.worldBounds()!!, 1e-9)
    }

    @Test
    fun decodesPixelsThroughToTheScreen() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_4x4.tif")))!!
        val dst = IntArray(16)
        assertTrue(g.decodeWindow(g.worldBounds()!!, 4, 4, dst))
        for (y in 0 until 4) for (x in 0 until 4) {
            assertEquals(expected(x, y), dst[y * 4 + x], "pixel $x,$y")
        }
    }

    @Test
    fun averagesWhenTheRasterIsFinerThanTheScreen() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_8x8.tif")))!!
        val dst = IntArray(4)
        assertTrue(g.decodeWindow(g.worldBounds()!!, 2, 2, dst))
        // red varies with x only: mean of 0,16,32,48 is 24, of 64,80,96,112 is 88.
        // green varies with y: mean of 0,16,32,48 is 24, of 64,80,96,112 is 88.
        assertEquals(24, (dst[0] shr 16) and 0xFF, "top-left red")
        assertEquals(88, (dst[1] shr 16) and 0xFF, "top-right red")
        assertEquals(24, (dst[2] shr 16) and 0xFF, "bottom-left red")
        assertEquals(88, (dst[3] shr 16) and 0xFF, "bottom-right red")
        assertEquals(24, (dst[0] shr 8) and 0xFF, "top-left green")
        assertEquals(88, (dst[2] shr 8) and 0xFF, "bottom-left green")
    }

    @Test
    fun deflateCompressedRasterDecodesIdentically() {
        val plain = GeoTiff.open(ArrayByteSource(load("rgb_8x8.tif")))!!
        val packed = GeoTiff.open(ArrayByteSource(load("rgb_8x8_deflate.tif")))!!
        assertEquals(8, packed.compression)
        val a = IntArray(64); val b = IntArray(64)
        assertTrue(plain.decodeWindow(plain.worldBounds()!!, 8, 8, a))
        assertTrue(packed.decodeWindow(packed.worldBounds()!!, 8, 8, b))
        assertArrayEquals(a, b, "deflate must decode to the same pixels as uncompressed")
    }

    @Test
    fun multipleRowsPerStripDecode() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_8x8_rps4.tif")))!!
        val dst = IntArray(64)
        assertTrue(g.decodeWindow(g.worldBounds()!!, 8, 8, dst))
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(expected(x, y), dst[y * 8 + x], "pixel $x,$y")
        }
    }

    @Test
    fun aWindowAwayFromTheOriginStillDecodes() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_8x8.tif")))!!
        val b = g.worldBounds()!!
        val dst = IntArray(16)
        // the south-east 4x4 corner
        assertTrue(g.decodeWindow(doubleArrayOf(b[0] + 4, b[1], b[2], b[1] + 4), 4, 4, dst))
        for (y in 0 until 4) for (x in 0 until 4) {
            assertEquals(expected(x + 4, y + 4), dst[y * 4 + x], "pixel ${x + 4},${y + 4}")
        }
    }

    @Test
    fun aWindowOffTheRasterDecodesNothing() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_4x4.tif")))!!
        assertFalse(g.decodeWindow(doubleArrayOf(1000.0, 1000.0, 1010.0, 1010.0), 4, 4, IntArray(16)))
    }

    @Test
    fun jpegWithoutADecoderIsNamedRatherThanFailingSilently() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_4x4_jpeg.tif")))!!
        assertEquals(7, g.compression)
        val why = g.unsupported()
        assertNotNull(why, "a JPEG raster with no decoder must say why it cannot be drawn")
        assertTrue(why!!.reason.contains("no JPEG decoder"), "should name the cause: ${why.reason}")
        assertFalse(g.decodeWindow(g.worldBounds()!!, 4, 4, IntArray(16)))
    }

    @Test
    fun anUngeoreferencedRasterIsNamedRatherThanMisplaced() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_4x4_nogeo.tif")))!!
        assertFalse(g.georeferenced)
        assertNotNull(g.unsupported())
        assertTrue(g.unsupported()!!.reason.contains("georeferencing"))
    }

    @Test
    fun aNonTiffIsRejected() {
        assertNull(GeoTiff.open(ArrayByteSource(ByteArray(64) { 0x41 })))
    }

    @Test
    fun tiledRasterIsRecognised() {
        val g = GeoTiff.open(ArrayByteSource(load("rgb_8x8_tiled.tif")))
        assertNotNull(g)
        assertTrue(g!!.tiled, "should be reported as tiled, not striped")
        assertNull(g.unsupported())
        assertEquals(8, g.width)
        assertEquals(8, g.height)
    }

    @Test
    fun tiledRasterDecodesIdenticallyToStriped() {
        val striped = GeoTiff.open(ArrayByteSource(load("rgb_8x8.tif")))!!
        val tiled = GeoTiff.open(ArrayByteSource(load("rgb_8x8_tiled.tif")))!!
        val a = IntArray(64); val b = IntArray(64)
        assertTrue(striped.decodeWindow(striped.worldBounds()!!, 8, 8, a))
        assertTrue(tiled.decodeWindow(tiled.worldBounds()!!, 8, 8, b))
        assertArrayEquals(a, b, "tiling must not change the picture")
    }

    @Test
    fun deflateTiledRasterDecodes() {
        val tiled = GeoTiff.open(ArrayByteSource(load("rgb_8x8_tiled_deflate.tif")))!!
        val dst = IntArray(64)
        assertTrue(tiled.decodeWindow(tiled.worldBounds()!!, 8, 8, dst))
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(expected(x, y), dst[y * 8 + x], "pixel $x,$y")
        }
    }

    @Test
    fun tilesThatDoNotDivideTheImageStillDecode() {
        // 8x8 with 3x5 tiles: the right and bottom tiles are partial, which is where
        // a decoder that assumes full tiles reads past the edge and returns wrong ground
        val g = GeoTiff.open(ArrayByteSource(load("rgb_8x8_tiled_odd.tif")))!!
        val dst = IntArray(64)
        assertTrue(g.decodeWindow(g.worldBounds()!!, 8, 8, dst))
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(expected(x, y), dst[y * 8 + x], "pixel $x,$y")
        }
    }

    @Test
    fun aStripedRasterIsNotReportedAsTiled() {
        assertFalse(GeoTiff.open(ArrayByteSource(load("rgb_8x8.tif")))!!.tiled)
    }
}
