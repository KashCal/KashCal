package org.onekash.kashcal.domain.quickadd

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.domain.quickadd.normalizer.SpannedText

class SpannedTextTest {

    private fun words(text: SpannedText) = text.words().map { it.text to it.sourceRange }

    @Test
    fun `an untouched text maps each word to itself`() {
        assertEquals(
            listOf("Ab" to 0..1, "c" to 3..3),
            words(SpannedText.of("Ab c"))
        )
    }

    @Test
    fun `a per-character change keeps every position`() {
        val lowered = SpannedText.of("Call Mom").lowercase()
        assertEquals("call mom", lowered.text)
        assertEquals(listOf("call" to 0..3, "mom" to 5..7), words(lowered))
    }

    @Test
    fun `a joined phrase maps to the whole typed phrase`() {
        val joined = SpannedText.of("Call Day after tomorrow")
            .lowercase()
            .replace(Regex("""\bday after tomorrow\b""")) { "day_after_tomorrow" }
        assertEquals(
            listOf("call" to 0..3, "day_after_tomorrow" to 5..22),
            words(joined)
        )
    }

    @Test
    fun `every word of a replacement maps to the whole match`() {
        val replaced = SpannedText.of("Read half an hour")
            .replace(Regex("half an hour")) { "30 minutes" }
        assertEquals(
            listOf("Read" to 0..3, "30" to 5..16, "minutes" to 5..16),
            words(replaced)
        )
    }

    @Test
    fun `a two-char code point replaced by one space keeps later positions exact`() {
        // U+1F44D is a symbol kept by the cleanup; the U+1F3FD skin-tone modifier after it is a
        // modifier symbol (two UTF-16 chars) that the cleanup turns into one space.
        val source = "👍🏽 Party"
        val cleaned = SpannedText.of(source)
            .replace(Regex("""[^\p{L}\p{N}\p{So}\s]""")) { " " }
            .collapseWhitespace()
        assertEquals("👍 Party", cleaned.text)
        assertEquals(listOf("👍" to 0..1, "Party" to 5..9), words(cleaned))
    }

    @Test
    fun `collapsing whitespace trims and joins runs`() {
        val collapsed = SpannedText.of("  Lunch \t with   Sam ").collapseWhitespace()
        assertEquals("Lunch with Sam", collapsed.text)
        assertEquals(
            listOf("Lunch" to 2..6, "with" to 10..13, "Sam" to 17..19),
            words(collapsed)
        )
    }

    @Test
    fun `an empty text has no words`() {
        assertEquals(emptyList<Pair<String, IntRange>>(), words(SpannedText.of("   ").collapseWhitespace()))
    }
}
