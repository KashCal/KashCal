package org.onekash.kashcal.network.dns

/**
 * Holds the outcome of decoding a DNS TXT response: everything decidable from the response
 * bytes alone. Like [SrvParseResult], it never throws and never holds a half-built value,
 * because the bytes come from an untrusted resolver or server.
 *
 * A TXT lookup follows a successful SRV lookup (RFC 6764 §6 step 3): the strings are searched
 * for a `path` key (RFC 6763 §6.4) that overrides the `.well-known` context path.
 * [TxtRecordParser.pathValue] does that extraction.
 */
sealed interface TxtParseResult {

    /**
     * Holds the TXT character-strings of every TXT RR in the response, in wire order.
     *
     * At least one string is present (no TXT string at all is [NoRecords]), but a string may be
     * empty: a TXT RR whose rdata is a single zero-length character-string decodes to `[""]`.
     * Read attributes through [TxtRecordParser.pathValue], which applies the RFC 6763 §6.4 key
     * rules; a raw string is not a key.
     */
    data class Records(val strings: List<String>) : TxtParseResult

    /**
     * Means no usable TXT data: a successful (RCODE 0) answer with no TXT RR or only ones with
     * empty rdata, or NXDOMAIN (RCODE 3). The caller falls back to the `.well-known` context
     * path.
     */
    object NoRecords : TxtParseResult

    /**
     * Means the response can't be trusted: malformed or truncated bytes, or any RCODE other than
     * 0 and 3 (SERVFAIL, REFUSED, ...). [reason] is a short diagnostic.
     */
    data class Failed(val reason: String) : TxtParseResult
}
