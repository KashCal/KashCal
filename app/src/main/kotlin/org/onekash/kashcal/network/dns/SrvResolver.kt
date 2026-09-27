package org.onekash.kashcal.network.dns

/**
 * Resolves an SRV service to its ordered endpoints over a [RawDnsChannel].
 *
 * Deliberately provider- and CalDAV-agnostic: it takes a service label, protocol and domain
 * and returns a typed [SrvResult]. The RFC 6764 fallback policy (which service, what to do
 * with NoRecords, the well-known and known-host ladder) belongs in an app-layer resolver
 * above this, such as [CardDavHostResolver][org.onekash.kashcal.sync.carddav.CardDavHostResolver],
 * never here.
 */
interface SrvResolver {
    /**
     * Looks up `_[service]._[proto].[domain]` (e.g. `_carddavs._tcp.example.com`) and
     * returns its endpoints ordered for connection attempts.
     */
    suspend fun resolve(service: String, proto: String, domain: String): SrvResult
}
