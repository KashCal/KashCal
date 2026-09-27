package org.onekash.kashcal.ui.components.hub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [normalizeInitials], behind the avatar's two-letter monogram and the inline
 * editor: keep only letters, take the first two, uppercase them locale-independently, and keep
 * blank as blank so a cleared field reverts the avatar to its generic glyph.
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
        // Han characters have no case, so uppercasing is a no-op; each ideograph is one letter.
        assertEquals("日本", normalizeInitials("日本語"))
        // Arabic (also caseless) is preserved.
        assertEquals("مر", normalizeInitials("مرحبا"))
    }

    @Test
    fun `uppercasing is locale-independent`() {
        // Under a Turkish locale "i".uppercase() gives "İ"; normalizeInitials must use
        // Locale.ROOT so "i" gives "I" on any device. This run uses the host locale.
        assertEquals("I", normalizeInitials("i"))
    }

    @Test
    fun `uppercase is applied per letter so expansion does not multiply across the pair`() {
        // German ß uppercases to SS. The cap is two source letters, each uppercased as it is
        // taken ("ßa" gives "SS" + "A"), not the rendered length, so an expanding letter doesn't
        // pull in a third.
        assertEquals("SSA", normalizeInitials("ßabc"))
        // A normal two-letter input is unaffected.
        assertEquals("AB", normalizeInitials("abc"))
    }

    @Test
    fun `does not split a surrogate pair`() {
        // A Deseret capital letter (astral plane, U+10400) is one letter but two Java chars.
        // Iterating by char would keep a broken half; iterating by code point keeps it whole.
        val deseret = "𐐀" // DESERET CAPITAL LETTER LONG I
        val result = normalizeInitials(deseret + "b")
        // Whatever the casing, the astral letter survives intact (2 chars), then the ASCII one.
        assertEquals(deseret.length + 1, result.length)
        assertEquals('b'.uppercaseChar(), result.last())
        assertTrue(result.startsWith(deseret) || result.startsWith(deseret.uppercase()))
    }

    private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
