package dev.autotapper.core

/**
 * Stand-ins for the plain data types in the app's Recipe.kt, which cannot be
 * compiled here because it also declares Recipe.load(Context, ...) and needs
 * android.graphics.Bitmap to read a template PNG. Engine.kt - the real,
 * unmodified shipped file, and the thing SettleInterruptCheck actually tests -
 * is compiled against these unchanged, so only trivial field containers are
 * duplicated, never any logic.
 *
 * Mirror any field change made to the real Gate/Recipe/Settle/TapSpec/Nudge in
 * Recipe.kt here too, or this link goes stale silently.
 */
sealed class TapSpec {
    object Center : TapSpec()
    data class Fixed(val x: Int, val y: Int) : TapSpec()
    data class Offset(val dx: Int, val dy: Int) : TapSpec()
    object None : TapSpec()
}

class Nudge(val x: Int, val y: Int, val everyMs: Long)

class Settle(
    val threshold: Float = 3.0f,
    val stableForMs: Long = 600,
    val thenWaitMs: Long = 1500,
    val maxWaitMs: Long = 25000,
)

class Gate(
    val name: String,
    val template: Matcher.Template,
    val roi: IntArray,
    val tap: TapSpec,
    val timeoutMs: Long,
    val preDelayMs: LongRange,
    val settle: Settle?,
    val postDelayMs: LongRange,
    val nudge: Nudge?,
    val unstickAfterMs: Long,
    val optional: Boolean,
    val note: String,
) {
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
)
