package org.onekash.kashcal.util

import org.onekash.icaldav.util.CalAddress

/**
 * Canonicalize a CAL-ADDRESS (RFC 5545 §3.3.3) for compare-time equality.
 *
 * `mailto:` is case-insensitive on prefix, local-part, and domain.
 * `urn:`, HTTP, and principal-relative forms compare byte-equal — server
 * casing is authoritative. Storage stays raw; canonicalization only
 * happens at lookup time.
 */
object AddressNormalizer {

    // Mailbox shape: local@domain.tld. Rejects bare logins ("alice"), dotless
    // internal hosts ("user@localhost"), and non-mailto CAL-ADDRESS forms
    // (urn:uuid:, principal paths — including a principal path whose login
    // segment is itself an email, which a "/"-permissive char class would wrongly
    // match). Reuses the CAL-ADDRESS mailbox pattern that the ICS parser/generator
    // already share, so the store-side and wire-side decisions cannot diverge.
    private val EMAIL_SHAPE = CalAddress.mailtoShape

    /**
     * True when [raw] (after any `mailto:` strip) is email-shaped — i.e. safe
     * to emit as a `mailto:` CAL-ADDRESS. A principal path / urn:uuid / bare
     * login returns false.
     */
    fun isEmailShaped(raw: String): Boolean = EMAIL_SHAPE.matches(stripMailto(raw))

    fun canonical(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("mailto:", ignoreCase = true) ->
                trimmed.substring("mailto:".length).trim().lowercase()
            else -> trimmed
        }
    }

    /**
     * Strip a leading `mailto:` (case-insensitive) without lowercasing the
     * remaining local part. Used by paths that need the bare email/URI but
     * must preserve the server-supplied casing for round-trips (Outlook
     * retains attendee-address casing, breaking byte-equality comparisons
     * if we lowercase here). For lookup-time identity matching, use
     * [canonical] instead.
     */
    fun stripMailto(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("mailto:", ignoreCase = true)) {
            trimmed.substring("mailto:".length)
        } else {
            trimmed
        }
    }
}
