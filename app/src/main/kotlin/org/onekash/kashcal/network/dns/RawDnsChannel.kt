package org.onekash.kashcal.network.dns

/**
 * Issues one raw DNS query and returns the response message bytes; the only
 * platform-dependent seam of DNS discovery. Everything above it (parsing, RFC 2782 selection,
 * RFC 6764 §6 fallback policy) is pure and unit-testable; below it is the Android system
 * resolver, verified only by integration.
 *
 * A `fun interface` so a test can supply canned bytes or throw a canned failure;
 * [AndroidRawDnsChannel] is the production adapter over `android.net.DnsResolver.rawQuery`.
 *
 * @param fqdn the fully qualified name to look up, e.g. `_carddavs._tcp.example.com`.
 * @param nsType the resource record TYPE to request (33 = SRV, 16 = TXT).
 * @return the response message bytes (header, question, answers) as the resolver returned
 *   them, for [SrvWireParser] or [TxtRecordParser] to decode.
 * @throws Exception on a transport failure (timeout, network or resolver error); the caller
 *   maps it to its typed error result.
 */
fun interface RawDnsChannel {
    suspend fun query(fqdn: String, nsType: Int): ByteArray
}
