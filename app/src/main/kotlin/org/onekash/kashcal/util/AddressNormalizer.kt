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
