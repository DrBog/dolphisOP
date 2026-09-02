import dev.autotapper.core.*

/**
 * Proves a run of the SAME interrupt with genuinely different content on each
 * occurrence (a different friend's name and avatar, say) does not trip the
 * "stuck" guard, while a run showing the IDENTICAL screen every time still
 * does. Dokkan can legitimately queue one friend-request confirmation per
 * borrowed support after a multi-clear, and the guard exists to catch a tap
 * that keeps missing the button - not six DIFFERENT popups in a row that were
 * each dismissed successfully.
 *
 * This is the Kotlin mirror of the desktop tool's
 * tools/tests/test_interrupt_repeats.py - same construction, same assertions.
 *
 * Run with: gradle run --args="repeats"
 */

private const val RW = 400
private const val RH = 100

private fun repTile(w: Int, h: Int, phase: Int): Gray {
    val px = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        px[y * w + x] = if ((x / 4 + y / 4 + phase) % 2 == 0) 20f else 235f
    }
    return Gray(w, h, px)
}

/**
 * The OK button (fixed pattern, x0..40) plus a "name and avatar" region
 * (x40..80) that differs between occurrences of the SAME popup. Both sit
 * inside the interrupt's own ROI (0,0,80,30), same as a real popup's name and
 * avatar sit inside its modal box - the fix this checks only compares within
 * that ROI, not the whole screen.
 */
private fun repPopupFrame(contentPhase: Int): Gray {
    val g = Gray(RW, RH, FloatArray(RW * RH) { 60f })
    val button = repTile(40, 30, 5)
    val content = repTile(40, 30, contentPhase)
    for (y in 0 until 30) for (x in 0 until 40) {
        g.px[y * RW + x] = button.px[y * 40 + x]
        g.px[y * RW + 40 + x] = content.px[y * 40 + x]
    }
    return g
}

private fun repStageFrame(): Gray {
    val g = Gray(RW, RH, FloatArray(RW * RH) { 60f })
    val stage = repTile(40, 30, 9)
    for (y in 0 until 30) for (x in 0 until 40) g.px[y * RW + 300 + x] = stage.px[y * 40 + x]
    return g
}

private class RepeatActuator(private val frames: List<Gray>) : Actuator {
    var index = 0
    val taps = mutableListOf<Pair<Int, Int>>()
    override fun capture(refW: Int, refH: Int): Gray {
        val f = frames[minOf(index, frames.size - 1)]
        index++
        return f
    }
    override fun tap(refX: Int, refY: Int) { taps.add(refX to refY) }
}

private class RepeatListener : EngineListener {
    var finishedOk: Boolean? = null
    val debugDumps = mutableListOf<String>()
    override fun onLog(line: String) {}
    override fun onState(loop: Int, totalLoops: Int, step: String) {}
    override fun onFinished(reason: String, ok: Boolean) { finishedOk = ok }
    override fun onDebugFrame(frame: Gray, tag: String, rows: List<Pair<Gate, MatchInfo>>) {
        debugDumps.add(tag)
    }
}

private fun repGate(name: String, roi: IntArray, tpl: Gray): Gate = Gate(
    name = name, templates = listOf(Matcher.Template(tpl)), roi = roi, tap = TapSpec.Center,
    timeoutMs = 5000, preDelayMs = 0L..0L, settle = null, postDelayMs = 0L..0L,
    nudge = null, unstickAfterMs = -1L, optional = false, note = "",
)

/** Neither popup nor stage visible - a plain background frame. */
private fun repBlankFrame(): Gray = Gray(RW, RH, FloatArray(RW * RH) { 60f })

private fun execScenario(
    frames: List<Gray>, maxRepeats: Int, confirmFrames: Int = 1,
): Pair<RepeatActuator, RepeatListener> {
    val popupGate = repGate("popup", intArrayOf(0, 0, 80, 30), repTile(40, 30, 5))
    val stageGate = repGate("stage", intArrayOf(300, 0, 340, 30), repTile(40, 30, 9))
    val recipe = Recipe(
        name = "synthetic", description = "", refW = RW, refH = RH,
        threshold = 0.9f, minContrast = 5.0f, confirmFrames = confirmFrames, pollMs = 1L, jitter = 0,
        resyncOnStart = false, maxInterruptRepeats = maxRepeats,
        ocrUnstick = false, unstickAfterMs = 100_000L, unstickMax = 0,
        steps = listOf(stageGate), interrupts = listOf(popupGate),
    )
    val act = RepeatActuator(frames)
    val listener = RepeatListener()
    Engine(recipe, act, listener).run(1)
    return act to listener
}

