package org.gcap.core

import java.io.ByteArrayOutputStream

object ShpFormat {
    const val TYPE_POINT = 1
    const val TYPE_POLYLINE = 3
    const val TYPE_POLYGON = 5
    const val TYPE_MULTIPOINT = 8
    const val TYPE_POINTZ = 11
    const val TYPE_POLYLINEZ = 13
    const val TYPE_POLYGONZ = 15
    const val TYPE_MULTIPOINTZ = 18

    data class Shape(
        val recordIndex: Int,
        val type: Int,
        val parts: List<List<DoubleArray>>,
        val xmin: Double,
        val ymin: Double,
        val xmax: Double,
        val ymax: Double,
        val label: String = "",
    )

    data class ShpInfo(
        val shapeType: Int,
        val recordCount: Int,
        val xmin: Double,
        val ymin: Double,
        val xmax: Double,
        val ymax: Double,
    )

    data class DbfField(
        val name: String,
        val type: Char,
        val len: Int,
        val decimals: Int,
    )

    data class Dbf(
        val numRecords: Int,
        val fieldLengths: Int,
        val fields: List<DbfField>,
    )

    data class DbfRow(
        val deleted: Boolean,
        val values: List<String>,
    )

    fun readShp(bytes: ByteArray, minX: Double, minY: Double, maxX: Double, maxY: Double): List<Shape> {
        if (bytes.size < 100) return emptyList()
        val shapes = mutableListOf<Shape>()
        var p = 100
        var recordIdx = 0
        while (p + 8 <= bytes.size) {
            val recNum = readIntBe(bytes, p)
            val contentLenWords = readIntBe(bytes, p + 4)
            val contentLen = contentLenWords * 2
            p += 8
            if (p + contentLen > bytes.size || contentLen < 4) break
            val shapeType = readIntLe(bytes, p)
            if (shapeType == 0) {
                p += contentLen
                recordIdx++
                continue
            }
            val parts = mutableListOf<List<DoubleArray>>()
            var xmin = 0.0; var ymin = 0.0; var xmax = 0.0; var ymax = 0.0

            // Z variants share the XY section layout (trailing Z/M arrays are
            // skipped via the record content length), so they parse identically.
            when (shapeType) {
                TYPE_POINT, TYPE_POINTZ -> {
                    val x = readDoubleLe(bytes, p + 4)
                    val y = readDoubleLe(bytes, p + 12)
                    xmin = x; ymin = y; xmax = x; ymax = y
                    parts.add(listOf(doubleArrayOf(x, y)))
                }
                TYPE_POLYLINE, TYPE_POLYGON, TYPE_POLYLINEZ, TYPE_POLYGONZ -> {
                    xmin = readDoubleLe(bytes, p + 4)
                    ymin = readDoubleLe(bytes, p + 12)
                    xmax = readDoubleLe(bytes, p + 20)
                    ymax = readDoubleLe(bytes, p + 28)
                    // Bbox-first cull: skip the vertex arrays of out-of-view
                    // records entirely (a 200 MB contour file otherwise parses
                    // every vertex on each load). Record indices still advance
                    // so DBF row alignment is preserved.
                    if (xmax < minX || xmin > maxX || ymax < minY || ymin > maxY) {
                        p += contentLen
                        recordIdx++
                        continue
                    }
                    // Every count below is attacker-controlled (the files come
                    // from a desktop copy, USB stick or file share), so it is
                    // validated against the record's own declared length before
                    // it is used to size an array or drive a loop. Without this
                    // a ~200-byte record claiming 50M parts allocates 200 MB
                    // and the process is killed; one claiming 50M points walks
                    // tens of millions of vertices, allocating a DoubleArray
                    // each, which exhausts the heap even faster.
                    // The spec puts a Z and M block between the box and the counts, so
                    // they sit at 68/72 with the part array at 76. Records written by
                    // earlier builds left that block out and put the counts at 36/40.
                    // Both are read, so an existing layer still draws; the next append
                    // rewrites every part in the correct layout.
                    val specCounts = if (contentLen >= 80) {
                        val np = readIntLe(bytes, p + 68)
                        val nv = readIntLe(bytes, p + 72)
                        val fits = np >= 1 && np * 4 <= contentLen - 80 &&
                            nv >= 0 && nv <= (contentLen - 76 - np * 4) / 16
                        if (fits) np to nv else null
                    } else null
                    val legacyCounts = if (contentLen >= 44) {
                        val np = readIntLe(bytes, p + 36)
                        val nv = readIntLe(bytes, p + 40)
                        val fits = np >= 1 && np * 4 <= contentLen - 44 &&
                            nv >= 0 && nv <= (contentLen - 44 - np * 4) / 16
                        if (fits) np to nv else null
                    } else null
                    val counts = specCounts ?: legacyCounts
                    if (counts == null) { p += contentLen; recordIdx++; continue }
                    val fieldsEnd = if (specCounts != null) 76 else 44
                    val bodyLen = contentLen - fieldsEnd
                    val maxParts = bodyLen / 4
                    val numParts = counts.first
                    val numPoints = counts.second
                    if (numParts < 0 || numPoints < 0 || numParts > maxParts) {
                        p += contentLen; recordIdx++; continue
                    }
                    val pointsBytes = bodyLen - numParts * 4
                    if (pointsBytes < 0 || numPoints > pointsBytes / 16) {
                        p += contentLen; recordIdx++; continue
                    }
                    val partsOffset = p + fieldsEnd
                    val pointsOffset = partsOffset + numParts * 4
                    val partIndices = IntArray(numParts + 1)
                    for (i in 0 until numParts) {
                        partIndices[i] = readIntLe(bytes, partsOffset + i * 4)
                    }
                    partIndices[numParts] = numPoints

                    for (i in 0 until numParts) {
                        val start = partIndices[i]
                        val end = partIndices[i + 1]
                        // Part offsets come from the file too: clamp them into
                        // the record rather than trusting them to be ordered.
                        if (start < 0 || end < start || end > numPoints) {
                            p += contentLen; recordIdx++; continue
                        }
                        val pts = ArrayList<DoubleArray>(end - start)
                        for (pt in start until end) {
                            val px = readDoubleLe(bytes, pointsOffset + pt * 16)
                            val py = readDoubleLe(bytes, pointsOffset + pt * 16 + 8)
                            // NaN/Inf defeat every bbox compare (all comparisons
                            // with NaN are false) so they are never culled, and
                            // minOf() over a poisoned bbox turns the map centre
                            // into NaN - the map then renders nothing. Drop them.
                            if (!px.isFinite() || !py.isFinite()) continue
                            pts.add(doubleArrayOf(px, py))
                        }
                        parts.add(pts)
                    }
                }
            }
            val finiteBox = xmin.isFinite() && ymin.isFinite() && xmax.isFinite() && ymax.isFinite()
            if (finiteBox && !(xmax < minX || xmin > maxX || ymax < minY || ymin > maxY)) {
                shapes.add(Shape(recordIdx, shapeType, parts, xmin, ymin, xmax, ymax))
            }
            p += contentLen
            recordIdx++
        }
        return shapes
    }

