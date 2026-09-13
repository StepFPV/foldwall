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

/**
 * Listens to every sensor on the device at once while the user folds it, and reports which
 * ones actually reacted.
 *
 * `TYPE_HINGE_ANGLE` on this hardware declares a resolution of 90° and delivers three values,
 * so an effect cannot follow the hinge from it. The obvious next thought is the accelerometer
 * or the gyroscope, but a phone carries one inertial unit inside one half: it measures how the
 * phone moves through space, not how the hinge articulates, and holding the sensor's own half
 * still while opening the other produces no signal at all.
 *
 * What is worth doing instead of guessing is asking the hardware. A foldable has magnets in
 * the hinge, several vendor sensors that mention folding, and light and proximity parts whose
 * view of the world changes as the two halves separate. Any of those might carry usable
 * information; the way to find out is to record all of them through one fold and see which
 * ones moved, and when relative to the hinge's own posture changes.
 *
 * Rates are deliberately modest. The question is which sensors carry fold information, not
 * their bandwidth, and registering forty sensors at the fastest rate would bury the answer in
 * accelerometer noise.
 */
object SensorSweep {

    private const val TAG = "FoldWall"

    /** Plenty to see a fold happen; far short of a firehose from forty sensors. */
    private const val RATE_US = SensorManager.SENSOR_DELAY_NORMAL

    /** Per sensor, so one chatty part cannot crowd out the rest. */
    private const val MAX_PER_SENSOR = 600

    private const val MAX_DURATION_MS = 5 * 60 * 1000L

    /** Vendor sensors worth trying by name, beyond whatever the sweep picks up anyway. */
    private val VENDOR_TYPES = listOf(
        "com.samsung.sensor.folding_angle",
        "com.samsung.sensor.folding_state",
        "com.samsung.sensor.folding_state_lpm",
    )

    private class Track(val sensor: Sensor) {
        var events = 0
        val first = FloatArray(3) { Float.NaN }
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        var last = FloatArray(3) { Float.NaN }
        var lastAt = 0L

        /** Widest swing on any one axis: how much this sensor reacted to the fold. */
        fun span(): Float {
            var best = 0f
            for (i in 0 until 3) {
                if (min[i] == Float.MAX_VALUE) continue
                val s = max[i] - min[i]
                if (s > best) best = s
            }
            return best
        }
    }

    /** One hinge posture change, so other sensors can be lined up against it. */
    private class Marker(val t: Long, val value: Float)

    private val tracks = LinkedHashMap<String, Track>()
    private val markers = ArrayList<Marker>()
    private val refused = ArrayList<String>()
    private var startedAt = 0L
    private var manager: SensorManager? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var revision: Long = 0L
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val s = event.sensor ?: return
            val now = SystemClock.uptimeMillis() - startedAt
            synchronized(tracks) {
                val t = tracks[key(s)] ?: return
                if (t.events >= MAX_PER_SENSOR) return
                for (i in 0 until minOf(3, event.values.size)) {
                    val v = event.values[i]
                    if (t.events == 0) t.first[i] = v
                    if (v < t.min[i]) t.min[i] = v
                    if (v > t.max[i]) t.max[i] = v
                }
                t.last = event.values.copyOf(minOf(3, event.values.size)).copyOf(3)
                t.lastAt = now
                t.events++
                if (s.type == Sensor.TYPE_HINGE_ANGLE) {
                    val v = event.values.firstOrNull() ?: return
                    if (markers.isEmpty() || markers.last().value != v) markers.add(Marker(now, v))
                }
            }
            revision++
            if (now > MAX_DURATION_MS) stop()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun key(s: Sensor) = s.stringType + "#" + s.name

    fun start(context: Context) {
        if (running) return
        val m = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
        synchronized(tracks) {
            tracks.clear()
            markers.clear()
            refused.clear()
        }
        manager = m
        startedAt = SystemClock.uptimeMillis()

        val all = m.getSensorList(Sensor.TYPE_ALL)
        for (s in all) {
            // One-shot and trigger sensors do not take a plain listener, and a refusal is
            // itself worth reporting rather than hiding.
            val ok = try {
                m.registerListener(listener, s, RATE_US)
            } catch (e: Exception) {
                Log.w(TAG, "sweep: cannot register " + s.name, e)
                false
            }
            if (ok) synchronized(tracks) { tracks[key(s)] = Track(s) }
            else synchronized(tracks) { refused.add(s.name + "  (" + s.stringType + ")") }
        }
        running = true
        revision++
        Log.i(TAG, "sweep: listening to " + tracks.size + " sensors, " + refused.size + " refused")
    }

    fun stop() {
        if (!running) return
        manager?.unregisterListener(listener)
        running = false
        revision++
        Log.i(TAG, "sweep: stopped")
    }

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else SystemClock.uptimeMillis() - startedAt

    fun listening(): Int = synchronized(tracks) { tracks.size }

