package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic tests for [QuickAddInputLimits], the single source of truth for the
 * Quick Add field's hard character cap and its "N/500" counter treatment.
 *
 * Runs under Robolectric so [android.icu.text.BreakIterator] resolves to the real
 * ICU4J implementation (UAX #29 extended grapheme clusters). The host JVM's
 * `java.text.BreakIterator` uses a legacy model that miscounts emoji ZWJ
 * sequences / flags / skin-tone modifiers, so it cannot back the "emoji = 1"
 * requirement — hence ICU + Robolectric here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class QuickAddInputLimitsTest {

    // Common emoji that are single user-perceived characters but multiple code units.
    private val familyZwj = "👨‍👩‍👧‍👦" // 👨‍👩‍👧‍👦
    private val usFlag = "🇺🇸" // 🇺🇸 regional indicators
    private val thumbUpTone = "👍🏽" // 👍🏽 thumbs up + medium skin tone
    private val combiningE = "é" // e + combining acute → é

    // --- graphemeCount -----------------------------------------------------

    @Test
    fun graphemeCount_emptyIsZero() {
        assertEquals(0, QuickAddInputLimits.graphemeCount(""))
    }

    @Test
    fun graphemeCount_asciiEqualsLength() {
        assertEquals(5, QuickAddInputLimits.graphemeCount("hello"))
    }

    @Test
    fun graphemeCount_familyZwjIsOne() {
        assertEquals(1, QuickAddInputLimits.graphemeCount(familyZwj))
    }

    @Test
    fun graphemeCount_flagIsOne() {
        assertEquals(1, QuickAddInputLimits.graphemeCount(usFlag))
    }

    @Test
    fun graphemeCount_skinToneModifierIsOne() {
        assertEquals(1, QuickAddInputLimits.graphemeCount(thumbUpTone))
    }

    @Test
    fun graphemeCount_combiningMarkIsOne() {
        assertEquals(1, QuickAddInputLimits.graphemeCount(combiningE))
    }

    @Test
    fun graphemeCount_mixedContent() {
        // "hi" + family + "!" = 2 + 1 + 1 = 4 graphemes (across 12 chars)
        val s = "hi$familyZwj!"
        assertEquals(4, QuickAddInputLimits.graphemeCount(s))
    }

    // --- takeGraphemes ------------------------------------------------------

    @Test
    fun takeGraphemes_underLimitReturnsWhole() {
        assertEquals("hello", QuickAddInputLimits.takeGraphemes("hello", 500))
    }

    @Test
    fun takeGraphemes_exactLimitReturnsWhole() {
        val s = "a".repeat(500)
        assertEquals(s, QuickAddInputLimits.takeGraphemes(s, 500))
    }

    @Test
    fun takeGraphemes_dropsOverflowAscii() {
        val s = "a".repeat(600)
        val result = QuickAddInputLimits.takeGraphemes(s, 500)
        assertEquals(500, result.length)
        assertEquals("a".repeat(500), result)
    }

    @Test
    fun takeGraphemes_neverSplitsClusterKeepsWhole() {
        // 499 ascii + one family emoji = 500 graphemes; the emoji must survive intact.
        val s = "a".repeat(499) + familyZwj
        val result = QuickAddInputLimits.takeGraphemes(s, 500)
        assertEquals(500, QuickAddInputLimits.graphemeCount(result))
        assertEquals(s, result)
    }

    @Test
    fun takeGraphemes_neverSplitsClusterDropsWhole() {
        // 500 ascii + one family emoji = 501 graphemes; take(500) must drop the
        // emoji entirely, never leaving a half-cluster of dangling surrogates.
        val s = "a".repeat(500) + familyZwj
        val result = QuickAddInputLimits.takeGraphemes(s, 500)
        assertEquals("a".repeat(500), result)
        assertEquals(500, QuickAddInputLimits.graphemeCount(result))
    }

    @Test
    fun takeGraphemes_countsEmojiAsOneTowardLimit() {
        // 500 family emoji = 500 graphemes (well under the char length), all kept.
        val s = familyZwj.repeat(500)
        val result = QuickAddInputLimits.takeGraphemes(s, 500)
        assertEquals(500, QuickAddInputLimits.graphemeCount(result))
        assertEquals(s, result)
    }

    @Test
    fun takeGraphemes_emptyReturnsEmpty() {
        assertEquals("", QuickAddInputLimits.takeGraphemes("", 500))
    }

    // --- counterState -------------------------------------------------------

    @Test
    fun counterState_zeroIsHidden() {
        assertEquals(QuickAddCounterState.HIDDEN, QuickAddInputLimits.counterState(0))
    }

    @Test
    fun counterState_below450IsHidden() {
        assertEquals(QuickAddCounterState.HIDDEN, QuickAddInputLimits.counterState(449))
    }

    @Test
    fun counterState_at450IsWarn() {
        assertEquals(QuickAddCounterState.WARN, QuickAddInputLimits.counterState(450))
    }

    @Test
    fun counterState_at499IsWarn() {
        assertEquals(QuickAddCounterState.WARN, QuickAddInputLimits.counterState(499))
    }

    @Test
    fun counterState_at500IsAtLimit() {
        assertEquals(QuickAddCounterState.AT_LIMIT, QuickAddInputLimits.counterState(500))
    }

    @Test
    fun counterState_above500IsAtLimit() {
        // Defensive: the cap makes >500 impossible through the field, but the
        // state function must still classify it as at-limit, never crash.
        assertEquals(QuickAddCounterState.AT_LIMIT, QuickAddInputLimits.counterState(501))
    }

    // --- constant -----------------------------------------------------------

    @Test
    fun maxLengthIs500() {
        assertEquals(500, QuickAddInputLimits.MAX_LENGTH)
    }
}
