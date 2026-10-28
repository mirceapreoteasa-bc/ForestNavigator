package org.gcap.core.raster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

/**
 * A real JPEG-in-TIFF orthophoto, decoded through the strip and table handling, and
 * compared with a reference decode made by an independent tool (Pillow). Skipped when
 * the survey folder is not on this machine.
 */
class RealJpegRasterTest {

    private val tiff = File("/home/mircea/ForestNavigator/FOR_NAV/google5m.tif")
    private val reference = "/raster/google5m_ref_64.png"

    /**
     * Stands in for the phone's decoder on the JVM.
     */
    private val jvmJpeg = JpegDecoder { bytes, _ ->
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@JpegDecoder null
        val w = img.width
        val h = img.height
        val rgb = IntArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val i = (y * w + x) * 3
            rgb[i] = (p shr 16) and 0xFF
            rgb[i + 1] = (p shr 8) and 0xFF
            rgb[i + 2] = p and 0xFF
        }
        JpegImage(w, h, rgb)
    }

    @Test
    fun realJpegStripsMatchTheReferenceDecode() {
        assumeTrue(tiff.exists(), "survey folder not present")
        val raf = RandomAccessFile(tiff, "r")
        try {
            val src = object : ByteSource {
                override fun size(): Long = raf.length()
                override fun read(offset: Long, length: Int): ByteArray {
                    raf.seek(offset)
                    val b = ByteArray(length)
                    raf.readFully(b)
                    return b
                }
            }
            val opened = GeoTiff.open(src, jvmJpeg)
            assertNotNull(opened)
            val g = opened!!
            assertNull(g.unsupported(), "a JPEG raster with a decoder must be drawable")

            // 64x64 pixels at column 8000, row 6000, expressed as a world window
            val x0 = 8000
            val y0 = 6000
            val n = 64
            val left = g.originE + (x0 - 0.5) * g.pixelScaleX
            val top = g.originN - (y0 - 0.5) * g.pixelScaleY
            val window = doubleArrayOf(
                left, top - n * g.pixelScaleY, left + n * g.pixelScaleX, top,
            )
            val out = IntArray(n * n)
            assertTrue(g.decodeWindow(window, n, n, out))

            val ref = ImageIO.read(GeoTiffTestResources.stream(reference))
            // The colour conversion of this JVM stand-in is not Pillow's, so exact colours
            // are checked on the phone instead. What this test must prove is that the strip
            // bytes, the merged tables and the pixel placement are right: the brightness
            // structure of the decoded window has to follow the reference image exactly.
            val got = DoubleArray(n * n)
            val want = DoubleArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                val p = out[y * n + x]
                got[y * n + x] = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)).toDouble()
                val q = ref.getRGB(x, y)
                want[y * n + x] = (((q shr 16) and 0xFF) + ((q shr 8) and 0xFF) + (q and 0xFF)).toDouble()
            }
            val r = correlation(got, want)
            println("google5m 64x64 brightness correlation with reference: $r")
            assertTrue(r > 0.9, "decoded structure does not follow the reference (r=$r)")
            assertEquals(255, (out[0] ushr 24) and 0xFF, "JPEG rasters are opaque")
        } finally {
            raf.close()
        }
    }
}

private fun correlation(a: DoubleArray, b: DoubleArray): Double {
    val ma = a.average()
    val mb = b.average()
    var num = 0.0; var da = 0.0; var db = 0.0
    for (i in a.indices) {
        val x = a[i] - ma; val y = b[i] - mb
        num += x * y; da += x * x; db += y * y
    }
    return num / Math.sqrt(da * db)
}

private object GeoTiffTestResources {
    fun stream(path: String) = RealJpegRasterTest::class.java.getResourceAsStream(path)
        ?: error("missing test resource $path")
}
