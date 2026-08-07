package org.onekash.kashcal.network.dns

/**
 * Resolves the RFC 6764 §6 step-3 TXT `path=` attribute for a service, over a
 * [RawDnsChannel]. Provider- and CalDAV-agnostic, like [SrvResolver]: it answers
 * only "does `_[service]._[proto].[domain]` publish a `path=` context path?" and
 * leaves the fallback policy to the app layer.
 */
interface TxtResolver {
    /** Look up `_[service]._[proto].[domain]` TXT and extract its `path=` value, if any. */
    suspend fun resolvePath(service: String, proto: String, domain: String): TxtResult
}
