package dev.autotapper.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Single-channel image, row-major, values 0..255 as floats.
 *
 * Luma uses the same BT.601 weights as OpenCV's COLOR_BGR2GRAY, because the
 * templates were cut with OpenCV. A different weighting would shift every score.
 */
class Gray(val w: Int, val h: Int, val px: FloatArray) {

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Gray {
        val cx0 = x0.coerceIn(0, w); val cy0 = y0.coerceIn(0, h)
        val cx1 = x1.coerceIn(cx0, w); val cy1 = y1.coerceIn(cy0, h)
        val cw = cx1 - cx0; val ch = cy1 - cy0
        val out = FloatArray(max(0, cw * ch))
        for (y in 0 until ch) System.arraycopy(px, (cy0 + y) * w + cx0, out, y * cw, cw)
        return Gray(cw, ch, out)
    }

    /** Box-average downscale by an integer factor. */
    fun downscale(s: Int): Gray {
        if (s <= 1) return this
        val nw = w / s; val nh = h / s
        if (nw <= 0 || nh <= 0) return this
        val out = FloatArray(nw * nh)
        val inv = 1f / (s * s)
        for (y in 0 until nh) {
            val base = y * s
            for (x in 0 until nw) {
                var acc = 0f
                for (dy in 0 until s) {
                    var i = (base + dy) * w + x * s
                    for (dx in 0 until s) acc += px[i + dx]
                }
                out[y * nw + x] = acc * inv
            }
        }
        return Gray(nw, nh, out)
    }

    fun resizeTo(nw: Int, nh: Int): Gray {
        if (nw == w && nh == h) return this
        val out = FloatArray(nw * nh)
        val sx = w.toFloat() / nw
        val sy = h.toFloat() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(y0 + 1, h - 1); val wy = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(x0 + 1, w - 1); val wx = fx - x0
                val a = px[y0 * w + x0]; val b = px[y0 * w + x1]
                val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
                out[y * nw + x] = (a * (1 - wx) + b * wx) * (1 - wy) + (c * (1 - wx) + d * wx) * wy
            }
        }
        return Gray(nw, nh, out)
    }

    /** Standard deviation - the contrast gate that stops us matching a screen mid-fade. */
    fun std(): Float {
        if (px.isEmpty()) return 0f
        var s = 0.0; var s2 = 0.0
        for (v in px) { s += v; s2 += v.toDouble() * v }
        val n = px.size
        val m = s / n
        return sqrt(max(0.0, s2 / n - m * m)).toFloat()
    }

    companion object {
        const val R_W = 0.299f
        const val G_W = 0.587f
        const val B_W = 0.114f
    }
}
