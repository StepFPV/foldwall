package com.stepdesign.foldwall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/**
 * The full-screen overlay's content: one frozen frame of the screen with the fold effect
 * applied straight to the view.
 *
 * A plain `View` with `setRenderEffect`, not a `SurfaceView`: the effect then lives inside
 * the window's own rendering, with no second composited layer to reason about and nothing
 * to keep in sync with the window's own lifecycle.
 */
class FoldOverlayView(context: Context) : View(context) {

    private val effects = FoldEffectBuilder()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    private var frame: Bitmap? = null

    var settings: FoldSettings = FoldSettings()

    /** 0 = shut, 1 = flat open. */
    var openness: Float = 1f

    /**
     * Called on any touch. The window has to be touchable to be allowed full opacity, so
     * it swallows taps while it is up — which makes a tap the natural way to say
     * "enough, give me my screen back".
     */
    var onTouched: (() -> Unit)? = null

    /** Called when the window is laid out at a new size, including a panel hand-over. */
    var onResized: ((Int, Int) -> Unit)? = null

    /**
     * Degrees to turn the frozen frame by before drawing it.
     *
     * The screen is captured in whatever orientation the display had at that instant, and
     * a foldable changes orientation mid-gesture: the inner and cover panels do not share
     * a natural orientation, so a frame captured on one and drawn on the other arrives a
     * quarter turn out.
     */
    var frameRotation: Int = 0

    init {
        // The frozen frame covers every pixel, so let the renderer skip whatever is behind.
        setWillNotDraw(false)
    }

    /** The bitmap stays owned by the caller; this view never recycles it. */
    fun setFrame(bitmap: Bitmap?) {
        frame = bitmap
        invalidate()
    }

    /** Applies the effect for the current [openness]. Cheap: no content redraw. */
    fun applyEffect() {
        if (width <= 0 || height <= 0) return
        setRenderEffect(effects.build(settings, openness, width, height))
    }

    fun release() {
        setRenderEffect(null)
        frame = null
        effects.clear()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) onTouched?.invoke()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyEffect()
        onResized?.invoke(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = frame ?: return
        if (bitmap.isRecycled || width <= 0 || height <= 0) return

        val turn = ((frameRotation % 360) + 360) % 360
        val quarter = turn == 90 || turn == 270

        // Cover, not fit: the capture is downscaled and its aspect can differ from the
        // panel by a rounding, and a letterbox would show the live screen through it.
        // On a quarter turn the bitmap's own axes swap roles against the screen's.
        val scale = if (quarter) {
            max(height.toFloat() / bitmap.width, width.toFloat() / bitmap.height)
        } else {
            max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        }
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val cx = width / 2f
        val cy = height / 2f
        dst.set(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)

        if (turn == 0) {
            canvas.drawBitmap(bitmap, null, dst, paint)
            return
        }
        val saved = canvas.save()
        canvas.rotate(turn.toFloat(), cx, cy)
        canvas.drawBitmap(bitmap, null, dst, paint)
        canvas.restoreToCount(saved)
    }
}
