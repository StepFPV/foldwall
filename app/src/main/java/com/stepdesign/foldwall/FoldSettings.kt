package com.stepdesign.foldwall

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Everything the renderer needs, in one immutable snapshot.
 *
 * Angles are degrees straight off the hinge sensor; [angleMin]/[angleMax] map that raw
 * range onto 0..1 openness. They are user-tunable because the window a wallpaper engine
 * actually observes depends on when the firmware hands the panel over, and that is not
 * something an app can discover ahead of time.
 */
data class FoldSettings(
    // The defaults are the Duo look, because that is the thing the app exists to do.
    // They used to be a gentle crease over 25–172°, which on a real Fold means a two-pixel
    // blur at any angle the inner panel is actually lit for: indistinguishable from off.
    val effect: FoldEffect = FoldEffect.DUO,
    val amount: Float = 0f,
    val maxBlur: Float = 72f,
    /** False: blur grows with the fold. True: it peaks half-open and clears at both ends. */
    val blurPeak: Boolean = false,
    val dim: Float = 0.62f,
    val desat: Float = 0f,
    val chroma: Float = 0f,
    val creaseWidth: Float = 0.10f,
    val tintColor: Int = 0xFF3A6FD8.toInt(),
    val tintAmount: Float = 0f,
    val glowColor: Int = 0xFF7FC6FF.toInt(),
    val glowAmount: Float = 0f,
    // A Galaxy Fold lights the inner panel around 90°; below that the wallpaper is handed
    // nothing. Starting there is a far better guess than 0–180, and the engine then
    // learns the device's real window anyway.
    val angleMin: Float = 90f,
    val angleMax: Float = 175f,
    val invert: Boolean = false,
    val smoothing: Float = 14f,
    val debug: Boolean = false,
    val imagePath: String? = null,
    val bgTop: Int = 0xFF101A2E.toInt(),
    val bgBottom: Int = 0xFF2E1030.toInt(),
) {
    /** Maps a raw hinge angle in degrees onto 0 (shut) .. 1 (flat). */
    fun opennessFor(angleDeg: Float): Float {
        val lo = minOf(angleMin, angleMax)
        val hi = maxOf(angleMin, angleMax)
        if (hi - lo < 1f) return 1f
        val t = ((angleDeg - lo) / (hi - lo)).coerceIn(0f, 1f)
        // Smoothstep keeps the ends calm so tiny sensor jitter near flat is invisible.
        return t * t * (3f - 2f * t)
    }

    companion object {
        private const val PREFS = "foldwall_settings"

        /**
         * Bumped whenever the defaults change in a way an existing install has to adopt.
         *
         * Stored settings win over defaults key by key, which is right for anything the user
         * picked and wrong for everything they did not. Without this, every look default
         * changed after someone's first run was dead on their phone: they kept the crease and
         * the two-pixel blur the app shipped with on the day they installed it, while the app
         * went on claiming the defaults were the Duo look. That is exactly what happened
         * between v1.0 and v1.5.
         */
        private const val SCHEMA = 2

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** True when [load] has just moved an older install onto the current look. */
        @Volatile
        var migrated: Boolean = false
            private set

        fun load(context: Context): FoldSettings {
            val p = prefs(context)
            if (!p.contains("effect") || p.getInt("schema", 1) >= SCHEMA) return read(p)

            val carried = read(p)
            // Keep what the user chose for themselves — their picture, their background, how
            // closely it follows the hinge — and adopt the look, which they never chose.
            val moved = FoldSettings(
                imagePath = carried.imagePath,
                bgTop = carried.bgTop,
                bgBottom = carried.bgBottom,
                smoothing = carried.smoothing,
                invert = carried.invert,
                debug = carried.debug,
            )
            save(context, moved)
            migrated = true
            return moved
        }

        fun read(p: SharedPreferences): FoldSettings {
            val d = FoldSettings()
            return FoldSettings(
                effect = FoldEffect.fromId(p.getString("effect", d.effect.id)),
                amount = p.getFloat("amount", d.amount),
                maxBlur = p.getFloat("maxBlur", d.maxBlur),
                blurPeak = p.getBoolean("blurPeak", d.blurPeak),
                dim = p.getFloat("dim", d.dim),
                desat = p.getFloat("desat", d.desat),
                chroma = p.getFloat("chroma", d.chroma),
                creaseWidth = p.getFloat("creaseWidth", d.creaseWidth),
                tintColor = p.getInt("tintColor", d.tintColor),
                tintAmount = p.getFloat("tintAmount", d.tintAmount),
                glowColor = p.getInt("glowColor", d.glowColor),
                glowAmount = p.getFloat("glowAmount", d.glowAmount),
                angleMin = p.getFloat("angleMin", d.angleMin),
                angleMax = p.getFloat("angleMax", d.angleMax),
                invert = p.getBoolean("invert", d.invert),
                smoothing = p.getFloat("smoothing", d.smoothing),
                debug = p.getBoolean("debug", d.debug),
                imagePath = p.getString("imagePath", d.imagePath),
                bgTop = p.getInt("bgTop", d.bgTop),
                bgBottom = p.getInt("bgBottom", d.bgBottom),
            )
        }

        fun save(context: Context, s: FoldSettings) {
            prefs(context).edit {
                putInt("schema", SCHEMA)
                putString("effect", s.effect.id)
                putFloat("amount", s.amount)
                putFloat("maxBlur", s.maxBlur)
                putBoolean("blurPeak", s.blurPeak)
                putFloat("dim", s.dim)
                putFloat("desat", s.desat)
                putFloat("chroma", s.chroma)
                putFloat("creaseWidth", s.creaseWidth)
                putInt("tintColor", s.tintColor)
                putFloat("tintAmount", s.tintAmount)
                putInt("glowColor", s.glowColor)
                putFloat("glowAmount", s.glowAmount)
                putFloat("angleMin", s.angleMin)
                putFloat("angleMax", s.angleMax)
                putBoolean("invert", s.invert)
                putFloat("smoothing", s.smoothing)
                putBoolean("debug", s.debug)
                if (s.imagePath == null) remove("imagePath") else putString("imagePath", s.imagePath)
                putInt("bgTop", s.bgTop)
                putInt("bgBottom", s.bgBottom)
            }
        }
    }
}

