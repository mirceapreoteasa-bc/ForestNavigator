package org.gcap.core.raster

/** A decoded JPEG: RGB samples, interleaved and row-major, width*height*3 values. */
class JpegImage(val width: Int, val height: Int, val rgb: IntArray)

/**
 * Decodes one complete JPEG stream. The core is plain JVM so it can be tested without a
 * phone; the app supplies an implementation backed by the platform decoder.
 */
fun interface JpegDecoder {
    /**
     * Null when the stream cannot be decoded. [sample] is 1, 2, 4 or 8: when above 1 the
     * caller only needs a reduced image, and the decoder may return one of that size
     * (the caller stretches it back).
     */
    fun decode(jpeg: ByteArray, sample: Int): JpegImage?
}
