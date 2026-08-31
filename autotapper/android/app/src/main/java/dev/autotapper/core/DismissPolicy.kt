package dev.autotapper.core

/**
 * Decides which on-screen word, if any, is safe to tap to clear an unknown
 * dialog.
 *
 * This is deliberately a plain deterministic table, separate from whatever reads
 * the text. Recognition decides what the words ARE; this decides what may be
 * touched. Nothing that reads the screen gets a say in that, because the failure
 * mode is spending real currency.
 */
object DismissPolicy {

    /**
     * Vertical reach, in reference pixels, of the "is this button safe" check.
     *
     * Only text belonging to the same dialog can tell us whether tapping a button
     * spends something. Dokkan's bottom nav bar permanently reads SHOP, SUMMON
     * and EXCHANGE, roughly 700px from where a modal's buttons sit, so scanning
     * the whole screen refused on every screen that shows it - the fallback was
     * disabled almost everywhere it might have helped. A real spend prompt puts
     * its cost right next to its buttons, well inside this.
     */
    const val NEAR_PX = 450

    /** If any of these appear near the button being considered, do not guess. */
    val FORBIDDEN = listOf(
        "BUY", "PURCHASE", "SHOP", "STORE", "PAY", "SUMMON", "RECHARGE", "REFILL",
        "CONTINUE", "REVIVE", "STONE", "STONES", "EXCHANGE", "PRICE", "COST",
    )

    /** Preferred: declining a dialog is always safer than accepting it. */
    val DECLINE = listOf("CANCEL", "CLOSE", "NO", "LATER", "SKIP", "DECLINE", "BACK")

    /** Only when the dialog offers no way out but forward. */
    val ACCEPT = listOf("OK")

    sealed class Decision {
        /** Tap this word's centre. */
        data class Tap(val word: Word, val group: String) : Decision()
        /** Something on screen makes guessing unsafe. */
        data class Refuse(val blocker: String) : Decision()
        /** Nothing button-like was recognised. */
        data class NothingFound(val seen: List<String>) : Decision()
    }

    fun normalise(s: String): String = s.uppercase().filter { it.isLetter() }

    fun decide(words: List<Word>): Decision {
        val seen = words.map { normalise(it.text) }.filter { it.isNotEmpty() }

        for ((group, list) in listOf("decline" to DECLINE, "accept" to ACCEPT)) {
            for (want in list) {
                val hit = words.firstOrNull { normalise(it.text) == want } ?: continue
                // Anything alarming beside this button means the whole dialog is
                // one to leave alone - do not fall through and try another button
                // in the same dialog.
                val blocker = words.firstOrNull {
                    normalise(it.text) in FORBIDDEN && kotlin.math.abs(it.cy - hit.cy) <= NEAR_PX
                }
                if (blocker != null) return Decision.Refuse(blocker.text)
                return Decision.Tap(hit, group)
            }
        }
        return Decision.NothingFound(seen)
    }
}
