package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [AddressNormalizer.canonical] — the compare-time
 * canonicalizer for CAL-ADDRESS forms (RFC 5545 §3.3.3).
 *
 * Cases cover all four address shapes observed across seven CalDAV
 * implementations: `mailto:`, `urn:uuid:`, absolute HTTP, and
 * principal-relative path.
 */
class AddressNormalizerTest {

    @Test
    fun `mailto with mixed case is lowercased and prefix-stripped`() {
        assertEquals("alice@example.com", AddressNormalizer.canonical("mailto:Alice@Example.COM"))
    }

    @Test
    fun `mailto with surrounding whitespace is trimmed`() {
        assertEquals("bob@x.com", AddressNormalizer.canonical("  mailto:bob@x.com  "))
    }

    @Test
    fun `MAILTO uppercase scheme is recognized and stripped`() {
        assertEquals("carol@y.com", AddressNormalizer.canonical("MAILTO:carol@y.com"))
    }

    @Test
    fun `urn uuid preserves case sensitivity`() {
        assertEquals("urn:uuid:abc-DEF-123", AddressNormalizer.canonical("urn:uuid:abc-DEF-123"))
    }

    @Test
    fun `https principal URI is preserved verbatim`() {
        val input = "https://server.example/principals/alice/"
        assertEquals(input, AddressNormalizer.canonical(input))
    }

    @Test
    fun `path-relative principal href is preserved verbatim`() {
        assertEquals("/646691839/principal/", AddressNormalizer.canonical("/646691839/principal/"))
    }

    @Test
    fun `empty string canonicalizes to empty string`() {
        assertEquals("", AddressNormalizer.canonical(""))
    }

    @Test
    fun `unrecognized form is trimmed and returned`() {
        assertEquals("unknown-form", AddressNormalizer.canonical("  unknown-form  "))
    }
}