    fun shpHeader(bytes: ByteArray): ShpInfo? {
        if (bytes.size < 100) return null
        val code = readIntBe(bytes, 0)
        if (code != 9994) return null
        val fileLengthWords = readIntBe(bytes, 24)
        val shapeType = readIntLe(bytes, 32)
        val xmin = readDoubleLe(bytes, 36)
        val ymin = readDoubleLe(bytes, 44)
        val xmax = readDoubleLe(bytes, 52)
        val ymax = readDoubleLe(bytes, 60)
        // Exact record count: walk the 8-byte record headers. The old
        // "(fileLengthWords*2-100)/10" estimate assumed one fixed record size
        // and miscounted every polyline/polygon file.
        val end = minOf(bytes.size, fileLengthWords * 2)
        var p = 100
        var recordCount = 0
        while (p + 8 <= end) {
            val contentWords = readIntBe(bytes, p + 4)
            if (contentWords < 2 || p + 8 + contentWords * 2 > end) break
            p += 8 + contentWords * 2
            recordCount++
        }
        return ShpInfo(shapeType, recordCount, xmin, ymin, xmax, ymax)
    }

    fun dbfHeader(bytes: ByteArray): Dbf {
        // The record count is a 4-byte little-endian field in dBASE (header length and
        // record length are 2-byte). Reading it as 16 bits silently truncated any table
        // with more than 65,535 rows, which desynchronised labels from geometries.
        val numRecords = readIntLe(bytes, 4)
        val headerLen = readIntLe16(bytes, 8)
        val recordLen = readIntLe16(bytes, 10)
        val fields = mutableListOf<DbfField>()
        var p = 32
        while (p + 32 <= headerLen) {
            if (bytes[p].toInt() == 0x0D) break
            val nameBytes = bytes.copyOfRange(p, p + 11)
            val name = String(nameBytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
            val type = bytes[p + 11].toInt().toChar()
            val len = bytes[p + 16].toInt() and 0xFF
            val decimals = bytes[p + 17].toInt() and 0xFF
            fields.add(DbfField(name, type, len, decimals))
            p += 32
        }
        return Dbf(numRecords, recordLen, fields)
    }

    fun readDbfRows(bytes: ByteArray): List<DbfRow> {
        val dbf = dbfHeader(bytes)
        val headerLen = readIntLe16(bytes, 8)
        val recordLen = dbf.fieldLengths
        val rows = mutableListOf<DbfRow>()
        var p = headerLen
        for (r in 0 until dbf.numRecords) {
            if (p >= bytes.size) break
            val deleted = bytes[p].toInt() == 0x2A // '*'
            var offset = p + 1
            val values = ArrayList<String>(dbf.fields.size)
            for (f in dbf.fields) {
                // Built straight out of the source array, with no intermediate copies.
                // This used to copy the whole row and then copy each field out of that
                // copy, so every field cost two short-lived arrays before its String.
                // A roads network .dbf is ~189,000 rows of ~10 fields, which is millions
                // of allocations, and that - not the parsing - was what took 30 s to read.
                val end = offset + f.len
                val v = if (end <= bytes.size) {
                    String(bytes, offset, f.len, Charsets.US_ASCII).trim()
                } else {
                    // a row truncated by the end of the file: read what is there
                    String(bytes, offset, (bytes.size - offset).coerceAtLeast(0), Charsets.US_ASCII).trim()
                }
                values.add(v)
                offset = end
            }
            rows.add(DbfRow(deleted, values))
            p += recordLen
        }
        return rows
    }

    fun buildPointShp(coords: List<DoubleArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val recCount = coords.size
        val fileLengthWords = 50 + recCount * 14
        var xmin = Double.MAX_VALUE; var ymin = Double.MAX_VALUE
        var xmax = -Double.MAX_VALUE; var ymax = -Double.MAX_VALUE
        for (c in coords) {
            xmin = minOf(xmin, c[0]); ymin = minOf(ymin, c[1])
            xmax = maxOf(xmax, c[0]); ymax = maxOf(ymax, c[1])
        }
        if (coords.isEmpty()) { xmin = 0.0; ymin = 0.0; xmax = 0.0; ymax = 0.0 }

        out.write(writeIntBe(9994))
        out.write(ByteArray(20))
        out.write(writeIntBe(fileLengthWords))
        out.write(writeIntLe(1000))
        out.write(writeIntLe(TYPE_POINT))
        out.write(writeDoubleLe(xmin))
        out.write(writeDoubleLe(ymin))
        out.write(writeDoubleLe(xmax))
        out.write(writeDoubleLe(ymax))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))

