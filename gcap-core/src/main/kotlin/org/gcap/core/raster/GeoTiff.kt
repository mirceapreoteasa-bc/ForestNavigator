package org.gcap.core.raster

import java.util.zip.Inflater

/**
 * Random access to a file, so a raster can be read in strips rather than whole.
 *
 * Deliberately the smallest thing that works: the orthophoto in a survey folder runs
 * to hundreds of megabytes and only a screenful is ever wanted, so nothing here ever
 * holds the whole file.
 */
interface ByteSource {
    fun size(): Long
    /** Read [length] bytes at [offset], or fewer at end of file. */
    fun read(offset: Long, length: Int): ByteArray
}

/** A [ByteSource] over an already-loaded array, for tests and small files. */
class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override fun size(): Long = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        val from = offset.coerceAtLeast(0).toInt()
        val to = (from.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
        if (from >= to) return ByteArray(0)
        return bytes.copyOfRange(from, to)
    }
}

/** Why a raster could not be drawn, said in words fit for the user. */
class Unsupported(val reason: String)

/**
 * A reader for baseline TIFF and GeoTIFF, enough to draw one as a basemap.
 *
 * Scope is stated rather than hidden:
 *
 *  - Compression **none** (1), **Deflate** (8, 32946) and **LZW** (5) are decoded.
 *  - **JPEG-in-TIFF** (7) is decoded when a [JpegDecoder] is supplied. The shared
 *    JPEGTables are merged into each strip, which is how GDAL writes them.
 *  - Old-style JPEG (6) and JPEG 2000 (34712) are named as unsupported, because a
 *    raster that silently fails to decode looks exactly like a blank map and the
 *    conclusion a crew draws is that the project is wrong, not the format.
 *
 * Reading is by strip. Peak memory is one strip plus the caller's output buffer,
 * whatever the size of the file, which is what makes a 350 MB orthophoto openable
 * on a phone.
 */
