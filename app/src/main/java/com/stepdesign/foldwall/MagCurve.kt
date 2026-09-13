package com.stepdesign.foldwall

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.util.Locale
import kotlin.math.sqrt

/**
 * Records the magnetic field against time through a slow, paused fold, so the shape of the
 * curve can be read.
 *
 * A sweep of every sensor showed the magnetometer swinging 146µT on one axis while the phone
 * folded — around three times the Earth's whole field, so unmistakably the hinge magnet rather
 * than noise. That says there is signal. It does not say the signal is usable: min and max
 * cannot tell a smooth ramp from a switch that snaps near closure, and only a ramp can give
 * back an angle.
 *
 * So this keeps the series rather than the extremes. The user folds slowly and pauses at
 * roughly known positions; the pauses show up as plateaux, and the plateaux are the
 * calibration points. The hinge sensor's three postures are recorded alongside as hard
 * timestamps to line the curve up against.
 *
 * The uncalibrated magnetometer is the one that matters here. Android continuously re-estimates
 * hard-iron bias on the calibrated stream, which is exactly the kind of silent correction that
 * would deform a curve being measured; the uncalibrated one reports the raw field and the bias
 * separately. Both are kept so the difference is visible rather than assumed.
 */
object MagCurve {

    private const val TAG = "FoldWall"

    /** Fast enough for a hand-speed fold, slow enough that two minutes stays manageable. */
    private const val RATE_US = 20_000

    private const val MAX_SAMPLES = 12_000
    private const val MAX_DURATION_MS = 3 * 60 * 1000L

    /** Rows in the pasteable summary; enough to see a shape, short enough for a comment. */
    private const val SUMMARY_ROWS = 45

    private class Row(
        val t: Long,
        val raw: FloatArray,
        val cal: FloatArray,
        val grav: FloatArray,
        val light: Float,
        val hinge: Float,
    )

    private val rows = ArrayList<Row>(2048)
    private var startedAt = 0L
    private var manager: SensorManager? = null

