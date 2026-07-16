package org.onekash.kashcal.domain.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for [TagTokenizer] — the single owner of the "#tag" grammar,
 * shared by Quick Add extraction and the form's inline "#" autocomplete.
 */
class TagTokenizerTest {

    // ==================== extract() ====================

    @Test
    fun `extracts a single tag and cleans the remaining text`() {
        val (text, tags) = TagTokenizer.extract("#work Lunch")
        assertEquals(listOf("work"), tags)
        assertEquals("Lunch", text)
    }

    @Test
    fun `extracts multiple tags`() {
        val (text, tags) = TagTokenizer.extract("Lunch #work #urgent")
        assertEquals(listOf("work", "urgent"), tags)
        assertEquals("Lunch", text)
    }

    @Test
    fun `extracts a tag in the middle preserving surrounding words`() {
        val (text, tags) = TagTokenizer.extract("Call #work Bob")
        assertEquals(listOf("work"), tags)
        assertEquals("Call Bob", text)
    }

    @Test
    fun `captures unicode tags`() {
        val (_, tags) = TagTokenizer.extract("Dinner #café #日本語")
        assertEquals(listOf("café", "日本語"), tags)
    }

    @Test
    fun `bare hash is not a tag`() {
        val (text, tags) = TagTokenizer.extract("Meet # now")
        assertEquals(emptyList<String>(), tags)
        assertEquals("Meet # now", text)
    }

    @Test
    fun `case-insensitive duplicate tags collapse to first-seen casing`() {
        val (_, tags) = TagTokenizer.extract("#Work #work")
        assertEquals(listOf("Work"), tags)
    }

    @Test
    fun `no tags leaves text unchanged`() {
        val (text, tags) = TagTokenizer.extract("Just lunch")
        assertEquals(emptyList<String>(), tags)
        assertEquals("Just lunch", text)
    }

    @Test
    fun `over-length tag is rejected, not truncated, and left in the text`() {
        val long = "a".repeat(70)
        val (text, tags) = TagTokenizer.extract("Lunch #$long")
        // Not accepted as a (truncated) tag...
        assertEquals(emptyList<String>(), tags)
        // ...and the literal #word stays in the title rather than vanishing.
        assertEquals("Lunch #$long", text)
    }

    @Test
    fun `a valid tag alongside a rejected over-length one is still captured`() {
        val long = "b".repeat(70)
        val (text, tags) = TagTokenizer.extract("Lunch #work #$long")
        assertEquals(listOf("work"), tags)
        assertEquals("Lunch #$long", text)
    }

    // ==================== trailingHashPrefix() ====================

    @Test
    fun `trailing hash prefix returns the in-progress fragment`() {
        assertEquals("wo", TagTokenizer.trailingHashPrefix("Lunch #wo"))
    }

    @Test
    fun `trailing hash prefix is empty string right after typing hash`() {
        assertEquals("", TagTokenizer.trailingHashPrefix("Lunch #"))
    }

    @Test
    fun `no trailing hash prefix when text ends in a space`() {
        assertNull(TagTokenizer.trailingHashPrefix("Lunch #work "))
    }

    @Test
    fun `no trailing hash prefix without a hash`() {
        assertNull(TagTokenizer.trailingHashPrefix("Lunch"))
    }

    // ==================== stripToken() ====================

    @Test
    fun `strips a committed token and collapses whitespace`() {
        assertEquals("Lunch", TagTokenizer.stripToken("Lunch #wo", "#wo"))
    }

    @Test
    fun `stripping the only token yields empty`() {
        assertEquals("", TagTokenizer.stripToken("#work", "#work"))
    }

    @Test
    fun `token that is not a suffix leaves the text unchanged`() {
        // stripToken only removes the token when it's at the very end; an
        // identical fragment earlier in the title must be left alone.
        assertEquals("Lunch #work", TagTokenizer.stripToken("Lunch #work", "#wo"))
    }

    // ==================== boundary + grammar coverage ====================

    @Test
    fun `tag of exactly the max length is accepted`() {
        val name = "a".repeat(64) // CategoryNameValidator.MAX_LENGTH
        val (text, tags) = TagTokenizer.extract("#$name")
        assertEquals(listOf(name), tags)
        assertEquals("", text)
    }

    @Test
    fun `tag one over the max length is rejected and left in the text`() {
        val name = "a".repeat(65)
        val (text, tags) = TagTokenizer.extract("#$name")
        assertEquals(emptyList<String>(), tags)
        assertEquals("#$name", text)
    }

    @Test
    fun `adjacent tags with no separator are split`() {
        // '#' is not in the tag char class, so it terminates the preceding token.
        val (text, tags) = TagTokenizer.extract("#a#b")
        assertEquals(listOf("a", "b"), tags)
        assertEquals("", text)
    }

    @Test
    fun `tags may contain digits underscores and hyphens`() {
        val (_, tags) = TagTokenizer.extract("Plan #2fa #foo_bar #foo-bar")
        assertEquals(listOf("2fa", "foo_bar", "foo-bar"), tags)
    }

    @Test
    fun `trailing hash prefix returns the last in-progress fragment`() {
        assertEquals("b", TagTokenizer.trailingHashPrefix("#a #b"))
    }
}
