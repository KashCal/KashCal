package org.onekash.kashcal.network.dns

/**
 * Holds one DNS SRV record (RFC 2782): the host, port and selection weights of one service
 * endpoint, used to find CalDAV and CardDAV hosts from an email domain (RFC 6764). For
 * example `_carddavs._tcp.icloud.com` resolves to `contacts.icloud.com` on port 443.
 *
 * No Android dependency, so the wire parser and selector are unit- and fuzz-testable
 * off-device.
 *
 * @property priority lower is preferred; endpoints are tried in ascending priority.
 * @property weight relative selection weight among records of equal priority.
 * @property port the TCP port the service listens on (often 443, but honor it).
 * @property target the canonical hostname of the service (no trailing dot).
 */
data class SrvRecord(
    val priority: Int,
    val weight: Int,
    val port: Int,
    val target: String,
)
