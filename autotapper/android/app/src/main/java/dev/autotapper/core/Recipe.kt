package dev.autotapper.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/** Where to tap once a gate matches. */
sealed class TapSpec {
    /** Centre of wherever the template actually matched - self-correcting if the UI shifts. */
    object Center : TapSpec()
    /** A fixed point in reference coordinates. */
    data class Fixed(val x: Int, val y: Int) : TapSpec()
    /** An offset from the matched template's top-left. */
    data class Offset(val dx: Int, val dy: Int) : TapSpec()
    /** Wait for the screen but do not tap it. */
    object None : TapSpec()
}

class Nudge(val x: Int, val y: Int, val everyMs: Long)

/**
 * Wait for the screen to stop moving before acting on a gate.
 *
 * A fixed delay is a guess about how long a transition takes. This measures it:
 * a loading overlay or a fade keeps the frame changing, and the screen is only
 * ready once that stops. It needs no template for the loading screen, which
 * matters because the loading screen is precisely the thing nothing recognises.
 */
class Settle(
    val threshold: Float = 3.0f,   // mean abs frame difference below this is "still"
    val stableForMs: Long = 600,   // ...for this long
    val thenWaitMs: Long = 1500,   // ...then hold this long before tapping
    val maxWaitMs: Long = 25000,   // give up waiting and tap anyway
)

class Gate(
    val name: String,
    val template: Matcher.Template,
    val roi: IntArray,           // x0, y0, x1, y1 in reference coords
    val tap: TapSpec,
    val timeoutMs: Long,
    val preDelayMs: LongRange,
    val settle: Settle?,
    val postDelayMs: LongRange,
    val nudge: Nudge?,
    val optional: Boolean,
    val note: String,
) {
    /** Tap point in reference coords, given where the template matched. */
    fun tapPoint(matchX: Int, matchY: Int): Pair<Int, Int>? = when (tap) {
        is TapSpec.None -> null
        is TapSpec.Center -> (matchX + template.w / 2) to (matchY + template.h / 2)
        is TapSpec.Offset -> (matchX + tap.dx) to (matchY + tap.dy)
        is TapSpec.Fixed -> tap.x to tap.y
    }
}

class Recipe(
    val name: String,
    val description: String,
    val refW: Int,
    val refH: Int,
    val threshold: Float,
    val minContrast: Float,
    val confirmFrames: Int,
    val pollMs: Long,
    val jitter: Int,
    val resyncOnStart: Boolean,
    val maxInterruptRepeats: Int,
    val ocrUnstick: Boolean,
    val unstickAfterMs: Long,
    val unstickMax: Int,
    val steps: List<Gate>,
    val interrupts: List<Gate>,
) {
    companion object {
        fun listAvailable(ctx: Context): List<String> =
            (ctx.assets.list("recipes") ?: emptyArray()).toList().sorted()

        fun load(ctx: Context, folder: String): Recipe {
            val root = "recipes/$folder"
            val json = JSONObject(ctx.assets.open("$root/recipe.json").bufferedReader().use { it.readText() })
            val res = json.getJSONArray("reference_resolution")

            fun gray(path: String): Gray {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: throw IllegalStateException("cannot decode template $path")
                val w = bmp.width; val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                bmp.recycle()
                val out = FloatArray(w * h)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    out[i] = Gray.R_W * ((p shr 16) and 0xFF) +
                             Gray.G_W * ((p shr 8) and 0xFF) +
                             Gray.B_W * (p and 0xFF)
                }
                return Gray(w, h, out)
            }

            fun gate(o: JSONObject): Gate {
                val roiArr = o.getJSONArray("roi")
                val roi = IntArray(4) { roiArr.getInt(it) }
                val tap: TapSpec = when (val t = o.opt("tap")) {
                    null, JSONObject.NULL -> TapSpec.None
                    "center" -> TapSpec.Center
                    is org.json.JSONArray -> TapSpec.Fixed(t.getInt(0), t.getInt(1))
                    is JSONObject -> {
                        val off = t.getJSONArray("offset")
                        TapSpec.Offset(off.getInt(0), off.getInt(1))
                    }
                    else -> TapSpec.Center
                }
                val sf = o.optJSONObject("settle_first")
                val settle = if (sf != null) Settle(
                    threshold = sf.optDouble("threshold", 3.0).toFloat(),
                    stableForMs = (sf.optDouble("stable_for", 0.6) * 1000).toLong(),
                    thenWaitMs = (sf.optDouble("then_wait", 1.5) * 1000).toLong(),
                    maxWaitMs = (sf.optDouble("max_wait", 25.0) * 1000).toLong(),
                ) else null
                val pre = o.optJSONArray("pre_delay")
                val hold = if (pre != null)
                    (pre.getDouble(0) * 1000).toLong()..(pre.getDouble(1) * 1000).toLong()
                else 0L..0L
                val pd = o.optJSONArray("post_delay")
                val delay = if (pd != null)
                    (pd.getDouble(0) * 1000).toLong()..(pd.getDouble(1) * 1000).toLong()
                else 500L..900L
                val nu = o.optJSONObject("nudge")?.let {
                    val p = it.getJSONArray("point")
                    Nudge(p.getInt(0), p.getInt(1), (it.optDouble("every", 1.5) * 1000).toLong())
                }
                return Gate(
                    name = o.getString("name"),
                    template = Matcher.Template(gray("$root/templates/${o.getString("template")}")),
                    roi = roi,
                    tap = tap,
                    timeoutMs = (o.optDouble("timeout", 60.0) * 1000).toLong(),
                    preDelayMs = hold,
                    settle = settle,
                    postDelayMs = delay,
                    nudge = nu,
                    optional = o.optBoolean("optional", false),
                    note = o.optString("note", ""),
                )
            }

            fun gates(key: String): List<Gate> {
                val arr = json.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).map { gate(arr.getJSONObject(it)) }
            }

            return Recipe(
                name = json.getString("name"),
                description = json.optString("description", ""),
                refW = res.getInt(0),
                refH = res.getInt(1),
                threshold = json.optDouble("match_threshold", 0.82).toFloat(),
                minContrast = json.optDouble("min_contrast", 18.0).toFloat(),
                confirmFrames = json.optInt("confirm_frames", 2),
                pollMs = (json.optDouble("poll_interval", 0.35) * 1000).toLong(),
                jitter = json.optInt("tap_jitter", 8),
                resyncOnStart = json.optBoolean("resync_on_start", true),
                maxInterruptRepeats = json.optInt("max_interrupt_repeats", 6),
                ocrUnstick = json.optBoolean("ocr_unstick", true),
                unstickAfterMs = (json.optDouble("unstick_after", 20.0) * 1000).toLong(),
                unstickMax = json.optInt("unstick_max", 3),
                steps = gates("steps"),
                interrupts = gates("interrupts"),
            )
        }
    }
}
