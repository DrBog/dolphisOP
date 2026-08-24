import dev.autotapper.core.Gray
import dev.autotapper.core.Matcher
import org.json.JSONObject
import java.io.File

/**
 * How much garbage does one poll of the engine produce?
 *
 * Sustained allocation is what took a real device down: every capture built a
 * fresh multi-megabyte array, and at a few polls a second that is tens of MB per
 * second of large-object churn while the system is also mirroring the display.
 * Run with: gradle run --args="alloc <reference-data-dir>"
 */
private fun allocatedBytes(): Long {
    val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
    return bean.getThreadAllocatedBytes(Thread.currentThread().id)
}

fun allocMain(dir: String): Int {
    val base = File(dir)
    val spec = JSONObject(File(base, "expected.json").readText())
    val cases = spec.getJSONArray("cases")

    // One representative gate: the biggest template over the widest ROI.
    var pick: JSONObject? = null
    for (i in 0 until cases.length()) {
        val c = cases.getJSONObject(i)
        if (c.getString("gate") == "tap_next_level") { pick = c; break }
    }
    val c = pick ?: cases.getJSONObject(0)
    val frame = readGray(File(base, "frames/%04d.bin".format(c.getInt("frame"))))
    val tpl = Matcher.Template(readGray(File(base, "tpl/${c.getString("template")}.bin")))
    val r = c.getJSONArray("roi")
    val x0 = r.getInt(0); val y0 = r.getInt(1); val x1 = r.getInt(2); val y1 = r.getInt(3)

    // warm up
    repeat(3) { Matcher.find(frame.crop(x0, y0, x1, y1), tpl) }

    val polls = 20

    // BEFORE: a fresh frame, a fresh crop, a fresh pyramid, every poll.
    var before = allocatedBytes()
    repeat(polls) {
        val captured = Gray(frame.w, frame.h, FloatArray(frame.w * frame.h))
        System.arraycopy(frame.px, 0, captured.px, 0, frame.px.size)
        Matcher.find(captured.crop(x0, y0, x1, y1), tpl)
        captured.downscale(8)
    }
    val naive = (allocatedBytes() - before) / polls

    // AFTER: the engine owns its buffers and refills them.
    val captured = Gray(frame.w, frame.h, FloatArray(frame.w * frame.h))
    var cropBuf: Gray? = null
    var sA: Gray? = null
    var sB: Gray? = null
    repeat(3) {
        cropBuf = captured.cropInto(x0, y0, x1, y1, cropBuf)
        Matcher.find(cropBuf!!, tpl)
        sA = captured.downscaleInto(8, sA); sB = captured.downscaleInto(8, sB)
    }
    before = allocatedBytes()
    repeat(polls) {
        System.arraycopy(frame.px, 0, captured.px, 0, frame.px.size)   // refill, no alloc
        cropBuf = captured.cropInto(x0, y0, x1, y1, cropBuf)
        Matcher.find(cropBuf!!, tpl)
        sA = captured.downscaleInto(8, sA); sB = captured.downscaleInto(8, sB)
    }
    val perPoll = (allocatedBytes() - before) / polls

    println("gate ${c.getString("gate")}, frame ${frame.w}x${frame.h}, roi ${x1 - x0}x${y1 - y0}\n")
    println("  allocating per poll (before) : %9.2f MB   -> %6.1f MB/s   %5.1f GB / 20 min"
        .format(naive / 1048576.0, naive * 2 / 1048576.0, naive * 2 * 1200 / 1073741824.0))
    println("  reusing buffers     (after)  : %9.2f MB   -> %6.1f MB/s   %5.1f GB / 20 min"
        .format(perPoll / 1048576.0, perPoll * 2 / 1048576.0, perPoll * 2 * 1200 / 1073741824.0))
    val factor = if (perPoll > 0) naive.toDouble() / perPoll else Double.POSITIVE_INFINITY
    println("\n  reduction: %.0fx".format(factor))
    return if (perPoll * 2 < 2 * 1048576) 0 else 1   // demand under 2 MB/s
}