    fun hingeChanges(): Int = synchronized(tracks) { markers.size }

    /** Sensors that moved, widest swing first — the ones worth looking at. */
    fun movers(limit: Int = 6): List<String> = synchronized(tracks) {
        tracks.values
            .filter { it.events > 1 && it.span() > 0f }
            .sortedByDescending { it.span() }
            .take(limit)
            .map { it.sensor.name + "  " + f(it.span(), 3) }
    }

    private fun f(v: Float, d: Int = 3): String =
        if (v.isNaN() || v == Float.MAX_VALUE || v == -Float.MAX_VALUE) "n/a"
        else String.format(Locale.US, "%." + d + "f", v)

    private fun axes(a: FloatArray, d: Int = 3): String =
        (0 until 3).filter { !a[it].isNaN() && a[it] != Float.MAX_VALUE && a[it] != -Float.MAX_VALUE }
            .joinToString(", ") { f(a[it], d) }

    /**
     * Registers on the vendor sensors by name and says what happened — present, accepted, and
     * whether anything arrived. Listing a sensor is not the same as being allowed to read it,
     * and that difference is the whole question.
     */
    private fun vendorAttempt(context: Context): String {
        val m = context.getSystemService(SensorManager::class.java) ?: return "  (no manager)\n"
        val all = m.getSensorList(Sensor.TYPE_ALL)
        val b = StringBuilder()
        for (name in VENDOR_TYPES) {
            val s = all.firstOrNull { it.stringType == name }
            if (s == null) {
                b.append("  ").append(name).append(": not listed\n")
                continue
            }
            val t = synchronized(tracks) { tracks[key(s)] }
            b.append("  ").append(name).append("  (type ").append(s.type).append(")\n")
            b.append("    listed: yes   registerListener accepted: ")
                .append(if (t != null) "yes" else "NO").append('\n')
            if (t != null) {
                b.append("    events received: ").append(t.events)
                if (t.events > 0) {
                    b.append("   first: ").append(axes(t.first))
                    b.append("   last: ").append(axes(t.last))
                    b.append("   span: ").append(f(t.span()))
                }
                b.append('\n')
            }
        }
        return b.toString()
    }

    fun report(context: Context): String {
        val b = StringBuilder()
        b.append("Full sensor sweep during a fold\n\n")
        b.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        b.append("android: ").append(Build.VERSION.RELEASE)
            .append("   sdk ").append(Build.VERSION.SDK_INT).append('\n')
        b.append("build: ").append(Build.DISPLAY).append('\n')
        b.append("duration: ").append(elapsedMs() / 1000).append("s")
            .append("   sensors listened to: ").append(listening()).append('\n')

        val snapshot = synchronized(tracks) { tracks.values.toList() }
        val marks = synchronized(tracks) { markers.toList() }
        val refusedList = synchronized(tracks) { refused.toList() }

        b.append("\nhinge posture changes seen (ms, value):\n")
        if (marks.isEmpty()) b.append("  none\n")
        else for (mk in marks) b.append("  ").append(mk.t).append("\t").append(f(mk.value, 1)).append('\n')

        b.append("\nvendor folding sensors, tried by name:\n")
        b.append(vendorAttempt(context))

        b.append("\nevery sensor that moved, widest swing first:\n")
        b.append("  span\tevents\tname\t(stringType)\n")
        for (t in snapshot.filter { it.span() > 0f }.sortedByDescending { it.span() }) {
            b.append("  ").append(f(t.span())).append('\t').append(t.events).append('\t')
                .append(t.sensor.name).append('\t').append(t.sensor.stringType).append('\n')
            b.append("      first: ").append(axes(t.first))
                .append("   min: ").append(axes(t.min))
                .append("   max: ").append(axes(t.max)).append('\n')
        }

        b.append("\nsensors that reported but never changed:\n")
        val still = snapshot.filter { it.events > 0 && it.span() <= 0f }
        if (still.isEmpty()) b.append("  none\n")
        else for (t in still) b.append("  ").append(t.sensor.name).append('\n')

        b.append("\nsensors that never reported:\n")
        val silent = snapshot.filter { it.events == 0 }
        if (silent.isEmpty()) b.append("  none\n")
        else for (t in silent) b.append("  ").append(t.sensor.name).append('\n')

        b.append("\nsensors that refused a listener:\n")
        if (refusedList.isEmpty()) b.append("  none\n")
        else for (n in refusedList) b.append("  ").append(n).append('\n')

        b.append("\nMeasured with FoldWall (MIT): https://github.com/StepFPV/foldwall\n")
        return b.toString()
    }

    /** Writes the sweep into Downloads. Returns the file name, or null if it could not. */
    fun saveToDownloads(context: Context): String? {
        val name = "FoldWall-sweep-" + System.currentTimeMillis() + ".txt"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(report(context).toByteArray()) }
                ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        } catch (e: Exception) {
            Log.e(TAG, "sweep: cannot write the report", e)
            null
        }
    }
}
