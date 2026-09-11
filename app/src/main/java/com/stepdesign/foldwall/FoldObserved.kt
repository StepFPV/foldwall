package com.stepdesign.foldwall

import android.content.Context
import androidx.core.content.edit

/**
 * The span of hinge angles the live wallpaper has actually *seen*.
 *
 * This is not the same as 0–180°. The firmware lights the inner panel part-way through an
 * unfold — on a Galaxy Fold around 90° — and the engine receives no sensor events while its
 * panel is dark. So the effect is normally half over before anyone can look at it, which is
 * exactly what makes a hand-tuned range worth having.
 *
 * Rather than ask the user to read the number off a debug overlay, the engine records it.
 * A few folds are enough to learn the real window, and the settings screen offers it back.
 *
 * Kept in its own preferences file on purpose: [FoldSettings] has a change listener that
 * reloads the renderer, and a value written on every sensor event has no business waking it.
 */
object FoldObserved {

    private const val PREFS = "foldwall_observed"
    private const val KEY_MIN = "seenMin"
    private const val KEY_MAX = "seenMax"

    /** Below this a new extreme is sensor noise, not new information. */
    private const val EPSILON_DEG = 0.5f

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call with every angle the engine sees while its own panel is lit. */
    fun record(context: Context, angle: Float) {
        if (angle.isNaN()) return
        val p = prefs(context)
        val min = p.getFloat(KEY_MIN, Float.NaN)
        val max = p.getFloat(KEY_MAX, Float.NaN)

        val newMin = if (min.isNaN() || angle < min - EPSILON_DEG) angle else null
        val newMax = if (max.isNaN() || angle > max + EPSILON_DEG) angle else null
        if (newMin == null && newMax == null) return

        p.edit {
            if (newMin != null) putFloat(KEY_MIN, newMin)
            if (newMax != null) putFloat(KEY_MAX, newMax)
        }
    }

    /** Null until the wallpaper has been live long enough to have seen a real range. */
    fun read(context: Context): ClosedFloatingPointRange<Float>? {
        val p = prefs(context)
        val min = p.getFloat(KEY_MIN, Float.NaN)
        val max = p.getFloat(KEY_MAX, Float.NaN)
        if (min.isNaN() || max.isNaN() || max - min < 5f) return null
        return min..max
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }
}
