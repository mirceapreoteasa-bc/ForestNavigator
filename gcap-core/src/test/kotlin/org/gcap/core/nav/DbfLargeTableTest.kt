package org.gcap.core.nav

import org.gcap.core.ShpFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression test for a record-count truncation that silently dropped the tail of any
 * dBASE table larger than 65,535 rows. The project has layers above that size, and the
 * symptom was a network layer written with 111,178 features reading back as 45,642.
 */
class DbfLargeTableTest {

    @Test
    fun `a table larger than 65535 rows keeps every record`() {
        val n = 70000
        val fields = listOf(
            NetworkWriter.Field("ID", 'N', 9),
            NetworkWriter.Field("NAME", 'C', 8),
        )
        val rows = (0 until n).map { listOf(it.toString(), "n$it") }
        val dbf = NetworkWriter.buildDbf(fields, rows)

        val hdr = ShpFormat.dbfHeader(dbf)
        assertEquals(n, hdr.numRecords, "the header must report the full record count")

        val back = ShpFormat.readDbfRows(dbf)
        assertEquals(n, back.size, "every row must read back")
        assertEquals("0", back.first().values[0].trim())
        assertEquals((n - 1).toString(), back.last().values[0].trim())
        assertEquals("n${n - 1}", back.last().values[1].trim())
    }

    @Test
    fun `small tables are unaffected`() {
        val fields = listOf(NetworkWriter.Field("A", 'C', 4))
        val rows = (0 until 10).map { listOf("v$it") }
        val dbf = NetworkWriter.buildDbf(fields, rows)
        assertEquals(10, ShpFormat.dbfHeader(dbf).numRecords)
        assertEquals(10, ShpFormat.readDbfRows(dbf).size)
    }
}
