package com.stepdesign.foldwall

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Asks the compositor to blur whatever is already on screen, and reports whether it did.
 *
 * The overlay mode freezes a screenshot and blurs that. This asks for something different:
 * a bare overlay window carrying [WindowManager.LayoutParams.FLAG_BLUR_BEHIND], which makes
 * SurfaceFlinger blur the layers *behind* it. Nothing is captured, so there is no consent
 * dialog, no recording indicator, no black rectangle where a bank app used to be, and no
 * frozen frame that can end up a quarter turn out.
 *
 * It works on an AOSP emulator. Whether One UI honours it is the open question, and the only
 * way to answer it is to ask the device — hence this probe. Two findings from the emulator are
 * baked in below: the window must be focusable, and the radius is picked up on a later
 * placement pass rather than at the moment the window is added.
 */
object LiveBlurProbe {

    private const val TAG = "FoldWall"

    /**
     * Strong enough to be unmistakable, short of the point where a dark low-contrast screen
     * turns to mush and cannot be told apart from a display that simply went black.
     */
    private const val RADIUS_PX = 90

    /** Long enough to see, and to reach the home screen while it is up. */
    private const val DURATION_MS = 6_000L

    /**
     * A blur asked for at the instant the window is added did not appear on the emulator;
     * the same radius re-applied a moment later did. Nudge it once, so a single placement
     * pass cannot be the reason the answer comes back "no".
     */
    private const val NUDGE_MS = 250L

    private val main = Handler(Looper.getMainLooper())

    private var window: View? = null

    /**
     * Why the system might be saying no, read from the switches that are allowed to say it.
     *
     * `isCrossWindowBlurEnabled` gives a verdict and no reason. These three are the documented
     * reasons, so reading them separates "a setting on this phone" from "the vendor does not
     * offer it" — which is the difference between something to turn off and a wall.
     */
    private fun diagnose(context: Context, enabled: Boolean): String {
        val disabledGlobally = try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                "disable_window_blurs",
                0,
            ) != 0
        } catch (e: Exception) {
            false
        }
        val powerSave = try {
            context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        } catch (e: Exception) {
            false
        }
        val supported = try {
            // The compositor's own answer, set per device at build time. False here means the
            // hardware path was never enabled, whatever the user's settings say.
            @Suppress("PrivateApi")
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, "ro.surface_flinger.supports_background_blur", "?") as String
        } catch (e: Exception) {
            "?"
        }
        return "il sistema dice sfocature " + (if (enabled) "ATTIVE" else "DISATTIVATE") +
            "  ·  interruttore \"disable_window_blurs\": " + (if (disabledGlobally) "ACCESO" else "spento") +
            "  ·  risparmio energetico: " + (if (powerSave) "ACCESO" else "spento") +
            "  ·  il compositore dichiara supporto: " + supported
    }

    /**
     * Runs the probe. [report] is called with a line to show the user: first when the blur has
     * been asked for, then again when it is over.
     */
    fun run(context: Context, report: (String) -> Unit) {
        if (window != null) return
        if (!Settings.canDrawOverlays(context)) {
            report("Serve prima il permesso \"Mostra sopra altre app\".")
            return
        }
        val wm = context.getSystemService(WindowManager::class.java)
        if (wm == null) {
            report("Non riesco a parlare con il gestore finestre.")
            return
        }

        val enabled = try {
            wm.isCrossWindowBlurEnabled
        } catch (e: Exception) {
            Log.w(TAG, "cannot read isCrossWindowBlurEnabled", e)
            false
        }
        // Deliberately no early return on `false`. The flag is the system's declaration, not
        // proof: it goes false for battery saver and for the reduce-transparency setting, and
        // a vendor can leave it false while the compositor still honours the request. On a
        // Fold 8 it reads false with all three of those switches off, which is the claim that
        // needs testing rather than believing. So ask anyway, and let the screen answer.
        val why = diagnose(context, enabled)
        Log.i(TAG, "live blur probe: " + why)

        val view = View(context)
        // A window that draws nothing at all can be left out of composition, and the blur
        // behind it with it. One percent of black is invisible and keeps it composing.
        view.setBackgroundColor(0x02000000)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Deliberately *not* FLAG_NOT_FOCUSABLE: with it the compositor applied no blur
            // at all, and SurfaceFlinger reported no blurred layer. FLAG_NOT_TOUCHABLE is
            // fine, so touches still reach whatever is underneath.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT,
        )
        params.fitInsetsTypes = 0
        params.blurBehindRadius = RADIUS_PX

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "live blur probe: cannot add the window", e)
            report("Non riesco ad aggiungere la finestra: " + e.javaClass.simpleName)
            return
        }
        window = view
        Log.i(TAG, "live blur probe: asked for radius " + RADIUS_PX)
        report(
            "Sfocatura chiesta al sistema, per " + DURATION_MS / 1000 + " secondi. Guarda lo " +
                "schermo — e se vuoi premi Home adesso: la prova resta in piedi, così la " +
                "vedi sulla schermata principale con le tue icone.\n\n" + why,
        )

        main.postDelayed({
            val held = window ?: return@postDelayed
            params.blurBehindRadius = RADIUS_PX
            try {
                wm.updateViewLayout(held, params)
            } catch (e: Exception) {
                Log.w(TAG, "live blur probe: cannot re-apply the radius", e)
            }
        }, NUDGE_MS)

        main.postDelayed({
            val held = window ?: return@postDelayed
            window = null
            try {
                wm.removeViewImmediate(held)
            } catch (e: Exception) {
                Log.w(TAG, "live blur probe: cannot remove the window", e)
            }
            Log.i(TAG, "live blur probe: finished")
            report(
                "Finita. La finestra è stata aggiunta e la sfocatura chiesta davvero, " +
                    "nonostante il sistema dicesse di no: quindi adesso conta solo cosa " +
                    "hai visto.\n\n" +
                    "Schermo andato fuori fuoco — icone e app comprese → si può fare, e " +
                    "quel \"no\" era una dichiarazione sbagliata.\n" +
                    "Niente di niente → One UI non la concede, e la strada è chiusa " +
                    "davvero.\n\n" + why,
            )
        }, DURATION_MS)
    }
}
