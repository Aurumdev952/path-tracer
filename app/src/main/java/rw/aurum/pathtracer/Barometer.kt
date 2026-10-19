package rw.aurum.pathtracer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Altitude from the phone's air pressure sensor. Absolute values drift with the weather, but
 * changes over a minute are good to a few tens of centimeters: enough to notice an elevator ride.
 */
class Barometer(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val available: Boolean get() = sensor != null

    /** Called with (milliseconds since boot, meters). Runs on the sensor thread. */
    var onAltitude: ((Long, Float) -> Unit)? = null

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val meters = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0])
        // Sensor timestamps count nanoseconds since boot, like SystemClock.elapsedRealtimeNanos().
        onAltitude?.invoke(event.timestamp / 1_000_000, meters)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
