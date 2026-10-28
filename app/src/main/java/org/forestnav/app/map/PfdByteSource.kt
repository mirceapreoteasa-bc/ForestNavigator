package org.forestnav.app.map

import android.os.ParcelFileDescriptor
import org.gcap.core.raster.ByteSource
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Random access to one SAF file through a single open descriptor.
 *
 * Each SAF open is a cross-process call, and on some phones it takes over a second. A
 * GeoTIFF is read in many small pieces at scattered offsets, so reopening the file for
 * each one made the raster probe run for well over ten seconds on the UI thread. Here
 * the file is opened once and every read is a positional read on that descriptor.
 */
class PfdByteSource(
    private val pfd: ParcelFileDescriptor,
    private val length: Long,
) : ByteSource, Closeable {

    private val channel = FileInputStream(pfd.fileDescriptor).channel

    override fun size(): Long = length

    override fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return if (buf.position() == length) buf.array() else buf.array().copyOf(buf.position())
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }
}
