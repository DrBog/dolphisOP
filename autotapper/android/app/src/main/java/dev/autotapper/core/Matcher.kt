package dev.autotapper.core

import kotlin.math.sqrt

/**
 * Normalised cross-correlation, equivalent to OpenCV's TM_CCOEFF_NORMED, so the
 * thresholds validated on the desktop tool carry over unchanged.
 *
 * A flat search would be far too slow in Kotlin - the largest template here is
 * 885x220 over a 1080x440 region, which is ~34 billion multiply-adds. Instead we
 * search exhaustively on a heavily downscaled copy and then walk back down a
 * pyramid, refining in a +/-2 window at each level. That lands on the same peak
 * for a few million ops.
 */
object Matcher {

    /** One scale level of a template, with the statistics NCC needs precomputed. */
    class Level(val g: Gray) {
        val mean: Double
        val norm: Double   // sqrt(sum((T - mean)^2))
        init {
            var s = 0.0
            for (v in g.px) s += v
            val m = if (g.px.isEmpty()) 0.0 else s / g.px.size
            var acc = 0.0
            for (v in g.px) { val d = v - m; acc += d * d }
            mean = m
            norm = sqrt(acc)
        }
    }

    class Template(full: Gray) {
        val scales: List<Int> = pyramid(full.w, full.h)
        private val levels: Map<Int, Level> =
            scales.associateWith { Level(if (it == 1) full else full.downscale(it)) }
        val w = full.w
        val h = full.h
        fun at(s: Int): Level = levels.getValue(s)

        /**
         * Scratch pyramid for the ROI this template is searched in. A template
         * belongs to exactly one gate, and that gate always searches the same
         * ROI, so these buffers are allocated once instead of every poll.
         */
        val roiPyramid = HashMap<Int, Gray>()

        private companion object {
            /** Descending powers of two, coarsest first, keeping the top level >= 16px. */
            fun pyramid(w: Int, h: Int): List<Int> {
                var s = 1
                while (minOf(w, h) / (s * 2) >= 16 && s < 32) s *= 2
                val out = ArrayList<Int>()
                var c = s
                while (c >= 1) { out.add(c); c /= 2 }
                return out
            }
        }
    }

    class Result(val score: Float, val x: Int, val y: Int)

    private fun nccAt(img: Gray, t: Level, ox: Int, oy: Int): Float {
        val tpl = t.g
        val tw = tpl.w; val th = tpl.h
        if (ox < 0 || oy < 0 || ox + tw > img.w || oy + th > img.h) return -1f

        // Accumulate in Double. The variance below is a difference of two large,
        // nearly equal quantities; in Float that cancellation leaves only noise on
        // a near-uniform window, and dividing by its square root sends the score
        // far outside the [-1, 1] that a normalised correlation can occupy.
        var sI = 0.0; var sII = 0.0; var sIT = 0.0
        for (y in 0 until th) {
            val ii = (oy + y) * img.w + ox
            val ti = y * tw
            for (x in 0 until tw) {
                val i = img.px[ii + x].toDouble()
                sI += i; sII += i * i; sIT += i * tpl.px[ti + x]
            }
        }
        val n = (tw * th).toDouble()
        // sum((I-Ibar)(T-Tbar)) == sum(I*T) - Tbar*sum(I)
        val num = sIT - t.mean * sI
        val varI = sII - sI * sI / n
        // Relative guard: a flat window has no meaningful correlation at all, and
        // an absolute epsilon does not scale with image magnitude.
        if (varI <= 1e-9 * maxOf(1.0, sII) || t.norm <= 1e-6) return 0f
        val v = num / (kotlin.math.sqrt(varI) * t.norm)
        return v.coerceIn(-1.0, 1.0).toFloat()
    }

    /** Locate [tpl] inside [roi]. Returns the best score and its top-left in roi coords. */
    fun find(roi: Gray, tpl: Template): Result {
        // Build the ROI pyramid once per call, into buffers the template owns, so
        // a steady-state poll allocates nothing here.
        val pyr = HashMap<Int, Gray>(tpl.scales.size)
        for (s in tpl.scales) {
            pyr[s] = if (s == 1) roi else roi.downscaleInto(s, tpl.roiPyramid[s])
                .also { tpl.roiPyramid[s] = it }
        }
        val top = tpl.scales.first()
        val img0 = pyr.getValue(top)
        val t0 = tpl.at(top)
        if (img0.w < t0.g.w || img0.h < t0.g.h) return Result(0f, 0, 0)

        val sw = img0.w - t0.g.w + 1
        val sh = img0.h - t0.g.h + 1
        val map = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) map[y * sw + x] = nccAt(img0, t0, x, y)
        }

        // Refining only the single coarse peak is not safe: on a smooth, low-detail
        // template the correlation surface at 1/8 scale is nearly flat, the argmax
        // lands in the wrong basin, and a +/-2 refinement window cannot walk out of
        // it. Carrying the best few peaks down the pyramid fixes that for a few
        // extra milliseconds.
        var best = Result(-2f, 0, 0)
        for ((cx0, cy0) in peaks(map, sw, sh, CANDIDATES, maxOf(2, minOf(t0.g.w, t0.g.h) / 2))) {
            var fx = cx0 * top
            var fy = cy0 * top
            var fs = map[cy0 * sw + cx0]
            for (s in tpl.scales.drop(1)) {
                val img = pyr.getValue(s)
                val t = tpl.at(s)
                val cx = fx / s
                val cy = fy / s
                var lbs = -2f; var lbx = cx; var lby = cy
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val v = nccAt(img, t, cx + dx, cy + dy)
                        if (v > lbs) { lbs = v; lbx = cx + dx; lby = cy + dy }
                    }
                }
                if (lbs > -2f) { fx = lbx * s; fy = lby * s; fs = lbs }
            }
            if (fs > best.score) best = Result(fs, fx, fy)
        }
        return if (best.score <= -2f) Result(0f, 0, 0) else best
    }

    /** Up to [k] local maxima of the score map, non-max suppressed by [radius]. */
    private fun peaks(map: FloatArray, w: Int, h: Int, k: Int, radius: Int): List<Pair<Int, Int>> {
        val work = map.copyOf()
        val out = ArrayList<Pair<Int, Int>>(k)
        repeat(k) {
            var bi = -1; var bv = -2f
            for (i in work.indices) if (work[i] > bv) { bv = work[i]; bi = i }
            if (bi < 0 || bv <= -2f) return@repeat
            val px = bi % w; val py = bi / w
            out.add(px to py)
            for (y in maxOf(0, py - radius)..minOf(h - 1, py + radius)) {
                for (x in maxOf(0, px - radius)..minOf(w - 1, px + radius)) work[y * w + x] = -2f
            }
        }
        return out
    }

    private const val CANDIDATES = 4
}
