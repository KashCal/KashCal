package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ===== isEmailShaped — gates mailto-emittable ORGANIZER/ATTENDEE addresses =====

    @Test
    fun `isEmailShaped accepts a plain email`() {
        assertTrue(AddressNormalizer.isEmailShaped("alice@example.com"))
    }

    @Test
    fun `isEmailShaped accepts a mailto-prefixed email (strips first)`() {
        assertTrue(AddressNormalizer.isEmailShaped("mailto:alice@example.com"))
        assertTrue(AddressNormalizer.isEmailShaped("MAILTO:Alice@Example.COM"))
    }

    @Test
    fun `isEmailShaped accepts plus-addressing and subdomains`() {
        assertTrue(AddressNormalizer.isEmailShaped("user+tag@mail.example.co.uk"))
    }

    @Test
    fun `isEmailShaped rejects a dotless internal host`() {
        // The exact gap the review flagged: user@localhost / testuser1@radicale
        // must NOT be emitted as a mailto ORGANIZER (no TLD dot).
        assertFalse(AddressNormalizer.isEmailShaped("user@localhost"))
        assertFalse(AddressNormalizer.isEmailShaped("testuser1@radicale"))
    }

    @Test
    fun `isEmailShaped rejects bare login`() {
        assertFalse(AddressNormalizer.isEmailShaped("alice"))
    }

    @Test
    fun `isEmailShaped rejects urn and principal-path forms`() {
        assertFalse(AddressNormalizer.isEmailShaped("urn:uuid:123456789"))
        assertFalse(AddressNormalizer.isEmailShaped("/123/principal/"))
    }

    @Test
    fun `isEmailShaped rejects a principal path whose login segment is itself an email`() {
        // A CalDAV principal href for an account whose login is an email address
        // embeds an '@' and a dot, so a permissive local/domain char class would
        // match the whole path and wrongly emit it as a mailto ORGANIZER. It is a
        // DAV path, not an email transport address (RFC 5545 3.3.3).
        assertFalse(
            AddressNormalizer.isEmailShaped("/cloud/remote.php/caldav/principals/organizer@example.com/")
        )
        assertFalse(
            AddressNormalizer.isEmailShaped("/remote.php/dav/principals/users/organizer@example.com/")
        )
    }

    @Test
    fun `isEmailShaped rejects empty and whitespace and bare prefix`() {
        assertFalse(AddressNormalizer.isEmailShaped(""))
        assertFalse(AddressNormalizer.isEmailShaped("   "))
        assertFalse(AddressNormalizer.isEmailShaped("mailto:"))
    }
}
