package org.onekash.kashcal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for EmojiMatcher keyword-to-emoji matching logic.
 *
 * Tests cover:
 * - Core matching functionality
 * - Case insensitivity
 * - Word boundary matching (prevents partial matches)
 * - Priority ordering
 * - Edge cases
 * - Category coverage (spot checks)
 * - formatWithEmoji helper function
 */
class EmojiMatcherTest {

    // ==================== Core Matching ====================

    @Test
    fun `getEmoji returns coffee emoji for coffee keyword`() {
        assertEquals("\u2615", EmojiMatcher.getEmoji("Coffee with Sarah"))
    }

    @Test
    fun `getEmoji returns birthday emoji for birthday keyword`() {
        assertEquals("\uD83C\uDF82", EmojiMatcher.getEmoji("John's Birthday"))
    }

    @Test
    fun `getEmoji returns birthday emoji for bday abbreviation`() {
        assertEquals("\uD83C\uDF82", EmojiMatcher.getEmoji("Mom's bday party"))
    }

    @Test
    fun `getEmoji returns birthday emoji for b-day hyphenated`() {
        assertEquals("\uD83C\uDF82", EmojiMatcher.getEmoji("Sarah's b-day"))
    }

    @Test
    fun `getEmoji returns flight emoji for flight keyword`() {
        assertEquals("\u2708\uFE0F", EmojiMatcher.getEmoji("Flight to NYC"))
    }

    @Test
    fun `getEmoji returns null for no match`() {
        assertNull(EmojiMatcher.getEmoji("Team standup"))
    }

    // ==================== Case Insensitivity ====================

    @Test
    fun `getEmoji is case insensitive - lowercase`() {
        assertEquals("\u2615", EmojiMatcher.getEmoji("coffee with sarah"))
    }

    @Test
    fun `getEmoji is case insensitive - uppercase`() {
        assertEquals("\u2615", EmojiMatcher.getEmoji("COFFEE WITH SARAH"))
    }

    @Test
    fun `getEmoji is case insensitive - mixed case`() {
        assertEquals("\u2615", EmojiMatcher.getEmoji("CoFfEe with Sarah"))
    }

    // ==================== Word Boundary Matching ====================

    @Test
    fun `getEmoji matches whole words only - scoffee should not match`() {
        assertNull(EmojiMatcher.getEmoji("Scoffee time"))
    }

    @Test
    fun `getEmoji matches whole words only - coffeetime should not match`() {
        assertNull(EmojiMatcher.getEmoji("Coffeetime meeting"))
    }

    @Test
    fun `getEmoji matches word at start of title`() {
        assertEquals("\u2615", EmojiMatcher.getEmoji("Coffee: Morning routine"))
    }

    @Test
    fun `getEmoji matches word at end of title`() {
        assertEquals("\u2615", EmojiMatcher.getEmoji("Morning coffee"))
    }

    @Test
    fun `getEmoji matches word with punctuation`() {
        assertEquals("\u2615", EmojiMatcher.getEmoji("Coffee! With friends"))
    }

    // ==================== Priority Ordering ====================

    @Test
    fun `getEmoji respects priority - birthday party returns birthday emoji`() {
        // Birthday (priority 10) should win over party (priority 10)
        // but since birthday comes first in sorted list, it wins
        val emoji = EmojiMatcher.getEmoji("Birthday party")
        assertEquals("\uD83C\uDF82", emoji)
    }

