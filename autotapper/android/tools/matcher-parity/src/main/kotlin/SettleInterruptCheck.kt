import dev.autotapper.core.*

/**
 * Proves a popup appearing during a settle-wait gets dismissed instead of
 * ignored - the defect fixed alongside this test. Constructs two synthetic
 * gates (a "stage" step with settle_first, and a "popup" interrupt) and a
 * scripted actuator that shows the popup for exactly one poll in the middle
 * of the settle-wait, then removes it.
 *
 * Before the fix, waitForSettle never called handleInterrupts: a static popup
 * reads as "nothing is moving", so it would have been read as SETTLED and the
 * engine would have tapped straight through it. After the fix, the popup must
 * be dismissed first, and only then does the settle-wait complete.
 *
 * Run with: gradle run --args="settle"
 */

private const val W = 400
private const val H = 200

/** A distinct checkerboard-ish pattern, high-contrast and clearly not a flat fill. */
private fun pattern(w: Int, h: Int, phase: Int): Gray {
    val px = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        px[y * w + x] = if ((x / 4 + y / 4 + phase) % 2 == 0) 20f else 235f
    }
    return Gray(w, h, px)
}

/** A full frame with the stage pattern fixed at x0..40,y0..30, and the popup
 *  pattern present or absent at x100..140,y0..30. Everything else is flat. */
private fun frame(popupPresent: Boolean): Gray {
    val px = FloatArray(W * H) { 60f }
    val g = Gray(W, H, px)
    val stage = pattern(40, 30, 0)
    for (y in 0 until 30) for (x in 0 until 40) g.px[y * W + x] = stage.px[y * 40 + x]
    if (popupPresent) {
        val popup = pattern(40, 30, 1)
        for (y in 0 until 30) for (x in 0 until 40) g.px[y * W + 100 + x] = popup.px[y * 40 + x]
    }
    return g
}

private class ScriptedActuator(private val frames: List<Gray>) : Actuator {
    var index = 0
    val taps = mutableListOf<Pair<Int, Int>>()
    override fun capture(refW: Int, refH: Int): Gray {
        val f = frames[minOf(index, frames.size - 1)]
        index++
        return f
    }
    override fun tap(refX: Int, refY: Int) { taps.add(refX to refY) }
}

private class RecordingListener : EngineListener {
    val lines = mutableListOf<String>()
    var finishedOk: Boolean? = null
    override fun onLog(line: String) { lines.add(line) }
    override fun onState(loop: Int, totalLoops: Int, step: String) {}
    override fun onFinished(reason: String, ok: Boolean) { finishedOk = ok; lines.add("finished: $reason") }
}

private fun gate(name: String, roiX: Int, tpl: Gray, settle: Settle?): Gate = Gate(
    name = name,
    templates = listOf(Matcher.Template(tpl)),
    roi = intArrayOf(roiX, 0, roiX + tpl.w, tpl.h),
    tap = TapSpec.Center,
    timeoutMs = 5000,
    preDelayMs = 0L..0L,
    settle = settle,
    postDelayMs = 0L..0L,
    nudge = null,
    unstickAfterMs = -1L,
    optional = false,
    note = "",
)

fun settleMain(): Int {
    val stageGate = gate("stage", 0, pattern(40, 30, 0),
        Settle(threshold = 3.0f, stableForMs = 0, thenWaitMs = 0, maxWaitMs = 2000))
    val popupGate = gate("popup", 100, pattern(40, 30, 1), null)

    val recipe = Recipe(
        name = "synthetic", description = "", refW = W, refH = H,
        threshold = 0.9f, minContrast = 5.0f, confirmFrames = 1, pollMs = 1L, jitter = 0,
        resyncOnStart = false, maxInterruptRepeats = 10,
        ocrUnstick = false, unstickAfterMs = 100_000L, unstickMax = 0,
        steps = listOf(stageGate), interrupts = listOf(popupGate),
    )

    // frame 0: stage visible, no popup - satisfies the step match.
    // frame 1: popup appears mid settle-wait - must be dismissed, not ignored.
    // frame 2+: popup gone, screen unchanged - settle-wait now completes.
    val act = ScriptedActuator(listOf(frame(false), frame(true), frame(false), frame(false), frame(false)))
    val listener = RecordingListener()
    Engine(recipe, act, listener).run(1)

    println("taps recorded: ${act.taps}")
    println("finished ok  : ${listener.finishedOk}")

    val poppedFirst = act.taps.size == 2 && act.taps[0] == (120 to 15) && act.taps[1] == (20 to 15)
    println()
    if (poppedFirst && listener.finishedOk == true) {
        println("PASS - the popup was dismissed during the settle-wait, before the stage tap")
        return 0
    }
    println("FAIL - expected exactly [popup tap, stage tap]; a popup during settle-wait was " +
            "ignored (waitForSettle read a static popup as \"settled\" and tapped through it)")
    for (l in listener.lines) println("  log: $l")
    return 1
}
