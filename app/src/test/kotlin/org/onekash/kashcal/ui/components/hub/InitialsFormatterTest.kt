package org.onekash.kashcal.ui.components.hub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [normalizeInitials], the pure logic behind the avatar's
 * 2-letter monogram and the inline editor.
 *
 * Contract: uppercase (locale-independent), keep only letters, take the first
 * two, and preserve blank as blank so a cleared field reverts the avatar to its
 * generic glyph.
 */
class InitialsFormatterTest {

    @Test
    fun `blank stays blank`() {
        assertEquals("", normalizeInitials(""))
        assertEquals("", normalizeInitials("   "))
    }

    @Test
    fun `two letters uppercase`() {
        assertEquals("AB", normalizeInitials("ab"))
        assertEquals("AB", normalizeInitials("AB"))
    }

    @Test
    fun `single letter kept`() {
        assertEquals("A", normalizeInitials("a"))
    }

    @Test
    fun `takes first two letters of a longer word`() {
        assertEquals("JO", normalizeInitials("john"))
    }

    @Test
    fun `strips digits and symbols keeping first two letters`() {
        assertEquals("XY", normalizeInitials("12x9y"))
        assertEquals("AB", normalizeInitials("a!b@c"))
    }

    @Test
    fun `strips surrounding and internal whitespace`() {
        assertEquals("AB", normalizeInitials("  a b "))
    }

    @Test
    fun `unicode letters are allowed`() {
        // Cyrillic letters are letters; uppercased and kept.
        assertEquals("ПР", normalizeInitials("пр"))
    }

    @Test
    fun `accented latin letters are kept`() {
        assertEquals("ÉÑ", normalizeInitials("éñ"))
    }

    @Test
    fun `caseless scripts are kept as-is`() {
        // Han characters have no case, so uppercasing is a no-op; a single
        // ideograph is a complete initial on its own.
        assertEquals("日本", normalizeInitials("日本語"))
        // Arabic (also caseless) is preserved.
        assertEquals("مر", normalizeInitials("مرحبا"))
    }

    @Test
    fun `uppercasing is locale-independent`() {
        // Turkish dotless-i trap: with a Turkish locale, "i".uppercase() -> "İ".
        // normalizeInitials must use Locale.ROOT, so "i" -> "I" regardless of the
        // device locale.
        assertEquals("I", normalizeInitials("i"))
    }

    @Test
    fun `uppercase is applied per letter so expansion does not multiply across the pair`() {
        // German ß uppercases to SS. Taking two SOURCE letters then uppercasing
        // each: "ßa" -> "SS" + "A". The cap is on source letters (two), not on the
        // rendered length; a single expanding letter must not also drag in a third.
        assertEquals("SSA", normalizeInitials("ßabc"))
        // A normal two-letter input is unaffected.
        assertEquals("AB", normalizeInitials("abc"))
    }

    @Test
    fun `does not split a surrogate pair`() {
        // A Deseret capital letter (astral plane, U+10400) is a single letter but
        // two Java chars. Iterating by char would keep a broken half; iterating by
        // code point keeps the whole glyph.
        val deseret = "𐐀" // DESERET CAPITAL LETTER LONG I
        val result = normalizeInitials(deseret + "b")
        // Whatever the casing, the astral letter must survive intact (2 chars) and
        // the ASCII letter follows.
        assertEquals(deseret.length + 1, result.length)
        assertEquals('b'.uppercaseChar(), result.last())
        assertTrue(result.startsWith(deseret) || result.startsWith(deseret.uppercase()))
    }

    private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
