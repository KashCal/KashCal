package org.onekash.kashcal.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.TTL
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.encodeName
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.header
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.hex
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.otherRr
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.question
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.u16

/**
 * Adversarial + real-fixture tests for the DNS SRV response wire decoder.
 *
 * The parser consumes raw bytes returned by the system resolver for an
 * `_carddavs._tcp.<domain>` / `_caldavs._tcp.<domain>` SRV query (RFC 6764).
 * Those bytes are fully attacker-influenced (a malicious or broken nameserver),
 * so the decoder must never throw, never loop, and never emit a half-built
 * record — it returns a typed [SrvParseResult] for every input.
 *
 * Positive fixtures are the exact wire bytes captured from live queries to
 * three real providers; the Zoho fixture is a genuine NXDOMAIN response.
 */
class SrvWireParserTest {

    // ---- real captured fixtures (hex of the exact UDP response bytes) --------

    /** iCloud: one SRV, priority 0 / weight 0 / port 443, target contacts.icloud.com.
     *  The answer RR NAME is a 0xc00c compression pointer back to the question. */
    private val ICLOUD = hex(
        "123481800001000100000000" +                     // header: RCODE 0, QD 1, AN 1
        "095f6361726464617673045f7463700669636c6f756403636f6d0000210001" + // question
        "c00c0021000100000e10001b0000000001bb08636f6e74616374730669636c6f756403636f6d00"
    )

    /** Fastmail: priority 0 / weight 1 / port 443, target d277161.carddav.fastmail.com. */
    private val FASTMAIL = hex(
        "123481800001000100000000" +
        "095f6361726464617673045f74637008666173746d61696c03636f6d0000210001" +
        "c00c0021000100000e1000240000000101bb0764323737313631076361726464617608666173746d61696c03636f6d00"
    )

    /** mailbox.org: priority 10 / weight 1 / port 443, target dav.mailbox.org. */
    private val MAILBOX = hex(
        "123481800001000100000000" +
        "095f6361726464617673045f746370076d61696c626f78036f72670000210001" +
        "c00c0021000100002a300017000a000101bb03646176076d61696c626f78036f726700"
    )

    /** Zoho: NXDOMAIN (RCODE 3), zero answers, an SOA in the authority section. */
    private val ZOHO = hex(
        "123481830001000000010000" +                     // header: RCODE 3, QD 1, AN 0, NS 1
        "095f6361726464617673045f746370047a6f686f03636f6d0000210001" +
        "c01b000600010000012c0035036e7331087a6f686f636f7270c02008646e7361646d696e0676746974616e" +
        "c02078c388f700001c20000007080012750000000e10"
    )

    // ---- real fixtures parse to the expected records -------------------------

    @Test
    fun `iCloud fixture parses to contacts icloud com on 443`() {
        val result = SrvWireParser.parse(ICLOUD)
        assertTrue("expected Records, got $result", result is SrvParseResult.Records)
        val recs = (result as SrvParseResult.Records).records
        assertEquals(listOf(SrvRecord(0, 0, 443, "contacts.icloud.com")), recs)
    }

    @Test
    fun `Fastmail fixture parses to sharded carddav host on 443`() {
        val result = SrvWireParser.parse(FASTMAIL)
        assertTrue(result is SrvParseResult.Records)
        assertEquals(
            listOf(SrvRecord(0, 1, 443, "d277161.carddav.fastmail.com")),
            (result as SrvParseResult.Records).records
        )
    }

    @Test
    fun `mailbox fixture parses with priority 10`() {
        val result = SrvWireParser.parse(MAILBOX)
        assertTrue(result is SrvParseResult.Records)
        assertEquals(
            listOf(SrvRecord(10, 1, 443, "dav.mailbox.org")),
            (result as SrvParseResult.Records).records
        )
    }

    @Test
    fun `Zoho NXDOMAIN fixture is NoRecords`() {
        assertEquals(SrvParseResult.NoRecords, SrvWireParser.parse(ZOHO))
    }

    // ---- empty / truncated inputs -------------------------------------------

    @Test
    fun `empty buffer is Failed`() {
        assertTrue(SrvWireParser.parse(ByteArray(0)) is SrvParseResult.Failed)
    }

