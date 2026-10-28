package org.forestnav.app.map

import java.io.ByteArrayInputStream
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads cells out of an OpenDocument spreadsheet.
 *
 * The project's settings and layer settings live in `.ods` files the user edits on a
 * desktop, so they are the design of record and the app has to read them as they are.
 *
 * An `.ods` file is a zip holding `content.xml`. Cell values arrive split across any
 * number of `<text:span>` runs - a spreadsheet re-saves a value whenever formatting
 * changes, so naive tag-stripping loses or truncates text. Rows and columns are also
 * repeated rather than written out, so `number-columns-repeated` has to be honoured or
 * every address after a blank cell shifts.
 *
 * Deliberately read-only. Writing one means rebuilding the whole zip, which is a
 * separate piece of work.
 */
class OdsTable private constructor(
    /** Cell text by column index, zero-based, for each row. Missing cells are absent. */
    private val rows: List<Map<Int, String>>,
    val sheetName: String,
) {
    operator fun get(row: Int, col: Int): String? = rows.getOrNull(row)?.get(col)

    /** Value at an A1-style address such as `A17`. Row and column are one-based. */
    fun cell(address: String): String? {
        var i = 0
        while (i < address.length && address[i].isLetter()) i++
        if (i == 0 || i == address.length) return null
        val col = columnIndex(address.substring(0, i))
        val row = address.substring(i).toIntOrNull() ?: return null
        return get(row - 1, col)
    }

    /** First non-blank cell in a row, one-based row. Used for label/value pairs. */
    fun firstInRow(row: Int): String? =
        rows.getOrNull(row - 1)?.entries?.minByOrNull { it.key }?.value

    fun lastModifiedRowWithText(prefix: String): Int? =
        rows.indices.firstOrNull { rows[it].values.any { it.contains(prefix, true) } }

    companion object {
        private fun columnIndex(letters: String): Int {
            var n = 0
            for (ch in letters.uppercase()) {
                if (ch !in 'A'..'Z') return -1
                n = n * 26 + (ch - 'A' + 1)
            }
            return n - 1
        }

        /** Parse [bytes], returning the first sheet, or null when it is not an ODS. */
        fun parse(bytes: ByteArray): OdsTable? = runCatching { parseOrThrow(bytes) }.getOrNull()

        private fun parseOrThrow(bytes: ByteArray): OdsTable {
            val xml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry.name != "content.xml") entry = zip.nextEntry
                zip.readBytes()
            }
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // the file is user-supplied; no external entities, no DTD
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }.newDocumentBuilder()
            val doc: Document = dbf.parse(ByteArrayInputStream(xml))
            val tables = doc.getElementsByTagNameNS(TABLE_NS, "table")
            val table = tables.item(0) as? Element ?: return OdsTable(emptyList(), "")
            val name = table.attributes?.getNamedItemNS(TABLE_NS, "name")?.nodeValue ?: ""
            val rows = ArrayList<Map<Int, String>>()
            val rowEls = table.getElementsByTagNameNS(TABLE_NS, "table-row")
            var r = 0
            var i = 0
            while (i < rowEls.length) {
                val rowEl = rowEls.item(i) as? Element
                if (rowEl == null) {
                    i++
                    continue
                }
                val rep = rowEl.attributes?.getNamedItemNS(TABLE_NS, "number-rows-repeated")
                    ?.nodeValue?.toIntOrNull()?.coerceIn(1, 1024) ?: 1
                val cells = LinkedHashMap<Int, String>()
                val cellEls = rowEl.getElementsByTagNameNS(TABLE_NS, "table-cell")
                var c = 0
                var j = 0
                while (j < cellEls.length) {
                    val cellEl = cellEls.item(j) as? Element
                    if (cellEl != null) {
                        val crep = cellEl.attributes?.getNamedItemNS(TABLE_NS, "number-columns-repeated")
                            ?.nodeValue?.toIntOrNull()?.coerceIn(1, 1024) ?: 1
                        val text = collectText(cellEl)
                        if (text.isNotBlank()) {
                            repeat(crep) { k -> cells[c + k] = text }
                        }
                        c += crep
                    }
                    j++
                }
                // Empty rows are kept. They are real rows in the sheet, and dropping
                // them shifts every later address up by one.
                repeat(rep) { rows.add(cells) }
                r += rep
                i++
            }
            return OdsTable(rows, name)
        }

        /**
         * All text under [el], concatenated.
         *
         * A value split across `<text:span>` runs has to rejoin or it comes out
         * truncated or missing, which is why this walks every descendant rather than
         * taking the first text node.
         */
        private fun collectText(el: Element): String {
            val sb = StringBuilder()
            collect(el, sb)
            return sb.toString().trim()
        }

        private fun collect(node: Node, sb: StringBuilder) {
            if (node.nodeType == Node.TEXT_NODE) {
                sb.append(node.nodeValue)
                return
            }
            var c = node.firstChild
            while (c != null) {
                collect(c, sb)
                c = c.nextSibling
            }
        }

        private const val TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        private const val OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    }
}