    private var lastRaw = FloatArray(3) { Float.NaN }
    private var lastCal = FloatArray(3) { Float.NaN }
    private var lastGrav = FloatArray(3) { Float.NaN }
    private var lastLight = Float.NaN
    private var lastHinge = Float.NaN

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var revision: Long = 0L
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                    lastRaw = floatArrayOf(event.values[0], event.values[1], event.values[2])
                    record()
                }
                Sensor.TYPE_MAGNETIC_FIELD ->
                    lastCal = floatArrayOf(event.values[0], event.values[1], event.values[2])
                // Tilting the phone is the one rotation the magnetometer's Z axis cannot
                // shrug off, and gravity measures tilt directly. Recorded alongside so the
                // two can be compared rather than assumed independent.
                Sensor.TYPE_GRAVITY ->
                    lastGrav = floatArrayOf(event.values[0], event.values[1], event.values[2])
                Sensor.TYPE_LIGHT -> lastLight = event.values[0]
                Sensor.TYPE_HINGE_ANGLE -> {
                    lastHinge = event.values[0]
                    record()
                }
                else -> Unit
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** One row per uncalibrated-magnetometer event: it is the fastest of the four. */
    private fun record() {
        val t = SystemClock.uptimeMillis() - startedAt
        synchronized(rows) {
            if (rows.size >= MAX_SAMPLES) return
            rows.add(
                Row(t, lastRaw.copyOf(), lastCal.copyOf(), lastGrav.copyOf(), lastLight, lastHinge),
            )
        }
        revision++
        if (t > MAX_DURATION_MS) stop()
    }

    fun start(context: Context) {
        if (running) return
        val m = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
        synchronized(rows) { rows.clear() }
        manager = m
        startedAt = SystemClock.uptimeMillis()
        for (type in listOf(
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_HINGE_ANGLE,
        )) {
            m.getDefaultSensor(type)?.let { m.registerListener(listener, it, RATE_US) }
        }
        running = true
        revision++
        Log.i(TAG, "mag curve: recording")
    }

    fun stop() {
        if (!running) return
        manager?.unregisterListener(listener)
        running = false
        revision++
        Log.i(TAG, "mag curve: stopped, " + count() + " rows")
    }

    fun count(): Int = synchronized(rows) { rows.size }

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else SystemClock.uptimeMillis() - startedAt

    /** Field strength now, for a live read-out while folding. */
    fun magnitudeNow(): Float = synchronized(rows) {
        rows.lastOrNull()?.let { mag(it.raw) } ?: Float.NaN
    }

    fun hingeNow(): Float = lastHinge

    /** The axis that actually tracks the hinge, for a live read-out while folding. */
    fun zNow(): Float = synchronized(rows) { rows.lastOrNull()?.raw?.get(2) ?: Float.NaN }

    private fun mag(v: FloatArray): Float {
        if (v.any { it.isNaN() }) return Float.NaN
        return sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    }

    private fun f(v: Float, d: Int = 1): String =
        if (v.isNaN()) "" else String.format(Locale.US, "%." + d + "f", v)

    /** The whole series, one row per event, for keeping. */
    fun csv(context: Context): String {
        val b = StringBuilder()
        b.append("# magnetic field through a fold\n")
        b.append("# device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("   android ").append(Build.VERSION.RELEASE)
            .append("   build ").append(Build.DISPLAY).append('\n')
        b.append("# raw = uncalibrated magnetometer, cal = calibrated, uT\n")
        b.append(
            "ms,raw_x,raw_y,raw_z,raw_mag,cal_x,cal_y,cal_z,cal_mag," +
                "grav_x,grav_y,grav_z,light,hinge\n",
        )
        synchronized(rows) {
            for (r in rows) {
                b.append(r.t).append(',')
                    .append(f(r.raw[0], 3)).append(',').append(f(r.raw[1], 3)).append(',')
                    .append(f(r.raw[2], 3)).append(',').append(f(mag(r.raw), 3)).append(',')
                    .append(f(r.cal[0], 3)).append(',').append(f(r.cal[1], 3)).append(',')
                    .append(f(r.cal[2], 3)).append(',').append(f(mag(r.cal), 3)).append(',')
                    .append(f(r.grav[0], 3)).append(',').append(f(r.grav[1], 3)).append(',')
                    .append(f(r.grav[2], 3)).append(',')
                    .append(f(r.light, 1)).append(',').append(f(r.hinge, 1)).append('\n')
            }
        }
        return b.toString()
    }

    /**
     * A thinned version small enough to paste: evenly spaced rows across the whole recording,
     * plus every instant the hinge changed posture, which are the only fixed points there are.
     */
    fun summary(context: Context): String {
        val snapshot = synchronized(rows) { rows.toList() }
        val b = StringBuilder()
        b.append("Magnetic field through a fold\n\n")
        b.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("   android ").append(Build.VERSION.RELEASE).append('\n')
        b.append("rows: ").append(snapshot.size)
            .append("   duration: ").append(elapsedMs() / 1000).append("s\n")

        if (snapshot.isEmpty()) {
            b.append("\nnothing recorded.\n")
            return b.toString()
        }

        b.append("\nhinge posture changes (ms, value):\n")
        var prev = Float.NaN
        for (r in snapshot) {
            if (!r.hinge.isNaN() && r.hinge != prev) {
                b.append("  ").append(r.t).append('\t').append(f(r.hinge)).append('\n')
                prev = r.hinge
            }
        }

        // raw_z leads, because it is the axis that carries the hinge; the magnitude is
        // deliberately gone from this table after it turned out not to be monotonic.
        b.append("\nevenly spaced samples. raw_z tracks the hinge;\n")
        b.append("grav_z says how far the phone is tilted away from lying flat.\n")
        b.append("ms\traw_z\traw_x\traw_y\tgrav_x\tgrav_y\tgrav_z\thinge\n")
        val step = maxOf(1, snapshot.size / SUMMARY_ROWS)
        var i = 0
        while (i < snapshot.size) {
            val r = snapshot[i]
            b.append(r.t).append('\t')
                .append(f(r.raw[2])).append('\t').append(f(r.raw[0])).append('\t')
                .append(f(r.raw[1])).append('\t')
                .append(f(r.grav[0], 2)).append('\t').append(f(r.grav[1], 2)).append('\t')
                .append(f(r.grav[2], 2)).append('\t').append(f(r.hinge)).append('\n')
            i += step
        }
        b.append("\nMeasured with FoldWall (MIT): https://github.com/StepFPV/foldwall\n")
        return b.toString()
    }

    /** Writes the full series into Downloads. Returns the file name, or null. */
    fun saveToDownloads(context: Context): String? {
        val name = "FoldWall-mag-" + System.currentTimeMillis() + ".csv"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(csv(context).toByteArray()) }
                ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        } catch (e: Exception) {
            Log.e(TAG, "mag curve: cannot write", e)
            null
        }
    }
}
