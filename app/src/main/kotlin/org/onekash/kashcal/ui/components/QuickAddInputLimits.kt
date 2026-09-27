package org.onekash.kashcal.ui.components

import android.icu.text.BreakIterator

/** Visual treatment of the Quick Add character counter, by how close input is to the cap. */
enum class QuickAddCounterState {
    /** Below [QuickAddInputLimits.COUNTER_REVEAL_THRESHOLD]: the counter is hidden. */
    HIDDEN,

    /** From the reveal threshold up to the cap: shown in amber. */
    WARN,

    /** At the cap: bold and muted, never red, since the hard cap keeps input from going over. */
    AT_LIMIT,
}

/**
 * Holds the Quick Add field's hard input cap and its "N/500" counter thresholds, the single
 * source of truth for both. Framework-free so the thresholds and the count are unit-tested
 * directly.
 *
 * Counts are in graphemes via ICU's UAX #29 character break iterator: a family emoji, a flag or
 * a base plus combining mark each count as one, as the user reads them, so the cap matches the
 * counter.
 */
object QuickAddInputLimits {

    /** Hard cap on the field's length (title and note together), in graphemes. */
    const val MAX_LENGTH = 500

    /** Grapheme count at or above which the counter shows. */
    const val COUNTER_REVEAL_THRESHOLD = 450

    /** Counts [text] in user-perceived characters (extended grapheme clusters). */
    fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }

    /**
     * Returns the longest prefix of [text] with at most [max] graphemes, never splitting a
     * cluster (a trailing emoji is kept or dropped whole); [text] itself when within [max].
     */
    fun takeGraphemes(text: String, max: Int): String {
        if (text.isEmpty()) return text
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var count = 0
        var end = it.first()
        while (it.next() != BreakIterator.DONE) {
            if (count == max) break
            count++
            end = it.current()
        }
        return if (end >= text.length) text else text.substring(0, end)
    }

    /** Returns the counter treatment for [count] graphemes in the field. */
    fun counterState(count: Int): QuickAddCounterState = when {
        count >= MAX_LENGTH -> QuickAddCounterState.AT_LIMIT
        count >= COUNTER_REVEAL_THRESHOLD -> QuickAddCounterState.WARN
        else -> QuickAddCounterState.HIDDEN
    }
}