class GeoTiff internal constructor(
    private val source: ByteSource,
    val width: Int,
    val height: Int,
    val samplesPerPixel: Int,
    val compression: Int,
    private val blockOffsets: LongArray,
    private val blockByteCounts: LongArray,
    /** Block size in pixels: a strip is full-width, a tile is square-ish. */
    private val blockRows: Int,
    private val blockCols: Int,
    /** True when the raster is internally tiled rather than striped. */
    val tiled: Boolean,
    // then pixel scale and origin
    val pixelScaleX: Double,
    val pixelScaleY: Double,
    val originE: Double,
    val originN: Double,
    val georeferenced: Boolean,
    private val jpeg: JpegDecoder? = null,
    private val jpegTables: ByteArray? = null,
) {

    /** True when the compression scheme is one this reader can decode. */
    val decodable: Boolean
        get() = compression == 1 || compression == 5 || compression == 8 || compression == 32946 ||
            (compression == 7 && jpeg != null)

    /** Null when drawable, otherwise why not. */
    fun unsupported(): Unsupported? = when {
        !georeferenced -> Unsupported(
            "no georeferencing, so it cannot be placed on the map. " +
                "In QGIS: Layer > Properties > Source > Generate Spatial Reference."
        )
        compression == 7 && jpeg == null -> Unsupported(
            "JPEG-compressed TIFF, but this build has no JPEG decoder."
        )
        compression == 6 || compression == 34712 -> Unsupported(
            "TIFF compression $compression (old-style JPEG or JPEG 2000) is not supported. " +
                "Convert it once on a desktop with: gdal_translate -co COMPRESS=DEFLATE in.tif out.tif"
        )
        !decodable -> Unsupported("compression $compression, which this app cannot decode")
        else -> null
    }

    /** World bounds as e0,n0,e1,n1. */
    fun worldBounds(): DoubleArray? {
        if (!georeferenced) return null
        val west = originE - pixelScaleX / 2.0
        val east = originE + (width - 1) * pixelScaleX + pixelScaleX / 2.0
        val south = originN - (height - 1) * pixelScaleY - pixelScaleY / 2.0
        val north = originN + pixelScaleY / 2.0
        return doubleArrayOf(minOf(west, east), minOf(south, north), maxOf(west, east), maxOf(south, north))
    }

    private val blocksAcross: Int =
        if (tiled) (width + blockCols - 1) / blockCols else 1

    /**
     * A few recent blocks, because one screen row of a tiled raster touches every tile
     * across it. One slot would re-read each tile once per row; this holds a tile-row.
     */
    private val blockCache = object : LinkedHashMap<Int, IntArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, IntArray>?): Boolean =
            size > BLOCK_CACHE_ENTRIES
    }

    private fun cachedBlock(key: Int): IntArray? = blockCache[key]

    /** A reduced JPEG strip, kept at its decoded size: 1/8 of a strip is 1/64 of the memory. */
    private class ReducedBlock(val w: Int, val h: Int, val data: IntArray)

    private val reducedCache = object : LinkedHashMap<Int, ReducedBlock>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ReducedBlock>?): Boolean =
            size > BLOCK_CACHE_ENTRIES
    }

    private fun reducedBlock(index: Int, sample: Int): ReducedBlock? {
        val key = index * 4 + sampleShift(sample)
        reducedCache[key]?.let { return it }
        if (index < 0 || index >= blockOffsets.size) return null
        val len = blockByteCounts.getOrNull(index)?.toInt() ?: return null
        if (len <= 0) return null
        val raw = source.read(blockOffsets[index], len)
        if (raw.isEmpty()) return null
        val dec = jpeg ?: return null
        val image = dec.decode(withJpegTables(raw), sample) ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val spp = samplesPerPixel
        val data = IntArray(image.width * image.height * spp)
        for (i in 0 until image.width * image.height) {
            val r = image.rgb.getOrElse(i * 3) { 0 }
            val g = image.rgb.getOrElse(i * 3 + 1) { 0 }
            val b = image.rgb.getOrElse(i * 3 + 2) { 0 }
            val d = i * spp
            when (spp) {
                1 -> data[d] = g
                2 -> { data[d] = g; data[d + 1] = 255 }
                3 -> { data[d] = r; data[d + 1] = g; data[d + 2] = b }
                else -> { data[d] = r; data[d + 1] = g; data[d + 2] = b; data[d + 3] = 255 }
            }
        }
        return ReducedBlock(image.width, image.height, data).also { reducedCache[key] = it }
    }

    /** One full-width row stretched from a reduced strip by nearest neighbour. */
    private fun reducedRow(y: Int, sample: Int): IntArray? {
        val bi = y / blockRows
        val cb = reducedBlock(bi, sample) ?: return null
        val ry = y - bi * blockRows
        val cy = (ry * cb.h / blockRows).coerceAtMost(cb.h - 1)
        val spp = samplesPerPixel
        val out = IntArray(width * spp)
        for (x in 0 until width) {
            val cx = (x * cb.w / width).coerceAtMost(cb.w - 1)
            val src = (cy * cb.w + cx) * spp
            val dst = x * spp
            for (k in 0 until spp) out[dst + k] = cb.data[src + k]
        }
        return out
    }

    private fun sampleShift(sample: Int): Int = when {
        sample >= 8 -> 3
        sample >= 4 -> 2
        sample >= 2 -> 1
        else -> 0
    }

    /**
     * Samples of block [index] as interleaved ints, or null when it cannot be read.
     *
     * Only one block is ever held. That is the whole reason a raster hundreds of
     * megabytes on disk opens at all: a 256-pixel tile of RGBA is 256 KB, where a
     * full-width strip of a 23,000-wide orthophoto is 37 MB.
     */
    private fun block(index: Int, sample: Int = 1): IntArray? {
        val key = index * 4 + sampleShift(sample)
        cachedBlock(key)?.let { return it }
        if (index < 0 || index >= blockOffsets.size) return null
        val off = blockOffsets[index]
        val len = blockByteCounts.getOrNull(index)?.toInt() ?: return null
        if (len <= 0) return null
        val raw = source.read(off, len)
        if (raw.isEmpty()) return null
        val out = IntArray(blockCols * blockRows * samplesPerPixel)
        val got = when (compression) {
            1 -> unpack(raw, out)
            8, 32946 -> inflate(raw, out)
            5 -> TiffLzw.decode(raw, out, samplesPerPixel)
            7 -> decodeJpeg(raw, out, sample)
            else -> 0
        }
        if (got <= 0) return null
        blockCache[key] = out
        return out
    }

    /**
     * Decode one JPEG-in-TIFF block into [out]. Strips usually omit their Huffman and
     * quantisation tables, which live once in JPEGTables, so those are spliced in first.
     */
    private fun decodeJpeg(raw: ByteArray, out: IntArray, sample: Int): Int {
        val dec = jpeg ?: return 0
        val image = dec.decode(withJpegTables(raw), sample) ?: return 0
        if (image.width <= 0 || image.height <= 0) return 0
        // A reduced image is stretched back to block size by nearest neighbour; a full
        // one maps one to one. Either way the block keeps its full layout for row().
        val spp = samplesPerPixel
        for (y in 0 until blockRows) {
            val sy = (y * image.height / blockRows).coerceAtMost(image.height - 1)
            for (x in 0 until blockCols) {
                val sx = (x * image.width / blockCols).coerceAtMost(image.width - 1)
                val src = (sy * image.width + sx) * 3
                if (src + 2 >= image.rgb.size) continue
                val r = image.rgb[src]; val g = image.rgb[src + 1]; val b = image.rgb[src + 2]
                val dst = (y * blockCols + x) * spp
                when (spp) {
                    1 -> out[dst] = g
                    2 -> { out[dst] = g; out[dst + 1] = 255 }
                    3 -> { out[dst] = r; out[dst + 1] = g; out[dst + 2] = b }
                    else -> { out[dst] = r; out[dst + 1] = g; out[dst + 2] = b; out[dst + 3] = 255 }
                }
            }
        }
        return out.size
    }

    /** SOI + JPEGTables (without their own SOI/EOI) + the strip (without its SOI). */
    private fun withJpegTables(raw: ByteArray): ByteArray {
        val t = jpegTables ?: return raw
        val tStart = if (t.size >= 2 && t[0] == 0xFF.toByte() && t[1] == 0xD8.toByte()) 2 else 0
        val tEnd = if (t.size >= 2 && t[t.size - 2] == 0xFF.toByte() && t[t.size - 1] == 0xD9.toByte()) t.size - 2 else t.size
        val dStart = if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()) 2 else 0
        val out = ByteArray(2 + (tEnd - tStart) + (raw.size - dStart))
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        System.arraycopy(t, tStart, out, 2, tEnd - tStart)
        System.arraycopy(raw, dStart, out, 2 + (tEnd - tStart), raw.size - dStart)
        return out
    }

    /** One source row as interleaved samples, copied out of the block holding it. */
    private fun row(y: Int, sample: Int = 1): IntArray? {
        if (y < 0 || y >= height) return null
        if (sample > 1 && !tiled && compression == 7) return reducedRow(y, sample)
        val data: IntArray
        val rowInBlock: Int
        val base: Int
        if (tiled) {
            // A row crosses every tile across it, and the tiles at the right and bottom
            // edges are partial, so the row is stitched rather than assumed whole.
            val ty = y / blockRows
            val by = y - ty * blockRows
            val out = IntArray(width * samplesPerPixel)
            var x = 0
            var tx = 0
            while (x < width) {
                val tileX = tx * blockCols
                val cols = minOf(blockCols, width - tileX).coerceAtLeast(0)
                if (cols > 0) {
                    val tile = block(ty * blocksAcross + tx, sample) ?: return null
                    // A tile at the right edge is clipped: it holds only the columns that
                    // exist, so its row stride is that width and not TileWidth. Assuming
                    // the full stride shifts every row after the first.
                    val src = (by * cols) * samplesPerPixel
                    if (src + cols * samplesPerPixel > tile.size) return null
                    System.arraycopy(tile, src, out, x * samplesPerPixel, cols * samplesPerPixel)
                }
                x += blockCols
                tx++
            }
            return out
        } else {
            val bi = y / blockRows
            data = block(bi, sample) ?: return null
            rowInBlock = y - bi * blockRows
            base = rowInBlock * width * samplesPerPixel
        }
        if (base + width * samplesPerPixel > data.size) return null
        return data.copyOfRange(base, base + width * samplesPerPixel)
    }

    /**
     * Draw the part of the raster covering [world] into [dst], an ARGB buffer
     * [dstW] x [dstH] laid out row-major.
     *
     * Where the raster is more detailed than the screen - the normal case, a 5 cm
     * orthophoto on a 1080-wide screen is sampled about 20:1 - the source pixels
     * are averaged. Point sampling would keep one bright pixel and drop a narrow
     * track, which on imagery reads as the track being missing.
     *
     * Returns false when nothing in [world] is covered.
     */
    private fun largestPowerOfTwoAtMost(n: Int, cap: Int): Int {
        var p = 1
        while (p * 2 <= n && p * 2 <= cap) p *= 2
        return p
    }

    /** ARGB of one raster column in a row, or transparent when the row is missing. */
    private fun nearestArgb(r: IntArray?, sx: Int): Int {
        if (r == null) return 0
        val o = sx * samplesPerPixel
        if (o + 2 >= r.size) return 0
        return (0xFF shl 24) or (r[o] shl 16) or (r[o + 1] shl 8) or r[o + 2]
    }

    fun decodeWindow(world: DoubleArray, dstW: Int, dstH: Int, dst: IntArray): Boolean {
        if (dstW <= 0 || dstH <= 0 || dst.size < dstW * dstH) return false
        val b = worldBounds() ?: return false
        if (world[2] < b[0] || world[0] > b[2] || world[3] < b[1] || world[1] > b[3]) return false
        if (unsupported() != null) return false

        val west = originE - pixelScaleX / 2.0
        val north = originN + pixelScaleY / 2.0
        val sx0 = (((world[0] - west) / pixelScaleX).toInt()).coerceIn(0, width - 1)
        val sx1 = (((world[2] - west) / pixelScaleX).toInt() + 1).coerceIn(0, width - 1)
        val sy0 = (((north - world[3]) / pixelScaleY).toInt()).coerceIn(0, height - 1)
        val sy1 = (((north - world[1]) / pixelScaleY).toInt() + 1).coerceIn(0, height - 1)
        if (sx1 < sx0 || sy1 < sy0) return false

        val perDstX = (sx1 - sx0 + 1).toDouble() / dstW
        val perDstY = (sy1 - sy0 + 1).toDouble() / dstH
        val accR = IntArray(dstW); val accG = IntArray(dstW); val accB = IntArray(dstW)
        val accN = IntArray(dstW)

        // Zoomed out over a large raster, averaging every source pixel reads every row of
        // the file, which on a phone is minutes. There a basemap only needs a sample: one
        // nearest pixel per screen pixel, with JPEG strips decoded at a reduced size. Small
        // windows keep the exact average.
        val sourceRows = sy1 - sy0 + 1
        if ((perDstX >= 2.0 || perDstY >= 2.0) && sourceRows > AVERAGING_MAX_SOURCE_ROWS) {
            val sample = largestPowerOfTwoAtMost(minOf(perDstX, perDstY).toInt(), 8)
            for (dy in 0 until dstH) {
                val sy = (sy0 + ((dy + 0.5) * perDstY).toInt()).coerceIn(sy0, sy1)
                val r = row(sy, sample)
                val base = dy * dstW
                for (dx in 0 until dstW) {
                    val sx = (sx0 + ((dx + 0.5) * perDstX).toInt()).coerceIn(sx0, sx1)
                    dst[base + dx] = nearestArgb(r, sx)
                }
            }
            return true
        }

        for (dy in 0 until dstH) {
            java.util.Arrays.fill(accR, 0); java.util.Arrays.fill(accG, 0)
            java.util.Arrays.fill(accB, 0); java.util.Arrays.fill(accN, 0)
            val yFrom = sy0 + (dy * perDstY).toInt()
            val yTo = (sy0 + ((dy + 1) * perDstY).toInt()).coerceAtMost(sy1 + 1).coerceAtLeast(yFrom + 1)
            for (sy in yFrom until yTo) {
                // row(sy) is one row on its own, so there is no window-relative offset
                val r = row(sy) ?: continue
                for (dx in 0 until dstW) {
                    val xFrom = sx0 + (dx * perDstX).toInt()
                    val xTo = (sx0 + ((dx + 1) * perDstX).toInt())
                        .coerceAtMost(sx1 + 1).coerceAtLeast(xFrom + 1)
                    var sr = 0; var sg = 0; var sb = 0; var n = 0
                    for (sx in xFrom until xTo) {
                        val o = sx * samplesPerPixel
                        if (o + 2 >= r.size) continue
                        sr += r[o]; sg += r[o + 1]; sb += r[o + 2]; n++
                    }
                    if (n == 0) continue
                    accR[dx] += sr; accG[dx] += sg; accB[dx] += sb; accN[dx] += n
                }
            }
            val base = dy * dstW
            for (dx in 0 until dstW) {
                val n = accN[dx]
                dst[base + dx] = if (n == 0) 0 else {
                    (0xFF shl 24) or
                        ((accR[dx] / n).coerceIn(0, 255) shl 16) or
                        ((accG[dx] / n).coerceIn(0, 255) shl 8) or
                        (accB[dx] / n).coerceIn(0, 255)
                }
            }
        }
        return true
    }

    private fun unpack(raw: ByteArray, dst: IntArray): Int {
        var i = 0
        var o = 0
        while (i + samplesPerPixel <= raw.size && o < dst.size) {
            for (c in 0 until samplesPerPixel) dst[o + c] = raw[i + c].toInt() and 0xFF
            i += samplesPerPixel
            o += samplesPerPixel
        }
        return o
    }

    private fun inflate(raw: ByteArray, dst: IntArray): Int {
        val inf = Inflater()
        inf.setInput(raw)
        val buf = ByteArray(1 shl 16)
        var written = 0
        try {
            while (!inf.finished() && written < dst.size) {
                val n = try { inf.inflate(buf) } catch (e: Exception) { break }
                if (n <= 0) break
                var i = 0
                while (i + samplesPerPixel <= n && written + samplesPerPixel <= dst.size) {
                    for (c in 0 until samplesPerPixel) dst[written + c] = buf[i + c].toInt() and 0xFF
                    written += samplesPerPixel
                    i += samplesPerPixel
                }
            }
        } finally {
            inf.end()
        }
        return written
    }

    companion object {
        /** Blocks kept in memory; a tile-row of 256-pixel tiles needs a handful. */
        private const val BLOCK_CACHE_ENTRIES = 8
        /** Windows spanning more source rows than this are sampled, not averaged. */
        private const val AVERAGING_MAX_SOURCE_ROWS = 512

        /** Parse the header and first IFD, or null when this is not a TIFF. */
        fun open(source: ByteSource, jpeg: JpegDecoder? = null): GeoTiff? {
            val head = source.read(0, 8)
            if (head.size < 8) return null
            val le = when {
                head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> true
                head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> false
                else -> return null
            }
            val magic = u16(head, 2, le)
            if (magic != 42 && magic != 0x4F52 && magic != 0x5352) return null
            val ifd = u32(head, 4, le).toInt()
            if (ifd <= 0 || ifd >= source.size()) return null
            // Read exactly the IFD: a speculative over-read leaves the stream sitting
            // past the start of the image data, and every later strip read would then
            // have to reopen it - which on a SAF document costs more than the decode.
            val countBuf = source.read(ifd.toLong(), 2)
            if (countBuf.size < 2) return null
            val count = u16(countBuf, 0, le)
            if (count <= 0 || count > 4096) return null
            val buf = source.read(ifd.toLong(), 2 + 12 * count + 4)
            if (buf.size < 2 + 12 * count) return null
            val entry = HashMap<Int, Int>()

            fun offsetOf(tag: Int): Int {
                entry[tag]?.let { return it }
                for (i in 0 until count) {
                    val o = 2 + i * 12
                    if (o + 12 > buf.size) break
                    if (u16(buf, o, le) == tag) { entry[tag] = o; return o }
                }
                return -1
            }

            fun tagLong(tag: Int, i: Int): Long? {
                val o = offsetOf(tag)
                if (o < 0) return null
                val type = u16(buf, o + 2, le)
                val n = u32(buf, o + 4, le).toInt()
                if (i >= n) return null
                val size = typeSize(type) * n
                val data = if (size <= 4) {
                    buf.copyOfRange(o + 8, minOf(o + 8 + size, buf.size))
                } else {
                    val at = u32(buf, o + 8, le).toInt()
                    if (at < 0 || at.toLong() + size > source.size()) return null
                    source.read(at.toLong(), size)
                }
                if (data.isEmpty()) return null
                return when (type) {
                    1, 2, 6, 7 -> (data[i].toInt() and 0xFF).toLong()
                    3 -> u16(data, i * 2, le).toLong()
                    4 -> u32(data, i * 4, le)
                    11 -> f32(data, i * 4, le).toRawBits().toLong()
                    12 -> f64(data, i * 8, le).toRawBits()
                    else -> null
                }
            }

            fun tagBytes(tag: Int): ByteArray? {
                val o = offsetOf(tag)
                if (o < 0) return null
                val type = u16(buf, o + 2, le)
                val n = u32(buf, o + 4, le).toInt()
                val size = typeSize(type) * n
                if (size <= 0) return null
                return if (size <= 4) {
                    buf.copyOfRange(o + 8, minOf(o + 8 + size, buf.size))
                } else {
                    val at = u32(buf, o + 8, le).toInt()
                    if (at < 0 || at.toLong() + size > source.size()) return null
                    source.read(at.toLong(), size)
                }
            }

            fun tagDoubles(tag: Int): DoubleArray? {
                val o = offsetOf(tag)
                if (o < 0) return null
                val type = u16(buf, o + 2, le)
                val n = u32(buf, o + 4, le).toInt()
                val size = typeSize(type) * n
                val data = if (size <= 4) {
                    buf.copyOfRange(o + 8, minOf(o + 8 + size, buf.size))
                } else {
                    val at = u32(buf, o + 8, le).toInt()
                    if (at < 0 || at.toLong() + size > source.size()) return null
                    source.read(at.toLong(), size)
                }
                if (type != 12 || data.size < n * 8) return null
                return DoubleArray(n) { f64(data, it * 8, le) }
            }

            val w = tagLong(256, 0)?.toInt() ?: return null
            val h = tagLong(257, 0)?.toInt() ?: return null
            if (w <= 0 || h <= 0 || w > 300_000 || h > 300_000) return null
            val samples = (tagLong(277, 0)?.toInt() ?: 1).coerceIn(1, 4)
            val comp = (tagLong(259, 0)?.toInt() ?: 1)
            // Internally tiled (322/323/324/325) or striped (273/278/279). Tiling is
            // what makes a large raster quick to pan: only the tiles on screen are read.
            val tileW = tagLong(322, 0)?.toInt()
            val tileH = tagLong(323, 0)?.toInt()
            val tileOffsetsEntry = offsetOf(324)
            val isTiled = tileW != null && tileH != null && tileW > 0 && tileH > 0 &&
                tileOffsetsEntry >= 0

            val countEntry = offsetOf(if (isTiled) 324 else 273)
            if (countEntry < 0) return null
            val n = u32(buf, countEntry + 4, le).toInt()
            if (n <= 0) return null
            val offsets = LongArray(n)
            val counts = LongArray(n)
            val offTag = if (isTiled) 324 else 273
            val cntTag = if (isTiled) 325 else 279
            for (i in 0 until n) {
                offsets[i] = tagLong(offTag, i) ?: 0
                counts[i] = tagLong(cntTag, i) ?: 0
            }
            val rps = if (isTiled) tileH!! else (tagLong(278, 0)?.toInt() ?: h).coerceAtLeast(1)
            val bCols = if (isTiled) tileW!! else w

            var sx = 1.0; var sy = 1.0; var oe = Double.NaN; var on = Double.NaN
            var geo = false
            val scale = tagDoubles(33550)
            val tie = tagDoubles(33922)
            if (scale != null && scale.size >= 2 && tie != null && tie.size >= 6) {
                sx = scale[0]; sy = scale[1]
                // a tiepoint names the *centre* of pixel i,j; worldBounds() converts to
                // the outer edge, so the half-pixel belongs there and not here
                oe = tie[3]
                on = tie[4]
                geo = sx > 0.0 && sy > 0.0 && oe.isFinite() && on.isFinite()
            }

            return GeoTiff(
                source, w, h, samples, comp, offsets, counts, rps, bCols,
                isTiled, sx, sy, oe, on, geo,
                jpeg, if (comp == 7) tagBytes(347) else null,
            )
        }

        private fun typeSize(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> 1
        }

        private fun u16(b: ByteArray, o: Int, le: Boolean): Int {
            if (o + 1 >= b.size) return 0
            val a = b[o].toInt() and 0xFF
            val c = b[o + 1].toInt() and 0xFF
            return if (le) a or (c shl 8) else (a shl 8) or c
        }

        private fun u32(b: ByteArray, o: Int, le: Boolean): Long {
            if (o + 3 >= b.size) return 0
            var v = 0L
            if (le) for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            else for (i in 0 until 4) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }

        private fun f32(b: ByteArray, o: Int, le: Boolean): Float {
            if (o + 3 >= b.size) return 0f
            return Float.fromBits(u32(b, o, le).toInt())
        }

        private fun f64(b: ByteArray, o: Int, le: Boolean): Double {
            if (o + 7 >= b.size) return 0.0
            var v = 0L
            if (le) for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            else for (i in 0 until 7) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return Double.fromBits(v)
        }
    }
}

