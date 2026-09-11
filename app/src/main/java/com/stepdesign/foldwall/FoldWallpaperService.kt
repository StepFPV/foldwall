package com.stepdesign.foldwall

import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The live wallpaper itself.
 *
 * The engine only draws while it is visible and while the openness value is still
 * moving; once the hinge settles it goes completely idle until the next sensor event.
 * A wallpaper that renders at 60fps forever is a battery bug, not a feature.
 */
class FoldWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FoldEngine()

    private inner class FoldEngine : Engine(), Choreographer.FrameCallback {

        private val renderer = FoldRenderer()
        private var hinge: HingeSource? = null
        private var choreographer: Choreographer? = null

        private var visible = false
        private var frameScheduled = false

        /** On wake the previous openness is stale; the first reading must snap, not ease. */
        private var firstSample = true

        private var targetOpenness = 1f
        private var currentOpenness = 1f
        private var lastFrameNanos = 0L
        private var sweepPhase = 0f

        private var surfaceWidth = 0
        private var surfaceHeight = 0

        // Held as a field on purpose: SharedPreferences keeps only a weak reference and
        // an inline lambda would be collected, silently killing live updates.
        private val prefListener =
            SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
                // One callback fires per changed key, and a settings save writes every
                // key, so most of these are duplicates of a state we already hold.
                val next = FoldSettings.read(prefs)
                if (next != renderer.settings) {
                    renderer.settings = next
                    requestFrame()
                }
            }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setOffsetNotificationsEnabled(false)
            setTouchEventsEnabled(false)

            choreographer = Choreographer.getInstance()
            renderer.settings = FoldSettings.load(this@FoldWallpaperService)

            val source = HingeSource(this@FoldWallpaperService) { onHingeAngle(it) }
            hinge = source
            FoldSettings.prefs(this@FoldWallpaperService)
                .registerOnSharedPreferenceChangeListener(prefListener)

            Log.i(
                TAG,
                "engine created preview=" + isPreview + " hingeSensor=" + source.available,
            )
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            renderer.attach(holder.surface, width, height)
            Log.i(TAG, "surface " + width + "x" + height)
            drawNow()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            cancelFrame()
            renderer.detach()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                firstSample = true
                lastFrameNanos = 0L
                hinge?.start()
                requestFrame()
            } else {
                hinge?.stop()
                cancelFrame()
            }
        }

        override fun onDestroy() {
            cancelFrame()
            hinge?.stop()
            hinge = null
            FoldSettings.prefs(this@FoldWallpaperService)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
            renderer.destroy()
            super.onDestroy()
        }

        private fun onHingeAngle(angle: Float) {
            targetOpenness = renderer.settings.opennessFor(angle)
            if (firstSample) {
                firstSample = false
                currentOpenness = targetOpenness
            }
            requestFrame()
        }

        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!visible || !renderer.hasSurface) return

            val dt = if (lastFrameNanos == 0L) {
                0f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            lastFrameNanos = frameTimeNanos

            val animating: Boolean
            if (isPreview) {
                // Nobody can fold the device while standing in the wallpaper picker,
                // so run a slow sweep to show what the effect actually does.
                sweepPhase = (sweepPhase + dt / SWEEP_SECONDS) % 1f
                val tri = if (sweepPhase < 0.5f) sweepPhase * 2f else (1f - sweepPhase) * 2f
                currentOpenness = tri * tri * (3f - 2f * tri)
                animating = true
            } else {
                val delta = targetOpenness - currentOpenness
                if (abs(delta) < SETTLE_EPSILON) {
                    currentOpenness = targetOpenness
                    animating = false
                } else {
                    val rate = renderer.settings.smoothing.coerceIn(2f, 40f)
                    currentOpenness += delta * (1f - exp(-dt * rate))
                    animating = true
                }
            }

            renderFrame(frameTimeNanos)
            if (animating) requestFrame()
        }

        private fun drawNow() {
            renderFrame(System.nanoTime())
        }

        private fun renderFrame(vsyncNanos: Long) {
            renderer.openness = currentOpenness
            renderer.debugText = if (renderer.settings.debug) debugText() else null
            renderer.draw(vsyncNanos)
        }

        private fun requestFrame() {
            if (frameScheduled || !visible) return
            frameScheduled = true
            choreographer?.postFrameCallback(this)
        }

        private fun cancelFrame() {
            choreographer?.removeFrameCallback(this)
            frameScheduled = false
        }

        private fun debugText(): String {
            val s = renderer.settings
            val source = hinge
            val raw = source?.lastAngle ?: Float.NaN
            val angle = if (raw.isNaN()) "--" else String.format(Locale.US, "%.1f", raw)
            return buildString {
                append("FoldWall  ").append(if (isPreview) "anteprima" else "attivo").append('\n')
                append("sensore  ").append(if (source?.available == true) "ok" else "ASSENTE").append('\n')
                append("angolo   ").append(angle).append("°  ev=").append(source?.eventCount ?: 0).append('\n')
                append("apertura ").append(String.format(Locale.US, "%.3f", currentOpenness)).append('\n')
                append("range    ")
                    .append(s.angleMin.roundToInt()).append("-").append(s.angleMax.roundToInt())
                    .append("°\n")
                append("effetto  ").append(s.effect.id).append('\n')
                append("surface  ").append(surfaceWidth).append("x").append(surfaceHeight)
            }
        }
    }

    private companion object {
        const val TAG = "FoldWall"

        /** Openness delta below which the animation is considered finished. */
        const val SETTLE_EPSILON = 0.0008f

        /** Seconds for one closed-open-closed cycle of the picker preview sweep. */
        const val SWEEP_SECONDS = 3.2f
    }
}
