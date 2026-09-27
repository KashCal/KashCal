package org.onekash.kashcal.util

import org.onekash.icaldav.util.CalAddress

/**
 * Canonicalizes a CAL-ADDRESS (RFC 5545 §3.3.3) for compare-time equality.
 *
 * Mailboxes compare case-insensitively on local-part and domain, whether written as `mailto:`
 * or bare (the iCalendar parser strips the prefix, so a pulled ORGANIZER is stored bare).
 * RFC 5321 §2.4 keeps domains case-insensitive and discourages relying on local-part case, so
 * "Alice@Example.com" and "mailto:alice@example.com" are the same calendar user. `urn:`, HTTP
 * and principal-relative forms compare byte-equal: the server's casing is authoritative.
 * Storage stays raw and canonicalization happens only at lookup time, so addresses sent to
 * servers keep their casing.
 */
object AddressNormalizer {

    // Mailbox shape: local@domain.tld. Rejects bare logins ("alice"), dotless internal hosts
    // ("user@localhost") and non-mailto CAL-ADDRESS forms: urn:uuid: and principal paths,
    // including one whose login segment is itself an email, which a "/"-permissive class
    // would match. It is the pattern the ICS parser and generator share, so the store-side
    // and wire-side decisions can't diverge.
    private val EMAIL_SHAPE = CalAddress.mailtoShape

    /**
     * Returns true when [raw], after any `mailto:` strip, is email-shaped and so safe to emit
     * as a `mailto:` CAL-ADDRESS. A principal path, urn:uuid or bare login returns false.
     */
    fun isEmailShaped(raw: String): Boolean = EMAIL_SHAPE.matches(stripMailto(raw))

    fun canonical(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("mailto:", ignoreCase = true) ->
                trimmed.substring("mailto:".length).trim().lowercase()
            // A bare mailbox: has an '@' but no scheme (':') or path ('/'), so
            // urn:, http(s): and principal paths that embed an email stay exact.
            '@' in trimmed && ':' !in trimmed && '/' !in trimmed -> trimmed.lowercase()
            else -> trimmed
        }
    }

    /**
     * Strips a leading `mailto:` (case-insensitive) and keeps the rest's casing.
     *
     * For paths that need the bare email or URI but must round-trip the server-supplied
     * casing: Outlook keeps attendee-address casing, so lowercasing here breaks byte-equal
     * comparisons. For lookup-time identity matching, use [canonical].
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
