package org.onekash.kashcal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

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

    // ==================== Reachability Invariant ====================

    @Test
    fun `every keyword returns its own emoji when queried in isolation`() {
        val unreachable = mutableListOf<String>()
        for ((keyword, expectedEmoji) in EmojiMatcher.keywordEmojiPairs()) {
            val actual = EmojiMatcher.getEmoji(keyword)
            if (actual != expectedEmoji) {
                unreachable += "\"$keyword\" expected $expectedEmoji but got $actual"
            }
        }
        assertEquals(
            "Every keyword must resolve to its own rule's emoji in isolation",
            emptyList<String>(),
            unreachable,
        )
    }

    @Test
    fun `previously shadowed eye doctor keyword now resolves to eye emoji`() {
        assertEquals("👁️", EmojiMatcher.getEmoji("Eye doctor appointment"))
    }

    @Test
    fun `previously shadowed family dinner keyword now resolves to family emoji`() {
        assertEquals("👨‍👩‍👧", EmojiMatcher.getEmoji("Family dinner"))
    }

    // ==================== Locale-Safe Case Folding ====================

    @Test
    fun `getEmoji matches ASCII keyword regardless of Turkish default locale`() {
        val previous = Locale.getDefault()
        try {
            // Turkish folds uppercase I to a dotless 'ı', so a default-locale
            // lowercase() would turn "PILATES" into "pılates" and miss the keyword.
            // Locale.ROOT folding must keep it "pilates" and still match.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("🧘", EmojiMatcher.getEmoji("PILATES class"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // ==================== Priority Preserved Across Bands ====================

    @Test
    fun `getEmoji prefers higher priority keyword on disjoint match`() {
        // "coffee" (priority 5) must beat "shopping" (priority 3) even though
        // "shopping" is the longer word — priority, not length, decides disjoint matches.
        assertEquals("☕", EmojiMatcher.getEmoji("Coffee shopping"))
    }

    // ==================== Suppress List (grief / medical short-circuit) ====================

    @Test
    fun `getEmoji returns null for suppressed grief and medical terms`() {
        // These titles must never be decorated, even when a keyword would otherwise match.
        val suppressed = listOf(
            "Dad's funeral",
            "Memorial service",
            "Grandma's memorial dinner", // "dinner" would match 🍽️ without suppression
            "Hospice visit",
            "Knee surgery",
            "Biopsy results",
            "Chemo appointment", // "appointment" is not a keyword, but chemo must suppress regardless
            "Divorce mediation",
            "Custody hearing",
            "Layoff meeting",
        )
        for (title in suppressed) {
            assertNull("\"$title\" must not be decorated", EmojiMatcher.getEmoji(title))
        }
    }

    @Test
    fun `getEmoji suppresses even when a matching keyword is present`() {
        // "memorial dinner" contains the "dinner" keyword; suppression wins.
        assertNull(EmojiMatcher.getEmoji("Memorial dinner"))
    }

    // ==================== Bare-Word False Positives ====================

    @Test
    fun `getEmoji no longer fires on bare common words`() {
        // Dropped/qualified bare words must not decorate unrelated titles.
        assertNull(EmojiMatcher.getEmoji("Unit test"))
        assertNull(EmojiMatcher.getEmoji("A/B test"))
        assertNull(EmojiMatcher.getEmoji("Class action lawsuit"))
        assertNull(EmojiMatcher.getEmoji("Mass transit meeting"))
        assertNull(EmojiMatcher.getEmoji("Run the numbers"))
        assertNull(EmojiMatcher.getEmoji("Grill the vendor"))
        assertNull(EmojiMatcher.getEmoji("Car pool"))
        assertNull(EmojiMatcher.getEmoji("Product demo review")) // "demo" dropped
        assertNull(EmojiMatcher.getEmoji("Pitch a tent")) // bare "pitch" dropped; "sales pitch" kept below
    }

    @Test
    fun `getEmoji bar exam resolves to books not beer`() {
        // "bar" bare dropped, so "exam" (📚) wins instead of "bar" (🍺).
        assertEquals("📚", EmojiMatcher.getEmoji("Bar exam"))
    }

    @Test
    fun `getEmoji still matches qualified forms of dropped bare words`() {
        assertEquals("🍺", EmojiMatcher.getEmoji("Sports bar meetup"))
        assertEquals("🍺", EmojiMatcher.getEmoji("Wine bar"))
        assertEquals("🍺", EmojiMatcher.getEmoji("Pub crawl"))
        assertEquals("🏃", EmojiMatcher.getEmoji("Morning run"))
        assertEquals("📞", EmojiMatcher.getEmoji("Phone call with client"))
        assertEquals("📞", EmojiMatcher.getEmoji("Conference call"))
        assertEquals("🎬", EmojiMatcher.getEmoji("Film festival"))
        assertEquals("📊", EmojiMatcher.getEmoji("Sales pitch deck"))
        assertEquals("👨‍⚕️", EmojiMatcher.getEmoji("Annual physical"))
    }

    @Test
    fun `getEmoji preserves legitimate matches unaffected by bare-word cleanup`() {
        // Sibling keywords in the same rules must still work.
        assertEquals("🏃", EmojiMatcher.getEmoji("Marathon training"))
        assertEquals("🏃", EmojiMatcher.getEmoji("Morning jog"))
        assertEquals("📚", EmojiMatcher.getEmoji("Study group"))
        assertEquals("📚", EmojiMatcher.getEmoji("Final exam"))
        assertEquals("🍖", EmojiMatcher.getEmoji("Weekend bbq"))
        assertEquals("📊", EmojiMatcher.getEmoji("Client presentation"))
        assertEquals("📞", EmojiMatcher.getEmoji("Phone call"))
    }

    // ==================== Double-Emoji Guard ====================

    @Test
    fun `getEmoji returns null when title already contains an emoji`() {
        // Synced Apple/Notion events often already carry an emoji; never stack a second.
        assertNull(EmojiMatcher.getEmoji("🎂 Birthday"))
        assertNull(EmojiMatcher.getEmoji("Pizza night 🍕"))
        assertNull(EmojiMatcher.getEmoji("Lunch 🍽️ with team"))
    }

    @Test
    fun `getEmoji still matches titles that contain no emoji`() {
        // The guard must not false-positive on ordinary text, including CJK / accented scripts.
        assertEquals("🎂", EmojiMatcher.getEmoji("Birthday"))
        assertEquals("☕", EmojiMatcher.getEmoji("Café coffee break")) // accented letter is not an emoji
        assertEquals("☕", EmojiMatcher.getEmoji("コーヒー coffee")) // CJK text is not an emoji
    }

    @Test
    fun `getEmoji treats text-default symbols as text, not emoji`() {
        // ™ ✓ ➡ ↔ render as text without a variation selector, so a title carrying
        // one still decorates — the guard is for genuine emoji, not any glyph that
        // happens to have an emoji form.
        assertEquals("💻", EmojiMatcher.getEmoji("Zoom™ standup"))
        assertEquals("☕", EmojiMatcher.getEmoji("Coffee ✓ done"))
        assertEquals("🎂", EmojiMatcher.getEmoji("Birthday ➡ cake"))
    }

    @Test
    fun `getEmoji suppresses titles whose symbol is styled as an emoji with a variation selector`() {
        // U+FE0F forces emoji rendering, so ✈️ is a real emoji even though bare ✈ is text.
        assertNull(EmojiMatcher.getEmoji("✈️ Flight to Spain"))
    }

    @Test
    fun `formatWithEmoji does not stack a second emoji`() {
        assertEquals("🎂 Birthday", EmojiMatcher.formatWithEmoji("🎂 Birthday", showEmoji = true))
    }

    // ==================== Whole-word matching (no substring false positives) ====================

    @Test
    fun `getEmoji matches presentation only as a whole word`() {
        // Matching is whole-word (tokenized), not substring: "presentation" must not
        // fire inside "representation", nor "pitch" inside "pitcher".
        assertEquals("📊", EmojiMatcher.getEmoji("Quarterly presentation"))
        assertNull(EmojiMatcher.getEmoji("Proportional representation reform"))
        assertNull(EmojiMatcher.getEmoji("Pitcher rotation meeting"))
    }

    // ==================== Emoji-presence guard: presentation set vs text-default ====================

    @Test
    fun `getEmoji suppresses code points with default emoji presentation`() {
        // Emoji_Presentation=Yes glyphs render as emoji with no variation selector,
        // so a title already carrying one is left undecorated.
        assertNull(EmojiMatcher.getEmoji("⭐ Coffee")) // 0x2B50
        assertNull(EmojiMatcher.getEmoji("Coffee ✅")) // 0x2705
        assertNull(EmojiMatcher.getEmoji("Coffee ❌ cancelled")) // 0x274C
        assertNull(EmojiMatcher.getEmoji("Coffee ➕ tea")) // 0x2795
    }

    @Test
    fun `getEmoji does not suppress bare text-default symbols`() {
        // These have an emoji form but render as text without U+FE0F, so they must
        // not be mistaken for an already-present emoji — the title still decorates.
        assertEquals("☕", EmojiMatcher.getEmoji("Coffee ™ launch")) // 0x2122
        assertEquals("☕", EmojiMatcher.getEmoji("Coffee ✓ done")) // 0x2713
        assertEquals("☕", EmojiMatcher.getEmoji("Coffee ➡ next")) // 0x27A1
        assertEquals("☕", EmojiMatcher.getEmoji("Coffee ❤ you")) // 0x2764 bare, no FE0F
    }
}
