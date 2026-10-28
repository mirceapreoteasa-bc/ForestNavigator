package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtmZoneTest {

    private fun zoneOf(prj: String?) = Utm.classify(prj).utmZone
    private fun kindOf(prj: String?) = Utm.classify(prj).kind

    @Test
    fun `recognises the classic esri spelling in any zone`() {
        for (z in listOf(9, 10, 11, 33, 34)) {
            val r = Utm.classify("""PROJCS["NAD_1983_UTM_Zone_${z}N",GEOGCS["GCS_North_American_1983"]]""")
            assertEquals(Utm.Crs.UTM_METERS, r.kind)
            assertEquals(z, r.utmZone)
        }
    }

    @Test
    fun `recognises the wkt spelling`() {
        for (z in listOf(10, 33)) {
            val r = Utm.classify("""PROJCS["WGS_1984_UTM_Zone_${z}N"]""")
            assertTrue("wkt zone $z classified as ${r.utmZone}", r.utmZone == z)
        }
        val spaced = Utm.classify("""PROJCS["NAD_1983 / UTM zone 33N"]""")
        assertEquals(33, spaced.utmZone)
    }

    @Test
    fun `recognises epsg codes for north and south zones`() {
        assertEquals(10, zoneOf("""PROJCS["WGS_1984_UTM_Zone_10N",AUTHORITY["EPSG","32610"]]"""))
        assertEquals(33, zoneOf("""AUTHORITY["EPSG","32633"]"""))
        assertEquals(11, zoneOf("""AUTHORITY["EPSG","32711"]"""))
        assertNull(Utm.zoneFromEpsg(4326))
        assertNull(Utm.zoneFromEpsg(3857))
    }

    @Test
    fun `falls back to the central meridian when the name says nothing`() {
        val prj = """PROJCS["Custom_TM",GEOGCS["GCS_WGS_1984"],
            PROJECTION["Transverse_Mercator"],UNIT["Meter",1.0],
            PARAMETER["Central_Meridian",15.0]]"""
        val r = Utm.classify(prj)
        assertEquals(Utm.Crs.UTM_METERS, r.kind)
        assertTrue("central meridian 15 gave zone ${r.utmZone}", r.utmZone == 33)
    }

    @Test
    fun `geographic layers carry no zone`() {
        val r = Utm.classify("""GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984"]]""")
        assertEquals(Utm.Crs.WGS84_DEGREES, r.kind)
        assertEquals(0, r.utmZone)
    }

    @Test
    fun `albers is still recognised`() {
        val r = Utm.classify("""PROJCS["NAD_1983_BC_Albers",PROJECTION["Albers_Conic_Equal_Area"]]""")
        assertEquals(Utm.Crs.ALBERS, r.kind)
    }

    @Test
    fun `a missing prj keeps the historical assumption`() {
        assertEquals(Utm.Crs.UTM_METERS, kindOf(null))
        assertEquals(Utm.ASSUMED_ZONE, zoneOf(null))
        assertEquals(Utm.ASSUMED_ZONE, zoneOf("   "))
    }

    @Test
    fun `classifyCrs wrapper still works for kind-only callers`() {
        assertEquals(Utm.Crs.UTM_METERS, Utm.classifyCrs("""PROJCS["NAD_1983_UTM_Zone_11N"]"""))
        assertEquals(Utm.Crs.WGS84_DEGREES, Utm.classifyCrs("""GEOGCS["GCS_WGS_1984"]"""))
        assertEquals(Utm.Crs.OTHER, Utm.classifyCrs("""PROJCS["Lambert_Conformal_Conic_2SP"]"""))
    }

    @Test
    fun `a south hemisphere layer keeps its zone number`() {
        // zone numbers run 1..60 in both hemispheres; the zone is what matters for
        // reprojection, the hemisphere only changes the false northing
        val r = Utm.classify("""PROJCS["WGS_1984_UTM_Zone_33S"]""")
        assertEquals(33, r.utmZone)
        assertTrue("expected UTM_METERS", r.kind == Utm.Crs.UTM_METERS)
    }

    @Test
    fun `the project layers still classify as before`() {
        // regression: the real FOR_NAV sidecars are NAD83 zone 10
        val prj = """PROJCS["NAD_1983_UTM_Zone_10N",GEOGCS["GCS_North_American_1983",DATUM["D_North_American_1983",SPHEROID["GRS_1980",6378137.0,298.257222101]]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Transverse_Mercator"],PARAMETER["False_Easting",500000.0],PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",-123.0],PARAMETER["Scale_Factor",0.9996],PARAMETER["Latitude_Of_Origin",0.0],UNIT["Meter",1.0]]"""
        val r = Utm.classify(prj)
        assertEquals(Utm.Crs.UTM_METERS, r.kind)
        assertEquals(10, r.utmZone)
    }
}
