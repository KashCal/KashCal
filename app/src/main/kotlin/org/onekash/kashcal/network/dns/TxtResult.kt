package org.onekash.kashcal.network.dns

/**
 * The outcome of the RFC 6764 §6 step-3 TXT lookup that follows a successful SRV
 * lookup: does the service publish a `path=` key (RFC 6763 §6.4) to use as the DAV
 * *context path* (§4), or not?
 *
 * Only [Path] changes the ladder's behavior — §4: "When present, clients MUST use
 * the 'path' value as the 'context path'." Both [NoPath] and [Error] mean "no
 * usable path", and RFC 6764 §6 step 3's third bullet says a TXT-derived path that
 * cannot be obtained SHOULD fall back to `/.well-known/` — so a consumer treats
 * them the same. They stay distinct only so a resolver failure is loggable apart
 * from a legitimately-absent key.
 */
sealed interface TxtResult {

    /** A `path=` key was present; [value] is its (possibly empty) value, verbatim. */
    data class Path(val value: String) : TxtResult

    /** No `path=` key: no TXT record, an empty one, or a record without the key. */
    object NoPath : TxtResult

    /** The lookup failed (server-failure RCODE, malformed bytes, timeout). [reason] diagnoses it. */
    data class Error(val reason: String) : TxtResult
}
