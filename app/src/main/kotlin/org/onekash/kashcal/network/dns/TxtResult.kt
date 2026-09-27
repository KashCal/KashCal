package org.onekash.kashcal.network.dns

/**
 * Holds the outcome of the TXT lookup after a successful SRV lookup (RFC 6764 §6 step 3):
 * whether the service publishes a `path=` key (RFC 6763 §6.4) to use as the DAV context path
 * (RFC 6764 §4).
 *
 * Only [Path] can change the caller's behavior. RFC 6764 §4: "When present, clients MUST use the
 * 'path' value as the 'context path'." [NoPath] and [Error] both mean no usable path, and §6
 * step 3's second bullet takes the `.well-known` URI when no context path came from a TXT
 * record, so a consumer treats them the same. They stay distinct only so a resolver failure
 * can be logged apart from an absent key.
 */
sealed interface TxtResult {

    /** A `path=` key was present; [value] is its (possibly empty) value, verbatim. */
    data class Path(val value: String) : TxtResult

    /** No `path=` key: no TXT record, an empty one, or a record without the key. */
    object NoPath : TxtResult

    /** Means the lookup failed: a failure RCODE, malformed bytes, or a channel exception. */
    data class Error(val reason: String) : TxtResult
}
