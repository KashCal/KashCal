package org.onekash.kashcal.ui.components

import android.icu.text.BreakIterator

/** Visual treatment of the Quick Add character counter, by how close input is to the cap. */
enum class QuickAddCounterState {
    /** Below the reveal threshold — the counter is not shown. */
    HIDDEN,

    /** Near the cap (reveal threshold ..< limit) — shown in a warning (amber) color. */
    WARN,

    /** At the cap — shown in a neutral "at limit" treatment (bold + muted, never red). */
    AT_LIMIT,
}

/**
 * Single source of truth for the Quick Add field's hard input cap and its
 * "N/500" counter treatment. Kept as a pure, framework-free object so the
 * thresholds and the grapheme-aware count can be unit-tested directly rather
 * than only through the composable.
 *
 * Counting is grapheme-aware (a family emoji, a flag, or a base+combining-mark
 * pair each count as one user-perceived character) via ICU's UAX #29 grapheme
 * break iterator — matching how a user reads the field, and matching the cap to
 * what they see in the counter.
 */
object QuickAddInputLimits {

    /** Hard cap on total input length, in graphemes (the combined title + note field). */
    const val MAX_LENGTH = 500

    /** At/above this grapheme count the counter becomes visible. */
    const val COUNTER_REVEAL_THRESHOLD = 450

    /** Count [text] in user-perceived characters (extended grapheme clusters). */
    fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }

    /**
     * Return the longest prefix of [text] whose grapheme count is <= [max],
     * never splitting a grapheme cluster (so a trailing emoji is kept whole or
     * dropped whole). Returns [text] unchanged when it is already within [max].
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

    /** Counter treatment for a given [count] of graphemes currently in the field. */
    fun counterState(count: Int): QuickAddCounterState = when {
        count >= MAX_LENGTH -> QuickAddCounterState.AT_LIMIT
        count >= COUNTER_REVEAL_THRESHOLD -> QuickAddCounterState.WARN
        else -> QuickAddCounterState.HIDDEN
    }
}
