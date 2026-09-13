package com.stepdesign.foldwall

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.util.Locale

/**
 * Records everything this device's hinge sensor does, and writes it out as evidence.
 *
 * Two developers report that Samsung's public hinge sensor returns a posture — 0, 90, 180 —
 * rather than a continuous angle, with the real one behind a signature-level permission. That
 * claim decides whether any angle-driven effect can work here at all, so it deserves
 * measurement rather than agreement: every event the sensor emits, the instant it arrives, and
 * everything the sensor says about itself.
 *
 * Deliberately raw. [HingeSource] drops changes under 0.4° to stop a chatty sensor redrawing
 * the screen; here nothing is filtered, because the size of the steps and the gaps between
 * them are the measurement.
 *
 * Deliberately process-wide rather than tied to a screen: folding turns the inner panel off
 * and pauses the activity, which is exactly when the interesting samples arrive. It starts and
 * stops on the user's word, with a cap so a forgotten session cannot hold the sensor all day.
 */
object HingeProbe {

    private const val TAG = "FoldWall"

    /** A full log is the point, so this is generous; ~20k samples is a few hundred KB. */
    private const val MAX_SAMPLES = 20_000

    /** A forgotten recording stops itself rather than holding the sensor open. */
    private const val MAX_DURATION_MS = 10 * 60 * 1000L

    /** Readings are compared at this precision, so float noise cannot invent distinct values. */
    private const val DECIMALS = 3

    /** How far off 0/90/180 a reading can sit and still count as that posture. */
    private const val POSTURE_TOLERANCE = 0.5f

    /** A gap longer than this means the phone was resting, not that the sensor is slow. */
    private const val MOVING_GAP_MS = 1_000L

    /** One event: milliseconds since recording began, and the raw degrees reported. */
    private class Sample(val t: Long, val v: Float, val sensorTimeNs: Long)

    private val samples = ArrayList<Sample>(1024)
    private var startedAt = 0L
    private var manager: SensorManager? = null
    private var sensor: Sensor? = null

    @Volatile
    var recording: Boolean = false
        private set

    /** Bumped on every event so a polling UI can tell something moved. */
    @Volatile
    var revision: Long = 0L
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values.firstOrNull() ?: return
            val now = SystemClock.uptimeMillis()
            synchronized(samples) {
                if (samples.size >= MAX_SAMPLES) return
                samples.add(Sample(now - startedAt, v, event.timestamp))
            }
            revision++
            if (now - startedAt > MAX_DURATION_MS) stop()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun available(context: Context): Boolean = hingeSensor(context) != null

