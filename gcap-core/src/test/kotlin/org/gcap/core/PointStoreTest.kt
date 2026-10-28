package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PointStoreTest {

    @Test
    fun mobNameIsTwoDigitDayHourMinute() {
        assertEquals("071045", PointStore.defaultName(7, 10, 45))
        assertEquals("312359", PointStore.defaultName(31, 23, 59))
    }

    @Test
    fun mobNamesSortChronologicallyAsText() {
        val names = listOf(
            PointStore.defaultName(7, 9, 5),
            PointStore.defaultName(7, 10, 45),
            PointStore.defaultName(7, 8, 59),
        ).sorted()
        assertEquals(listOf("070859", "070905", "071045"), names)
    }

    @Test
    fun aNewLayerCarriesAllFourSidecars() {
        val layer = PointStore.newLayer("GCALL_ID", null)
        assertEquals(setOf("shp", "shx", "dbf", "prj"), layer.keys)
        assertTrue(layer["prj"]!!.isNotEmpty())
    }

    @Test
    fun aFreshLayerIsRecognisedAsAPlaceholder() {
        val layer = PointStore.newLayer("GCALL_ID", null)
        assertTrue(PointStore.isPlaceholder(PointStore.coords(layer["shp"]!!), emptyList()))
    }

    @Test
    fun appendingReplacesThePlaceholder() {
        val fresh = PointStore.newLayer("GCALL_ID", null)
        val out = PointStore.append(fresh, "GCALL_ID", "071045", 500_000.0, 4_500_000.0)
        val pts = PointStore.coords(out["shp"]!!)
        // the origin placeholder must not survive into real data
        assertEquals(1, pts.size)
        assertEquals(500_000.0, pts[0][0], 1e-6)
        assertEquals(listOf("071045"), PointStore.names(out["dbf"]!!, "GCALL_ID"))
    }

    @Test
    fun severalMobsAccumulateInOrder() {
        var layer = PointStore.newLayer("GCALL_ID", null)
        val times = listOf(8 to 15, 8 to 16, 8 to 17)
        for ((h, m) in times) {
            layer = PointStore.append(layer, "GCALL_ID", PointStore.defaultName(7, h, m),
                500_000.0 + h, 4_500_000.0 + m)
        }
        assertEquals(3, PointStore.coords(layer["shp"]!!).size)
        assertEquals(
            listOf("070815", "070816", "070817"),
            PointStore.names(layer["dbf"]!!, "GCALL_ID"),
        )
    }

    @Test
    fun dbfRowCountMatchesShpRecordCount() {
        var layer = PointStore.newLayer("GCALL_ID", null)
        repeat(4) { i ->
            layer = PointStore.append(layer, "GCALL_ID", "07104$i", 100.0 * i, 200.0 * i)
        }
        val shp = ShpFormat.shpHeader(layer["shp"]!!)!!
        val dbf = ShpFormat.dbfHeader(layer["dbf"]!!)!!
        assertEquals(4, shp.recordCount)
        assertEquals(4, dbf.numRecords)
    }

    @Test
    fun aFieldOtherThanTheDefaultIsHonoured() {
        val layer = PointStore.append(
            PointStore.newLayer("CALL_ID", null), "CALL_ID", "P1", 1.0, 2.0)
        assertEquals(listOf("P1"), PointStore.names(layer["dbf"]!!, "CALL_ID"))
    }

    @Test
    fun crsTextFromTheSourceLayerIsKept() {
        val crs = "PROJTEST-CRS-TEXT"
        val layer = PointStore.append(
            PointStore.newLayer("GCALL_ID", crs), "GCALL_ID", "1", 1.0, 2.0)
        assertEquals(crs, String(layer["prj"]!!))
    }

    @Test
    fun appendingWithoutAnShpIsRefusedRatherThanSilentlyIgnored() {
        var threw = false
        try {
            PointStore.append(mapOf("dbf" to byteArrayOf(1)), "GCALL_ID", "x", 0.0, 0.0)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "a missing .shp should be reported, not ignored")
    }

    @Test
    fun namesOfAnAbsentFieldAreEmptyRatherThanAnError() {
        val layer = PointStore.newLayer("GCALL_ID", null)
        assertEquals(emptyList<String>(), PointStore.names(layer["dbf"]!!, "NOT_A_FIELD"))
    }

    @Test
    fun writtenShpHasAValidHeader() {
        val layer = PointStore.append(PointStore.newLayer("GCALL_ID", null), "GCALL_ID", "071045", 1.0, 2.0)
        val info = ShpFormat.shpHeader(layer["shp"]!!)
        assertNotNull(info)
        assertEquals(ShpFormat.TYPE_POINT, info!!.shapeType)
    }
}
class GoToLookupTest {

    private fun seed(): Map<String, ByteArray> {
        var l = PointStore.newLayer("GCALL_ID", null)
        l = PointStore.append(l, "GCALL_ID", "C1", 10.0, 20.0)
        l = PointStore.append(l, "GCALL_ID", "C2", 30.0, 40.0)
        l = PointStore.append(l, "GCALL_ID", "C3", 50.0, 60.0)
        return l
    }

    @Test
    fun aChosenNameFindsItsOwnRecord() {
        val l = seed()
        val i = PointStore.indexOf(l["dbf"]!!, "GCALL_ID", "C2")
        assertEquals(1, i)
        val c = PointStore.coordAt(l["shp"]!!, i)!!
        assertEquals(30.0, c[0], 1e-9)
        assertEquals(40.0, c[1], 1e-9)
    }

    @Test
    fun recordsAreFoundInAnyOrder() {
        val l = seed()
        assertEquals(2, PointStore.indexOf(l["dbf"]!!, "GCALL_ID", "C3"))
        assertEquals(0, PointStore.indexOf(l["dbf"]!!, "GCALL_ID", "C1"))
    }

    @Test
    fun anUnknownNameIsReportedNotGuessed() {
        assertEquals(-1, PointStore.indexOf(seed()["dbf"]!!, "GCALL_ID", "NOPE"))
        assertEquals(-1, PointStore.indexOf(seed()["dbf"]!!, "MISSING", "C1"))
    }

    @Test
    fun surroundingSpacesDoNotPreventAMatch() {
        // DBF pads names to a fixed width, so the stored text carries trailing blanks
        assertEquals(1, PointStore.indexOf(seed()["dbf"]!!, "GCALL_ID", "  C2 "))
    }

    @Test
    fun anOutOfRangeRecordHasNoCoordinate() {
        assertNull(PointStore.coordAt(seed()["shp"]!!, 99))
    }
}
