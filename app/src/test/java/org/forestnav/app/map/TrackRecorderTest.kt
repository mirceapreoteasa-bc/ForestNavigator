package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRecorderTest {

    private fun rec(
        intervalSec: Int = 10, minDistanceM: Double = 5.0, maxJumpM: Double = 1000.0,
    ) = TrackRecorder(intervalSec, minDistanceM, maxJumpM, Utm.ASSUMED_ZONE)

    private fun feed(r: TrackRecorder, vararg pts: Triple<Double, Double, Long>) {
        for ((e, n, t) in pts) r.addFix(e, n, t)
    }

    @Test
    fun nothingIsRecordedBeforeStart() {
        val r = rec()
        assertFalse(r.addFix(1.0, 1.0, 0))
        assertEquals(0, r.count)
    }

    @Test
    fun theFirstFixIsAlwaysKept() {
        val r = rec()
        r.start()
        assertTrue(r.addFix(500_000.0, 4_500_000.0, 0))
        assertEquals(1, r.count)
    }

    @Test
    fun aFixInsideTheIntervalIsDropped() {
        val r = rec(intervalSec = 10)
        r.start()
        feed(r, Triple(0.0, 0.0, 0L), Triple(100.0, 0.0, 5_000L))
        assertEquals(1, r.count)
        assertEquals(1, r.skipped)
    }

    @Test
    fun aFixPastTheIntervalAndFarEnoughIsKept() {
        val r = rec(intervalSec = 10, minDistanceM = 5.0)
        r.start()
        feed(r, Triple(0.0, 0.0, 0L), Triple(100.0, 0.0, 12_000L))
        assertEquals(2, r.count)
    }

    @Test
    fun standingStillDoesNotFillTheTrackWithDuplicates() {
        val r = rec(intervalSec = 1, minDistanceM = 5.0)
        r.start()
        for (i in 0..9) r.addFix(100.0, 100.0, i * 2000L)
        assertEquals(1, r.count)
        assertEquals(9, r.skipped)
    }

    @Test
    fun aWildJumpIsRejectedRatherThanDrawn() {
        val r = rec(intervalSec = 1, minDistanceM = 5.0, maxJumpM = 1000.0)
        r.start()
        r.addFix(0.0, 0.0, 0)
        assertFalse("a fix 500 km away must not be kept", r.addFix(500_000.0, 0.0, 5000))
        assertEquals(1, r.count)
    }

    @Test
    fun afterARejectedJumpTheNextGoodFixStillWorks() {
        val r = rec(intervalSec = 1, minDistanceM = 5.0, maxJumpM = 1000.0)
        r.start()
        feed(r, Triple(0.0, 0.0, 0L), Triple(500_000.0, 0.0, 5000L))
        assertTrue(r.addFix(300.0, 0.0, 10_000))
        assertEquals(2, r.count)
    }

    @Test
    fun theJumpTestRunsBeforeTheDistanceTest() {
        // a huge move must be judged a glitch, not a long leg that is kept
        val r = rec(intervalSec = 1, minDistanceM = 5.0, maxJumpM = 1000.0)
        r.start()
        r.addFix(0.0, 0.0, 0)
        r.addFix(400_000.0, 0.0, 5000)
        assertEquals(1, r.count)
    }

    @Test
    fun brokenReadingsAreIgnored() {
        val r = rec()
        r.start()
        r.addFix(Double.NaN, 0.0, 0)
        r.addFix(0.0, Double.POSITIVE_INFINITY, 0)
        assertEquals(0, r.count)
        assertEquals(2, r.skipped)
    }

    @Test
    fun aFixGoingBackwardsInTimeIsIgnored() {
        val r = rec(intervalSec = 1)
        r.start()
        r.addFix(0.0, 0.0, 10_000)
        assertFalse(r.addFix(100.0, 0.0, 5_000))
        assertEquals(1, r.count)
    }

    @Test
    fun lengthAddsUpTheLegs() {
        val r = rec(intervalSec = 1, minDistanceM = 1.0)
        r.start()
        feed(r, Triple(0.0, 0.0, 0L), Triple(100.0, 0.0, 2000L), Triple(100.0, 100.0, 4000L))
        assertEquals(200.0, r.lengthM(), 0.001)
    }

    @Test
    fun startingAgainDiscardsThePreviousTrack() {
        val r = rec(intervalSec = 1)
        r.start()
        feed(r, Triple(0.0, 0.0, 0L), Triple(100.0, 0.0, 2000L))
        assertEquals(2, r.count)
        r.start()
        assertEquals(0, r.count)
    }

    @Test
    fun stoppingEndsTheTrack() {
        val r = rec(intervalSec = 1)
        r.start()
        r.addFix(0.0, 0.0, 0)
        r.stop()
        assertFalse(r.recording)
        assertFalse(r.addFix(100.0, 0.0, 5000))
    }

    @Test
    fun gpxIsWellFormedAndHoldsEveryPoint() {
        val r = rec(intervalSec = 1, minDistanceM = 1.0)
        r.start()
        feed(r, Triple(500_000.0, 4_500_000.0, 0L), Triple(500_100.0, 4_500_000.0, 2000L))
        val gpx = String(r.gpx("2026-10-07 1432"))
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("<trkpt"))
        assertEquals(2, Regex("<trkpt").findAll(gpx).count())
        assertTrue(gpx.contains("2026-10-07 1432"))
        assertTrue(gpx.trim().endsWith("</gpx>"))
    }

    @Test
    fun gpxCoordinatesAreRealDegrees() {
        val r = rec(intervalSec = 1, minDistanceM = 1.0)
        r.start()
        r.addFix(500_000.0, 4_500_000.0, 0)
        val gpx = String(r.gpx("t"))
        val lat = Regex("""lat="([-\d.]+)"""").find(gpx)!!.groupValues[1].toDouble()
        val lon = Regex("""lon="([-\d.]+)"""").find(gpx)!!.groupValues[1].toDouble()
        assertTrue("lat $lat is not a degree", lat in -90.0..90.0)
        assertTrue("lon $lon is not a degree", lon in -180.0..180.0)
        // Zone 10N, central meridian 123W
        assertTrue("lon $lon is nowhere near zone 10", lon in -126.0..-120.0)
        assertTrue("lat $lat is out of the survey band", lat in 30.0..60.0)
    }

    @Test
    fun anEmptyTrackStillProducesValidGpx() {
        val r = rec()
        r.start()
        r.stop()
        val gpx = String(r.gpx("empty"))
        assertTrue(gpx.contains("<gpx"))
        assertEquals(0, Regex("<trkpt").findAll(gpx).count())
    }

    @Test
    fun namesWithMarkupAreEscaped() {
        val r = rec()
        r.start()
        r.addFix(0.0, 0.0, 0)
        val gpx = String(r.gpx("a<b>&\"c\""))
        assertTrue(gpx.contains("a&lt;b&gt;&amp;"))
        assertFalse(gpx.contains("a<b>"))
    }
}

/**
 * The clock handed to the recorder has to be one clock.
 *
 * The seed fix taken when recording starts and the fixes that follow come from different
 * sources unless they are made to match: mixing epoch time with elapsed-realtime makes
 * every later fix look older than the first, so a track can never accumulate.
 */
class RecorderClockTest {

    @Test
    fun aFixStampedOnTheSameClockAsTheSeedIsAccepted() {
        val r = TrackRecorder(intervalSec = 10, minDistanceM = 5.0, maxJumpM = 1000.0)
        r.start()
        // seed on the uptime clock, as the activity now does
        assertTrue(r.addFix(500_000.0, 4_500_000.0, 12_345_678L))
        // a fix 12 s later on that same clock is a legitimate next leg
        assertTrue(r.addFix(500_200.0, 4_500_000.0, 12_357_678L))
        assertEquals(2, r.count)
    }

    @Test
    fun aFixStampedOnADifferentClockIsRefused() {
        val r = TrackRecorder(intervalSec = 10, minDistanceM = 5.0, maxJumpM = 1000.0)
        r.start()
        r.addFix(500_000.0, 4_500_000.0, 1_700_000_000_000L) // epoch
        assertFalse(r.addFix(500_200.0, 4_500_000.0, 12_357_678L)) // uptime
        assertEquals(1, r.count)
    }
}

/**
 * GPX timestamps must be real dates even though filtering uses a monotonic clock.
 */
class GpxTimestampTest {

    private fun recorder(wall: Long) = TrackRecorder(
        intervalSec = 1, minDistanceM = 1.0, maxJumpM = 1000.0, zone = Utm.ASSUMED_ZONE,
        wallClock = { wall },
    )

    @Test
    fun gpxCarriesTheRealDateNotTheUptime() {
        val r = recorder(1_760_000_000_000L) // a plausible epoch stamp
        r.start()
        r.addFix(500_000.0, 4_500_000.0, 12_345L)   // monotonic
        r.addFix(500_200.0, 4_500_000.0, 14_345L)
        val gpx = String(r.gpx("t"))
        assertTrue("must not be 1970: $gpx", !gpx.contains("1970-01-01"))
        assertTrue("expected the supplied date, got $gpx",
            gpx.contains("2025-10-09") || gpx.contains("2025-"))
    }

    @Test
    fun aMonotonicStampNeverLeaksIntoTheGpx() {
        val r = recorder(1_760_000_000_000L)
        r.start()
        r.addFix(500_000.0, 4_500_000.0, 99L)
        assertFalse(String(r.gpx("t")).contains("1970"))
    }
}
