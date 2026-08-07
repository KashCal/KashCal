package org.onekash.kashcal.network.dns

/**
 * Shared wire-builder helpers for the DNS parser tests. The SRV and TXT decoders
 * both read the same RFC 1035 message frame (header + question + answer RRs with
 * 0xc00c-compressed owner names), so their tests assemble identical byte
 * scaffolding — keeping one copy here prevents the two suites from drifting apart
 * on the exact hazard [DnsWire] itself exists to avoid.
 *
 * Only the record-type-specific rdata builders (`srvRr`, `txtRr`) stay in the
 * per-parser test classes; everything frame-level lives here.
 */
internal object DnsWireTestFixtures {

    /** Standard 1-hour TTL for answer RRs (0x0e10 = 3600). */
    val TTL: ByteArray = byteArrayOf(0, 0, 0x0e, 0x10)

    /** A 12-octet response header: QR=1, RD=1, RA=1, the given RCODE, and counts. */
    fun header(rcode: Int, qd: Int, an: Int): ByteArray = byteArrayOf(
        0x12, 0x34,                                   // transaction id
        0x81.toByte(), (0x80 or rcode).toByte(),      // flags: QR=1, RD=1, RA=1, RCODE
    ) + u16(qd) + u16(an) + u16(0) + u16(0)           // QD, AN, NS=0, AR=0

    /** The question `_carddavs._tcp.example.test` with the given QTYPE, IN class. Starts at offset 12. */
    fun question(qtype: Int): ByteArray = encodeName("_carddavs._tcp.example.test") + u16(qtype) + u16(1)

    /** A non-answer-of-interest RR (NAME pointer to the question) of the given TYPE. */
    fun otherRr(type: Int, rdata: ByteArray): ByteArray =
        byteArrayOf(0xc0.toByte(), 0x0c) + u16(type) + u16(1) + TTL + u16(rdata.size) + rdata

    /** A big-endian unsigned 16-bit value. */
    fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    /** Encode a dotted name into length-prefixed labels terminated by a zero octet. */
    fun encodeName(name: String): ByteArray {
        val out = ArrayList<Byte>()
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            out.add(label.length.toByte())
            for (c in label.toByteArray(Charsets.US_ASCII)) out.add(c)
        }
        out.add(0)
        return out.toByteArray()
    }

    /** Decode a whitespace-tolerant hex string into its raw bytes. */
    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