/**
 * TIFF's LZW variant: codes start at 9 bits, grow to 12, and the table is cleared
 * rather than held once full.
 */
internal object TiffLzw {
    fun decode(raw: ByteArray, dst: IntArray, samples: Int): Int {
        val clear = 1 shl 9
        val eoi = clear + 1
        var next = eoi + 1
        var width = 9
        var dict = arrayOfNulls<ByteArray>(4096)
        for (i in 0 until 256) dict[i] = byteArrayOf(i.toByte())
        var prev: ByteArray? = null
        var out = 0
        var bit = 0
        val totalBits = raw.size * 8

        fun code(): Int {
            if (bit + width > totalBits) return eoi
            var v = 0
            for (i in 0 until width) {
                val bp = bit + i
                if ((raw[bp ushr 3].toInt() shr (bp and 7)) and 1 == 1) v = v or (1 shl i)
            }
            bit += width
            return v
        }

        while (true) {
            val c = code()
            if (c == eoi) break
            if (c == clear) {
                width = 9; next = eoi + 1
                dict = arrayOfNulls(4096)
                for (i in 0 until 256) dict[i] = byteArrayOf(i.toByte())
                prev = null
                continue
            }
            val cur: ByteArray = when {
                c < next && dict[c] != null -> dict[c]!!
                prev != null -> prev!! + prev!![0]
                else -> break
            }
            for (b in cur) {
                if (out + samples > dst.size) return out
                for (s in 0 until samples) dst[out + s] = b.toInt() and 0xFF
                out += samples
            }
            if (prev != null && next < 4096) {
                dict[next] = prev!! + cur[0]
                next++
                if (next + 1 >= (1 shl width) && width < 12) width++
            }
            prev = cur
        }
        return out
    }
}

