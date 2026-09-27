package org.onekash.kashcal.network.dns

/**
 * Resolves a service's TXT `path=` attribute (RFC 6764 §6 step 3) over a [RawDnsChannel].
 *
 * Provider- and protocol-agnostic, like [SrvResolver]: it answers only whether
 * `_[service]._[proto].[domain]` publishes a `path=` context path, and leaves the fallback
 * policy to the caller.
 */
interface TxtResolver {
    /** Looks up `_[service]._[proto].[domain]` TXT and returns its `path=` value, if any. */
    suspend fun resolvePath(service: String, proto: String, domain: String): TxtResult
}
