package org.onekash.kashcal.network.dns

import org.onekash.kashcal.network.dns.DnsWire.WireFormatException

/**
 * Decodes a DNS SRV response (RFC 1035 message, RFC 2782 SRV rdata) into a [SrvParseResult],
 * for finding CalDAV and CardDAV hosts from an email domain (RFC 6764).
 *
 * Framing, RCODE handling, bounds checks and the name reader live in [DnsWire]; this parser
 * adds only the SRV rdata shape. Malformed structure returns [SrvParseResult.Failed], never
 * an exception or a partial record.
 *
 * Pure JVM, with no Android APIs, so it is unit- and fuzz-testable off-device.
 */
object SrvWireParser {

    private const val TYPE_SRV = 33

    // SRV rdata = priority(2) + weight(2) + port(2) + target(>=1 for the root ".").
    private const val MIN_SRV_RDATA = 7

    fun parse(response: ByteArray): SrvParseResult =
        try {
            decode(response)
        } catch (e: WireFormatException) {
            SrvParseResult.Failed(e.reason)
        }

    private fun decode(buf: ByteArray): SrvParseResult {
        val records = ArrayList<SrvRecord>()
        var sawRootTarget = false
        for (rr in DnsWire.answers(buf)) {
            if (rr.type != TYPE_SRV) continue
            if (rr.rdlength < MIN_SRV_RDATA) throw WireFormatException("SRV rdata too short")
            val priority = DnsWire.u16(buf, rr.rdataStart)
            val weight = DnsWire.u16(buf, rr.rdataStart + 2)
            val port = DnsWire.u16(buf, rr.rdataStart + 4)
            // The target must be read within this RR's rdata window, so a target past
            // its RDLENGTH fails instead of reading into the next record. A followed
            // compression pointer may still chase backward outside the window.
            val target = DnsWire.readName(buf, rr.rdataStart + 6, rr.rdataStart + rr.rdlength).name
            // RFC 2782: a root "." target means the service is decidedly not offered here
            // and is never a host. Drop it, even when a hostile server mixes it with real
            // records, and remember it was seen.
            if (target.isEmpty()) sawRootTarget = true else records.add(SrvRecord(priority, weight, port, target))
        }

        return when {
            records.isNotEmpty() -> SrvParseResult.Records(records)
            sawRootTarget -> SrvParseResult.NotAvailable   // only "." target(s), no real host
            else -> SrvParseResult.NoRecords
        }
    }
}
