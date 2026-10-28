package org.gcap.core.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedZoomTest {

    private fun settle(z: SpeedZoom, speedKph: Double, startMs: Long, ms: Long = 20000L): Int {
        var t = startMs
        var last = z.currentScale
        while (t < startMs + ms) {
            last = z.update(speedKph, t)
            t += 250
        }
        return last
    }

    @Test
    fun `each speed band maps to the scale the user specified`() {
        val cases = listOf(
            250.0 to 50000,
            199.0 to 20000,
            150.0 to 20000,
            100.0 to 20000,
            99.0 to 15000,
            80.0 to 15000,
            70.0 to 15000,
            69.0 to 10000,
            55.0 to 10000,
            50.0 to 10000,
            49.0 to 7500,
            45.0 to 7500,
            40.0 to 7500,
            39.0 to 5000,
            35.0 to 5000,
            30.0 to 5000,
            29.0 to 3000,
            20.0 to 3000,
            15.0 to 3000,
            14.0 to 2000,
            8.0 to 2000,
            5.0 to 2000,
            4.0 to 1000,
            0.0 to 1000,
        )
        for ((speed, expected) in cases) {
            val z = SpeedZoom(dwellMs = 0L, hysteresisKph = 0.0, maxStepsPerUpdate = 9)
            z.reset(expected)
            var got = z.currentScale
            var t = 0L
            while (t < 4000) { got = z.update(speed, t); t += 100 }
            assertEquals(expected, got, "speed $speed km/h should settle at 1:$expected")
        }
    }

    @Test
    fun `the app opens at the helicopter transit scale`() {
        assertEquals(20000, SpeedZoom.DEFAULT_SCALE)
        assertEquals(20000, SpeedZoom().currentScale)
    }

    @Test
    fun `noise on a boundary does not flip the scale`() {
        // parked at 49.8 / 50.2 km/h, which straddles the 50 km/h boundary
        val z = SpeedZoom(dwellMs = 1500L, hysteresisKph = 8.0)
        z.reset(10000)
        var t = 0L
        while (t < 60000) {
            val speed = if ((t / 1000L) % 2L == 0L) 49.8 else 50.2
            z.update(speed, t)
            t += 100
        }
        assertEquals(10000, z.currentScale, "wobbling across the boundary must not change scale")
    }

    @Test
    fun `a momentary spike is ignored but a sustained change is adopted`() {
        val z = SpeedZoom(dwellMs = 1500L, hysteresisKph = 8.0)
        z.reset(20000)
        var t = 0L
        // well under the dwell time
        while (t < 800) { z.update(60.0, t); t += 100 }
        assertEquals(20000, z.currentScale, "a spike shorter than the dwell must not change scale")
        // sustained; 60 km/h is the 69-50 band
        while (t < 30000) { z.update(60.0, t); t += 100 }
        assertEquals(10000, z.currentScale, "sustained 60 km/h is the 1:10,000 band")
    }

    @Test
    fun `scale changes are damped rather than jumping straight to the target`() {
        val z = SpeedZoom(dwellMs = 0L, hysteresisKph = 0.0, maxStepsPerUpdate = 1)
        z.reset(20000)
        // the first call only arms the candidate band
        assertEquals(20000, z.update(400.0, 0L), "first sighting only arms the target")
        // 1:50,000 is adjacent to 1:20,000 on the ladder, so that hop is a single rung
        assertEquals(50000, z.update(400.0, 100L), "the next update takes it")
        // the long descent to walking pace is what damping is for: one rung per call
        val steps = ArrayList<Int>()
        var t = 100L
        while (t < 5000) { steps.add(z.update(2.0, t)); t += 100 }
        assertEquals(20000, steps[1], "one rung down from 1:50,000 is 1:20,000")
        assertEquals(15000, steps[2], "then 1:15,000")
        assertEquals(10000, steps[3], "then 1:10,000")
        assertEquals(1000, steps.last(), "and it does arrive at walking scale eventually")
        // the defining property: never more than one rung of the ladder per update
        val ladder = SpeedZoom.LADDER
        var previous = 50000
        for (s in steps) {
            val jump = kotlin.math.abs(ladder.indexOf(previous) - ladder.indexOf(s))
            assertTrue(jump <= 1, "scale jumped $jump rungs in one update: $previous -> $s")
            previous = s
        }
    }

    @Test
    fun `slowing down is treated symmetrically to speeding up`() {
        val z = SpeedZoom(dwellMs = 0L, hysteresisKph = 0.0, maxStepsPerUpdate = 9)
        z.reset(50000)
        var t = 0L
        var got = 50000
        while (t < 5000) { got = z.update(2.0, t); t += 100 }
        assertEquals(1000, got, "walking pace should end at the finest band")
    }

    @Test
    fun `a non finite speed is treated as stopped`() {
        val z = SpeedZoom(dwellMs = 0L, hysteresisKph = 0.0, maxStepsPerUpdate = 9)
        z.reset(50000)
        var t = 0L
        var got = 50000
        while (t < 5000) { got = z.update(Double.NaN, t); t += 100 }
        assertEquals(1000, got)
    }

    @Test
    fun `reset returns to a chosen scale`() {
        val z = SpeedZoom()
        z.reset(7500)
        assertEquals(7500, z.currentScale)
    }

    @Test
    fun `the published table matches the implemented bands`() {
        val z = SpeedZoom(dwellMs = 0L, hysteresisKph = 0.0, maxStepsPerUpdate = 9)
        for ((label, scale, lower) in SpeedZoom.TABLE) {
            val probe = lower + 1.0
            z.reset(scale)
            var got = scale
            var t = 0L
            while (t < 2000) { got = z.update(probe, t); t += 100 }
            assertEquals(scale, got, "band '$label' should report 1:$scale")
        }
    }

    @Test
    fun `every band stays inside the configured scale list`() {
        for ((_, scale, _) in SpeedZoom.TABLE) {
            assertTrue(
                scale in listOf(500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 7500,
                    10000, 15000, 20000, 25000, 40000, 50000, 75000, 100000, 150000,
                    200000, 250000, 400000, 500000, 750000, 1000000, 1500000, 2000000,
                    2500000, 3000000, 4000000),
                "1:$scale is not an available scale step",
            )
        }
    }
}
