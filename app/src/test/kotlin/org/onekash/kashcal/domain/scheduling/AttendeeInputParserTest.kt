package org.onekash.kashcal.domain.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [AttendeeInputParser] — the lenient (RFC 5322 §3.4.1)
 * parse of free-typed attendee input into a name + email, gated by the shared
 * email-shape predicate.
 */
class AttendeeInputParserTest {

    @Test
    fun `bare email parses to email with no name`() {
        assertEquals(
            AttendeeInput.Valid(displayName = null, email = "alice@example.com"),
            AttendeeInputParser.parse("alice@example.com"),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            AttendeeInput.Valid(displayName = null, email = "alice@example.com"),
            AttendeeInputParser.parse("   alice@example.com  "),
        )
    }

    @Test
    fun `display name with bracketed email parses both`() {
        assertEquals(
            AttendeeInput.Valid(displayName = "Alice Chen", email = "alice@example.com"),
            AttendeeInputParser.parse("Alice Chen <alice@example.com>"),
        )
    }

    @Test
    fun `bracketed email trims inner whitespace and name`() {
        assertEquals(
            AttendeeInput.Valid(displayName = "Alice Chen", email = "alice@example.com"),
            AttendeeInputParser.parse("  Alice Chen  < alice@example.com >  "),
        )
    }

    @Test
    fun `quoted display name has its quotes stripped`() {
        assertEquals(
            AttendeeInput.Valid(displayName = "Chen, Alice", email = "alice@example.com"),
            AttendeeInputParser.parse("\"Chen, Alice\" <alice@example.com>"),
        )
    }

    @Test
    fun `bracket form with empty name yields null name`() {
        assertEquals(
            AttendeeInput.Valid(displayName = null, email = "alice@example.com"),
            AttendeeInputParser.parse("<alice@example.com>"),
        )
    }

    @Test
    fun `input with no at-sign is invalid`() {
        assertEquals(AttendeeInput.Invalid, AttendeeInputParser.parse("alice"))
    }

    @Test
    fun `blank input is invalid`() {
        assertEquals(AttendeeInput.Invalid, AttendeeInputParser.parse("   "))
    }

    @Test
    fun `dotless domain is invalid (matches shared email shape)`() {
        // AddressNormalizer.isEmailShaped rejects user@localhost.
        assertEquals(AttendeeInput.Invalid, AttendeeInputParser.parse("user@localhost"))
    }

    @Test
    fun `email with leading mailto is accepted and normalized off`() {
        assertEquals(
            AttendeeInput.Valid(displayName = null, email = "alice@example.com"),
            AttendeeInputParser.parse("mailto:alice@example.com"),
        )
    }

    @Test
    fun `bracketed email inside a name-only string with no at is invalid`() {
        assertEquals(AttendeeInput.Invalid, AttendeeInputParser.parse("Alice <alice>"))
    }

    @Test
    fun `bare address with a stray trailing bracket is invalid`() {
        assertEquals(AttendeeInput.Invalid, AttendeeInputParser.parse("a@b.com>"))
    }

    @Test
    fun `comma-separated address list is rejected (one at a time)`() {
        assertEquals(AttendeeInput.Invalid, AttendeeInputParser.parse("a@b.com,c@d.com"))
    }

    @Test
    fun `semicolon-separated address list is rejected`() {
        assertEquals(AttendeeInput.Invalid, AttendeeInputParser.parse("a@b.com;c@d.com"))
    }
}