    private fun hingeSensor(context: Context): Sensor? =
        context.getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    fun start(context: Context) {
        if (recording) return
        val m = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
        val s = m.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (s == null) {
            Log.w(TAG, "hinge probe: TYPE_HINGE_ANGLE not present")
            return
        }
        synchronized(samples) { samples.clear() }
        revision++
        manager = m
        sensor = s
        startedAt = SystemClock.uptimeMillis()
        // FASTEST, not GAME: asking for less could hide how often the sensor really reports,
        // which is half of what is being measured.
        m.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST)
        recording = true
        Log.i(TAG, "hinge probe: recording on " + s.name)
    }

    fun stop() {
        if (!recording) return
        manager?.unregisterListener(listener)
        recording = false
        revision++
        Log.i(TAG, "hinge probe: stopped, " + count() + " samples")
    }

    fun count(): Int = synchronized(samples) { samples.size }

    fun elapsedMs(): Long =
        if (recording) SystemClock.uptimeMillis() - startedAt
        else synchronized(samples) { samples.lastOrNull()?.t ?: 0L }

    /** Every reading seen, once each. */
    fun distinct(): List<Float> {
        val seen = LinkedHashSet<Float>()
        synchronized(samples) { for (s in samples) seen.add(round(s.v)) }
        return seen.toList()
    }

    /**
     * True when every reading sits on 0, 90 or 180 — the pattern that would mean the sensor
     * reports which posture the phone is in rather than how far the hinge has turned.
     */
    fun postureOnly(): Boolean {
        val values = distinct()
        if (values.isEmpty()) return false
        return values.all { v ->
            listOf(0f, 90f, 180f).any { p -> kotlin.math.abs(v - p) <= POSTURE_TOLERANCE }
        }
    }

    /** The smallest gap between two neighbouring readings: the sensor's real step size. */
    fun smallestStep(): Float {
        val sorted = distinct().sorted()
        if (sorted.size < 2) return Float.MAX_VALUE.let { Float.NaN }
        var min = Float.MAX_VALUE
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            if (gap > 0f && gap < min) min = gap
        }
        return if (min == Float.MAX_VALUE) Float.NaN else min
    }

    /**
     * Gaps between consecutive events, in milliseconds, counting only those short enough to
     * be the hinge actually moving. A long gap means the phone was sitting still: an on-change
     * sensor says nothing when nothing changes, and averaging that in would understate the rate.
     */
    private fun movingGaps(): List<Long> {
        val out = ArrayList<Long>()
        synchronized(samples) {
            for (i in 1 until samples.size) {
                val g = samples[i].t - samples[i - 1].t
                if (g in 0..MOVING_GAP_MS) out.add(g)
            }
        }
        out.sort()
        return out
    }

    /** Median gap while moving, in ms, or NaN with too little data. */
    fun medianGapMs(): Float {
        val g = movingGaps()
        if (g.isEmpty()) return Float.NaN
        return g[g.size / 2].toFloat()
    }

    private fun round(v: Float): Float {
        var f = 1f
        repeat(DECIMALS) { f *= 10f }
        return kotlin.math.round(v * f) / f
    }

    private fun f(v: Float, decimals: Int = 2): String =
        if (v.isNaN()) "n/a" else String.format(Locale.US, "%." + decimals + "f", v)

    /** Samsung encodes the One UI version here; absent on everything else. */
    private fun oneUi(): String = try {
        val platform = Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT").getInt(null)
        if (platform < 100000) "n/a" else {
            val v = platform - 90000
            "" + (v / 10000) + "." + (v % 10000 / 100)
        }
    } catch (e: Throwable) {
        "n/a"
    }

    private fun reportingMode(s: Sensor): String = when (s.reportingMode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "on-change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "one-shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special-trigger"
        else -> "unknown(" + s.reportingMode + ")"
    }

    private fun describe(s: Sensor): String = buildString {
        append("  name: ").append(s.name).append('\n')
        append("  vendor: ").append(s.vendor).append("   version: ").append(s.version).append('\n')
        append("  stringType: ").append(s.stringType).append("   type: ").append(s.type).append('\n')
        append("  reportingMode: ").append(reportingMode(s)).append('\n')
        append("  resolution: ").append(f(s.resolution, 5))
            .append("   maxRange: ").append(f(s.maximumRange, 3)).append('\n')
        append("  minDelay: ").append(s.minDelay).append("us")
            .append("   maxDelay: ").append(s.maxDelay).append("us")
            .append("   power: ").append(f(s.power, 3)).append("mA").append('\n')
        append("  fifoReserved: ").append(s.fifoReservedEventCount)
            .append("   fifoMax: ").append(s.fifoMaxEventCount)
            .append("   wakeUp: ").append(s.isWakeUpSensor).append('\n')
    }

    /**
     * Every sensor on this device whose name or type mentions the hinge or the fold — which is
     * how a vendor's own continuous sensor would show up if the app can see it at all.
     */
    private fun relatedSensors(context: Context): String {
        val m = context.getSystemService(SensorManager::class.java)
            ?: return "  (no SensorManager)\n"
        val all = m.getSensorList(Sensor.TYPE_ALL)
        val hits = all.filter {
            val n = (it.name + " " + it.stringType).lowercase(Locale.US)
            n.contains("hinge") || n.contains("fold") || n.contains("flip")
        }
        if (hits.isEmpty()) return "  none visible to a normal app\n"
        return buildString {
            for (s in hits) {
                append(describe(s))
                append('\n')
            }
        }
    }

    private fun header(context: Context): String = buildString {
        append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("  (").append(Build.DEVICE).append(")\n")
        append("android: ").append(Build.VERSION.RELEASE)
            .append("   sdk ").append(Build.VERSION.SDK_INT)
            .append("   one ui ").append(oneUi()).append('\n')
        append("build: ").append(Build.DISPLAY).append('\n')
    }

    /**
     * The short version, sized to paste into a comment. Facts only: what the device is, what
     * the sensor says about itself, what it produced, and how often.
     */
    fun summary(context: Context): String {
        val s = sensor ?: hingeSensor(context)
        val values = distinct()
        val sorted = values.sorted()
        val b = StringBuilder()

        b.append("TYPE_HINGE_ANGLE measurement\n\n")
        b.append(header(context))

        if (s == null) {
            b.append("\nTYPE_HINGE_ANGLE: NOT PRESENT on this device.\n")
            b.append("\nhinge/fold sensors visible to a normal app:\n")
            b.append(relatedSensors(context))
            return b.toString()
        }

        b.append("\nTYPE_HINGE_ANGLE reports itself as:\n").append(describe(s))

        b.append("\nrecording: ").append(count()).append(" events over ")
            .append(elapsedMs() / 1000).append("s\n")
        b.append("distinct values: ").append(values.size).append('\n')
        if (sorted.isNotEmpty()) {
            b.append("range seen: ").append(f(sorted.first(), 1)).append(" .. ")
                .append(f(sorted.last(), 1)).append('\n')
            b.append("smallest step between readings: ").append(f(smallestStep())).append('\n')
            b.append("median gap between events while moving: ")
                .append(f(medianGapMs(), 0)).append(" ms\n")
            b.append("values: ").append(sorted.joinToString(", ") { f(it, 1) }).append('\n')
        }

        b.append("\nverdict: ")
        when {
            values.isEmpty() -> b.append("no events received while recording.")
            postureOnly() -> b.append(
                "POSTURE ONLY - every reading sits on 0/90/180, so this device does not " +
                    "expose a continuous hinge angle through the public API.",
            )
            else -> b.append(
                "CONTINUOUS - readings land between the postures, so this device does " +
                    "expose a real hinge angle through the public API.",
            )
        }
        b.append("\n\nhinge/fold sensors visible to a normal app:\n")
        b.append(relatedSensors(context))
        b.append("\nMeasured with FoldWall (MIT): https://github.com/StepFPV/foldwall\n")
        return b.toString()
    }

    /** The summary, then every single event: milliseconds since start, degrees, sensor clock. */
    fun fullLog(context: Context): String {
        val b = StringBuilder(summary(context))
        b.append("\n\n--- every event ---\n")
        b.append("ms_since_start\tdegrees\tgap_ms\tsensor_timestamp_ns\n")
        synchronized(samples) {
            var prev = -1L
            for (s in samples) {
                b.append(s.t).append('\t')
                    .append(String.format(Locale.US, "%.4f", s.v)).append('\t')
                    .append(if (prev < 0) "" else (s.t - prev).toString()).append('\t')
                    .append(s.sensorTimeNs).append('\n')
                prev = s.t
            }
        }
        return b.toString()
    }

    /**
     * Writes the full log into Downloads, where the user can find it and pass it on. MediaStore
     * rather than a raw path: no storage permission, and it shows up in Files straight away.
     * Returns the file name, or null if it could not be written.
     */
    fun saveToDownloads(context: Context): String? {
        val name = "FoldWall-hinge-" + System.currentTimeMillis() + ".txt"
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
            resolver.openOutputStream(uri)?.use { it.write(fullLog(context).toByteArray()) }
                ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "hinge probe: wrote " + name)
            name
        } catch (e: Exception) {
            Log.e(TAG, "hinge probe: cannot write the log", e)
            null
        }
    }

    /** Where [saveToDownloads] puts things, for telling the user where to look. */
    fun downloadsLabel(): String = Environment.DIRECTORY_DOWNLOADS
}