    @Test
    fun `sub-header buffer is Failed`() {
        assertTrue(SrvWireParser.parse(ByteArray(11)) is SrvParseResult.Failed)
    }

    @Test
    fun `header-only RCODE 0 zero answers is NoRecords`() {
        // 12-byte header, RCODE 0, QD 0, AN 0 — a well-formed empty answer.
        assertEquals(SrvParseResult.NoRecords, SrvWireParser.parse(header(rcode = 0, qd = 0, an = 0)))
    }

    // ---- RCODE fan-out -------------------------------------------------------

    @Test
    fun `NXDOMAIN RCODE 3 is NoRecords`() {
        assertEquals(SrvParseResult.NoRecords, SrvWireParser.parse(header(rcode = 3, qd = 0, an = 0)))
    }

    @Test
    fun `NXDOMAIN with a lying nonzero ANCOUNT still yields NoRecords`() {
        // The name-error RCODE is authoritative: a hostile server that sets RCODE 3
        // but ANCOUNT 1 with no RR bytes following must NOT make the parser walk
        // past the question into garbage — the RCODE wins and the count is ignored.
        val pkt = header(rcode = 3, qd = 1, an = 1) + QUESTION
        assertEquals(SrvParseResult.NoRecords, SrvWireParser.parse(pkt))
    }

    @Test
    fun `NXDOMAIN with a malformed question is still NoRecords`() {
        // RCODE 3 means the name does not exist; that verdict is authoritative and
        // the body is not trusted or parsed. A garbled/truncated question on such a
        // response must not flip "the domain has no SRV" into a parse failure — the
        // parser must short-circuit on the RCODE before touching the question bytes.
        val pkt = header(rcode = 3, qd = 1, an = 0) + byteArrayOf(40, 0x61, 0x62, 0x63)
        assertEquals(SrvParseResult.NoRecords, SrvWireParser.parse(pkt))
    }

    @Test
    fun `SERVFAIL RCODE 2 is Failed`() {
        assertTrue(SrvWireParser.parse(header(rcode = 2, qd = 0, an = 0)) is SrvParseResult.Failed)
    }

    @Test
    fun `REFUSED RCODE 5 is Failed`() {
        assertTrue(SrvWireParser.parse(header(rcode = 5, qd = 0, an = 0)) is SrvParseResult.Failed)
    }

    // ---- count-lies (QDCOUNT / ANCOUNT beyond the bytes present) -------------

