package com.stepdesign.foldwall

/**
 * Shared AGSL scaffolding around each [FoldEffect]'s `warp()` / `shade()` pair.
 *
 * `uOpen` is 0 when the device is shut and 1 when it lies flat; `fold` inside the
 * shader is the inverse (or `uOpen` itself when the user inverts the effect), so every
 * term reads "how much of the effect to apply right now".
 */
object FoldShaders {

    private const val PRELUDE = """
        uniform shader content;

        uniform float2 uSize;     // surface size in px
        uniform float uOpen;      // 0 = closed, 1 = flat open
        uniform float uInvert;    // >0.5 flips which end of the travel is "folded"
        uniform float uAmount;    // master warp intensity, 0..1
        uniform float uDim;       // darkening when folded, 0..1
        uniform float uDesat;     // desaturation when folded, 0..1
        uniform float uChroma;    // chromatic aberration, 0..1
        uniform float uCreaseW;   // crease half-width, normalised 0.02..0.5
        uniform float uTintAmt;   // 0..1
        uniform float3 uTint;
        uniform float uGlowAmt;   // 0..1
        uniform float3 uGlow;

        // Signed horizontal distance from the crease, -1 at the left edge, +1 at the right.
        float dcx(float2 coord) {
            return (coord.x / uSize.x - 0.5) * 2.0;
        }
    """

    private const val EPILOGUE = """
        float3 creaseGlow(float3 rgb, float2 coord, float fold) {
            float d = abs(dcx(coord));
            float w = max(uCreaseW, 0.02);
            float g = exp(-(d * d) / (w * w));
            return rgb + uGlow * (uGlowAmt * fold * g);
        }

        float3 grade(float3 rgb, float fold) {
            float lum = dot(rgb, float3(0.2126, 0.7152, 0.0722));
            rgb = mix(rgb, float3(lum), uDesat * fold);
            // Duotone-style tint: keeps the luminance structure, swaps the hue.
            rgb = mix(rgb, uTint * (0.35 + 0.65 * lum), uTintAmt * fold);
            return rgb * (1.0 - uDim * fold);
        }

        half4 main(float2 coord) {
            float open = clamp(uOpen, 0.0, 1.0);
            float fold = uInvert > 0.5 ? open : 1.0 - open;

            float2 lo = float2(0.5, 0.5);
            float2 hi = uSize - 0.5;
            float2 src = warp(coord, fold);

            float3 rgb;
            float ca = uChroma * fold * 0.010 * uSize.x;
            if (ca > 0.25) {
                float2 off = float2(ca * dcx(coord), 0.0);
                rgb = float3(
                    content.eval(clamp(src + off, lo, hi)).r,
                    content.eval(clamp(src,       lo, hi)).g,
                    content.eval(clamp(src - off, lo, hi)).b);
            } else {
                rgb = float3(content.eval(clamp(src, lo, hi)).rgb);
            }

            rgb = shade(rgb, coord, fold);
            rgb = creaseGlow(rgb, coord, fold);
            rgb = grade(rgb, fold);
            return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);
        }
    """

    /** Name of the input-shader uniform, passed to `RenderEffect.createRuntimeShaderEffect`. */
    const val INPUT_UNIFORM = "content"

    /** Last resort if an effect fails to compile: show the wallpaper, drop the effect. */
    const val PASSTHROUGH = """
        uniform shader content;
        half4 main(float2 coord) {
            return content.eval(coord);
        }
    """

    fun sourceFor(effect: FoldEffect): String =
        PRELUDE + effect.agslBody + EPILOGUE
}