    @Test
    fun `getEmoji matches higher priority first`() {
        // Birthday (priority 10) should beat coffee (priority 5)
        val emoji = EmojiMatcher.getEmoji("Birthday coffee meetup")
        assertEquals("\uD83C\uDF82", emoji)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `getEmoji handles empty string`() {
        assertNull(EmojiMatcher.getEmoji(""))
    }

    @Test
    fun `getEmoji handles whitespace only`() {
        assertNull(EmojiMatcher.getEmoji("   "))
    }

    @Test
    fun `getEmoji handles apostrophes in title`() {
        assertEquals("\uD83C\uDF82", EmojiMatcher.getEmoji("Mom's Birthday"))
    }

    @Test
    fun `getEmoji handles special characters`() {
        assertEquals("\u2615", EmojiMatcher.getEmoji("Coffee & Tea"))
    }

    @Test
    fun `getEmoji handles numbers in keywords - 5k run`() {
        assertEquals("\uD83C\uDFC3", EmojiMatcher.getEmoji("Morning 5k"))
    }

    @Test
    fun `getEmoji handles multi-word keywords - happy hour`() {
        assertEquals("\uD83C\uDF7A", EmojiMatcher.getEmoji("Team happy hour"))
    }

    @Test
    fun `getEmoji handles multi-word keywords - road trip`() {
        assertEquals("\uD83D\uDE97", EmojiMatcher.getEmoji("Summer road trip"))
    }

    // ==================== Category Coverage (Spot Checks) ====================

    @Test
    fun `getEmoji matches holidays - christmas`() {
        assertEquals("\uD83C\uDF84", EmojiMatcher.getEmoji("Christmas dinner"))
    }

    @Test
    fun `getEmoji matches holidays - thanksgiving`() {
        assertEquals("\uD83E\uDD83", EmojiMatcher.getEmoji("Thanksgiving dinner"))
    }

    @Test
    fun `getEmoji matches holidays - easter`() {
        assertEquals("\uD83D\uDC30", EmojiMatcher.getEmoji("Easter brunch"))
    }

    @Test
    fun `getEmoji matches sports - gym`() {
        assertEquals("\uD83C\uDFCB\uFE0F", EmojiMatcher.getEmoji("Gym session"))
    }

    @Test
    fun `getEmoji matches sports - yoga`() {
        assertEquals("\uD83E\uDDD8", EmojiMatcher.getEmoji("Yoga class"))
    }

    @Test
    fun `getEmoji matches sports - golf`() {
        assertEquals("\u26F3", EmojiMatcher.getEmoji("Golf with John"))
    }

    @Test
    fun `getEmoji matches sports - tennis`() {
        assertEquals("\uD83C\uDFBE", EmojiMatcher.getEmoji("Tennis match"))
    }

    @Test
    fun `getEmoji matches travel - hotel`() {
        assertEquals("\uD83C\uDFE8", EmojiMatcher.getEmoji("Hotel check-in"))
    }

    @Test
    fun `getEmoji matches travel - cruise`() {
        assertEquals("\uD83D\uDEA2", EmojiMatcher.getEmoji("Cruise departure"))
    }

    @Test
    fun `getEmoji matches health - doctor`() {
        assertEquals("\uD83D\uDC68\u200D\u2695\uFE0F", EmojiMatcher.getEmoji("Doctor appointment"))
    }

    @Test
    fun `getEmoji matches health - therapy`() {
        assertEquals("\uD83E\uDDE0", EmojiMatcher.getEmoji("Therapy session"))
    }

    @Test
    fun `getEmoji matches health - vet`() {
        assertEquals("\uD83D\uDC15", EmojiMatcher.getEmoji("Vet appointment"))
    }

    @Test
    fun `getEmoji matches entertainment - movie`() {
        assertEquals("\uD83C\uDFAC", EmojiMatcher.getEmoji("Movie night"))
    }

    @Test
    fun `getEmoji matches entertainment - concert`() {
        assertEquals("\uD83C\uDFB5", EmojiMatcher.getEmoji("Concert tickets"))
    }

    @Test
    fun `getEmoji matches entertainment - museum`() {
        assertEquals("\uD83C\uDFDB\uFE0F", EmojiMatcher.getEmoji("Museum visit"))
    }

    // ==================== formatWithEmoji Tests ====================

    @Test
    fun `formatWithEmoji prepends emoji when enabled and match found`() {
        val result = EmojiMatcher.formatWithEmoji("Coffee with Sarah", showEmoji = true)
        assertEquals("\u2615 Coffee with Sarah", result)
    }

    @Test
    fun `formatWithEmoji returns original title when disabled`() {
        val result = EmojiMatcher.formatWithEmoji("Coffee with Sarah", showEmoji = false)
        assertEquals("Coffee with Sarah", result)
    }

    @Test
    fun `formatWithEmoji returns original title when no match`() {
        val result = EmojiMatcher.formatWithEmoji("Team standup", showEmoji = true)
        assertEquals("Team standup", result)
    }

    @Test
    fun `formatWithEmoji handles empty string`() {
        val result = EmojiMatcher.formatWithEmoji("", showEmoji = true)
        assertEquals("", result)
    }
}
