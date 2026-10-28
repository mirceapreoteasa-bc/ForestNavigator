package org.forestnav.app.map

import android.graphics.BitmapFactory
import org.gcap.core.raster.JpegDecoder
import org.gcap.core.raster.JpegImage

/** JPEG-in-TIFF strips, decoded by the phone's own JPEG decoder. */
class AndroidJpegDecoder : JpegDecoder {
    override fun decode(jpeg: ByteArray, sample: Int): JpegImage? {
        // inSampleSize makes the codec skip work: a 1/8 decode is far cheaper than a full one
        val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceIn(1, 8) }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        try {
            val w = bmp.width
            val h = bmp.height
            val argb = IntArray(w * h)
            bmp.getPixels(argb, 0, w, 0, 0, w, h)
            val rgb = IntArray(w * h * 3)
            for (i in argb.indices) {
                val p = argb[i]
                rgb[i * 3] = (p shr 16) and 0xFF
                rgb[i * 3 + 1] = (p shr 8) and 0xFF
                rgb[i * 3 + 2] = p and 0xFF
            }
            return JpegImage(w, h, rgb)
        } finally {
            bmp.recycle()
        }
    }
}