/**
 * A [ByteSource] over a stream that can be reopened.
 *
 * A SAF document gives a one-way stream, but a TIFF needs to read the header, then
 * jump to strip offsets scattered across the file. Rather than hold the file - which
 * is the whole point of not doing so - the stream is reopened when a read lands
 * before the current position and skipped forward otherwise, so a screenful costs a
 * handful of small reads rather than hundreds of megabytes of memory.
 */
class StreamByteSource(
    private val open: () -> java.io.InputStream,
    private val sizeOf: () -> Long,
) : ByteSource {

    override fun size(): Long = sizeOf()

    private var stream: java.io.InputStream? = null
    private var pos = 0L

    private fun streamAt(offset: Long): java.io.InputStream {
        val s = stream
        if (s != null && pos == offset) return s
        val fresh = open()
        stream?.close()
        stream = fresh
        if (offset > 0) {
            var skipped = 0L
            while (skipped < offset) {
                val n = fresh.skip(offset - skipped)
                if (n <= 0L) {
                    // skip() may legitimately return 0 near the end; read to be sure
                    if (fresh.read() < 0) break
                    skipped++
                } else {
                    skipped += n
                }
            }
        }
        pos = offset
        return fresh
    }

    override fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val s = streamAt(offset)
        val buf = ByteArray(length)
        var read = 0
        var zeroes = 0
        while (read < length && zeroes < 100) {
            val n = try { s.read(buf, read, length - read) } catch (e: Exception) { break }
            if (n < 0) break
            if (n == 0) { zeroes++; continue }
            zeroes = 0
            read += n
        }
        pos += read
        return if (read == length) buf else buf.copyOf(read)
    }

    fun close() {
        runCatching { stream?.close() }
        stream = null
    }
}
