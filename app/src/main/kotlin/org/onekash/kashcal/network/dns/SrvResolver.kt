package org.onekash.kashcal.network.dns

/**
 * Resolves an SRV service to its ordered endpoints, over a [RawDnsChannel].
 *
 * The interface is provider- and CalDAV-agnostic on purpose: it takes a service
 * label / protocol / domain and returns a typed [SrvResult]. The RFC 6764 fallback
 * *policy* (which service, what to do with NoRecords, the well-known/known-host
 * ladder) lives in an app-layer resolver above this, never here — keeping this
 * layer pure and reusable for any SRV lookup.
 */
interface SrvResolver {
    /**
     * Look up `_[service]._[proto].[domain]` (e.g. `_carddavs._tcp.example.com`)
     * and return its endpoints ordered for connection attempts.
     */
    suspend fun resolve(service: String, proto: String, domain: String): SrvResult
}
