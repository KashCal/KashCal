package org.onekash.kashcal.domain.scheduling

import org.onekash.kashcal.util.AddressNormalizer

/** Outcome of parsing free-typed attendee input. */
sealed interface AttendeeInput {
    /** A usable address, optionally with a display name parsed from a `Name <email>` form. */
    data class Valid(val displayName: String?, val email: String) : AttendeeInput

    /** The input isn't an email-shaped address (no usable attendee). */
    data object Invalid : AttendeeInput
}

/**
 * Parses what the user types in the attendee field into a name + email.
 *
 * Lenient per RFC 5322 §3.4.1 (the strict ABNF is impractical client-side):
 * accepts a bare `local@domain.tld`, a `Display Name <email>` form (quotes
 * around the name are stripped), and a leading `mailto:`. Validity is decided
 * by the shared [AddressNormalizer.isEmailShaped] predicate — the single
 * source of truth for "is this a mailto-emittable address" — so the parser
 * never introduces its own competing `@`-check.
 */
object AttendeeInputParser {

    private val BRACKETED = Regex("""^(.*)<\s*(.*?)\s*>$""")

    fun parse(raw: String): AttendeeInput {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return AttendeeInput.Invalid

        val match = BRACKETED.matchEntire(trimmed)
        val (namePart, addressPart) = if (match != null) {
            match.groupValues[1].trim().trim('"').trim() to match.groupValues[2]
        } else {
            null to trimmed
        }

        val email = AddressNormalizer.stripMailto(addressPart).trim()
        // Defense-in-depth against address-list / angle-bracket punctuation
        // (< > , ;) — the picker adds one clean address at a time, never a
        // "a@b.com>" or "a@b.com,c@d.com" list. The strict shape below already
        // rejects these chars; this guard keeps that guarantee explicit should
        // the shared mailbox pattern ever loosen.
        if (email.any { it in "<>,;" }) return AttendeeInput.Invalid
        if (!AddressNormalizer.isEmailShaped(email)) return AttendeeInput.Invalid

        return AttendeeInput.Valid(
            displayName = namePart?.ifBlank { null },
            email = email,
        )
    }
}
