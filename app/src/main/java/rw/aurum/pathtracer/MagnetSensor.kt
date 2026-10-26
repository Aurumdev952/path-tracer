package rw.aurum.pathtracer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * The magnetic field, as its strength and its vertical part in microtesla. Neither changes when
 * the phone turns, so the same spot reads the same however it's held. Steel in a building bends
 * the field in a pattern that stays put, which may tell how far along a corridor someone is.
 */
class MagnetSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val magnet = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val down = FloatArray(3)
    private var hasDown = false

    /** Called with strength and vertical part. Runs on the sensor thread. */
    var onField: ((Float, Float) -> Unit)? = null

    fun start() {
        if (magnet == null || gravity == null) return
        manager?.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
        manager?.registerListener(this, magnet, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        manager?.unregisterListener(this)
        hasDown = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            val g = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (g < 1f) return
            for (i in 0..2) down[i] = v[i] / g
            hasDown = true
            return
        }
        if (!hasDown) return
        val total = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        onField?.invoke(total, v[0] * down[0] + v[1] * down[1] + v[2] * down[2])
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
