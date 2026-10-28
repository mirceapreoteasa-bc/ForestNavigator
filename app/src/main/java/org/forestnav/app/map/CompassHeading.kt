package org.forestnav.app.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Magnetic heading from the device compass, for "Map up: compass".
 *
 * Course over ground needs movement: standing still, a phone reports no heading at
 * all, so a crew checking the map before setting off has nothing to orient by. The
 * magnetometer reads a heading either way.
 *
 * [TYPE_ROTATION_VECTOR] is used in preference to the raw magnetometer because it is
 * already fused with the accelerometer and gyro, so it does not need the tilt
 * correction a magnetometer alone would. Devices without it fall back to
 * [TYPE_MAGNETIC_FIELD] plus [TYPE_ACCELEROMETER].
 *
 * Readings are held briefly rather than passed straight through: a handheld compass
 * swings as a person walks, and an unfiltered map spins with every arm movement.
 */
class CompassHeading(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotation = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetic = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val inclinationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveGeomagnetic = false

    /** True when this device has something to read a heading from. */
    val available: Boolean get() = sm != null && (rotation != null || (magnetic != null && accelerometer != null))

    private var lastReadingDeg = Double.NaN
    private var lastReadingAtMs = 0L

    /** Newest heading in degrees, or NaN when there is nothing usable yet. */
    @Volatile
    var bearingDeg: Double = Double.NaN
        private set

    fun start() {
        val s = sm ?: return
        rotation?.let { s.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (rotation == null) {
            magnetic?.let { s.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            accelerometer?.let { s.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // This runs on a sensor thread, where an uncaught exception kills the process.
        // Sensor quirks differ between handsets, so nothing here is allowed to throw.
        try {
            handle(event)
        } catch (e: Exception) {
            // A compass that misbehaves must cost the heading, not the whole app.
            android.util.Log.w("ForestNav", "compass reading failed", e)
        } catch (e: ArrayIndexOutOfBoundsException) {
            android.util.Log.w("ForestNav", "compass vector too short", e)
        }
    }

    private fun handle(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val v = event.values
                if (v.size < 4) {
                    android.util.Log.w("ForestNav", "rotation vector too short: ${v.size}")
                    return
                }
                SensorManager.getRotationMatrixFromVector(rotationMatrix, v)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // getOrientation reports azimuth anticlockwise from north; the map uses
                // clockwise degrees, so negate it back.
                val deg = Math.toDegrees(orientationAngles[0].toDouble())
                publish(-deg, event.timestamp / 1_000_000L)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (event.values.size < 3) return
                geomagnetic[0] = event.values[0]
                geomagnetic[1] = event.values[1]
                geomagnetic[2] = event.values[2]
                haveGeomagnetic = true
                maybeCombine()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size < 3) return
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                haveGravity = true
                maybeCombine()
            }
        }
    }

    /** Magnetometer heading, but only once both halves of the tilt correction exist. */
    private fun maybeCombine() {
        if (!haveGravity || !haveGeomagnetic) return
        if (!SensorManager.getRotationMatrix(inclinationMatrix, null, gravity, geomagnetic)) return
        val values = SensorManager.getOrientation(inclinationMatrix, orientationAngles)
        val deg = Math.toDegrees(values[0].toDouble())
        publish(-deg, System.currentTimeMillis())
    }

    /**
     * Hold the reading briefly so a walking swing does not spin the map, but follow any
     * real turn straight away.
     */
    private fun publish(deg: Double, atMs: Long) {
        val normalised = ((deg % 360.0) + 360.0) % 360.0
        val previous = lastReadingDeg
        val turned = previous.isNaN() ||
            abs(((normalised - previous + 540.0) % 360.0) - 180.0) > HOLD_DEGREES
        if (!turned && atMs - lastReadingAtMs < HOLD_MS) return
        lastReadingDeg = normalised
        lastReadingAtMs = atMs
        bearingDeg = normalised
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** A turn smaller than this is hand swing, not a change of direction. */
        private const val HOLD_DEGREES = 15.0

        /** How long a small movement keeps the previous reading. */
        private const val HOLD_MS = 1500L
    }
}
