package org.onekash.kashcal.network.dns

/**
 * Builds the RFC 1035 message frame (header, question, answer RRs with 0xc00c-compressed owner
 * names) that the SRV and TXT parser and resolver tests share. One copy keeps the suites from
 * drifting apart, the same hazard [DnsWire] exists to avoid.
 *
 * The type-specific rdata builders (`srvRr`, `txtRr`) stay in each test class.
 */
internal object DnsWireTestFixtures {

    /** Standard 1-hour TTL for answer RRs (0x0e10 = 3600). */
    val TTL: ByteArray = byteArrayOf(0, 0, 0x0e, 0x10)

    /** A 12-octet response header: QR=1, RD=1, RA=1, the given RCODE, and counts. */
    fun header(rcode: Int, qd: Int, an: Int): ByteArray = byteArrayOf(
        0x12, 0x34,                                   // transaction id
        0x81.toByte(), (0x80 or rcode).toByte(),      // flags: QR=1, RD=1, RA=1, RCODE
    ) + u16(qd) + u16(an) + u16(0) + u16(0)           // QD, AN, NS=0, AR=0

    /** Builds the question `_carddavs._tcp.example.test`, QTYPE [qtype], IN class, at offset 12. */
    fun question(qtype: Int): ByteArray = encodeName("_carddavs._tcp.example.test") + u16(qtype) + u16(1)

    /**
     * Builds an RR of the given TYPE whose owner name points to the question, for a record the
     * parser under test skips.
     */
    fun otherRr(type: Int, rdata: ByteArray): ByteArray =
        byteArrayOf(0xc0.toByte(), 0x0c) + u16(type) + u16(1) + TTL + u16(rdata.size) + rdata

    /** A big-endian unsigned 16-bit value. */
    fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    /** Encodes a dotted name into length-prefixed labels terminated by a zero octet. */
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

    /** Decodes a hex string into its bytes; spaces are ignored, other whitespace is not. */
    fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
