package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HeadingEstimatorTest {

    /** Feed a straight run of `count` fixes spaced `stepM` apart on the given bearing. */
    private fun run(
        est: HeadingEstimator,
        count: Int,
        stepM: Double,
        bearingDeg: Double,
        startE: Double = 0.0,
        startN: Double = 0.0,
        t0: Long = 0L,
        dtMs: Long = 1000L,
    ): Long {
        val b = Math.toRadians(bearingDeg)
        var t = t0
        for (i in 0 until count) {
            est.add(startE + i * stepM * sin(b), startN + i * stepM * cos(b), t)
            t += dtMs
        }
        return t
    }

    @Test
    fun `recovers the cardinal courses`() {
        for (bearing in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
            val est = HeadingEstimator(windowMetres = 100.0)
            val now = run(est, count = 40, stepM = 10.0, bearingDeg = bearing)
            val s = est.estimate(now)
            assertTrue(s.valid, "no estimate for bearing $bearing")
            var diff = Math.abs(s.bearingDeg - bearing) % 360.0
            if (diff > 180) diff = 360 - diff
            assertTrue(diff < 1.0, "expected ~$bearing got ${s.bearingDeg}")
            assertTrue(s.moving, "should read as moving for bearing $bearing")
            assertTrue(s.quality > 0.99, "straight run should be high quality, got ${s.quality}")
        }
    }

    /** The crew reported the arrow taking minutes to turn. The window is bounded in
     *  time as well as in metres, so the catch-up after a turn is bounded in seconds
     *  at walking pace rather than by how far 100 m takes to walk. */
    @Test
    fun `the arrow settles promptly after a turn at walking pace`() {
        val est = HeadingEstimator()
        var e = 0.0
        var n = 0.0
        var t = 0L
        // 2 s fixes, 1.2 m/s: 2.4 m per fix
        repeat(40) { est.add(e, n, t); e += 2.4; t += 2000 }
        val before = est.estimate(t).bearingDeg
        // bearing is clockwise from north, so due east is 90
        assertTrue(Math.abs(before - 90.0) < 5.0, "should be heading east before the turn, got $before")

        var metres = 0.0
        var settled = false
        var seconds = 0L
        for (i in 1..200) {
            est.add(e, n, t)
            n += 2.4
            t += 2000
            metres += 2.4
            val hs = est.estimate(t)
            var diff = Math.abs(hs.bearingDeg) % 360.0
            if (diff > 180) diff = 360 - diff
            if (hs.moving && diff < 20.0) { settled = true; seconds = (metres / 1.2 * 1000).toLong(); break }
        }
        assertTrue(settled, "the arrow never caught up with the new course")
        assertTrue(seconds < 25_000, "arrow took ${seconds}ms to settle after a turn, want under 25s")
    }

    /** A stopped receiver is not a slow one: with a short window the speed must come
     *  from the window average, or receiver noise alone makes the arrow reappear. */
    @Test
    fun `noise does not make a stationary phone read as travelling`() {
        val est = HeadingEstimator()
        val rnd = java.util.Random(7)
        var t = 0L
        repeat(60) {
            est.add(rnd.nextGaussian() * 3.0, rnd.nextGaussian() * 3.0, t)
            t += 2000
        }
        val s = est.estimate(t)
        assertFalse(s.moving, "pure scatter should not read as moving (speed ${s.speedMps})")
    }

    @Test
    fun `stationary drift does not report moving`() {
        val est = HeadingEstimator(windowMetres = 100.0, minSpeedMps = 1.0)
        // receiver noise only: a couple of metres of wander, one fix per second
        var t = 0L
        val jitter = listOf(0.0, 1.4, -0.9, 1.1, -1.3, 0.6, -0.4, 1.2)
        for (i in 0 until 30) {
            est.add(jitter[i % jitter.size], jitter[(i + 3) % jitter.size], t)
            t += 1000
        }
        val s = est.estimate(t)
        assertFalse(s.moving, "GPS noise must not be reported as movement")
    }

    @Test
    fun `a short walk below the distance threshold is not yet moving`() {
        val est = HeadingEstimator(windowMetres = 100.0, minSpeedMps = 1.0)
        val now = run(est, count = 8, stepM = 1.0, bearingDeg = 90.0) // only 8 m covered
        val s = est.estimate(now)
        assertTrue(s.valid, "a heading is still computable")
        assertFalse(s.moving, "8 m of travel is below the 25% of a 100 m window gate")
    }

    @Test
    fun `weightless noise gives low quality and poor straightness`() {
        val est = HeadingEstimator(windowMetres = 200.0, minSpeedMps = 1.0)
        var t = 0L
        val rnd = java.util.Random(42)
        // wander inside a small box, repeatedly reversing
        for (i in 0 until 60) {
            est.add(rnd.nextGaussian() * 30.0, rnd.nextGaussian() * 30.0, t)
            t += 1000
        }
        val s = est.estimate(t)
        assertTrue(s.valid)
        assertTrue(s.straightness < 0.5, "doubling-back track should score low straightness, got ${s.straightness}")
    }

    @Test
    fun `stale fixes are dropped so a stopped receiver cannot keep a stale heading`() {
        val est = HeadingEstimator(windowMetres = 100.0, maxFixAgeMs = 10_000L)
        var t = run(est, count = 20, stepM = 10.0, bearingDeg = 90.0) // 200 m of travel
        val fresh = est.estimate(t)
        assertTrue(fresh.moving)
        // no new fixes for a minute
        val later = est.estimate(t + 60_000)
        assertFalse(later.moving, "without fresh fixes we must stop claiming movement")
    }

    @Test
    fun `no fixes at all yields the invalid state`() {
        val s = HeadingEstimator().estimate(0L)
        assertFalse(s.valid)
        assertTrue(s.bearingDeg.isNaN())
    }

    @Test
    fun `perpendicular noise does not drag the heading`() {
        // the point of a least-squares fit: add cross-track scatter and check the
        // recovered bearing barely moves
        val clean = HeadingEstimator(windowMetres = 200.0)
        val nowC = run(clean, count = 40, stepM = 10.0, bearingDeg = 30.0)
        val b0 = clean.estimate(nowC).bearingDeg

        val noisy = HeadingEstimator(windowMetres = 200.0)
        val b = Math.toRadians(30.0)
        val rnd = java.util.Random(7)
        var t = 0L
        for (i in 0 until 40) {
            val along = i * 10.0
            noisy.add(
                along * sin(b) + rnd.nextGaussian() * 4.0,
                along * cos(b) + rnd.nextGaussian() * 4.0,
                t,
            )
            t += 1000
        }
        val b1 = noisy.estimate(t).bearingDeg
        var diff = Math.abs(b0 - b1) % 360.0
        if (diff > 180) diff = 360 - diff
        assertTrue(diff < 5.0, "4 m of cross-track scatter moved heading by $diff deg")
    }

    /** Age weighting is what shortens the catch-up: the newest fixes dominate a turn. */
    @Test
    fun `age weighting settles the arrow faster after a turn`() {
        // walking pace, 2 s fixes: east for a while, then north
        fun settleSeconds(est: HeadingEstimator): Long {
            var e = 0.0
            var n = 0.0
            var t = 0L
            repeat(40) { est.add(e, n, t); e += 2.4; t += 2000 }
            var metres = 0.0
            for (i in 1..400) {
                est.add(e, n, t)
                n += 2.4
                t += 2000
                metres += 2.4
                val hs = est.estimate(t)
                var diff = Math.abs(hs.bearingDeg) % 360.0
                if (diff > 180) diff = 360 - diff
                if (hs.moving && diff < 20.0) return (metres / 1.2 * 1000).toLong()
            }
            return Long.MAX_VALUE
        }
        val weighted = settleSeconds(HeadingEstimator())
        val plain = settleSeconds(HeadingEstimator(recencyHalfLifeS = 0.0))
        assertTrue(weighted < plain, "weighting should settle sooner: weighted=$weighted plain=$plain")
        assertTrue(weighted < 15_000, "weighted arrow took ${weighted}ms to settle, want under 15s")
    }

    /** Below the movement speed the arrow may still have a course worth showing. */
    @Test
    fun `a slow steady walk reports a course even below the moving gate`() {
        val est = HeadingEstimator()
        var e = 0.0
        var t = 0L
        repeat(40) { est.add(e, 0.0, t); e += 1.0; t += 2000 } // 0.5 m/s, due east
        val s = est.estimate(t)
        assertTrue(s.valid)
        assertFalse(s.moving, "0.5 m/s is below the movement gate")
        assertTrue(s.hasCourse, "a straight slow walk should still point the arrow")
    }

    @Test
    fun `scatter does not report a course`() {
        val est = HeadingEstimator()
        val rnd = java.util.Random(11)
        var t = 0L
        repeat(60) {
            est.add(rnd.nextGaussian() * 3.0, rnd.nextGaussian() * 3.0, t)
            t += 2000
        }
        assertFalse(est.estimate(t).hasCourse, "GPS scatter must not produce a travel arrow")
    }
}

