package com.stepdesign.foldwall

/**
 * The five distortion styles the wallpaper can run.
 *
 * Every effect shares the same colour grading, crease glow and chromatic-aberration
 * stages; they differ only in how they warp the sampled coordinate and how they shade
 * the result. [agslBody] therefore supplies just `warp()` and `shade()` and is spliced
 * into [FoldShaders.PRELUDE] / [FoldShaders.EPILOGUE].
 */
enum class FoldEffect(
    val id: String,
    val label: String,
    val blurb: String,
    val agslBody: String,
) {
    CREASE(
        id = "crease",
        label = "Piega",
        blurb = "Valle d'ombra sulla cerniera con riflesso laterale. Il più vicino all'originale.",
        agslBody = """
            float2 warp(float2 coord, float fold) {
                float2 c = uSize * 0.5;
                float d = dcx(coord);
                float ad = abs(d);
                // Squeeze stays small on purpose: a large one exposes the clamped
                // edges as visible bands behind the launcher icons.
                float squeeze = 1.0 - 0.06 * uAmount * fold;
                float sx = c.x + (coord.x - c.x) / squeeze;
                float bow = 0.030 * uAmount * fold * (1.0 - ad * ad);
                float sy = c.y + (coord.y - c.y) * (1.0 - bow);
                return float2(sx, sy);
            }

            float3 shade(float3 rgb, float2 coord, float fold) {
                float ad = abs(dcx(coord));
                float w = max(uCreaseW, 0.02);
                float valley = exp(-(ad * ad) / (w * w));
                rgb *= 1.0 - 0.55 * uAmount * fold * valley;
                float lit = (ad - w * 1.9) / (w * 0.9);
                float sheen = exp(-lit * lit) * uAmount * fold * 0.14;
                return rgb + sheen;
            }
        """,
    ),

    GLASS(
        id = "glass",
        label = "Vetro",
        blurb = "Rifrazione da lente lungo la piega, come vetro smerigliato che si inarca.",
        agslBody = """
            float2 warp(float2 coord, float fold) {
                float d = dcx(coord);
                float w = max(uCreaseW, 0.03);
                // Derivative of a gaussian bump: the classic thin-lens displacement.
                float disp = -d / (w * w) * exp(-(d * d) / (w * w));
                float dx = disp * uSize.x * 0.030 * uAmount * fold;
                return float2(coord.x + dx, coord.y);
            }

            float3 shade(float3 rgb, float2 coord, float fold) {
                float d = dcx(coord);
                float w = max(uCreaseW, 0.03);
                float g = exp(-(d * d) / (w * w));
                // Bright rim either side of the lens, dark core: reads as thick glass.
                float rim = exp(-((abs(d) - w * 1.3) / (w * 0.7)) * ((abs(d) - w * 1.3) / (w * 0.7)));
                rgb *= 1.0 - 0.28 * uAmount * fold * g;
                return rgb + rim * uAmount * fold * 0.20;
            }
        """,
    ),

    PAGE(
        id = "page",
        label = "Pagina",
        blurb = "La metà destra si arrotola come un foglio che gira, con ombra di costa.",
        agslBody = """
            // Arc-length curl: the right half wraps onto a cylinder whose total bend
            // angle grows with the fold. warp() inverts that mapping to find the
            // source texel for a given output column.
            //
            // The sin(a) normalisation matters: without it the curled half projects
            // narrower than the screen and leaves a black wedge at the right edge,
            // which reads as a rendering bug rather than as a page.
            float pageAngle(float fold) {
                return max(fold * uAmount * 1.35, 1e-3);
            }

            float pageParam(float u, float a) {
                float s = clamp((u - 0.5) * 2.0 * sin(a), -1.0, 1.0);
                return asin(s) / a;
            }

            float2 warp(float2 coord, float fold) {
                float u = coord.x / uSize.x;
                if (u <= 0.5) {
                    return coord;
                }
                float t = pageParam(u, pageAngle(fold));
                return float2((0.5 + t * 0.5) * uSize.x, coord.y);
            }

            float3 shade(float3 rgb, float2 coord, float fold) {
                float u = coord.x / uSize.x;
                float w = max(uCreaseW * 0.5, 0.01);
                float spine = exp(-((u - 0.5) / w) * ((u - 0.5) / w));
                rgb *= 1.0 - 0.35 * uAmount * fold * spine;
                if (u <= 0.5) {
                    return rgb;
                }
                float a = pageAngle(fold);
                float t = pageParam(u, a);
                // cos of the surface tilt, floored: a fully dark outer edge looks
                // broken behind launcher icons.
                float facing = 0.30 + 0.70 * clamp(cos(t * a), 0.0, 1.0);
                return rgb * mix(1.0, facing, uAmount * fold);
            }
        """,
    ),

    DEPTH(
        id = "depth",
        label = "Profondità",
        blurb = "Nessuna deformazione: solo zoom, vignettatura e sfocatura. Il più sobrio.",
        agslBody = """
            float2 warp(float2 coord, float fold) {
                float2 c = uSize * 0.5;
                float z = 1.0 + 0.14 * uAmount * fold;
                return c + (coord - c) / z;
            }

            float3 shade(float3 rgb, float2 coord, float fold) {
                float2 p = (coord / uSize - 0.5) * 2.0;
                float r = clamp(dot(p, p) * 0.5, 0.0, 1.0);
                return rgb * (1.0 - 0.55 * uAmount * fold * r);
            }
        """,
    ),

    RIPPLE(
        id = "ripple",
        label = "Onda",
        blurb = "Onda che parte dalla piega e si smorza verso i bordi.",
        agslBody = """
            float2 warp(float2 coord, float fold) {
                float d = dcx(coord);
                float w = max(uCreaseW, 0.05);
                float amp = 0.030 * uAmount * fold * uSize.x;
                float ph = d / w * 3.14159265;
                float env = exp(-abs(d) * 1.6);
                return float2(
                    coord.x + sin(ph) * amp * env,
                    coord.y + cos(ph * 0.7) * amp * 0.35 * env);
            }

            float3 shade(float3 rgb, float2 coord, float fold) {
                float d = dcx(coord);
                float w = max(uCreaseW, 0.05);
                float ph = d / w * 3.14159265;
                float env = exp(-abs(d) * 1.6);
                return rgb * (1.0 + cos(ph) * env * 0.22 * uAmount * fold);
            }
        """,
    ),
    ;

    companion object {
        fun fromId(id: String?): FoldEffect = entries.firstOrNull { it.id == id } ?: CREASE
    }
}
