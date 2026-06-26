package org.onekash.kashcal.util

/**
 * Canonicalize a CAL-ADDRESS (RFC 5545 §3.3.3) for compare-time equality.
 *
 * `mailto:` is case-insensitive on prefix, local-part, and domain.
 * `urn:`, HTTP, and principal-relative forms compare byte-equal — server
 * casing is authoritative. Storage stays raw; canonicalization only
 * happens at lookup time.
 */
object AddressNormalizer {

    // Lenient RFC 5322 §3.4.1 email shape: local@domain.tld. Rejects bare
    // logins ("alice"), dotless internal hosts ("user@localhost"), and
    // non-mailto CAL-ADDRESS forms (urn:uuid:, principal paths). Single source
    // of truth for "is this a mailto-emittable address" across the organizer
    // resolution + attendee-entity + integration-test paths.
    private val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

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