fun repeatsMain(): Int {
    var failed = 0

    // Case 1: 8 DIFFERENT popups in a row, exceeding maxInterruptRepeats(6) -
    // must NOT get stuck, must still reach the stage tap, and since nothing
    // failed there is nothing to dump a debug frame for.
    run {
        val frames = (0 until 8).map { repPopupFrame(it) } + listOf(repStageFrame())
        val (act, listener) = execScenario(frames, maxRepeats = 6)
        val ok = listener.finishedOk == true && act.taps.size == 9 && listener.debugDumps.isEmpty()
        println("${if (ok) "ok  " else "FAIL"}  8 distinct repeats do not trip the stuck guard " +
                "(taps=${act.taps.size}, finishedOk=${listener.finishedOk}, dumps=${listener.debugDumps})")
        if (!ok) failed++
    }

    // Case 2: 8 IDENTICAL popups in a row - this is the case the guard exists
    // for, and must still trip it, AND leave behind a debug frame naming the
    // stuck interrupt - the whole point of dumping one is so a stall like this
    // is diagnosable without the log alone.
    run {
        val frames = (0 until 8).map { repPopupFrame(0) } + listOf(repStageFrame())
        val (act, listener) = execScenario(frames, maxRepeats = 6)
        val ok = listener.finishedOk == false && act.taps.size == 8 &&
                listener.debugDumps == listOf("stuck_popup")
        println("${if (ok) "ok  " else "FAIL"}  8 identical repeats still trip the stuck guard, " +
                "and dump a debug frame (taps=${act.taps.size}, finishedOk=${listener.finishedOk}, " +
                "dumps=${listener.debugDumps})")
        if (!ok) failed++
    }

    println()
    println(if (failed == 0) "PASS - repeats of a genuinely changing popup are not mistaken for a stuck tap"
            else "FAIL - $failed case(s) wrong")
    return if (failed == 0) 0 else 1
}

/**
 * Proves an interrupt now needs confirmFrames consecutive matches before it
 * taps anything - the fix for a live run that matched friend_request at
 * 1.000 and tapped the OTHER known layout's button position, on a screen
 * that a moment later was showing a plain single-OK popup with no button at
 * that location at all. A one-frame compositing artifact between two
 * queued dialogs looks exactly like a real popup for a single poll; a
 * genuine dialog stays up for many. Steps already required this; interrupts
 * never did.
 *
 * Run with: gradle run --args="confirm"
 */
fun confirmMain(): Int {
    val popupTap = 20 to 15    // centre of the 40x30 popup template at roi (0,0,80,30)
    val stageTap = 320 to 15   // centre of the 40x30 stage template at roi (300,0,340,30)

    // A stray single-frame match, gone the next frame, must NOT be tapped.
    // Then the SAME popup appears again and stays for confirmFrames(2)
    // frames - that occurrence must still be dismissed normally.
    val frames = listOf(
        repPopupFrame(0),   // transient - one frame only
        repBlankFrame(),    // gone - resets the confirm counter
        repPopupFrame(0), repPopupFrame(0),  // stable - two frames, confirmed
        repStageFrame(),
    )
    val (act, listener) = execScenario(frames, maxRepeats = 6, confirmFrames = 2)
    val ok = listener.finishedOk == true && act.taps == listOf(popupTap, stageTap)
    println("${if (ok) "ok  " else "FAIL"}  a one-frame popup is not tapped, a two-frame one is " +
            "(taps=${act.taps}, finishedOk=${listener.finishedOk})")

    println()
    println(if (ok) "PASS - interrupts require the same confirmation steps already do"
            else "FAIL - confirmation guard is not working")
    return if (ok) 0 else 1
}
