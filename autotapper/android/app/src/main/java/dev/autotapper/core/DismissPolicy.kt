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

    /** If any of these appear anywhere on screen, do not guess at all. */
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

        FORBIDDEN.firstOrNull { it in seen }?.let { return Decision.Refuse(it) }

        for ((group, list) in listOf("decline" to DECLINE, "accept" to ACCEPT)) {
            for (want in list) {
                val hit = words.firstOrNull { normalise(it.text) == want }
                if (hit != null) return Decision.Tap(hit, group)
            }
        }
        return Decision.NothingFound(seen)
    }
}
