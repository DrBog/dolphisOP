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

class MatchInfo(val found: Boolean, val score: Float, val contrast: Float, val x: Int, val y: Int)

/**
 * The same visual-gated state machine as the desktop tool: never tap on a timer,
 * never tap a screen we cannot see, and stop rather than flail when a screen we
 * expect does not arrive.
 */
class Engine(
    private val recipe: Recipe,
    private val act: Actuator,
    private val listener: EngineListener,
) {
    @Volatile private var stopping = false
    private val rng = Random(System.nanoTime())

    fun stop() { stopping = true }

    fun evaluate(frame: Gray, gate: Gate): MatchInfo {
        val roi = frame.crop(gate.roi[0], gate.roi[1], gate.roi[2], gate.roi[3])
        if (roi.w < gate.template.w || roi.h < gate.template.h) return MatchInfo(false, 0f, 0f, 0, 0)
        val contrast = roi.std()
        val r = Matcher.find(roi, gate.template)
        val ax = r.x + gate.roi[0]
        val ay = r.y + gate.roi[1]
        // Normalised correlation ignores brightness, so a screen fading in from
        // black scores ~0.99 while still invisible and not yet accepting touches.
        val ok = r.score >= recipe.threshold && contrast >= recipe.minContrast
        return MatchInfo(ok, r.score, contrast, ax, ay)
    }

    /** One screenshot, every gate scored. Used by the Probe button. */
    fun probe(): List<Pair<Gate, MatchInfo>> {
        val frame = act.capture(recipe.refW, recipe.refH) ?: return emptyList()
        return (recipe.steps + recipe.interrupts).map { it to evaluate(frame, it) }
    }

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
                listener.onLog("    ! ${g.name} (${"%.3f".format(m.score)}) -> dismissing")
                g.tapPoint(m.x, m.y)?.let { doTap(it.first, it.second, g.name) }
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

        while (!stopping && System.currentTimeMillis() < deadline) {
            val frame = act.capture(recipe.refW, recipe.refH)
            if (frame == null) { sleep(recipe.pollMs); continue }

            if (handleInterrupts(frame)) continue

            val m = evaluate(frame, gate)
            if (m.score > best) best = m.score

            if (m.found) {
                hits++
                if (hits >= recipe.confirmFrames) {
                    val secs = (System.currentTimeMillis() - started) / 1000.0
                    listener.onLog("    ${gate.name} seen (${"%.3f".format(m.score)}, ${"%.1f".format(secs)}s)")
                    gate.tapPoint(m.x, m.y)?.let { doTap(it.first, it.second, gate.name) }
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
            }
            sleep(recipe.pollMs)
        }

        if (stopping) return false
        if (gate.optional) {
            listener.onLog("    ${gate.name} not seen (optional, skipping)")
            return true
        }
        listener.onLog("    ${gate.name} TIMEOUT after ${gate.timeoutMs / 1000}s " +
                "(best ${"%.3f".format(best)}, needed ${recipe.threshold})")
        return false
    }

    fun run(loops: Int) {
        listener.onLog("recipe: ${recipe.name}")
        listener.onLog("steps : ${recipe.steps.joinToString(" -> ") { it.name }}")
        if (recipe.interrupts.isNotEmpty())
            listener.onLog("watch : ${recipe.interrupts.joinToString(", ") { it.name }} (any time)")
        val t0 = System.currentTimeMillis()
        var done = 0
        for (n in 1..loops) {
            if (stopping) break
            listener.onLog("  loop $n/$loops")
            for (gate in recipe.steps) {
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
            done++
        }
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val per = if (done > 0) " (${"%.1f".format(secs / done)}s per loop)" else ""
        listener.onFinished("$done loop(s) in ${"%.0f".format(secs)}s$per", true)
    }
}