/** Named starting points; each one only overrides the look, never the calibration. */
enum class FoldPreset(val label: String, val apply: (FoldSettings) -> FoldSettings) {
    /**
     * A 72px blur over the whole frame and a darkening strong enough to read as the panel
     * going out, with nothing else — no crease, no tint, no aberration.
     *
     * The numbers come from a third-party reconstruction of Apple's transition, not from
     * Apple's own footage, and a second reconstruction disagrees with it: that one blurs
     * one half with a gradient from the crease outward and leaves the other sharp. Which
     * is right is unsettled, so read this as one plausible shape rather than the shape.
     */
    DUO("Duo", { s ->
        s.copy(
            effect = FoldEffect.DUO, amount = 0f, maxBlur = 72f, dim = 0.62f,
            desat = 0f, chroma = 0f, creaseWidth = 0.10f, tintAmount = 0f,
            glowAmount = 0f, blurPeak = false, invert = false,
        )
    }),
    ORIGINALE("Originale", { s ->
        s.copy(
            effect = FoldEffect.CREASE, amount = 0.85f, maxBlur = 34f, dim = 0.22f,
            desat = 0.30f, chroma = 0.15f, creaseWidth = 0.10f, tintAmount = 0f,
            glowAmount = 0.18f, glowColor = 0xFF7FC6FF.toInt(), invert = false,
        )
    }),
    SOFFICE("Soffice", { s ->
        s.copy(
            effect = FoldEffect.DEPTH, amount = 0.55f, maxBlur = 58f, dim = 0.30f,
            desat = 0.45f, chroma = 0f, creaseWidth = 0.18f, tintAmount = 0f,
            glowAmount = 0.05f, invert = false,
        )
    }),
    CINEMA("Cinema", { s ->
        s.copy(
            effect = FoldEffect.GLASS, amount = 1f, maxBlur = 46f, dim = 0.34f,
            desat = 0.15f, chroma = 0.45f, creaseWidth = 0.13f,
            tintAmount = 0.30f, tintColor = 0xFF2B5FD9.toInt(),
            glowAmount = 0.32f, glowColor = 0xFF9FD8FF.toInt(), invert = false,
        )
    }),
    LIBRO("Libro", { s ->
        s.copy(
            effect = FoldEffect.PAGE, amount = 0.9f, maxBlur = 12f, dim = 0.18f,
            desat = 0.10f, chroma = 0f, creaseWidth = 0.14f, tintAmount = 0f,
            glowAmount = 0f, invert = false,
        )
    }),
    NEON("Neon", { s ->
        s.copy(
            effect = FoldEffect.RIPPLE, amount = 0.75f, maxBlur = 26f, dim = 0.40f,
            desat = 0.55f, chroma = 0.35f, creaseWidth = 0.09f,
            tintAmount = 0.55f, tintColor = 0xFFB13AD8.toInt(),
            glowAmount = 0.5f, glowColor = 0xFF43F5D0.toInt(), invert = false,
        )
    }),
}
