package rw.aurum.pathtracer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** The phone's step detector: one event per step, reported up to a couple of seconds late. */
class StepSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val available: Boolean get() = sensor != null

    /** Called with the milliseconds since boot when the step was taken. Runs on the sensor thread. */
    var onStep: ((Long) -> Unit)? = null

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Sensor timestamps count nanoseconds since boot, like SystemClock.elapsedRealtimeNanos().
        onStep?.invoke(event.timestamp / 1_000_000)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
