package com.stepdesign.foldwall

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Live preview of the wallpaper inside the settings screen, driven by the same
 * [FoldRenderer] the wallpaper uses.
 *
 * The point is tuning without folding: drag the angle slider (or let [autoSweep] run)
 * and see the exact result. On a device with no hinge sensor this is the only way to
 * see the effect at all.
 */
class FoldPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val renderer = FoldRenderer()
    private var frameScheduled = false
    private var surfaceReady = false
    private var lastFrameNanos = 0L
    private var sweepPhase = 0f

    var settings: FoldSettings = FoldSettings()
        set(value) {
            if (field == value) return
            field = value
            renderer.settings = value
            requestFrame()
        }

    /** 0 = shut, 1 = flat open. Driven straight from the slider, no easing. */
    var openness: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            requestFrame()
        }

    var autoSweep: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            lastFrameNanos = 0L
            requestFrame()
        }

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        renderer.settings = settings
        renderer.attach(holder.surface, width, height)
        drawFrame(System.nanoTime())
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        cancelFrame()
        renderer.detach()
    }

    override fun onDetachedFromWindow() {
        cancelFrame()
        renderer.destroy()
        surfaceReady = false
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!surfaceReady) return

        if (autoSweep) {
            val dt = if (lastFrameNanos == 0L) {
                0f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            sweepPhase = (sweepPhase + dt / SWEEP_SECONDS) % 1f
            val tri = if (sweepPhase < 0.5f) sweepPhase * 2f else (1f - sweepPhase) * 2f
            openness = tri * tri * (3f - 2f * tri)
        }
        lastFrameNanos = frameTimeNanos

        drawFrame(frameTimeNanos)
        if (autoSweep) requestFrame()
    }

    private fun drawFrame(vsyncNanos: Long) {
        renderer.openness = openness
        renderer.draw(vsyncNanos)
    }

    private fun requestFrame() {
        if (frameScheduled || !surfaceReady) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun cancelFrame() {
        Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
    }

    private companion object {
        const val SWEEP_SECONDS = 3.2f
    }
}
