package com.infinite8looper.compass.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.infinite8looper.compass.nav.DEFAULT_HEADING_KIND
import com.infinite8looper.compass.nav.HeadingBlend
import com.infinite8looper.compass.nav.HeadingKind
import com.infinite8looper.compass.nav.headingErrorDeg
import com.infinite8looper.compass.nav.normalizeDeg
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One heading reading. [magneticDeg] is the magnetic heading the compass is given, the one of [kind]; [fusedDeg],
 * [geomagneticDeg] and [blendDeg] are the three sources' own, each not a number while it is not being worked out
 * (only the fused one runs for [HeadingKind.FUSED], unless all three were asked for). [pitchDeg] is the fused
 * sensor's. [accuracy] and [fieldMicroTesla] are the magnetometer's last level and field strength, and [fieldX],
 * [fieldY], [fieldZ] its last field in the watch's frame (not a number before its first event). [headingErrorDeg]
 * is the rotation vector's estimated heading error, null when it gives none ([headingErrorDeg] in nav/).
 */
data class HeadingReading(
    val magneticDeg: Float,
    val pitchDeg: Float,
    val accuracy: Int?,
    val fieldMicroTesla: Float?,
    val kind: HeadingKind = HeadingKind.FUSED,
    val fusedDeg: Float = magneticDeg,
    val geomagneticDeg: Float = Float.NaN,
    val blendDeg: Float = Float.NaN,
    val headingErrorDeg: Float? = null,
    val fieldX: Float = Float.NaN,
    val fieldY: Float = Float.NaN,
    val fieldZ: Float = Float.NaN,
)

/** How the heading from the magnetometer and gravity alone is worked out. */
enum class MagneticMethod {
    /** The system's geomagnetic rotation vector: accelerometer and magnetometer, no gyroscope. */
    GEOMAGNETIC_ROTATION_VECTOR,
    /** `SensorManager.getRotationMatrix` from the accelerometer's and the magnetometer's last readings. */
    ACCELEROMETER_AND_MAGNETOMETER,
}

/**
 * The watch's heading sensors. [magneticMethod] is the geomagnetic rotation vector where the watch has one and the
 * accelerometer with the magnetometer otherwise, unless [method] names one (the device tests run both).
 */
class HeadingSource(context: Context, method: MagneticMethod? = null) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnet: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val geomagnetic: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val isSupported: Boolean get() = rotation != null

    val magneticMethod: MagneticMethod = method
        ?: if (geomagnetic != null) MagneticMethod.GEOMAGNETIC_ROTATION_VECTOR else MagneticMethod.ACCELEROMETER_AND_MAGNETOMETER

    /**
     * The heading of [kind] for as long as the flow is collected, with the magnetometer's last accuracy, field
     * strength and field. A reading comes with each event of the rotation vector, which gives the pitch; for
     * [HeadingKind.MAGNETIC] none comes until the first magnetic heading has been worked out. The sensors the
     * magnetic heading needs (the geomagnetic rotation vector, or the accelerometer) are registered only for
     * [HeadingKind.MAGNETIC] and [HeadingKind.BLEND], or when [allThree] asks for every source's heading.
     *
     * The sensors' events are delivered on the app's sensor thread ([SensorThread]), not on the main thread: the
     * arithmetic for a reading is done there, on values only that thread touches, and the reading is handed to the
     * collector through the flow, which keeps the newest one. When the collection ends the listeners are taken off
     * and the thread is given back.
     */
    fun readings(kind: HeadingKind = DEFAULT_HEADING_KIND, allThree: Boolean = false): Flow<HeadingReading> = callbackFlow {
        val rot = rotation
        if (rot == null) { close(); return@callbackFlow }
        val magneticWanted = allThree || kind != HeadingKind.FUSED
        val method = magneticMethod
        val matrix = FloatArray(9); val angles = FloatArray(3)
        val magMatrix = FloatArray(9); val magAngles = FloatArray(3)
        val gravity = FloatArray(3); val field = FloatArray(3)
        var haveGravity = false; var haveField = false
        var accuracy: Int? = null
        var strength: Float? = null
        var magneticHeading = Float.NaN
        val blend = HeadingBlend()

        fun magneticFrom(m: FloatArray) {
            SensorManager.getOrientation(m, magAngles)
            val azimuth = Math.toDegrees(magAngles[0].toDouble()).toFloat()
            if (!azimuth.isNaN()) magneticHeading = normalizeDeg(azimuth)
        }
        fun magneticFromAccelerometerAndMagnetometer() {
            if (haveGravity && haveField && SensorManager.getRotationMatrix(magMatrix, null, gravity, field)) magneticFrom(magMatrix)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        field[0] = e.values[0]; field[1] = e.values[1]; field[2] = e.values[2]
                        haveField = true
                        strength = sqrt(field[0] * field[0] + field[1] * field[1] + field[2] * field[2])
                        accuracy = e.accuracy
                        if (magneticWanted && method == MagneticMethod.ACCELEROMETER_AND_MAGNETOMETER) magneticFromAccelerometerAndMagnetometer()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2]
                        haveGravity = true
                        magneticFromAccelerometerAndMagnetometer()
                    }
                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(magMatrix, e.values)
                        magneticFrom(magMatrix)
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(matrix, e.values)
                        SensorManager.getOrientation(matrix, angles)
                        val azimuth = Math.toDegrees(angles[0].toDouble()).toFloat()
                        val pitch = abs(Math.toDegrees(angles[1].toDouble()).toFloat())
                        if (azimuth.isNaN() || pitch.isNaN()) return
                        val fused = normalizeDeg(azimuth)
                        val blended = if (magneticWanted) blend.update(fused, magneticHeading, e.timestamp) else Float.NaN
                        val shown = when (kind) {
                            HeadingKind.FUSED -> fused
                            HeadingKind.MAGNETIC -> magneticHeading
                            HeadingKind.BLEND -> blended
                        }
                        if (shown.isNaN()) return
                        trySend(
                            HeadingReading(
                                shown, pitch, accuracy, strength, kind, fused,
                                if (magneticWanted) magneticHeading else Float.NaN, blended, headingErrorDeg(e.values),
                                if (haveField) field[0] else Float.NaN, if (haveField) field[1] else Float.NaN, if (haveField) field[2] else Float.NaN,
                            ),
                        )
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, value: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) accuracy = value
            }
        }
        val handler = SensorThread.acquire()
        try {
            sensors.registerListener(listener, rot, SensorManager.SENSOR_DELAY_GAME, handler)
            magnet?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, handler) }
            if (magneticWanted) {
                val extra = if (method == MagneticMethod.GEOMAGNETIC_ROTATION_VECTOR) geomagnetic else accelerometer
                extra?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
            }
        } catch (e: Throwable) {
            // Not registered after all: the listener comes off whatever it was put on, and the thread is given back.
            sensors.unregisterListener(listener)
            SensorThread.release()
            throw e
        }
        awaitClose {
            sensors.unregisterListener(listener)
            SensorThread.release()
        }
    }.conflate()
}