class OrientationControllerTest {

    private val moving = HeadingState(bearingDeg = 75.0, moving = true, valid = true, speedMps = 12.0)
    private val still = HeadingState.INVALID

    @Test
    fun `course up follows the direction of travel`() {
        val c = OrientationController(OrientationMode.COURSE_UP, maxDegPerSecond = 1e9)
        assertEquals(75.0, c.bearingFor(0L, moving), 1e-6)
        assertEquals(OrientationReason.COURSE, c.reason)
    }

    @Test
    fun `stationary holds the last heading for thirty seconds then goes north`() {
        val c = OrientationController(OrientationMode.COURSE_UP, maxDegPerSecond = 1e9)
        c.bearingFor(0L, moving)
        assertEquals(75.0, c.appliedBearing, 1e-6)

        // 10 s stationary: still holding
        assertEquals(75.0, c.bearingFor(10_000L, still, stillForMs = 10_000L), 1e-6)
        assertEquals(OrientationReason.HOLDING, c.reason)

        // 45 s stationary: past the hold window
        assertEquals(0.0, c.bearingFor(45_000L, still, stillForMs = 45_000L), 1e-6)
        assertEquals(OrientationReason.NORTH, c.reason)
    }

    @Test
    fun `a hundred and twenty five seconds stationary is still north`() {
        val c = OrientationController(OrientationMode.COURSE_UP, maxDegPerSecond = 1e9)
        c.bearingFor(0L, moving)
        assertEquals(0.0, c.bearingFor(125_000L, still, stillForMs = 125_000L), 1e-6)
    }

