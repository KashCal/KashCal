package org.onekash.kashcal.network.dns

/**
 * The single platform-dependent seam of DNS discovery: issue one raw DNS query and
 * return the response message bytes. Everything above this line (parse, RFC 2782
 * selection, RFC 6764 §6 fallback policy) is pure and unit-testable; everything
 * below it is the Android system resolver, verified only by integration.
 *
 * A `fun interface` so a test can supply canned bytes or throw a canned failure,
 * and the production adapter ([AndroidRawDnsChannel]) is the one class exercising
 * `android.net.DnsResolver.rawQuery`.
 *
 * @param fqdn the fully-qualified name to look up, e.g. `_carddavs._tcp.example.com`.
 * @param nsType the DNS resource record TYPE to request (33 = SRV, 16 = TXT).
 * @return the raw DNS response message bytes (header + question + answers), exactly
 *   as the resolver returned them, for a [SrvWireParser]/[TxtRecordParser] to decode.
 * @throws Exception on transport failure (timeout, network error, resolver error) —
 *   the caller maps a thrown failure to its typed error result.
 */
fun interface RawDnsChannel {
    suspend fun query(fqdn: String, nsType: Int): ByteArray
}
