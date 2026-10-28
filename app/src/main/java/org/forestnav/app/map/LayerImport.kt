package org.forestnav.app.map

import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * Pure helpers behind "Add layer": grouping a multi-file selection into
 * shapefile sets, naming the sidecars a .shp travels with, and deriving the
 * sibling document URI of a picked file so a single pick can still pull
 * .dbf/.shx/.prj out of the same folder. Android-free — JVM unit tested.
 */
object LayerImport {

    private val SIDECAR_EXTS = listOf("shx", "dbf", "prj", "cpg")

    fun stemOf(name: String): String = name.substringBeforeLast('.')

    /**
     * Trimmed layer stem for a new layer, or null when blank, "." / "..", or
     * carrying path-illegal characters. Collision with existing stems is
     * checked by the caller (it needs the folder listing).
     */
    fun cleanLayerName(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s == "." || s == "..") return null
        if (s.any { it in "/\\?%*:|\"<>" || it.code < 32 }) return null
        // Lowercased: the field convention is one lower-case stem per layer, so
        // every .shp/.shx/.dbf/.prj/.cpg shares it. Android storage is
        // case-insensitive, so a mixed-case stem would silently collide with an
        // existing lower-case file instead of creating a second one.
        return s.lowercase()
    }

    /**
     * dBASE field name (uppercased) or null: 1–10 chars, letters/digits/_
     * starting with a letter. Blank input yields null (caller treats field 2
     * blank as "single-field table").
     */
    fun cleanDbfField(raw: String): String? {
        val s = raw.trim().uppercase()
        if (s.isEmpty() || s.length > 10) return null
        if (!s[0].isLetter()) return null
        if (s.any { !it.isLetterOrDigit() && it != '_' }) return null
        return s
    }

    fun isShp(name: String): Boolean = name.endsWith(".shp", true)

    fun isSidecar(name: String): Boolean = SIDECAR_EXTS.any { name.endsWith(".$it", true) }

    /**
     * Names to try per sidecar extension of `shpName`, lower case first
     * (`Ground_Call.shp` -> `Ground_Call.shx`, `Ground_Call.SHX`, …).
     */
    fun sidecarCandidates(shpName: String): List<List<String>> {
        val stem = stemOf(shpName)
        return SIDECAR_EXTS.map { ext -> listOf("$stem.$ext", "$stem.${ext.uppercase()}") }
    }

    /**
     * A multi-selection grouped by layer: every group whose stem has a `.shp`
     * (sidecars without a matching `.shp` are dropped), `.shp` first.
     */
    fun groupsByStem(names: List<String>): List<List<String>> {
        val byStem = LinkedHashMap<String, MutableList<String>>()
        for (n in names) byStem.getOrPut(stemOf(n).lowercase()) { mutableListOf() }.add(n)
        return byStem.values
            .filter { g -> g.any { isShp(it) } }
            .map { g -> g.sortedByDescending { isShp(it) } }
    }

    /** Sidecar names in `names` matching `shpName`'s stem, by extension. */
    fun sidecarsIn(names: List<String>, shpName: String): Map<String, String> {
        val stem = stemOf(shpName).lowercase()
        val out = LinkedHashMap<String, String>()
        for (n in names) {
            if (isShp(n)) continue
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext in SIDECAR_EXTS && stemOf(n).lowercase() == stem) out[ext] = n
        }
        return out
    }

    /**
     * Decode a .prj sidecar regardless of how Windows saved it: UTF-8 (with
     * or without BOM) or UTF-16 (either endianity, with or without BOM). Raw
     * UTF-16 decoded as bytes litters the text with NULs that defeat every
     * `contains` check in CRS classification, silently skipping the layer.
     */
    fun decodePrj(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                .replace("\u0000", "").trimStart('\uFEFF').ifBlank { null }
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                .replace("\u0000", "").trimStart('\uFEFF').ifBlank { null }
        }
        var s = String(bytes, Charsets.UTF_8)
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        s = s.replace("\u0000", "")
        return s.ifBlank { null }
    }

    /**
     * Document id behind a picked `content://…/document/<id>` URI, decoded
     * (`primary:Docs/a.shp`), or null when the URI is not path-shaped (media
     * store, opaque ids) — those expose no folder to look siblings up in.
     */
    fun documentDocId(uri: String): String? {
        val u = try { URI(uri) } catch (e: Exception) { return null }
        if (!"content".equals(u.scheme, true)) return null
        val segs = u.rawPath?.split('/')?.filter { it.isNotEmpty() } ?: return null
        if (segs.size < 2 || segs[segs.size - 2] != "document") return null
        return percentDecode(segs.last())
    }

    /** Document id of `siblingName` next to `picked`, or null (see above). */
    fun siblingDocId(picked: String, siblingName: String): String? {
        val docId = documentDocId(picked) ?: return null
        val cut = docId.lastIndexOf('/')
        if (cut <= 0) return null
        return docId.substring(0, cut + 1) + siblingName
    }

    /**
     * Plain document URI of `siblingName` next to the picked file, e.g.
     * `…/document/primary%3ADocs%2Fa.shp` + `a.dbf` → `…/document/primary%3ADocs%2Fa.dbf`.
     * Having the URI does not grant read access — see [siblingDocId] for the
     * tree-grant form the app prefers when it holds one.
     */
    fun siblingDocUri(picked: String, siblingName: String): String? {
        val u = try { URI(picked) } catch (e: Exception) { return null }
        val docId = siblingDocId(picked, siblingName) ?: return null
        val authority = u.authority ?: return null
        return "content://$authority/document/${percentEncode(docId)}"
    }

    /** Percent-encode a document id the way DocumentsContract expects (Uri.encode). */
    private fun percentEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.!~*'()") append(c)
            else {
                append('%')
                append("%02X".format(b.toInt() and 0xFF))
            }
        }
    }

    /** Inverse of [percentEncode]; literal `+` is kept (never a space in ids). */
    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = try { s.substring(i + 1, i + 3).toInt(16) } catch (e: Exception) { -1 }
                if (hex in 0..255) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            s.substring(i, i + 1).toByteArray(Charsets.UTF_8).forEach { out.write(it.toInt()) }
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
