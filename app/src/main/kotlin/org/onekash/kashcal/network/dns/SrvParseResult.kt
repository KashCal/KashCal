package org.onekash.kashcal.network.dns

/**
 * Holds the outcome of decoding a DNS SRV response, everything decidable from the bytes
 * alone. A typed result, never an exception or a half-built record, because the bytes come
 * from an untrusted resolver or server.
 *
 * [SrvResolverImpl] maps it 1:1 onto [SrvResult], adding only a channel that threw. An empty
 * body arrives here as [Failed].
 */
sealed interface SrvParseResult {

    /** Holds the decoded SRV records in wire order, not yet RFC 2782 ordered. */
    data class Records(val records: List<SrvRecord>) : SrvParseResult

    /**
     * Only SRV records whose target is the DNS root (`.`) were found. RFC 2782 makes that an
     * explicit "the service is not available at this domain", so the client must not fall
     * back to guessing a host, unlike [NoRecords].
     */
    object NotAvailable : SrvParseResult

    /**
     * No SRV records: an RCODE 0 answer without any, or NXDOMAIN (RCODE 3). The caller
     * should fall back to the well-known URI or a bootstrap host.
     */
    object NoRecords : SrvParseResult

    /**
     * The response couldn't be trusted: malformed, truncated or empty bytes, or a failure
     * RCODE (SERVFAIL, REFUSED, ...). [reason] is a short diagnostic.
     */
    data class Failed(val reason: String) : SrvParseResult
}
