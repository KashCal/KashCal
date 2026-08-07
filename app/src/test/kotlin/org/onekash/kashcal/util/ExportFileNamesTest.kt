package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileNamesTest {

    @Test
    fun `preserves latin diacritics and eszett`() {
        // "Stra{ß}enfest M{ü}ller" using precomposed code points.
        val result = sanitizeExportBaseName("Straßenfest Müller", "event")
        assertTrue("Should keep eszett", result.contains("ß"))
        assertTrue("Should keep u-umlaut", result.contains("ü"))
        assertEquals("Straßenfest-Müller", result)
    }

    @Test
    fun `preserves non-latin scripts`() {
        val result = sanitizeExportBaseName("会議 Meeting", "event")
        assertTrue("Should keep CJK", result.contains("会議"))
        assertTrue("Should keep ASCII word", result.contains("Meeting"))
    }

    @Test
    fun `strips path-unsafe characters`() {
        val result = sanitizeExportBaseName("Q1/Q2: Budget", "event")
        assertFalse("No slash", result.contains("/"))
        assertFalse("No colon", result.contains(":"))
        assertTrue("Keeps letters/digits", result.contains("Q1"))
        assertTrue(result.contains("Q2"))
        assertTrue(result.contains("Budget"))
    }

    @Test
    fun `strips symbols punctuation and emoji`() {
        // "Party @ Caf{é} #1 {party-popper}!"
        val result = sanitizeExportBaseName("Party @ Café #1 🎉!", "event")
        assertFalse("No @", result.contains("@"))
        assertFalse("No #", result.contains("#"))
        assertFalse("No emoji", result.contains("🎉"))
        assertTrue("Keeps unicode letters", result.contains("Café"))
        assertTrue("Keeps digit", result.contains("1"))
    }

    @Test
    fun `collapses whitespace to single hyphens`() {
        assertEquals("Team-Meeting", sanitizeExportBaseName("Team   Meeting", "event"))
    }

    @Test
    fun `collapses non-ascii whitespace instead of stripping it`() {
        // U+3000 ideographic space and U+00A0 nbsp are common in CJK/macOS titles.
        // They must separate words (become a hyphen), not vanish and run words together.
        assertEquals("会議-Meeting", sanitizeExportBaseName("会議　Meeting", "event"))
        assertEquals("Team-Meeting", sanitizeExportBaseName("Team Meeting", "event"))
    }

    @Test
    fun `does not leave a trailing hyphen after truncation`() {
        // take(maxLength) can land right after a separator; the result must not
        // end in a dangling hyphen.
        val result = sanitizeExportBaseName("A".repeat(49) + " B", "event", maxLength = 50)
        assertFalse("No trailing hyphen", result.endsWith("-"))
    }

    @Test
    fun `falls back when nothing usable remains`() {
        assertEquals("event", sanitizeExportBaseName("@#\$%^&*", "event"))
        assertEquals("share-card", sanitizeExportBaseName("---", "share-card"))
    }

    @Test
    fun `caps length`() {
        val result = sanitizeExportBaseName("A".repeat(100), "event", maxLength = 50)
        assertEquals(50, result.length)
    }

    @Test
    fun `preserves decomposed NFD diacritics`() {
        // Base 'u' (U+0075) plus combining diaeresis (U+0308) = NFD form, not the
        // precomposed U+00FC. Proves the helper normalizes to NFC rather than
        // dropping the accent (a combining mark is \p{M}, not \p{L}).
        val nfd = "Müller"
        val result = sanitizeExportBaseName(nfd, "event")
        assertEquals("Müller", result) // precomposed u-umlaut, accent retained
    }

    @Test
    fun `does not leave a lone surrogate when truncating a supplementary letter`() {
        // U+20000 is a CJK Extension B ideograph (surrogate pair D840 DC00), a letter.
        val astral = "𠀀"
        val base = "A".repeat(49)
        val result = sanitizeExportBaseName(base + astral, "event", maxLength = 50)
        // take(50) would land mid-pair; the trailing high surrogate must be dropped.
        assertEquals(49, result.length)
        assertFalse("No lone high surrogate", result.last().isHighSurrogate())
    }

    @Test
    fun `keeps a supplementary letter that fits within the cap`() {
        val astral = "𠀀"
        val result = sanitizeExportBaseName("Hi $astral", "event", maxLength = 50)
        assertTrue("Keeps the full surrogate pair", result.contains(astral))
    }

    @Test
    fun `falls back when truncation drops a lone surrogate to empty`() {
        // maxLength=1 on a single supplementary letter: take(1) yields a lone
        // high surrogate, which is dropped to empty, which must hit the fallback.
        // Locks the invariant that ifEmpty runs after the surrogate trim.
        assertEquals("event", sanitizeExportBaseName("𠀀", "event", maxLength = 1))
    }
}
