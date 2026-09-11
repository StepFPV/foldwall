package com.stepdesign.foldwall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Experimental: runs the fold effect over whatever is on screen, not just the wallpaper.
 *
 * The honest version of what this is. A third-party app cannot hook SystemUI's display
 * handover, so this fakes it the only way the platform allows: grab one frame of the
 * screen through `MediaProjection`, freeze it, and run the same shader over it inside a
 * `TYPE_APPLICATION_OVERLAY` window while the hinge moves. When the hinge settles the
 * overlay goes away and the real screen is back.
 *
 * Two constraints shape the whole design:
 *
 *  - From Android 14 a `MediaProjection` may be used for exactly one `createVirtualDisplay`
 *    call; a second one throws `SecurityException`. So the virtual display is created once
 *    and then parked with `setSurface(null)`, which the platform treats like switching the
 *    display off — no buffers are produced while parked.
 *  - The virtual display mirrors display 0, and this overlay lives on display 0. Capturing
 *    while the overlay is up would feed the effect back into itself. Parking the display
 *    before the overlay appears removes that possibility entirely rather than papering
 *    over it.
 */
class OverlayFoldService : Service(), Choreographer.FrameCallback {

    private enum class State { IDLE, CAPTURING, SHOWING }

    private lateinit var windowManager: WindowManager

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    private var settings = FoldSettings()
    private var hinge: HingeSource? = null
    private var choreographer: Choreographer? = null

    private var overlay: FoldOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var frame: Bitmap? = null

    private var captureWidth = 0
    private var captureHeight = 0

    private var state = State.IDLE

    /** Read on the capture thread, written on the main one: the gate for a single frame. */
    @Volatile
    private var awaitingFrame = false

    private var frameScheduled = false
    private var lastFrameNanos = 0L

    private var currentOpenness = 1f
    private var targetOpenness = 1f

