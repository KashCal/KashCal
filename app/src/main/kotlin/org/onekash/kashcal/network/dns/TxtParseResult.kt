package org.onekash.kashcal.network.dns

/**
 * The outcome of decoding a DNS TXT response — everything decidable from the
 * response bytes alone. Like [SrvParseResult], it is a typed result (never an
 * exception, never a half-built value) because the bytes come from an untrusted
 * resolver/server.
 *
 * A TXT lookup is the RFC 6764 §6 step 3 companion to a successful SRV lookup:
 * the strings are searched for a `path` key (RFC 6763 §6.4) that overrides the
 * `.well-known` context path. [TxtRecordParser.pathValue] does that extraction.
 */
sealed interface TxtParseResult {

    /**
     * The TXT character-strings decoded from the response, aggregated across all
     * TXT RRs in wire order. At least one string is present (an answer with no TXT
     * RR at all is [NoRecords]), but an individual string may be empty: a TXT RR
     * whose rdata is a single zero-length character-string decodes to `[""]`.
     * Consumers that want attribute semantics should read via [TxtRecordParser.pathValue],
     * which applies the RFC 6763 §6.4 key rules rather than treating raw strings as keys.
     */
    data class Records(val strings: List<String>) : TxtParseResult

    /**
     * No usable TXT data: a successful (RCODE 0) answer with no TXT RR (or only
     * empty ones), or NXDOMAIN (RCODE 3). The caller falls back to the
     * `.well-known` context path.
     */
    object NoRecords : TxtParseResult

    /**
     * The response could not be trusted: malformed/truncated bytes, or a server
     * failure RCODE (SERVFAIL, REFUSED, ...). [reason] is a short diagnostic.
     */
    data class Failed(val reason: String) : TxtParseResult
}
