package org.onekash.kashcal.network.dns

/**
 * A single DNS SRV record (RFC 2782): the host, port, and selection weights for
 * one service endpoint. Used to discover CalDAV/CardDAV hosts from an email
 * domain (RFC 6764) — e.g. `_carddavs._tcp.icloud.com` resolves to
 * `contacts.icloud.com` on port 443.
 *
 * Pure value type — no Android dependency — so the wire parser and selector are
 * fully unit- and fuzz-testable off-device.
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
