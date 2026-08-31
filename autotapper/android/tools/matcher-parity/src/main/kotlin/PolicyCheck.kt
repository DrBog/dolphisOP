import dev.autotapper.core.DismissPolicy
import dev.autotapper.core.Word

/**
 * Table test for the OCR dismissal policy - the part that decides what is safe
 * to tap on a screen nobody templated. Run with: gradle run --args="policy"
 */
/** A word at a given height, because proximity is now part of the decision. */
private fun w(t: String, y: Int = 1400) = Word(t, 500, y, 80, 40)

/** Dokkan's permanent bottom nav - present on every stage screen. */
private fun navBar() = listOf(
    w("HOME", 2150), w("TEAM", 2150), w("SUMMON", 2150), w("SHOP", 2150), w("EXCHANGE", 2150)
)

private data class Case(
    val name: String,
    val words: List<Word>,
    val expect: String,          // "tap:WORD" | "refuse" | "nothing"
)

private val CASES = listOf(
    Case("friend request (Cancel + OK)",
        listOf(w("Friend", 900), w("Request", 900), w("Cancel", 1430), w("OK", 1430)),
        "tap:Cancel"),
    Case("friend request (single OK)",
        listOf(w("Friend", 900), w("Request", 900), w("limit", 1300), w("OK", 1430)),
        "tap:OK"),
    Case("friend request sent (no title)",
        listOf(w("Friend", 980), w("request", 980), w("sent.", 980), w("OK", 1305)),
        "tap:OK"),
    Case("plain notice",
        listOf(w("Notice", 1100), w("Maintenance", 1200), w("OK", 1430)),
        "tap:OK"),
    Case("daily login",
        listOf(w("Login", 1100), w("Bonus", 1200), w("Close", 1430)),
        "tap:Close"),

    // The regression this fix is for: a modal over a screen whose nav bar reads
    // SHOP / SUMMON / EXCHANGE ~700px away must still be dismissable.
    Case("notice over a screen with the nav bar",
        listOf(w("Notice", 1100), w("OK", 1430)) + navBar(),
        "tap:OK"),
    Case("friend request over the nav bar",
        listOf(w("Friend", 900), w("request", 900), w("sent.", 900), w("OK", 1305)) + navBar(),
        "tap:OK"),

    // Everything below must still NOT be guessed at - the cost sits beside the buttons.
    Case("stamina refill prompt",
        listOf(w("Recover", 1200), w("Use", 1280), w("Dragon", 1280), w("Stone", 1280),
               w("Cancel", 1430), w("OK", 1430)),
        "refuse"),
    Case("continue after a loss",
        listOf(w("Continue?", 1200), w("Dragon", 1280), w("Stone", 1280),
               w("No", 1430), w("Yes", 1430)),
        "refuse"),
    Case("summon confirmation",
        listOf(w("Multi-Summon", 1200), w("50", 1280), w("Dragon", 1280), w("Stones", 1280),
               w("Cancel", 1430), w("OK", 1430)),
        "refuse"),
    Case("shop purchase",
        listOf(w("Buy", 1200), w("this", 1200), w("item?", 1200),
               w("Cancel", 1430), w("OK", 1430)),
        "refuse"),
    Case("bare game screen, nothing to press",
        listOf(w("Epitome", 900), w("Goku", 1000), w("Black", 1000)) + navBar(),
        "nothing"),
)

fun policyMain(): Int {
    var failed = 0
    println("dismissal policy - what is safe to tap on an untemplated screen\n")
    for (c in CASES) {
        val got = when (val d = DismissPolicy.decide(c.words)) {
            is DismissPolicy.Decision.Tap -> "tap:${d.word.text}"
            is DismissPolicy.Decision.Refuse -> "refuse"
            is DismissPolicy.Decision.NothingFound -> "nothing"
        }
        val ok = got == c.expect
        if (!ok) failed++
        val detail = when (val d = DismissPolicy.decide(c.words)) {
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
