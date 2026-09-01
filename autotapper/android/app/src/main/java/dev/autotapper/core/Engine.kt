package dev.autotapper.core

import kotlin.random.Random

/** What the engine can do to the device. Kept abstract so it is testable off-device. */
interface Actuator {
    fun capture(refW: Int, refH: Int): Gray?
    fun tap(refX: Int, refY: Int)
}

interface EngineListener {
    fun onLog(line: String)
    fun onState(loop: Int, totalLoops: Int, step: String)
    fun onFinished(reason: String, ok: Boolean)
}

class MatchInfo(
    val found: Boolean, val score: Float, val contrast: Float,
    val x: Int, val y: Int, val w: Int = 0, val h: Int = 0,
)

/**
 * The same visual-gated state machine as the desktop tool: never tap on a timer,
 * never tap a screen we cannot see, and stop rather than flail when a screen we
 * expect does not arrive.
 */
private const val INTERRUPT_REPEAT_THRESHOLD = 6.0f

class Engine(
    private val recipe: Recipe,
    private val act: Actuator,
    private val listener: EngineListener,
    private val vision: TextVision? = null,
) {
    @Volatile private var stopping = false
    private var interruptStreak = 0
    private var stuckOn: String? = null
    private val interruptLastCrop = HashMap<String, Gray>()
    /** Scratch ROI crops, one per gate, refilled rather than reallocated. */
    private val cropScratch = HashMap<String, Gray>()
    /** Settle detection needs two buffers - one alias and every frame looks identical. */
    private var settleA: Gray? = null
    private var settleB: Gray? = null
    private var heapWarnings = 0
    private val rng = Random(System.nanoTime())

    fun stop() { stopping = true }

    private fun meanAbsDiff(a: Gray, b: Gray): Float {
        if (a.w != b.w || a.h != b.h) return Float.MAX_VALUE
        var acc = 0.0
        for (i in a.px.indices) acc += kotlin.math.abs(a.px[i] - b.px[i])
        return (acc / a.px.size).toFloat()
    }

    /**
     * Block until the screen stops changing. True if it settled within maxWait.
     *
     * Checks interrupts on every poll, same as a normal step. Without this, a
     * popup that appears mid-transition sits there for the full maxWait - or
     * worse, a *static* popup reads as "settled" (nothing is moving) and gets
     * tapped through to whatever is behind it instead of being dismissed.
     */
    private fun waitForSettle(cfg: Settle): Boolean {
        val deadline = System.currentTimeMillis() + cfg.maxWaitMs
        var stableSince = 0L
        var prev: Gray? = null
        while (!stopping && System.currentTimeMillis() < deadline) {
            val f = act.capture(recipe.refW, recipe.refH)
            if (f == null) { sleep(recipe.pollMs); continue }
            if (handleInterrupts(f)) {
                // The screen just changed underneath us; any settle progress
                // and comparison frame are now stale. handleInterrupts already
                // waited out the interrupt's own post_delay, so no extra sleep
                // here - matches the plain step loop above.
                prev = null
                stableSince = 0L
                continue
            }
            // Alternate buffers: reusing one would make small and prev the same
            // array, every difference would be zero, and it would declare the
            // screen settled instantly.
            val useA = (prev !== settleA)
            val small = f.downscaleInto(8, if (useA) settleA else settleB)
                .also { if (useA) settleA = it else settleB = it }
            val p = prev
            if (p != null) {
                val d = meanAbsDiff(small, p)
                if (d <= cfg.threshold) {
                    if (stableSince == 0L) stableSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - stableSince >= cfg.stableForMs) {
                        listener.onLog("      screen settled (diff ${"%.2f".format(d)}), " +
                                "holding ${cfg.thenWaitMs / 1000.0}s")
                        sleep(cfg.thenWaitMs)
                        return true
                    }
                } else stableSince = 0L
            }
            prev = small
            sleep(recipe.pollMs)
        }
        if (!stopping) listener.onLog("      still moving after ${cfg.maxWaitMs / 1000}s - tapping anyway")
        return false
    }

    // ---- OCR fallback -----------------------------------------------------
    //
    // The template gates only know the screens they were cut from. When one of
    // them is stuck, read the words on screen and dismiss the dialog by its
    // button text instead. This is the part that handles a popup nobody has ever
    // templated.
    //
    // The policy below is deliberately deterministic and lives here, not in the
    // vision layer: text recognition decides what the words ARE, never what is
    // safe to tap.

    /**
     * Read the screen and dismiss whatever is on it. Returns true if it tapped.
     */
    private fun unstick(frame: Gray, why: String): Boolean {
        val v = vision ?: return false
        val words = v.read(frame)
        if (words == null) { listener.onLog("    ocr unavailable"); return false }
        if (words.isEmpty()) { listener.onLog("    ocr: no text found"); return false }

        // Log what was read either way. When this fails, the words it saw are the
        // whole diagnosis, and without them the next report is guesswork.
        val readable = words.map { it.text }.filter { it.isNotBlank() }
        listener.onLog("    ocr read ${readable.size} words: ${readable.take(14).joinToString(" ")}")
        return when (val d = DismissPolicy.decide(words)) {
            is DismissPolicy.Decision.Refuse -> {
                listener.onLog("    ocr: '${d.blocker}' sits beside the button - refusing to guess here")
                false
            }
            is DismissPolicy.Decision.Tap -> {
                listener.onLog("    ocr: nothing templated matched for $why, but found " +
                        "'${d.word.text}' (${d.group}) - tapping it")
                doTap(d.word.cx, d.word.cy, "ocr:${d.word.text}")
                true
            }
            is DismissPolicy.Decision.NothingFound -> {
                listener.onLog("    ocr: none of those is a button it knows how to press")
                false
            }
        }
    }

    fun evaluate(frame: Gray, gate: Gate): MatchInfo {
        val roi = frame.cropInto(gate.roi[0], gate.roi[1], gate.roi[2], gate.roi[3],
            cropScratch[gate.name]).also { cropScratch[gate.name] = it }
        val contrast = roi.std()

        var best: MatchInfo? = null
        for (tpl in gate.templates) {
            if (roi.w < tpl.w || roi.h < tpl.h) continue
            val r = Matcher.find(roi, tpl)
            if (best == null || r.score > best!!.score) {
                best = MatchInfo(false, r.score, contrast, r.x + gate.roi[0], r.y + gate.roi[1], tpl.w, tpl.h)
            }
        }
        val b = best ?: return MatchInfo(false, 0f, contrast, 0, 0)
        // Normalised correlation ignores brightness, so a screen fading in from
        // black scores ~0.99 while still invisible and not yet accepting touches.
        val ok = b.score >= recipe.threshold && contrast >= recipe.minContrast
        return MatchInfo(ok, b.score, contrast, b.x, b.y, b.w, b.h)
    }

    /** One screenshot, every gate scored, plus the frame itself for preview. */
    fun probe(): Pair<Gray, List<Pair<Gate, MatchInfo>>>? {
        val frame = act.capture(recipe.refW, recipe.refH) ?: return null
        return frame to (recipe.steps + recipe.interrupts).map { it to evaluate(frame, it) }
    }

    val allGates: List<Gate> get() = recipe.steps + recipe.interrupts

    private fun doTap(x: Int, y: Int, label: String) {
        val j = recipe.jitter
        val jx = x + rng.nextInt(-j, j + 1)
        val jy = y + rng.nextInt(-j, j + 1)
        act.tap(jx, jy)
        listener.onLog("      tap $label ($jx,$jy)")
    }

    private fun settle(range: LongRange) {
        val ms = if (range.first >= range.last) range.first
                 else rng.nextLong(range.first, range.last + 1)
        sleep(ms)
    }

    private fun sleep(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!stopping && System.currentTimeMillis() < end) {
            Thread.sleep(minOf(50L, maxOf(1L, end - System.currentTimeMillis())))
        }
    }

    /** Returns true if an interrupt fired and was dismissed. */
    private fun handleInterrupts(frame: Gray): Boolean {
        for (g in recipe.interrupts) {
            val m = evaluate(frame, g)
            if (m.found) {
                // Dokkan can queue several of these in a row - one friend-request
                // confirmation per borrowed support after a multi-clear - each
                // with a different name and avatar. Counting raw consecutive
                // dismissals cannot tell that apart from a tap that keeps
                // missing the same button: both look like "this interrupt fired
                // six times in a row". Only a repeat that shows the SAME screen
                // as last time is evidence the tap isn't landing.
                //
                // The comparison has to stay within the interrupt's own ROI,
                // not the whole frame: a changed name/avatar is a small
                // fraction of a 1080x2340 screen, and a whole-frame diff
                // dilutes it into noise below the threshold - caught by the
                // test for this fix classifying 8 genuinely different popups
                // as "unchanged". Allocates a fresh crop per firing rather than
                // reusing a buffer - interrupts are rare events, not the
                // steady-state poll this app was once burning 24MB/s on.
                val crop = frame.crop(g.roi[0], g.roi[1], g.roi[2], g.roi[3])
                val prev = interruptLastCrop[g.name]
                val changed = prev == null || prev.w != crop.w || prev.h != crop.h ||
                        meanAbsDiff(crop, prev) > INTERRUPT_REPEAT_THRESHOLD
                interruptLastCrop[g.name] = crop

                listener.onLog("    ! ${g.name} (${"%.3f".format(m.score)}) -> dismissing" +
                        if (changed) "" else "  (same as last time)")
                g.tapPoint(m.x, m.y, m.w, m.h)?.let { doTap(it.first, it.second, g.name) }

                if (changed) {
                    interruptStreak = 0
                } else {
                    interruptStreak++
                    if (interruptStreak > recipe.maxInterruptRepeats) {
                        // The template's tap is missing the button. Read the dialog
                        // and press the right one instead of hammering it.
                        if (recipe.ocrUnstick && vision != null && unstick(frame, g.name)) {
                            interruptStreak = 0
                            settle(g.postDelayMs)
                            return true
                        }
                        stuckOn = g.name
                        return true
                    }
                }
                settle(g.postDelayMs)
                return true
            }
        }
        return false
    }

    private fun runStep(gate: Gate): Boolean {
        val deadline = System.currentTimeMillis() + gate.timeoutMs
        val started = System.currentTimeMillis()
        var hits = 0
        var lastNudge = 0L
        var best = 0f
        var unstickTries = 0
        var lastUnstick = 0L

        while (!stopping && System.currentTimeMillis() < deadline) {
            val frame = act.capture(recipe.refW, recipe.refH)
            if (frame == null) { sleep(recipe.pollMs); continue }

            if (handleInterrupts(frame)) {
                if (stuckOn != null) return false
                continue
            }

            if (heapIsCritical()) {
                listener.onLog("    ! memory critical (${(heapUsedFraction() * 100).toInt()}%) - stopping")
                return false
            }

            val m = evaluate(frame, gate)
            if (m.score > best) best = m.score

            if (m.found) {
                hits++
                if (hits >= recipe.confirmFrames) {
                    interruptStreak = 0
                    val secs = (System.currentTimeMillis() - started) / 1000.0
                    listener.onLog("    ${gate.name} seen (${"%.3f".format(m.score)}, ${"%.1f".format(secs)}s)")
                    // Hold before tapping. A screen can be drawn and matched
                    // before it will actually accept input; tapping into that gap
                    // does nothing and the loop stalls waiting for a transition
                    // that never started.
                    val sf = gate.settle
                    if (sf != null) {
                        waitForSettle(sf)
                    } else if (gate.preDelayMs.last > 0) {
                        listener.onLog("      holding ${gate.preDelayMs.first / 1000.0}s before tapping")
                        settle(gate.preDelayMs)
                    }
                    gate.tapPoint(m.x, m.y, m.w, m.h)?.let { doTap(it.first, it.second, gate.name) }
                    settle(gate.postDelayMs)
                    return true
                }
            } else {
                hits = 0
                val nudge = gate.nudge
                if (nudge != null && System.currentTimeMillis() - lastNudge >= nudge.everyMs) {
                    doTap(nudge.x, nudge.y, "${gate.name}:nudge")
                    lastNudge = System.currentTimeMillis()
                }
                // Waiting far longer than expected usually means an unknown
                // dialog is in the way. Read it rather than sit out the timeout.
                val now = System.currentTimeMillis()
                // Per-step, because "waited too long" means different things: a
                // modal blocking a tap should be read within seconds, while a
                // battle legitimately takes over a minute with nothing to press.
                val after = if (gate.unstickAfterMs >= 0) gate.unstickAfterMs else recipe.unstickAfterMs
                if (recipe.ocrUnstick && vision != null && unstickTries < recipe.unstickMax &&
                    now - started > after && now - lastUnstick > after
                ) {
                    unstickTries++
                    lastUnstick = now
                    if (unstick(frame, gate.name)) settle(600L..900L)
                }
            }
            sleep(recipe.pollMs)
        }

        if (stopping) return false
        stuckOn?.let {
            listener.onLog("    '$it' was dismissed ${recipe.maxInterruptRepeats}+ times and the "
                    + "screen never changed.")
            listener.onLog("    The tap is most likely missing the button - this popup probably "
                    + "has a layout the template does not cover.")
            return false
        }
        if (gate.optional) {
            listener.onLog("    ${gate.name} not seen (optional, skipping)")
            return true
        }
        listener.onLog("    ${gate.name} TIMEOUT after ${gate.timeoutMs / 1000}s " +
                "(best ${"%.3f".format(best)}, needed ${recipe.threshold})")
        return false
    }

    /**
     * Which step is the game already on?
     *
     * The loop otherwise always starts at step 1, so starting while the game sits
     * mid-cycle means waiting for it to come all the way round - on a real run
     * that cost 83 seconds. Two gates can be live at once (the results banner is
     * still up when the OK button appears), so take the LAST match: that is the
     * most advanced state.
     */
    private fun resync(): Int? {
        repeat(6) {
            if (stopping) return null
            val frame = act.capture(recipe.refW, recipe.refH)
            if (frame == null) { sleep(recipe.pollMs); return@repeat }
            if (handleInterrupts(frame)) return@repeat
            var at: Int? = null
            for ((i, gate) in recipe.steps.withIndex()) {
                if (evaluate(frame, gate).found) at = i
            }
            if (at != null) return at
            // Nothing matched - most likely a transition between screens rather
            // than an unknown state. Give it a moment before giving up.
            sleep(recipe.pollMs * 2)
        }
        return null
    }

    /**
     * Fraction of the heap in use. Sustained capture-and-match is memory hungry
     * enough that running out is a real failure mode, and one that takes more
     * than this app down with it - so stop while stopping is still possible.
     */
    private fun heapUsedFraction(): Float {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()).toFloat() / rt.maxMemory().toFloat()
    }

    private fun heapIsCritical(): Boolean {
        if (heapUsedFraction() < 0.85f) { heapWarnings = 0; return false }
        heapWarnings++
        if (heapWarnings == 1)
            listener.onLog("    ! heap at ${(heapUsedFraction() * 100).toInt()}% - watching")
        return heapWarnings >= 5
    }

    fun run(loops: Int) {
        listener.onLog("recipe: ${recipe.name}")
        listener.onLog("steps : ${recipe.steps.joinToString(" -> ") { it.name }}")
        if (recipe.interrupts.isNotEmpty())
            listener.onLog("watch : ${recipe.interrupts.joinToString(", ") { it.name }} (any time)")
        val t0 = System.currentTimeMillis()
        var done = 0
        var startAt = 0
        if (recipe.resyncOnStart) {
            val at = resync()
            when {
                at == null -> listener.onLog("  no known screen recognised - starting from the top")
                at > 0 -> {
                    listener.onLog("  already at '${recipe.steps[at].name}' - resuming there")
                    startAt = at
                }
            }
        }
        for (n in 1..loops) {
            if (stopping) break
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
            val maxMb = rt.maxMemory() / 1048576
            listener.onLog("  loop $n/$loops   heap ${usedMb}/${maxMb} MB")
            for (gate in recipe.steps.drop(startAt)) {
                listener.onState(n, loops, gate.name)
                if (!runStep(gate)) {
                    if (stopping) {
                        listener.onFinished("stopped after $done loop(s)", true)
                    } else {
                        listener.onFinished("stopped in loop $n at '${gate.name}'", false)
                    }
                    return
                }
            }
            startAt = 0
            done++
        }
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val per = if (done > 0) " (${"%.1f".format(secs / done)}s per loop)" else ""
        listener.onFinished("$done loop(s) in ${"%.0f".format(secs)}s$per", true)
    }
}
