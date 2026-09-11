package com.stepdesign.foldwall

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Turns a [FoldSettings] plus an openness value into the [RenderEffect] that does the work.
 *
 * Shared by the two very different hosts: the wallpaper and the in-app preview draw through
 * a `HardwareRenderer` and a `RenderNode`, the full-screen overlay applies the same effect
 * straight to a `View`. Only the plumbing differs; the effect must not.
 *
 * Compiled shaders are cached per instance, because compiling AGSL on every frame would
 * cost far more than the effect itself.
 */
class FoldEffectBuilder {

    private val shaderCache = HashMap<FoldEffect, RuntimeShader>()

    fun build(settings: FoldSettings, openness: Float, width: Int, height: Int): RenderEffect {
        val shader = shaderFor(settings.effect)
        val fold = if (settings.invert) openness else 1f - openness

        shader.setFloat2("uSize", width.toFloat(), height.toFloat())
        shader.setFloat1("uOpen", openness)
        shader.setFloat1("uInvert", if (settings.invert) 1f else 0f)
        shader.setFloat1("uAmount", settings.amount)
        shader.setFloat1("uDim", settings.dim)
        shader.setFloat1("uDesat", settings.desat)
        shader.setFloat1("uChroma", settings.chroma)
        shader.setFloat1("uCreaseW", settings.creaseWidth)
        shader.setFloat1("uTintAmt", settings.tintAmount)
        shader.setColor3("uTint", settings.tintColor)
        shader.setFloat1("uGlowAmt", settings.glowAmount)
        shader.setColor3("uGlow", settings.glowColor)

        // A RenderEffect captures the shader uniforms as they are when it is created,
        // so it has to be rebuilt every frame or the animation freezes on frame one.
        val shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, FoldShaders.INPUT_UNIFORM)

        // Peaking half-way reads as an optical transition; growing monotonically reads as
        // a wallpaper that is simply blurred while the phone is shut. Both are wanted.
        val curve = if (settings.blurPeak) sin(fold * PI.toFloat()) else fold
        val blur = settings.maxBlur * curve
        if (blur < MIN_BLUR_PX) return shaderEffect

        // CLAMP, otherwise the blur samples transparent past the edges and the border
        // of the image fades out.
        val blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        return RenderEffect.createChainEffect(shaderEffect, blurEffect)
    }

    fun clear() {
        shaderCache.clear()
    }

    private fun shaderFor(effect: FoldEffect): RuntimeShader {
        shaderCache[effect]?.let { return it }
        val compiled = try {
            RuntimeShader(FoldShaders.sourceFor(effect))
        } catch (e: IllegalArgumentException) {
            // A shader that will not compile must not take the whole wallpaper down.
            Log.e(TAG, "AGSL compile failed for " + effect.id + ", using passthrough", e)
            RuntimeShader(FoldShaders.PASSTHROUGH)
        }
        shaderCache[effect] = compiled
        return compiled
    }

    /** Uniform setters ignore names the compiler stripped, so a lean shader still works. */
    private fun RuntimeShader.setFloat1(name: String, v: Float) {
        try {
            setFloatUniform(name, v)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun RuntimeShader.setFloat2(name: String, a: Float, b: Float) {
        try {
            setFloatUniform(name, a, b)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun RuntimeShader.setColor3(name: String, color: Int) {
        try {
            setFloatUniform(
                name,
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f,
            )
        } catch (_: IllegalArgumentException) {
        }
    }

    private companion object {
        const val TAG = "FoldWall"

        /** Below this a blur costs a full pass and changes nothing visible. */
        const val MIN_BLUR_PX = 0.6f
    }
}
