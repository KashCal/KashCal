package org.onekash.kashcal.network.dns

/**
 * Holds the outcome of an SRV lookup: [SrvParseResult] plus the transport failures the parser
 * can't see (timeout, resolver or network error). [SrvResolverImpl] maps it this way, passing
 * [Found] records through [SrvSelection] so they arrive ordered for connection attempts:
 *
 *   [SrvParseResult.Records]      -> [Found] (RFC 2782 ordered)
 *   [SrvParseResult.NotAvailable] -> [NotAvailable]
 *   [SrvParseResult.NoRecords]    -> [NoRecords]
 *   [SrvParseResult.Failed]       -> [Error] (includes an empty body)
 *   channel threw                 -> [Error]
 */
sealed interface SrvResult {

    /** At least one usable SRV record, already ordered per RFC 2782 (priority then weight). */
    data class Found(val records: List<SrvRecord>) : SrvResult

    /**
     * RFC 2782's explicit "service not available at this domain" (only root "." targets).
     * Unlike [NoRecords], the domain has decided it offers no such service, so the caller
     * must not fall through to guessing a host.
     */
    object NotAvailable : SrvResult

    /**
     * No SRV data (an RCODE 0 answer without any, or NXDOMAIN). The domain doesn't publish
     * the record; the caller falls back to the well-known URI or a known host.
     */
    object NoRecords : SrvResult

    /**
     * The lookup couldn't be trusted or completed: a failure RCODE (SERVFAIL, REFUSED, ...),
     * malformed or empty response bytes, or a channel that threw (e.g. a timeout). [reason] is
     * a short diagnostic.
     */
    data class Error(val reason: String) : SrvResult
}
