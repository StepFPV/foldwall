package com.stepdesign.foldwall

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Thin wrapper over `Sensor.TYPE_HINGE_ANGLE` — a public Android sensor type since
 * API 30, no vendor SDK involved. Reports degrees: 0 shut, 180 flat.
 *
 * Whether a [android.service.wallpaper.WallpaperService.Engine] receives these events,
 * and over what part of the travel, is decided by the firmware. That is why the app
 * surfaces the raw value in a debug overlay instead of assuming a range.
 */
class HingeSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    val available: Boolean get() = sensor != null

    /** Last raw reading in degrees, or NaN before the first event. */
    @Volatile
    var lastAngle: Float = Float.NaN
        private set

    @Volatile
    var eventCount: Long = 0L
        private set

    fun start() {
        val s = sensor
        if (s == null) {
            Log.w(TAG, "TYPE_HINGE_ANGLE not present on this device")
            return
        }
        manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values.firstOrNull() ?: return
        lastAngle = v
        eventCount++
        onAngle(v)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "FoldWall"
    }
}
