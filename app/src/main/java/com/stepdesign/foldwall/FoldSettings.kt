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
    val effect: FoldEffect = FoldEffect.CREASE,
    val amount: Float = 0.85f,
    val maxBlur: Float = 34f,
    val dim: Float = 0.22f,
    val desat: Float = 0.30f,
    val chroma: Float = 0.15f,
    val creaseWidth: Float = 0.10f,
    val tintColor: Int = 0xFF3A6FD8.toInt(),
    val tintAmount: Float = 0f,
    val glowColor: Int = 0xFF7FC6FF.toInt(),
    val glowAmount: Float = 0.18f,
    val angleMin: Float = 25f,
    val angleMax: Float = 172f,
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

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): FoldSettings = read(prefs(context))

        fun read(p: SharedPreferences): FoldSettings {
            val d = FoldSettings()
            return FoldSettings(
                effect = FoldEffect.fromId(p.getString("effect", d.effect.id)),
                amount = p.getFloat("amount", d.amount),
                maxBlur = p.getFloat("maxBlur", d.maxBlur),
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
                putString("effect", s.effect.id)
                putFloat("amount", s.amount)
                putFloat("maxBlur", s.maxBlur)
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