        for (i in coords.indices) {
            val c = coords[i]
            out.write(writeIntBe(i + 1))
            out.write(writeIntBe(10))
            out.write(writeIntLe(TYPE_POINT))
            out.write(writeDoubleLe(c[0]))
            out.write(writeDoubleLe(c[1]))
        }
        return out.toByteArray()
    }

    fun buildPointShx(coords: List<DoubleArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val recCount = coords.size
        val fileLengthWords = 50 + recCount * 4
        var xmin = Double.MAX_VALUE; var ymin = Double.MAX_VALUE
        var xmax = -Double.MAX_VALUE; var ymax = -Double.MAX_VALUE
        for (c in coords) {
            xmin = minOf(xmin, c[0]); ymin = minOf(ymin, c[1])
            xmax = maxOf(xmax, c[0]); ymax = maxOf(ymax, c[1])
        }
        if (coords.isEmpty()) { xmin = 0.0; ymin = 0.0; xmax = 0.0; ymax = 0.0 }

        out.write(writeIntBe(9994))
        out.write(ByteArray(20))
        out.write(writeIntBe(fileLengthWords))
        out.write(writeIntLe(1000))
        out.write(writeIntLe(TYPE_POINT))
        out.write(writeDoubleLe(xmin))
        out.write(writeDoubleLe(ymin))
        out.write(writeDoubleLe(xmax))
        out.write(writeDoubleLe(ymax))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))

        var offset = 50
        for (i in coords.indices) {
            out.write(writeIntBe(offset))
            out.write(writeIntBe(10))
            offset += 14
        }
        return out.toByteArray()
    }

    /** 16-bit words in one POLYLINE record body: type + bbox + counts + parts + XY. */
    /**
     * PolyLine record content: type 4 + box 32 + Z 16 + M 16 + numParts 4 + numPoints 4
     * + parts 4 + 16 per vertex. The Z and M blocks are mandatory; leaving them out
     * shifts every field after the box, and a reader then takes the part and point
     * counts out of the Z block and gets garbage.
     */
    private fun polylineContentWords(nPoints: Int): Int = (80 + 16 * nPoints) / 2

    /**
     * POLYLINE (type 3) shapefile: one record per part, single part each —
     * the layout [readShp] already understands. Parts with fewer than two
     * points are dropped (a line needs two vertices).
     */
    fun buildPolylineShp(parts: List<List<DoubleArray>>): ByteArray {
        val keep = parts.filter { it.size >= 2 }
        val out = ByteArrayOutputStream()
        var xmin = Double.MAX_VALUE; var ymin = Double.MAX_VALUE
        var xmax = -Double.MAX_VALUE; var ymax = -Double.MAX_VALUE
        for (part in keep) for (c in part) {
            xmin = minOf(xmin, c[0]); ymin = minOf(ymin, c[1])
            xmax = maxOf(xmax, c[0]); ymax = maxOf(ymax, c[1])
        }
        if (keep.isEmpty()) { xmin = 0.0; ymin = 0.0; xmax = 0.0; ymax = 0.0 }

        var fileLengthWords = 50
        for (part in keep) fileLengthWords += 4 + polylineContentWords(part.size)

        out.write(writeIntBe(9994))
        out.write(ByteArray(20))
        out.write(writeIntBe(fileLengthWords))
        out.write(writeIntLe(1000))
        out.write(writeIntLe(TYPE_POLYLINE))
        out.write(writeDoubleLe(xmin))
        out.write(writeDoubleLe(ymin))
        out.write(writeDoubleLe(xmax))
        out.write(writeDoubleLe(ymax))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))

        var recNo = 1
        for (part in keep) {
            var pxmin = Double.MAX_VALUE; var pymin = Double.MAX_VALUE
            var pxmax = -Double.MAX_VALUE; var pymax = -Double.MAX_VALUE
            for (c in part) {
                pxmin = minOf(pxmin, c[0]); pymin = minOf(pymin, c[1])
                pxmax = maxOf(pxmax, c[0]); pymax = maxOf(pymax, c[1])
            }
            out.write(writeIntBe(recNo++))
            out.write(writeIntBe(polylineContentWords(part.size)))
            out.write(writeIntLe(TYPE_POLYLINE))
            out.write(writeDoubleLe(pxmin))
            out.write(writeDoubleLe(pymin))
            out.write(writeDoubleLe(pxmax))
            out.write(writeDoubleLe(pymax))
            out.write(writeDoubleLe(0.0))        // Zmin
            out.write(writeDoubleLe(0.0))        // Zmax
            out.write(writeDoubleLe(0.0))        // Mmin
            out.write(writeDoubleLe(0.0))        // Mmax
            out.write(writeIntLe(1))            // numParts
            out.write(writeIntLe(part.size))    // numPoints
            out.write(writeIntLe(0))            // first part starts at vertex 0
            for (c in part) {
                out.write(writeDoubleLe(c[0]))
                out.write(writeDoubleLe(c[1]))
            }
        }
        return out.toByteArray()
    }

    /** Index companion for [buildPolylineShp] (same header, 8-byte records). */
    fun buildPolylineShx(parts: List<List<DoubleArray>>): ByteArray {
        val keep = parts.filter { it.size >= 2 }
        val out = ByteArrayOutputStream()
        var xmin = Double.MAX_VALUE; var ymin = Double.MAX_VALUE
        var xmax = -Double.MAX_VALUE; var ymax = -Double.MAX_VALUE
        for (part in keep) for (c in part) {
            xmin = minOf(xmin, c[0]); ymin = minOf(ymin, c[1])
            xmax = maxOf(xmax, c[0]); ymax = maxOf(ymax, c[1])
        }
        if (keep.isEmpty()) { xmin = 0.0; ymin = 0.0; xmax = 0.0; ymax = 0.0 }

        out.write(writeIntBe(9994))
        out.write(ByteArray(20))
        out.write(writeIntBe(50 + keep.size * 4))
        out.write(writeIntLe(1000))
        out.write(writeIntLe(TYPE_POLYLINE))
        out.write(writeDoubleLe(xmin))
        out.write(writeDoubleLe(ymin))
        out.write(writeDoubleLe(xmax))
        out.write(writeDoubleLe(ymax))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))
        out.write(writeDoubleLe(0.0))

        var offset = 50
        for (part in keep) {
            val words = polylineContentWords(part.size)
            out.write(writeIntBe(offset))
            out.write(writeIntBe(words))
            offset += 4 + words
        }
        return out.toByteArray()
    }

    fun buildDbf(@Suppress("UNUSED_PARAMETER") origDbf: ByteArray, names: List<String>, fieldName: String = "GCALL_ID", fieldLen: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        val numRecords = names.size
        val recordLen = 1 + fieldLen
        // 32-byte header + one 32-byte field descriptor + 1-byte 0x0D terminator
        val headerLen = 32 + 32 + 1

        out.write(3)
        val now = java.util.Calendar.getInstance()
        out.write(now.get(java.util.Calendar.YEAR) - 1900)
        out.write(now.get(java.util.Calendar.MONTH) + 1)
        out.write(now.get(java.util.Calendar.DAY_OF_MONTH))
        // dBASE header bytes 4-7: record count as 4-byte LE (was 2-byte: shifted every later field)
        out.write(writeIntLe(numRecords))
        out.write(writeIntLe16(headerLen))
        out.write(writeIntLe16(recordLen))
        out.write(ByteArray(20))

        // Field descriptor must be exactly 32 bytes: 11 name + 1 type + 4 addr + 1 len + 1 dec + 14 reserved
        val cleanField = fieldName.uppercase().filter { it.code in 32..126 }.take(10)
        val rawName = cleanField.toByteArray(Charsets.US_ASCII)
        val fNamePadded = ByteArray(11) { i -> if (i < rawName.size) rawName[i] else 0 }
        out.write(fNamePadded)
        out.write('C'.code)
        out.write(ByteArray(4))
        out.write(fieldLen)
        out.write(0)
        out.write(ByteArray(14))
        // Header terminator (separate from descriptor, which is now exactly 32 bytes)
        out.write(0x0D)

        for (name in names) {
            out.write(0x20)
            val clean = name.map { if (it.code in 32..126) it else '?' }.joinToString("").padEnd(fieldLen, ' ').take(fieldLen)
            out.write(clean.toByteArray(Charsets.US_ASCII))
        }
        out.write(0x1A)
        return out.toByteArray()
    }

    /**
     * dBASE table with several text (C) fields, for new-layer templates.
     * Same layout as [buildDbf] (4-byte record count, 32-byte descriptors):
     * [fields] are (name, length) pairs, [rows] are value lists (short rows
     * are space-padded, long rows truncated to the field count).
     */
    fun buildDbfFields(
        fields: List<Pair<String, Int>>,
        rows: List<List<String>>,
    ): ByteArray {
        require(fields.isNotEmpty() && fields.size <= 255)
        require(fields.all { it.second in 1..254 })
        val out = ByteArrayOutputStream()
        val numRecords = rows.size
        val recordLen = 1 + fields.sumOf { it.second }
        val headerLen = 32 + 32 * fields.size + 1

        out.write(3)
        val now = java.util.Calendar.getInstance()
        out.write(now.get(java.util.Calendar.YEAR) - 1900)
        out.write(now.get(java.util.Calendar.MONTH) + 1)
        out.write(now.get(java.util.Calendar.DAY_OF_MONTH))
        out.write(writeIntLe(numRecords))
        out.write(writeIntLe16(headerLen))
        out.write(writeIntLe16(recordLen))
        out.write(ByteArray(20))

        for ((fieldName, fieldLen) in fields) {
            val cleanField = fieldName.uppercase().filter { it.code in 32..126 }.take(10)
            val rawName = cleanField.toByteArray(Charsets.US_ASCII)
            val fNamePadded = ByteArray(11) { i -> if (i < rawName.size) rawName[i] else 0 }
            out.write(fNamePadded)
            out.write('C'.code)
            out.write(ByteArray(4))
            out.write(fieldLen)
            out.write(0)
            out.write(ByteArray(14))
        }
        out.write(0x0D)

        for (row in rows) {
            out.write(0x20)
            for (i in fields.indices) {
                val len = fields[i].second
                val value = row.getOrNull(i).orEmpty()
                val clean = value.map { if (it.code in 32..126) it else '?' }.joinToString("").padEnd(len, ' ').take(len)
                out.write(clean.toByteArray(Charsets.US_ASCII))
            }
        }
        out.write(0x1A)
        return out.toByteArray()
    }

    private fun readIntBe(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 4 > b.size) return 0
        return ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)
    }

    // Bounds-safe primitive readers. A corrupt file can make a computed offset
    // land outside the buffer, and an uncaught ArrayIndexOutOfBoundsException
    // from a background loader takes the activity down with it. Returning a
    // neutral value keeps a single bad record from killing the whole map.

    private fun readIntLe(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 4 > b.size) return 0
        return ((b[o + 3].toInt() and 0xFF) shl 24) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            (b[o].toInt() and 0xFF)
    }

    private fun readIntLe16(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 2 > b.size) return 0
        return ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
    }

    private fun readDoubleLe(b: ByteArray, o: Int): Double {
        if (o < 0 || o + 8 > b.size) return 0.0
        var accum = 0L
        for (i in 7 downTo 0) {
            accum = (accum shl 8) or (b[o + i].toLong() and 0xFFL)
        }
        return Double.fromBits(accum)
    }

    private fun writeIntBe(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun writeIntLe(v: Int): ByteArray = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()
    )

    private fun writeIntLe16(v: Int): ByteArray = byteArrayOf(
        v.toByte(), (v ushr 8).toByte()
    )

    private fun writeDoubleLe(v: Double): ByteArray {
        val bits = v.toBits()
        val b = ByteArray(8)
        for (i in 0..7) {
            b[i] = ((bits ushr (i * 8)) and 0xFFL).toByte()
        }
        return b
    }
}
