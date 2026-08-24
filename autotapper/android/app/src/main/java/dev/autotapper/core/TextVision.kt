package dev.autotapper.core

/** One recognised word and where it sits, in reference coordinates. */
data class Word(val text: String, val cx: Int, val cy: Int, val w: Int, val h: Int)

/**
 * Reads the words on screen. Kept as an interface with no ML Kit import so the
 * core stays compilable off-device, and so the engine can run without it.
 */
interface TextVision {
    /** Words in the frame, or null if text recognition is unavailable. */
    fun read(frame: Gray): List<Word>?
}
