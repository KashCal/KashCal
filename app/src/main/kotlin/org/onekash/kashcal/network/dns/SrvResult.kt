package org.onekash.kashcal.network.dns

/**
 * The outcome of an SRV *lookup* — a superset of [SrvParseResult] that adds the
 * transport failures the pure parser cannot see from bytes alone (timeout, empty
 * body, resolver error). The mapping from parse result is 1:1 except that a
 * [SrvResolver] additionally emits [Error] for a channel that threw or returned no
 * bytes, and hands [Found] records through [SrvSelection] so they arrive already
 * ordered for connection attempts.
 *
 *   [SrvParseResult.Records]      -> [Found] (RFC 2782 ordered)
 *   [SrvParseResult.NotAvailable] -> [NotAvailable]
 *   [SrvParseResult.NoRecords]    -> [NoRecords]
 *   [SrvParseResult.Failed]       -> [Error]
 *   channel threw / empty body    -> [Error]
 */
sealed interface SrvResult {

    /** At least one usable SRV record, already ordered per RFC 2782 (priority then weight). */
    data class Found(val records: List<SrvRecord>) : SrvResult

    /**
     * RFC 2782's explicit "service not available at this domain" (a lone root "."
     * target). Distinct from [NoRecords]: the domain has *decided* it offers no
     * such service, so the caller must not fall through to guessing a host.
     */
    object NotAvailable : SrvResult

    /**
     * No SRV data (RCODE 0 empty answer, or NXDOMAIN). The domain simply does not
     * publish the record; the caller falls back to well-known / a known host.
     */
    object NoRecords : SrvResult

    /**
     * The lookup could not be trusted or completed: a server-failure RCODE
     * (SERVFAIL/REFUSED), malformed response bytes, a channel timeout, or an empty
     * body. [reason] is a short diagnostic.
     */
    data class Error(val reason: String) : SrvResult
}
