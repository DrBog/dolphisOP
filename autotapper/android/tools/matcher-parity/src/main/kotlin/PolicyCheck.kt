import dev.autotapper.core.DismissPolicy
import dev.autotapper.core.Word

/**
 * Table test for the OCR dismissal policy - the part that decides what is safe
 * to tap on a screen nobody templated. Run with: gradle run --args="policy"
 */
private fun w(t: String) = Word(t, 500, 1400, 80, 40)

private data class Case(
    val name: String,
    val words: List<String>,
    val expect: String,          // "tap:WORD" | "refuse" | "nothing"
)

private val CASES = listOf(
    // The dialog that actually broke the run.
    Case("friend request (Cancel + OK)",
        listOf("Friend", "Request", "Send", "this", "user", "a", "friend", "request?", "Cancel", "OK"),
        "tap:Cancel"),
    // The variant from the recording - no way to decline, so OK is correct.
    Case("friend request (single OK)",
        listOf("Friend", "Request", "This", "user's", "friend", "limit", "has", "been", "reached", "OK"),
        "tap:OK"),
    Case("plain notice",
        listOf("Notice", "Maintenance", "is", "scheduled", "OK"),
        "tap:OK"),
    Case("daily login",
        listOf("Login", "Bonus", "Received", "Close"),
        "tap:Close"),

    // Everything below must NOT be guessed at.
    Case("stamina refill prompt",
        listOf("Recover", "Stamina?", "Use", "1", "Dragon", "Stone", "Cancel", "OK"),
        "refuse"),
    Case("continue after a loss",
        listOf("Continue?", "Use", "1", "Dragon", "Stone", "to", "continue", "No", "Yes"),
        "refuse"),
    Case("summon confirmation",
        listOf("Summon", "Multi-Summon", "50", "Dragon", "Stones", "Cancel", "OK"),
        "refuse"),
    Case("shop purchase",
        listOf("Shop", "Buy", "this", "item?", "Cancel", "OK"),
        "refuse"),
    Case("bare game screen, nothing to press",
        listOf("Epitome", "of", "Sublime", "Beauty", "Goku", "Black", "Lv.11"),
        "nothing"),
)

fun policyMain(): Int {
    var failed = 0
    println("dismissal policy - what is safe to tap on an untemplated screen\n")
    for (c in CASES) {
        val got = when (val d = DismissPolicy.decide(c.words.map { w(it) })) {
            is DismissPolicy.Decision.Tap -> "tap:${d.word.text}"
            is DismissPolicy.Decision.Refuse -> "refuse"
            is DismissPolicy.Decision.NothingFound -> "nothing"
        }
        val ok = got == c.expect
        if (!ok) failed++
        val detail = when (val d = DismissPolicy.decide(c.words.map { w(it) })) {
            is DismissPolicy.Decision.Refuse -> "  (blocked by '${d.blocker}')"
            is DismissPolicy.Decision.Tap -> "  (${d.group})"
            else -> ""
        }
        println("  ${if (ok) "ok  " else "FAIL"}  ${c.name.padEnd(34)} expected ${c.expect.padEnd(12)} got ${got}$detail")
    }
    println()
    println(if (failed == 0) "PASS - policy declines where it must and dismisses where it can"
            else "FAIL - $failed case(s) wrong")
    return if (failed == 0) 0 else 1
}
