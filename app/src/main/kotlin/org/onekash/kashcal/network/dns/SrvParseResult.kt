package org.onekash.kashcal.network.dns

/**
 * The outcome of decoding a DNS SRV response — everything decidable from the
 * response bytes alone. Kept as a typed result (never an exception, never a
 * half-built record) because the bytes come from an untrusted resolver/server.
 *
 * A higher resolver layer maps this 1:1 onto its transport-aware result, adding
 * only failures the parser cannot see from bytes (socket timeout, empty body).
 */
sealed interface SrvParseResult {

    /** One or more SRV records were decoded (in wire order; not yet RFC 2782 ordered). */
    data class Records(val records: List<SrvRecord>) : SrvParseResult

    /**
     * A single SRV record whose target is the DNS root (`.`). RFC 2782: this is
     * an explicit "the service is not available at this domain" — the client
     * must NOT fall back to guessing a host. Distinct from [NoRecords].
     */
    object NotAvailable : SrvParseResult

    /**
     * No SRV records: either a successful (RCODE 0) but empty answer, or NXDOMAIN
     * (RCODE 3). The caller should fall back to well-known / a bootstrap host.
     */
    object NoRecords : SrvParseResult

    /**
     * The response could not be trusted: malformed/truncated bytes, or a server
     * failure RCODE (SERVFAIL, REFUSED, ...). [reason] is a short diagnostic.
     */
    data class Failed(val reason: String) : SrvParseResult
}
