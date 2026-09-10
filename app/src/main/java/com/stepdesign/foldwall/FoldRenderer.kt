package com.stepdesign.foldwall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import android.view.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws one frame of the fold effect onto a [Surface].
 *
 * Shared by the live wallpaper engine and the in-app preview so both show exactly the
 * same pixels. The wallpaper image is recorded into [contentNode] once per surface or
 * image change; per frame only the [RenderEffect] is rebuilt, which is cheap.
 */
class FoldRenderer {

    private val rootNode = RenderNode("foldwall-root")
    private val contentNode = RenderNode("foldwall-content")
    private var renderer: HardwareRenderer? = null
    private var surface: Surface? = null

    private var width = 0
    private var height = 0

    private var bitmap: Bitmap? = null
    private var bitmapKey: String? = null

    private val shaderCache = HashMap<FoldEffect, RuntimeShader>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val gradientPaint = Paint()
    private val dst = RectF()

    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.MONOSPACE
    }
    private val debugBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0000000.toInt() }

    /** 0 = shut, 1 = flat open. Set by the host before each [draw]. */
    var openness: Float = 1f

    /** Multi-line text drawn sharp on top of the effect when diagnostics are on. */
    var debugText: String? = null

    var settings: FoldSettings = FoldSettings()
        set(value) {
            val previous = field
            field = value
            val contentChanged = previous.imagePath != value.imagePath ||
                previous.bgTop != value.bgTop ||
                previous.bgBottom != value.bgBottom
            if (contentChanged) {
                bitmapKey = null
                reloadContent()
            }
        }

    val hasSurface: Boolean get() = surface?.isValid == true && renderer != null

    fun attach(newSurface: Surface?, newWidth: Int, newHeight: Int) {
        surface = newSurface
        width = newWidth
        height = newHeight

        if (newSurface == null || !newSurface.isValid || newWidth <= 0 || newHeight <= 0) {
            renderer?.setSurface(null)
            return
        }

        val active = renderer ?: HardwareRenderer().also {
            it.setName("FoldWall")
            it.setOpaque(true)
            it.setContentRoot(rootNode)
            renderer = it
        }
        active.setSurface(newSurface)
        active.setLightSourceGeometry(newWidth / 2f, 0f, 800f, 800f)
        active.setLightSourceAlpha(0f, 0f)

        rootNode.setPosition(0, 0, newWidth, newHeight)
        contentNode.setPosition(0, 0, newWidth, newHeight)

        // Panels differ in aspect, so the decode is tied to the surface, not cached globally.
        bitmapKey = null
        reloadContent()
    }

    fun detach() {
        renderer?.setSurface(null)
        surface = null
    }

    fun destroy() {
        renderer?.let {
            it.setSurface(null)
            it.destroy()
        }
        renderer = null
        surface = null
        contentNode.discardDisplayList()
        rootNode.discardDisplayList()
        shaderCache.clear()
        bitmap = null
        bitmapKey = null
    }

    fun draw(vsyncNanos: Long) {
        val active = renderer ?: return
        val target = surface ?: return
        if (!target.isValid || width <= 0 || height <= 0) return

        contentNode.setRenderEffect(buildEffect())

        val canvas = rootNode.beginRecording(width, height)
        try {
            canvas.drawRenderNode(contentNode)
            debugText?.let { drawDebug(canvas, it) }
        } finally {
            rootNode.endRecording()
        }

        active.createRenderRequest()
            .setVsyncTime(vsyncNanos)
            .syncAndDraw()
    }

    // --- effect ------------------------------------------------------------------

    private fun buildEffect(): RenderEffect {
        val s = settings
        val shader = shaderFor(s.effect)
        val fold = if (s.invert) openness else 1f - openness

        shader.setFloat2("uSize", width.toFloat(), height.toFloat())
        shader.setFloat1("uOpen", openness)
        shader.setFloat1("uInvert", if (s.invert) 1f else 0f)
        shader.setFloat1("uAmount", s.amount)
        shader.setFloat1("uDim", s.dim)
        shader.setFloat1("uDesat", s.desat)
        shader.setFloat1("uChroma", s.chroma)
        shader.setFloat1("uCreaseW", s.creaseWidth)
        shader.setFloat1("uTintAmt", s.tintAmount)
        shader.setColor3("uTint", s.tintColor)
        shader.setFloat1("uGlowAmt", s.glowAmount)
        shader.setColor3("uGlow", s.glowColor)

        // A RenderEffect captures the shader uniforms as they are when it is created,
        // so it has to be rebuilt every frame or the animation freezes on frame one.
        val shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, FoldShaders.INPUT_UNIFORM)

        val blur = s.maxBlur * fold
        if (blur < MIN_BLUR_PX) return shaderEffect

        // CLAMP, otherwise the blur samples transparent past the edges and the border
        // of the wallpaper fades out.
        val blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        return RenderEffect.createChainEffect(shaderEffect, blurEffect)
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

    // --- content -----------------------------------------------------------------

    private fun reloadContent() {
        if (width <= 0 || height <= 0) return
        loadBitmap()
        recordContent()
    }

    private fun loadBitmap() {
        val path = settings.imagePath
        val key = path + "@" + width + "x" + height
        if (key == bitmapKey) return
        bitmapKey = key
        bitmap = if (path.isNullOrBlank()) null else decode(path, width, height)
    }

    private fun decode(path: String, reqW: Int, reqH: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "wallpaper image missing: " + path)
            return null
        }
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val sw = info.size.width
                val sh = info.size.height
                if (sw > 0 && sh > 0) {
                    // Cover the surface, never decode a 50MP frame at full size.
                    val scale = max(reqW.toFloat() / sw, reqH.toFloat() / sh).coerceAtMost(1f)
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            max(1, (sw * scale).roundToInt()),
                            max(1, (sh * scale).roundToInt()),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot decode " + path, e)
            null
        }
    }

    private fun recordContent() {
        if (width <= 0 || height <= 0) return
        val canvas = contentNode.beginRecording(width, height)
        try {
            val bmp = bitmap
            if (bmp != null && !bmp.isRecycled) {
                val scale = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val left = (width - dw) / 2f
                val top = (height - dh) / 2f
                dst.set(left, top, left + dw, top + dh)
                canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            } else {
                gradientPaint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    settings.bgTop, settings.bgBottom, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
            }
        } finally {
            contentNode.endRecording()
        }
    }

    private fun drawDebug(canvas: Canvas, text: String) {
        val lines = text.split('\n')
        val pad = 20f
        val top = 160f
        val lineHeight = debugPaint.textSize * 1.35f
        var widest = 0f
        for (line in lines) widest = max(widest, debugPaint.measureText(line))
        canvas.drawRoundRect(
            pad, top,
            pad + widest + pad * 2f,
            top + lineHeight * lines.size + pad * 2f,
            18f, 18f, debugBgPaint,
        )
        var y = top + pad + debugPaint.textSize
        for (line in lines) {
            canvas.drawText(line, pad * 2f, y, debugPaint)
            y += lineHeight
        }
    }

    private companion object {
        const val TAG = "FoldWall"

        /** Below this a blur costs a full pass and changes nothing visible. */
        const val MIN_BLUR_PX = 0.6f
    }
}
