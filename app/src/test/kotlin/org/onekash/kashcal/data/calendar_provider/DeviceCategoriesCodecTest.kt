package org.onekash.kashcal.data.calendar_provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the device-calendar categories codec.
 *
 * The stored VALUE joins tag names with a SINGLE backslash character (byte
 * 0x5C). In Kotlin literals that one character is written `"\\"`, so a stored
 * value of three tags is the 20-char string `Work\Personal\Errands`, written
 * `"Work\\Personal\\Errands"` here. Asserting against the doubled form would be
 * a two-backslash bug — these tests pin the single-char boundary deliberately.
 */
class DeviceCategoriesCodecTest {

    @Test
    fun `encode joins names with a single backslash`() {
        val encoded = encodeCategories(listOf("Work", "Personal", "Errands"))
        assertEquals("Work\\Personal\\Errands", encoded)
    }

    @Test
    fun `decode splits a single-backslash value into names`() {
        val decoded = decodeCategories("Work\\Personal\\Errands")
        assertEquals(listOf("Work", "Personal", "Errands"), decoded)
    }

    @Test
    fun `encode then decode round-trips a three-tag list`() {
        val names = listOf("Work", "Personal", "Errands")
        assertEquals(names, decodeCategories(encodeCategories(names)))
    }

    @Test
    fun `encode strips a backslash inside a name so the separator stays unambiguous`() {
        // "Work\Personal" is ONE name containing a literal backslash. Stripping
        // it yields "WorkPersonal" so the join delimiter can't be confused with
        // an in-name character.
        val encoded = encodeCategories(listOf("Work\\Personal", "Home"))
        assertEquals("WorkPersonal\\Home", encoded)
        assertEquals(listOf("WorkPersonal", "Home"), decodeCategories(encoded))
    }

    @Test
    fun `encode returns null for an empty list`() {
        assertNull(encodeCategories(emptyList()))
    }

    @Test
    fun `encode returns null when every name is blank`() {
        assertNull(encodeCategories(listOf("", "   ", "\t")))
    }

    @Test
    fun `encode drops blank names and trims survivors`() {
        assertEquals("Work\\Home", encodeCategories(listOf("  Work ", "", "Home")))
    }

    @Test
    fun `encode dedups case-insensitively keeping first-seen casing`() {
        // "Work" and "home" are first-seen; the later "WORK"/"Home" collapse
        // into them and don't change the stored casing.
        assertEquals("Work\\home", encodeCategories(listOf("Work", "home", "WORK", "Home")))
    }

    @Test
    fun `decode of null yields empty list`() {
        assertEquals(emptyList<String>(), decodeCategories(null))
    }

    @Test
    fun `decode of blank yields empty list`() {
        assertEquals(emptyList<String>(), decodeCategories("   "))
    }

    @Test
    fun `decode tolerates foreign mixed-case content without rewriting casing`() {
        // A value written by another app: unknown names, arbitrary casing.
        // The read path must surface them verbatim, never crash.
        assertEquals(listOf("WORK", "home"), decodeCategories("WORK\\home"))
    }

    @Test
    fun `decode drops blank segments from a malformed value`() {
        assertEquals(listOf("Work", "Home"), decodeCategories("Work\\\\Home\\"))
    }

    @Test
    fun `cleanCategoryNames yields the same names encode would store`() {
        // The registry records these names, so they must match what the provider
        // stores: backslash stripped, blanks dropped, dupes collapsed. A tag like
        // "a\b" persists as "ab", so it must be recorded as "ab".
        val raw = listOf("  Work ", "a\\b", "", "WORK")
        val cleaned = cleanCategoryNames(raw)
        assertEquals(listOf("Work", "ab"), cleaned)
        // Same shape encodeCategories would produce for the survivors.
        assertEquals(cleaned, decodeCategories(encodeCategories(raw)))
    }

    @Test
    fun `cleanCategoryNames yields empty list when nothing survives`() {
        assertEquals(emptyList<String>(), cleanCategoryNames(listOf("", "  ", "\\")))
    }
}