    @Test
    fun `north up ignores everything`() {
        val c = OrientationController(OrientationMode.NORTH_UP, maxDegPerSecond = 1e9)
        assertEquals(0.0, c.bearingFor(0L, moving), 1e-6)
        assertEquals(0.0, c.bearingFor(5_000L, moving), 1e-6)
        assertEquals(OrientationReason.NORTH, c.reason)
    }

    @Test
    fun `waypoint up uses the waypoint bearing`() {
        val c = OrientationController(OrientationMode.WAYPOINT_UP, maxDegPerSecond = 1e9)
        assertEquals(210.0, c.bearingFor(0L, moving, waypointBearing = 210.0), 1e-6)
        assertEquals(OrientationReason.WAYPOINT, c.reason)
    }

    @Test
    fun `waypoint up falls back to north when there is no waypoint`() {
        val c = OrientationController(OrientationMode.WAYPOINT_UP, maxDegPerSecond = 1e9)
        assertEquals(0.0, c.bearingFor(0L, moving, waypointBearing = null), 1e-6)
    }

    @Test
    fun `bearing changes are rate limited`() {
        val c = OrientationController(OrientationMode.COURSE_UP, maxDegPerSecond = 90.0)
        c.bearingFor(0L, moving)                    // 75 deg applied
        // half a second later the pilot turns to 225: only 45 deg may be applied
        val b = c.bearingFor(500L, moving.copy(bearingDeg = 225.0))
        assertTrue(Math.abs(b - 120.0) < 1e-6, "expected 75+45=120, got $b")
    }

    @Test
    fun `slew takes the short way round the compass`() {
        val c = OrientationController(OrientationMode.COURSE_UP, maxDegPerSecond = 1e9)
        c.bearingFor(0L, moving.copy(bearingDeg = 355.0))
        // asking for 5 deg should move forward by 10, not back by 350
        val b = c.bearingFor(1_000L, moving.copy(bearingDeg = 5.0))
        assertTrue(Math.abs(b - 5.0) < 1e-6, "got $b")
    }

    @Test
    fun `reset clears the held heading`() {
        val c = OrientationController(OrientationMode.COURSE_UP, maxDegPerSecond = 1e9)
        c.bearingFor(0L, moving)
        c.reset()
        assertEquals(0.0, c.appliedBearing, 1e-9)
        assertEquals(OrientationReason.NORTH, c.reason)
    }
}