    private var lastAngle = Float.NaN
    private var hingeEvents = 0L
    private var lastEventUptime = 0L
    private var travelSinceIdle = 0f
    private var lastMovementUptime = 0L
    private var shownAtUptime = 0L
    private var fadeStartUptime = 0L

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
            val next = FoldSettings.read(prefs)
            if (next != settings) {
                settings = next
                overlay?.settings = next
                if (state == State.SHOWING) requestFrame()
            }
        }

    /** A dark screen must never be left with a stale screenshot pasted over it. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (state != State.IDLE) teardownOverlay()
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture from the system chip, or another app took it.
            Log.i(TAG, "projection stopped by the system")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "started without a capture consent token")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14 wants the typed foreground service running *before* the projection
        // is obtained, not after.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        windowManager = getSystemService(WindowManager::class.java)
        settings = FoldSettings.load(this)
        FoldSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        choreographer = Choreographer.getInstance()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val active = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "cannot obtain the media projection", e)
            null
        }
        if (active == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = active
        // Mandatory from API 34: createVirtualDisplay throws without a registered callback.
        active.registerCallback(projectionCallback, main)

        val thread = HandlerThread("foldwall-capture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        if (!openVirtualDisplay(active)) {
            stopSelf()
            return START_NOT_STICKY
        }

        registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED,
        )

        val source = HingeSource(this) { onHingeAngle(it) }
        hinge = source
        source.start()

        setRunning(true)
        Log.i(TAG, "overlay mode running, capture " + captureWidth + "x" + captureHeight)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardownOverlay()
        cancelFrame()

        hinge?.stop()
        hinge = null

        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: IllegalArgumentException) {
        }
        FoldSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)

        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null

        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        setRunning(false)
        super.onDestroy()
    }

    // --- capture ---------------------------------------------------------------------

    private fun openVirtualDisplay(active: MediaProjection): Boolean {
        val (w, h) = captureSize()
        if (w <= 0 || h <= 0) return false
        captureWidth = w
        captureHeight = h

        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader.setOnImageAvailableListener({ onImageAvailable(it) }, captureHandler)
        reader = imageReader

        val display = try {
            active.createVirtualDisplay(
                "foldwall-capture",
                w,
                h,
                resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                captureHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }
        if (display == null) {
            imageReader.close()
            reader = null
            return false
        }
        virtualDisplay = display
        // Park it straight away: no frames are produced until a fold actually starts.
        display.setSurface(null)
        return true
    }

    /**
     * The panel is bigger than the effect needs — everything downstream is a blur and a
     * warp — so the mirror is capped, which keeps one capture well under a frame budget.
     */
    private fun captureSize(): Pair<Int, Int> {
        val bounds = windowManager.currentWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return 0 to 0
        val scale = (CAPTURE_CAP_PX.toFloat() / max(w, h)).coerceAtMost(1f)
        if (scale >= 1f) return w to h
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    private fun requestCapture() {
        val display = virtualDisplay ?: return
        state = State.CAPTURING
        awaitingFrame = true
        Log.i(TAG, "fold started at " + lastAngle + "°, grabbing one frame")
        main.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)

        val (w, h) = captureSize()
        if (w > 0 && h > 0 && (w != captureWidth || h != captureHeight)) {
            // The panel changed under us — unfolding swaps to a differently shaped screen.
            // resize() is the supported move here; a second createVirtualDisplay is not.
            captureWidth = w
            captureHeight = h
            reader?.close()
            val fresh = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
            fresh.setOnImageAvailableListener({ onImageAvailable(it) }, captureHandler)
            reader = fresh
            display.resize(w, h, resources.configuration.densityDpi)
        }

        val armed = reader ?: return
        captureHandler?.post {
            drain(armed)
            try {
                display.setSurface(armed.surface)
            } catch (e: Exception) {
                Log.e(TAG, "cannot arm the capture surface", e)
            }
        }
    }

    private val captureTimeout = Runnable {
        if (state != State.CAPTURING) return@Runnable
        Log.w(TAG, "no frame arrived in time, staying out of the way")
        awaitingFrame = false
        parkDisplay()
        state = State.IDLE
        travelSinceIdle = 0f
    }

    private fun parkDisplay() {
        val display = virtualDisplay ?: return
        captureHandler?.post {
            try {
                display.setSurface(null)
            } catch (e: Exception) {
                Log.e(TAG, "cannot park the virtual display", e)
            }
        }
    }

    /** Runs on the capture thread. */
    private fun onImageAvailable(source: ImageReader) {
        if (!awaitingFrame) {
            // A frame that arrived after we already got what we needed.
            drain(source)
            return
        }
        // acquireLatestImage drops everything older and hands back the newest, which is
        // exactly the semantics wanted here: freeze the screen as it is right now.
        val image = try {
            source.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquireLatestImage failed", e)
            null
        } ?: return

        val bitmap = try {
            toBitmap(image)
        } catch (e: Exception) {
            Log.e(TAG, "cannot convert the captured frame", e)
            null
        } finally {
            image.close()
        }
        if (bitmap == null) return

        awaitingFrame = false
        try {
            virtualDisplay?.setSurface(null)
        } catch (e: Exception) {
            Log.e(TAG, "cannot park the virtual display", e)
        }
        main.post { onFrameCaptured(bitmap) }
    }

    /**
     * Empties the reader. Without this the frame that landed a moment after the display
     * was parked would be served as the *next* fold's capture, one gesture out of date.
     */
    private fun drain(source: ImageReader) {
        try {
            source.acquireLatestImage()?.close()
        } catch (e: Exception) {
            Log.w(TAG, "cannot drain the capture queue", e)
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val rowPadding = rowStride - pixelStride * w
        val paddedWidth = w + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == w) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun onFrameCaptured(bitmap: Bitmap) {
        main.removeCallbacks(captureTimeout)
        if (state != State.CAPTURING) return

        Log.i(TAG, "frame captured " + bitmap.width + "x" + bitmap.height)
        frame = bitmap
        showOverlay()
        if (overlay == null) return

        state = State.SHOWING
        val now = SystemClock.uptimeMillis()
        shownAtUptime = now
        lastMovementUptime = now
        fadeStartUptime = 0L
        lastFrameNanos = 0L
        // The frozen frame is already the current state of the fold, so start there
        // instead of easing in from wherever the previous run finished.
        currentOpenness = targetOpenness
        requestFrame()
    }

    // --- overlay ---------------------------------------------------------------------

    private fun showOverlay() {
        if (overlay != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // Deliberately touchable. Android caps an untrusted overlay that lets
                // touches through at 80% opacity (anti-tapjacking), and at 80% the frozen
                // frame blends with the live screen and reads as a rendering fault. A
                // touchable window may be fully opaque — and swallowing taps for the
                // second the screen is frozen is the right behaviour anyway.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Without this the window stops at the navigation bar and the bottom strip
            // of the real screen stays sharp under the effect.
            fitInsetsTypes = 0
            alpha = 1f
        }

        val view = FoldOverlayView(this)
        view.onTouched = { beginFade() }
        view.onResized = { w, h -> onOverlayResized(w, h) }
        view.settings = settings
        view.openness = currentOpenness
        view.setFrame(frame)

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // Permission revoked while running, or the window manager refused the type.
            Log.e(TAG, "cannot show the overlay", e)
            state = State.IDLE
            frame = null
            return
        }
        overlay = view
        overlayParams = params
        Log.i(TAG, "overlay window added")
    }

    private fun teardownOverlay() {
        cancelFrame()
        main.removeCallbacks(captureTimeout)
        awaitingFrame = false
        parkDisplay()
        val view = overlay
        overlay = null
        overlayParams = null
        if (view != null) {
            view.release()
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "overlay was already gone", e)
            }
        }
        // Dropped, never recycled: the view's display list can still reference it.
        frame = null
        state = State.IDLE
        travelSinceIdle = 0f
        fadeStartUptime = 0L
    }

    /**
     * Unfolding hands the window over to a differently shaped panel part-way through the
     * gesture. A cover-screen frame stretched across the inner screen looks like a fault,
     * so the frozen frame is dropped instead; the hinge is still moving, so the next few
     * events re-arm the capture on the panel that is now lit.
     */
    private fun onOverlayResized(width: Int, height: Int) {
        val bitmap = frame ?: return
        if (width <= 0 || height <= 0) return
        val wanted = width.toFloat() / height
        val have = bitmap.width.toFloat() / bitmap.height
        if (abs(wanted - have) / wanted <= ASPECT_TOLERANCE) return
        Log.i(TAG, "panel changed under the overlay (" + width + "x" + height + "), recapturing")
        // Never remove a window from inside its own layout pass.
        main.post { if (state != State.IDLE) teardownOverlay() }
    }

    private fun setOverlayAlpha(alpha: Float) {
        val view = overlay ?: return
        val params = overlayParams ?: return
        if (abs(params.alpha - alpha) < 0.02f) return
        params.alpha = alpha
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "cannot fade the overlay", e)
        }
    }

    // --- hinge and frames --------------------------------------------------------------

    private fun onHingeAngle(angle: Float) {
        targetOpenness = settings.opennessFor(angle)
        hingeEvents++
        if (hingeEvents == 1L) Log.i(TAG, "first hinge event at " + angle + "°")

        val previous = lastAngle
        lastAngle = angle
        val now = SystemClock.uptimeMillis()
        val sinceLastEvent = now - lastEventUptime
        lastEventUptime = now
        if (previous.isNaN()) return

        val delta = abs(angle - previous)
        if (delta < JITTER_DEG) {
            // A device sitting still still emits events; that is not a fold.
            if (state == State.IDLE && sinceLastEvent > IDLE_RESET_MS) travelSinceIdle = 0f
            return
        }
        lastMovementUptime = now

        when (state) {
            State.IDLE -> {
                if (sinceLastEvent > IDLE_RESET_MS) travelSinceIdle = 0f
                travelSinceIdle += delta
                if (travelSinceIdle >= TRIGGER_DEG) requestCapture()
            }
            State.SHOWING -> requestFrame()
            State.CAPTURING -> Unit
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (state != State.SHOWING || overlay == null) return

        val dt = if (lastFrameNanos == 0L) {
            0f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        }
        lastFrameNanos = frameTimeNanos

        val delta = targetOpenness - currentOpenness
        val settled = abs(delta) < SETTLE_EPSILON
        if (settled) {
            currentOpenness = targetOpenness
        } else {
            val rate = settings.smoothing.coerceIn(2f, 40f)
            currentOpenness += delta * (1f - exp(-dt * rate))
        }

        val now = SystemClock.uptimeMillis()
        val quietFor = now - lastMovementUptime
        // The hard cap is the safety valve: a sensor that stops reporting must not leave
        // a frozen screenshot glued over the phone.
        val expired = now - shownAtUptime > MAX_SHOW_MS
        if (expired || (settled && quietFor > HOLD_MS)) beginFade()

        if (fadeStartUptime != 0L) {
            val t = ((now - fadeStartUptime).toFloat() / FADE_MS).coerceIn(0f, 1f)
            setOverlayAlpha(1f - t)
            if (t >= 1f) {
                teardownOverlay()
                return
            }
        }

        renderFrame()
        requestFrame()
    }

    private fun beginFade() {
        if (state != State.SHOWING || fadeStartUptime != 0L) return
        fadeStartUptime = SystemClock.uptimeMillis()
        requestFrame()
    }

    private fun renderFrame() {
        val view = overlay ?: return
        view.openness = currentOpenness
        view.applyEffect()
    }

    private fun requestFrame() {
        if (frameScheduled || state != State.SHOWING) return
        frameScheduled = true
        choreographer?.postFrameCallback(this)
    }

    private fun cancelFrame() {
        choreographer?.removeFrameCallback(this)
        frameScheduled = false
    }

    // --- notification ------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val notifications = getSystemService(NotificationManager::class.java)
        if (notifications.getNotificationChannel(CHANNEL_ID) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayFoldService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.overlay_notification_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "FoldWall"

        private const val ACTION_STOP = "com.stepdesign.foldwall.OVERLAY_STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "foldwall_overlay"
        private const val NOTIFICATION_ID = 41

        /** Longest edge of the mirrored frame. Anything sharper is spent on a blur. */
        private const val CAPTURE_CAP_PX = 1440

        private const val MAX_IMAGES = 2
        private const val CAPTURE_TIMEOUT_MS = 600L

        /** Degrees of sensor noise to ignore. */
        private const val JITTER_DEG = 0.4f

        /** Accumulated travel that counts as "the user is folding this thing". */
        private const val TRIGGER_DEG = 4f

        /** Movement older than this is a separate gesture, not the same one. */
        private const val IDLE_RESET_MS = 500L

        /** Kept on screen this long after the hinge goes quiet. */
        private const val HOLD_MS = 700L

        /** Hard ceiling on one appearance of the overlay. */
        private const val MAX_SHOW_MS = 6_000L

        private const val FADE_MS = 280f

        private const val SETTLE_EPSILON = 0.0008f

        /** Relative aspect difference that counts as "this is a different screen". */
        private const val ASPECT_TOLERANCE = 0.04f

        @Volatile
        var isRunning: Boolean = false
            private set

        private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

        fun addListener(listener: (Boolean) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (Boolean) -> Unit) {
            listeners.remove(listener)
        }

        private fun setRunning(value: Boolean) {
            isRunning = value
            for (listener in listeners) listener(value)
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, OverlayFoldService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayFoldService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
