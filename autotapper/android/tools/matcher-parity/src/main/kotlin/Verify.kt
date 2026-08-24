import dev.autotapper.core.Gray
import dev.autotapper.core.Matcher
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs

/**
 * Check Matcher.kt against OpenCV's TM_CCOEFF_NORMED on real frames.
 *
 * What has to hold is not "identical floats" but "same decisions": the same
 * accept/reject at the recipe's threshold, the same location where we act, and
 * near-identical scores anywhere near the threshold. Deltas down in the noise
 * floor, where no match exists, cannot change behaviour.
 */
fun readGray(f: File): Gray {
    DataInputStream(f.inputStream().buffered()).use { s ->
        val w = Integer.reverseBytes(s.readInt())
        val h = Integer.reverseBytes(s.readInt())
        val bytes = ByteArray(w * h)
        s.readFully(bytes)
        val px = FloatArray(w * h)
        for (i in px.indices) px[i] = (bytes[i].toInt() and 0xFF).toFloat()
        return Gray(w, h, px)
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "policy") kotlin.system.exitProcess(policyMain())
    if (args.firstOrNull() == "alloc") kotlin.system.exitProcess(allocMain(args[1]))
    val dir = args.firstOrNull() ?: System.getProperty("data")
        ?: error("usage: run --args=<reference-data-dir>  (see export_reference.py)")
    val base = File(dir)
    val spec = JSONObject(File(base, "expected.json").readText())
    val threshold = spec.optDouble("threshold", 0.82).toFloat()
    val cases = spec.getJSONArray("cases")

    val templates = HashMap<String, Matcher.Template>()
    val frames = HashMap<Int, Gray>()
    val details = ArrayList<Triple<String, FloatArray, Int>>()
    val perGate = HashMap<String, Float>()
    var worstPos = 0
    var disagreements = 0

    val t0 = System.currentTimeMillis()
    for (i in 0 until cases.length()) {
        val c = cases.getJSONObject(i)
        val fn = c.getInt("frame")
        val tplName = c.getString("template")
        val frame = frames.getOrPut(fn) { readGray(File(base, "frames/%04d.bin".format(fn))) }
        val tpl = templates.getOrPut(tplName) { Matcher.Template(readGray(File(base, "tpl/$tplName.bin"))) }
        val r = c.getJSONArray("roi")
        val roi = frame.crop(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))

        val got = Matcher.find(roi, tpl)
        val gotX = got.x + r.getInt(0)
        val gotY = got.y + r.getInt(1)
        val expScore = c.getDouble("score").toFloat()
        val dPos = maxOf(abs(gotX - c.getInt("x")), abs(gotY - c.getInt("y")))

        if ((got.score >= threshold) != (expScore >= threshold)) disagreements++
        if (expScore >= threshold) worstPos = maxOf(worstPos, dPos)

        val gate = c.getString("gate")
        val d = abs(got.score - expScore)
        perGate[gate] = maxOf(perGate[gate] ?: 0f, d)
        details.add(Triple("$gate@$fn", floatArrayOf(expScore, got.score), dPos))
    }
    val ms = System.currentTimeMillis() - t0
    val n = details.size

    // Only scores near or above the decision threshold can change behaviour.
    val near = details.filter { it.second[0] >= 0.60f || it.second[1] >= 0.60f }
    val worstNear = near.maxOfOrNull { abs(it.second[0] - it.second[1]) } ?: 0f
    val worstAny = details.maxOfOrNull { abs(it.second[0] - it.second[1]) } ?: 0f

    println("compared $n (gate, frame) cases against OpenCV TM_CCOEFF_NORMED  [threshold $threshold]")
    println("  threshold disagreements : $disagreements")
    println("  worst position delta    : $worstPos px (where score >= threshold)")
    println("  worst delta near/above 0.60 : %.6f  over ${near.size} cases".format(worstNear))
    println("  worst delta anywhere        : %.6f  (noise floor - no match present)".format(worstAny))
    println("  kotlin matcher            : ${ms}ms total, %.1fms per case".format(ms.toDouble() / n))
    println()
    for ((g, d) in perGate.toSortedMap()) println("  %-16s max delta %.6f".format(g, d))
    println()

    details.sortByDescending { abs(it.second[0] - it.second[1]) }
    println("  largest disagreements (opencv vs kotlin):")
    for (d in details.take(5))
        println("    %-24s %.4f vs %.4f   delta %.4f".format(
            d.first, d.second[0], d.second[1], abs(d.second[0] - d.second[1])))
    println()

    val ok = disagreements == 0 && worstPos <= 4 && worstNear < 0.02f
    println(if (ok) "PASS - the Kotlin matcher agrees with OpenCV where it matters"
            else "FAIL - implementations disagree")
    if (!ok) kotlin.system.exitProcess(1)
}