    @Test
    fun `ANCOUNT lying high past buffer is Failed`() {
        // Header claims 3 answers; only one SRV RR follows, then the buffer ends.
        val pkt = header(rcode = 0, qd = 1, an = 3) + QUESTION +
            srvRr(0, 0, 443, encodeName("a.example.test"))
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `QDCOUNT lying high past buffer is Failed`() {
        // Claims 2 questions but only one is present and nothing follows.
        val pkt = header(rcode = 0, qd = 2, an = 0) + QUESTION
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    // ---- malformed question names (attack class: unterminated / compressed) --

    @Test
    fun `question QNAME as self-pointer is Failed`() {
        // QNAME at offset 12 is a pointer to offset 12 (itself) — not strictly
        // backward, so the name reader must reject it rather than loop.
        val pkt = header(rcode = 0, qd = 1, an = 0) + byteArrayOf(0xc0.toByte(), 12) + u16(33) + u16(1)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `question QNAME running past buffer is Failed`() {
        // A label claims 40 bytes but the buffer holds only a few.
        val pkt = header(rcode = 0, qd = 1, an = 0) + byteArrayOf(40, 0x61, 0x62, 0x63)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    // ---- compression-pointer pathology in the SRV target ---------------------

    @Test
    fun `target self-pointer is Failed`() {
        val prefix = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(2 + 2 + 2 + 2) +
            u16(0) + u16(0) + u16(443)                      // priority, weight, port
        val targetOffset = prefix.size
        val pkt = prefix + byteArrayOf(0xc0.toByte(), targetOffset.toByte()) // points to itself
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `target forward-pointer is Failed`() {
        val prefix = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(2 + 2 + 2 + 2) +
            u16(0) + u16(0) + u16(443)
        val targetOffset = prefix.size
        // Points to targetOffset+4, strictly after itself — forward pointers rejected.
        val pkt = prefix + byteArrayOf(0xc0.toByte(), (targetOffset + 4).toByte()) + byteArrayOf(0, 0, 0, 0)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `target label followed by a backward pointer to itself is Failed not a hang`() {
        // The strictly-backward pointer rule does NOT forbid every cycle: a label
        // advances pos, so a following pointer can legally aim back at that label's
        // own offset and oscillate (label -> back-pointer -> same label -> ...).
        // Termination is guaranteed only by the MAX_NAME cap, not the backward rule.
        // The target here is a 1-octet label "a" immediately followed by a pointer
        // to that label's offset; the walk must bail with "name too long", not loop.
        val prefix = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(2 + 2 + 2 + 4) +
            u16(0) + u16(0) + u16(443)                      // priority, weight, port
        val labelOffset = prefix.size                       // where the "a" label lands
        val target = byteArrayOf(1, 0x61) + byteArrayOf(0xc0.toByte(), labelOffset.toByte())
        val result = SrvWireParser.parse(prefix + target)
        assertTrue("expected Failed, got $result", result is SrvParseResult.Failed)
        assertEquals("name too long", (result as SrvParseResult.Failed).reason)
    }

    @Test
    fun `target with a valid backward pointer parses`() {
        // Target = literal "sub" + pointer to the qname at offset 12, assembling
        // sub._carddavs._tcp.example.test.
        val target = byteArrayOf(3, 0x73, 0x75, 0x62) + byteArrayOf(0xc0.toByte(), 0x0c)
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 443, target)
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Records, got $result", result is SrvParseResult.Records)
        assertEquals(
            "sub._carddavs._tcp.example.test",
            (result as SrvParseResult.Records).records.single().target
        )
    }

    @Test
    fun `pointer past end of buffer is Failed`() {
        val target = byteArrayOf(0xc0.toByte(), 0xff.toByte()) // offset 255, past buffer
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 443, target)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    // ---- label-type / cap enforcement ----------------------------------------

    @Test
    fun `reserved label bits 0x40 is Failed`() {
        // 0x40 = 0b01000000: reserved label type (also >63 as a length) — reject.
        val target = byteArrayOf(0x40, 0x61, 0x62)
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 443, target)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `reserved label bits 0x80 is Failed`() {
        val target = byteArrayOf(0x80.toByte(), 0x61, 0x62)
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 443, target)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `assembled name over 255 octets is Failed`() {
        // Four maximal 63-octet labels (4 * 64 + 1 = 257 wire octets) overflow 255.
        val maxLabel = byteArrayOf(63) + ByteArray(63) { 0x61 }
        val target = maxLabel + maxLabel + maxLabel + maxLabel + byteArrayOf(0)
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 443, target)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    // ---- rdata / rdlength boundaries -----------------------------------------

    @Test
    fun `rdlength zero on a claimed SRV RR is Failed`() {
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(0) // rdlength 0
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `rdlength pointing past buffer is Failed`() {
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(100) +
            u16(0) + u16(0) + u16(443) + byteArrayOf(0)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `all-0xFF rdata is Failed`() {
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(8) + ByteArray(8) { 0xff.toByte() }
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `SRV target whose label runs past its own RDLENGTH is Failed`() {
        // The target name must be read WITHIN the RR's declared rdata window, not
        // merely within the whole buffer. Here RDLENGTH claims 8 (priority+weight+
        // port + a 2-octet target), but the target's first label declares 5 content
        // octets that spill past the window into bytes the record does not own.
        // Reading them anyway would fabricate a target ("abcde") from a malformed
        // record instead of rejecting it.
        val targetBytes = byteArrayOf(5, 0x61, 0x62, 0x63, 0x64, 0x65, 0)  // "abcde" + terminator (7 octets)
        val rdata = u16(0) + u16(0) + u16(443) + targetBytes               // 13 octets present
        val declaredRdlength = 8                                           // but only 8 declared
        val rr = byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(declaredRdlength) + rdata
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + rr
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Failed, got $result", result is SrvParseResult.Failed)
    }

    @Test
    fun `SRV target ending exactly at its RDLENGTH boundary parses`() {
        // The window is inclusive: a target that terminates precisely at the last
        // declared rdata octet is valid and must not be rejected by the bound.
        val targetBytes = encodeName("d.test")                             // 8 octets incl. terminator
        val rdata = u16(0) + u16(0) + u16(443) + targetBytes
        val rr = byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(rdata.size) + rdata
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + rr
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Records, got $result", result is SrvParseResult.Records)
        assertEquals("d.test", (result as SrvParseResult.Records).records.single().target)
    }

    // ---- root target -> NotAvailable ----------------------------------------

    @Test
    fun `single root target is NotAvailable`() {
        // RFC 2782: a lone SRV with target "." means the service is decidedly
        // not offered here — distinct from having no records at all.
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 0, byteArrayOf(0))
        assertEquals(SrvParseResult.NotAvailable, SrvWireParser.parse(pkt))
    }

    @Test
    fun `root target mixed with a real record drops the root and keeps the real one`() {
        // A conformant server never mixes the "." not-available sentinel with real
        // SRV records, but a hostile one can. The root-target record must be dropped
        // (it carries an empty target that must never reach host selection as a host
        // to probe), leaving only the genuine record.
        val pkt = header(rcode = 0, qd = 1, an = 2) + QUESTION +
            srvRr(0, 0, 0, byteArrayOf(0)) +                       // "." — not-available sentinel
            srvRr(10, 5, 443, encodeName("real.example.test"))
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Records, got $result", result is SrvParseResult.Records)
        assertEquals(
            listOf(SrvRecord(10, 5, 443, "real.example.test")),
            (result as SrvParseResult.Records).records,
        )
    }

    @Test
    fun `two root targets and nothing else is NotAvailable`() {
        // More than one "." target, no real record: still the not-available signal,
        // not a two-element Records list of empty-target junk.
        val pkt = header(rcode = 0, qd = 1, an = 2) + QUESTION +
            srvRr(0, 0, 0, byteArrayOf(0)) +
            srvRr(0, 0, 0, byteArrayOf(0))
        assertEquals(SrvParseResult.NotAvailable, SrvWireParser.parse(pkt))
    }

    // ---- non-SRV RRs interleaved --------------------------------------------

    @Test
    fun `A and TXT answers before an SRV are skipped by rdlength`() {
        val a = otherRr(type = 1, rdata = byteArrayOf(1, 2, 3, 4))                 // A
        val txt = otherRr(type = 16, rdata = byteArrayOf(3, 0x61, 0x62, 0x63))     // TXT "abc"
        val srv = srvRr(0, 0, 443, encodeName("dav.example.test"))
        val pkt = header(rcode = 0, qd = 1, an = 3) + QUESTION + a + txt + srv
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Records, got $result", result is SrvParseResult.Records)
        assertEquals(
            listOf(SrvRecord(0, 0, 443, "dav.example.test")),
            (result as SrvParseResult.Records).records
        )
    }

    // ---- multiple SRV records preserved (as-wire order) ----------------------

    @Test
    fun `two SRV answers both parsed`() {
        val pkt = header(rcode = 0, qd = 1, an = 2) + QUESTION +
            srvRr(10, 5, 443, encodeName("a.example.test")) +
            srvRr(20, 0, 8443, encodeName("b.example.test"))
        val result = SrvWireParser.parse(pkt)
        assertTrue(result is SrvParseResult.Records)
        assertEquals(
            listOf(
                SrvRecord(10, 5, 443, "a.example.test"),
                SrvRecord(20, 0, 8443, "b.example.test"),
            ),
            (result as SrvParseResult.Records).records
        )
    }

    // ---- Failed carries the specific diagnostic, not a generic fallback ------

    @Test
    fun `SERVFAIL Failed reason names the RCODE`() {
        // The elvis fallback in parse() must not swallow the real cause: a server
        // failure surfaces its RCODE so the resolver layer can log/branch on it.
        val result = SrvWireParser.parse(header(rcode = 2, qd = 0, an = 0))
        assertTrue(result is SrvParseResult.Failed)
        assertEquals("RCODE=2", (result as SrvParseResult.Failed).reason)
    }

    // ---- RR fixed-header length boundary (exactly 10 octets) -----------------

    @Test
    fun `RR header truncated below 10 octets is Failed`() {
        // Owner NAME (a 0xc00c pointer) then only 5 of the 10 fixed header octets
        // (TYPE/CLASS/TTL/RDLENGTH) — one octet short must fail, not read past.
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + byteArrayOf(0, 33, 0, 1, 0)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `non-SRV RR whose 10-octet header ends exactly at the buffer is NoRecords`() {
        // A (type 1) RR with RDLENGTH 0 filling the buffer to the last octet:
        // pos+10 == buf.size is valid, so the answer is a well-formed empty set.
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(1) + u16(1) + TTL + u16(0)
        assertEquals(SrvParseResult.NoRecords, SrvWireParser.parse(pkt))
    }

    // ---- question QTYPE/QCLASS advance boundary (exactly 4 octets) -----------

    @Test
    fun `question truncated before its 4 QTYPE and QCLASS octets is Failed`() {
        // QNAME then only 2 of the 4 trailing octets.
        val pkt = header(rcode = 0, qd = 1, an = 0) + encodeName("x.test") + byteArrayOf(0, 33)
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    @Test
    fun `question whose 4 QTYPE and QCLASS octets end exactly at the buffer is NoRecords`() {
        // QNAME + exactly QTYPE(2) + QCLASS(2), zero answers: pos+4 == buf.size is valid.
        val pkt = header(rcode = 0, qd = 1, an = 0) + encodeName("x.test") + u16(33) + u16(1)
        assertEquals(SrvParseResult.NoRecords, SrvWireParser.parse(pkt))
    }

    // ---- label-length vs name-length boundaries ------------------------------

    @Test
    fun `label whose octets reach exactly the buffer end fails as an unterminated name`() {
        // Label content ends flush with the buffer (labelStart+len == buf.size is
        // in-bounds), so the failure is the *missing terminator*, not the label
        // overrunning — the distinct reason pins the boundary direction.
        val pkt = header(rcode = 0, qd = 1, an = 0) + byteArrayOf(3, 0x61, 0x62, 0x63)
        val result = SrvWireParser.parse(pkt)
        assertTrue(result is SrvParseResult.Failed)
        assertEquals("name past buffer", (result as SrvParseResult.Failed).reason)
    }

    @Test
    fun `name of exactly 255 wire octets including the terminator parses`() {
        // RFC 1035 §3.1: the total encoded name length — every label length octet,
        // all label content, AND the terminating zero — is capped at 255. Three
        // 64-octet labels (63 content each) + one 62-octet label (61 content) + the
        // 1-octet terminator = 64*3 + 62 + 1 = 255 must assemble at the boundary.
        val label63 = byteArrayOf(63) + ByteArray(63) { 0x61 }   // 64 wire octets
        val label61 = byteArrayOf(61) + ByteArray(61) { 0x61 }   // 62 wire octets
        val target = label63 + label63 + label63 + label61 + byteArrayOf(0)  // 255 total
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 443, target)
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Records, got $result", result is SrvParseResult.Records)
        assertEquals(1, (result as SrvParseResult.Records).records.size)
    }

    @Test
    fun `name of 256 wire octets including the terminator is Failed`() {
        // One octet over the RFC 1035 §3.1 cap: 64*3 + 63 + 1 = 256. The terminating
        // zero counts toward the total, so this trips "name too long" — pinning the
        // boundary at 255 inclusive of the terminator rather than one octet looser.
        val label63 = byteArrayOf(63) + ByteArray(63) { 0x61 }   // 64 wire octets
        val label62 = byteArrayOf(62) + ByteArray(62) { 0x61 }   // 63 wire octets
        val target = label63 + label63 + label63 + label62 + byteArrayOf(0)  // 256 total
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 443, target)
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Failed, got $result", result is SrvParseResult.Failed)
        assertEquals("name too long", (result as SrvParseResult.Failed).reason)
    }

    // ---- compression pointer needs both octets present -----------------------

    @Test
    fun `compression pointer missing its second octet at buffer end is Failed`() {
        // rdata = priority+weight+port then a lone 0xc0 as the final octet: the
        // pointer's low byte is off the end, so reading it must fail cleanly.
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(7) +
            u16(0) + u16(0) + u16(443) + byteArrayOf(0xc0.toByte())
        assertTrue(SrvWireParser.parse(pkt) is SrvParseResult.Failed)
    }

    // ---- compression pointer with a high-byte offset (> 255) -----------------

    @Test
    fun `backward pointer to an offset above 255 resolves via the high byte`() {
        // The target name is planted at absolute offset 256, so the pointer's high
        // 6 bits are non-zero (0xC1 0x00). If the high byte were shifted the wrong
        // way the offset collapses to 0 and the name assembles from the header
        // instead — so asserting the exact target pins the shift direction.
        val header = header(rcode = 0, qd = 1, an = 2)          // padding RR + SRV
        val plantedName = encodeName("shifted.example.test")
        // Bytes before the planted name: 12 header + 33 question + 12 padding-RR
        // fixed header = 57. Pad the padding RR's rdata so the name lands at 256.
        val padBeforeName = 256 - (header.size + QUESTION.size + 12)
        val padRdata = ByteArray(padBeforeName) { 0x2a } + plantedName
        val paddingRr = byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL +
            u16(padRdata.size) + padRdata                       // TXT (type 16), skipped
        val srvTarget = byteArrayOf(0xc1.toByte(), 0x00)        // -> absolute offset 256
        val srv = srvRr(0, 0, 443, srvTarget)
        val pkt = header + QUESTION + paddingRr + srv
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Records, got $result", result is SrvParseResult.Records)
        assertEquals(
            "shifted.example.test",
            (result as SrvParseResult.Records).records.single().target,
        )
    }

    // ---- resume position is fixed at the FIRST pointer of a chained name -----

    @Test
    fun `owner name that follows two chained pointers resumes after the first`() {
        // RFC 1035: once a name is redirected by a compression pointer, the field
        // that follows in the RR stream begins right after THAT first pointer —
        // even if the target itself ends in a further pointer. Here an RR's owner
        // NAME is pointer1 -> ("z" + pointer2 -> root terminator). Resume must be
        // pinned to just past pointer1; if it were re-pinned at pointer2 the next
        // RR (the SRV answer) is read from the wrong offset and lost.
        val header = header(rcode = 0, qd = 1, an = 3)

        // A TXT padding RR whose rdata holds the chain's middle hop:
        // label "z" + pointer2 back to the question's root terminator.
        val rootTerminatorOffset = header.size + encodeName("_carddavs._tcp.example.test").size - 1
        val midHopBytes = byteArrayOf(1, 0x7a) +                                   // label "z"
            byteArrayOf(0xc0.toByte(), rootTerminatorOffset.toByte())             // pointer2 -> root
        val paddingRr = byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL +
            u16(midHopBytes.size) + midHopBytes                                    // TXT, skipped
        // Absolute offset of the mid-hop (rdata start of the padding RR).
        val midHopOffset = header.size + QUESTION.size + paddingRr.size - midHopBytes.size

        // Second answer: an A RR whose owner NAME is pointer1 -> the mid-hop.
        val chainedOwner = byteArrayOf(0xc0.toByte(), midHopOffset.toByte())
        val aRr = chainedOwner + u16(1) + u16(1) + TTL + u16(4) + byteArrayOf(1, 2, 3, 4)

        val srv = srvRr(0, 0, 443, encodeName("dav.example.test"))
        val pkt = header + QUESTION + paddingRr + aRr + srv
        val result = SrvWireParser.parse(pkt)
        assertTrue("expected Records, got $result", result is SrvParseResult.Records)
        assertEquals(
            listOf(SrvRecord(0, 0, 443, "dav.example.test")),
            (result as SrvParseResult.Records).records,
        )
    }

    // ---- helpers -------------------------------------------------------------

    /** The question section for `_carddavs._tcp.example.test`, IN SRV. Starts at offset 12. */
    private val QUESTION = question(qtype = 33)

    /** An SRV answer RR whose NAME is a 0xc00c pointer to the question. */
    private fun srvRr(priority: Int, weight: Int, port: Int, target: ByteArray): ByteArray {
        val rdata = u16(priority) + u16(weight) + u16(port) + target
        return byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(rdata.size) + rdata
    }
}
