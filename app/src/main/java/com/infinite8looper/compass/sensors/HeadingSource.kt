package com.infinite8looper.compass.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.infinite8looper.compass.nav.normalizeDeg
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.sqrt

data class HeadingReading(val magneticDeg: Float, val pitchDeg: Float, val accuracy: Int?, val fieldMicroTesla: Float?)

class HeadingSource(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnet: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val isSupported: Boolean get() = rotation != null

    /**
     * The heading as the rotation vector sensor gives it, with the magnetometer's last accuracy and field strength,
     * for as long as the flow is collected. The sensors' events are delivered on the app's sensor thread
     * ([SensorThread]), not on the main thread: the arithmetic for a reading is done there, on values only that
     * thread touches, and the reading is handed to the collector through the flow, which keeps the newest one.
     * When the collection ends the listener is taken off and the thread is given back.
     */
    fun readings(): Flow<HeadingReading> = callbackFlow {
        val rot = rotation
        if (rot == null) { close(); return@callbackFlow }
        val matrix = FloatArray(9); val angles = FloatArray(3)
        var accuracy: Int? = null
        var field: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    field = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    accuracy = e.accuracy
                    return
                }
                SensorManager.getRotationMatrixFromVector(matrix, e.values)
                SensorManager.getOrientation(matrix, angles)
                val azimuth = Math.toDegrees(angles[0].toDouble()).toFloat()
                val pitch = abs(Math.toDegrees(angles[1].toDouble()).toFloat())
                if (!azimuth.isNaN() && !pitch.isNaN()) {
                    trySend(HeadingReading(normalizeDeg(azimuth), pitch, accuracy, field))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, value: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) accuracy = value
            }
        }
        val handler = SensorThread.acquire()
        sensors.registerListener(listener, rot, SensorManager.SENSOR_DELAY_GAME, handler)
        magnet?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, handler) }
        awaitClose {
            sensors.unregisterListener(listener)
            SensorThread.release()
        }
    }.conflate()
}
