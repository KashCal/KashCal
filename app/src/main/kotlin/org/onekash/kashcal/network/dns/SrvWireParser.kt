package org.onekash.kashcal.network.dns

import org.onekash.kashcal.network.dns.DnsWire.WireFormatException

/**
 * Decodes a DNS SRV response (RFC 1035 message format, RFC 2782 SRV rdata) into
 * a typed [SrvParseResult]. Used to discover CalDAV/CardDAV hosts from an email
 * domain per RFC 6764.
 *
 * The message framing, RCODE handling, bounds checks, and the compression-aware
 * name reader all live in [DnsWire] (shared with [TxtRecordParser]); this parser
 * adds only the SRV-specific rdata shape. Any malformed structure surfaces as
 * [SrvParseResult.Failed] rather than an exception or a partially-built record.
 *
 * Pure JVM logic (no Android APIs) so it is unit- and fuzz-testable off-device.
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
            // The target must be read within THIS RR's rdata window, not merely the
            // buffer — a malformed target that runs past its own RDLENGTH must fail,
            // not read into the following record's bytes. (A compression pointer may
            // still chase backward outside the window; the name reader relaxes the
            // bound once it follows one.)
            val target = DnsWire.readName(buf, rr.rdataStart + 6, rr.rdataStart + rr.rdlength).name
            // RFC 2782: the root target "." is the "service decidedly not offered
            // here" sentinel, never a host — drop it (a hostile server may even mix
            // it with real records) and remember we saw it.
            if (target.isEmpty()) sawRootTarget = true else records.add(SrvRecord(priority, weight, port, target))
        }

        return when {
            records.isNotEmpty() -> SrvParseResult.Records(records)
            sawRootTarget -> SrvParseResult.NotAvailable   // only "." target(s), no real host
            else -> SrvParseResult.NoRecords
        }
    }
}
