package org.onekash.kashcal.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Adversarial + real-fixture tests for the DNS TXT response wire decoder and the
 * RFC 6763 §6.4 `path` attribute extractor.
 *
 * After a successful SRV lookup, RFC 6764 §6 step 3 requires the client to also
 * query the same name for a TXT record and honour a `path=` key as the context
 * path (§4). The bytes come from an untrusted resolver, so — like the SRV
 * decoder — the parser must never throw, never loop, and never emit a half-built
 * result: it returns a typed [TxtParseResult] for every input.
 *
 * The positive fixture is the exact wire bytes captured from a live query to a
 * real provider that publishes `_carddavs._tcp ... TXT "path=/.well-known/carddav"`.
 */
class TxtRecordParserTest {

    // ---- real captured fixture ----------------------------------------------

    /** A real `_carddavs._tcp` TXT answer: one string, `path=/.well-known/carddav`.
     *  The answer RR NAME is a 0xc00c compression pointer back to the question. */
    private val REAL_PATH_TXT = hex(
        "123481800001000100000000" +                             // header: RCODE 0, QD 1, AN 1
        "095f6361726464617673045f74637006676f6f676c6503636f6d0000100001" + // question, QTYPE 16
        "c00c0010000100005460001a" +                             // answer hdr: TXT, rdlength 26
        "19" +                                                   // character-string length 25
        "706174683d2f2e77656c6c2d6b6e6f776e2f63617264646176"     // "path=/.well-known/carddav"
    )

    @Test
    fun `real fixture parses to the single path string`() {
        val result = TxtRecordParser.parse(REAL_PATH_TXT)
        assertTrue("expected Records, got $result", result is TxtParseResult.Records)
        assertEquals(listOf("path=/.well-known/carddav"), (result as TxtParseResult.Records).strings)
    }

    @Test
    fun `real fixture yields the path value`() {
        val result = TxtRecordParser.parse(REAL_PATH_TXT) as TxtParseResult.Records
        assertEquals("/.well-known/carddav", TxtRecordParser.pathValue(result.strings))
    }

    // ---- more real captured fixtures (train half: full wire → parse) --------
    //
    // Exact UDP response bytes from live `_carddavs._tcp` / `_caldavs._tcp` TXT
    // queries against real providers. These exercise the full decode path on
    // genuine wire shapes; the validation half below feeds their decoded strings
    // straight into pathValue() to lock the §6.4 extraction against real values.

    /** gmx.net _caldavs: one string, `path=/begenda/dav/users/`. */
    private val GMX = hex(
        "123481800001000100000000085f63616c64617673045f74637003676d78036e6574" +
        "0000100001c00c0010000100005460001918706174683d2f626567656e64612f6461762f75736572732f"
    )

    /** posteo.de _carddavs: shortest real path, `path=/`. */
    private val POSTEO = hex(
        "123481800001000100000000095f6361726464617673045f74637006706f7374656f0264650000100001" +
        "c00c001000010000012c000706706174683d2f"
    )

    /** freenet.de _carddavs: `path=/carddav`. */
    private val FREENET = hex(
        "123481800001000100000000095f6361726464617673045f74637007667265656e65740264650000100001" +
        "c00c0010000100005460000e0d706174683d2f63617264646176"
    )

    /** hey.com _carddavs: a bare boolean attribute (no '='), no path key. */
    private val HEY = hex(
        "123481800001000100000000095f6361726464617673045f7463700368657903636f6d0000100001" +
        "c00c001000010000003c0021205f6438707775366871356d36797067636a336d6f6f7074687377643775623669"
    )

    /** yandex.com _carddavs: the answer is a CNAME (type 5), not TXT. */
    private val YANDEX_CNAME = hex(
        "123481800001000100010000095f6361726464617673045f7463700679616e64657803636f6d0000100001" +
        "c00c0005000100000258000f03616e790679616e64657802727500" +
        "c03b00060001000003840031036e7331c03b0873797361646d696e0b79616e6465782d7465616dc042" +
        "789520ac000002580000012c00278d0000000384"
    )

    @Test
    fun `gmx real fixture parses to its begenda path string`() {
        val r = TxtRecordParser.parse(GMX)
        assertTrue("expected Records, got $r", r is TxtParseResult.Records)
        assertEquals(listOf("path=/begenda/dav/users/"), (r as TxtParseResult.Records).strings)
    }

    @Test
    fun `posteo real fixture parses to the root path string`() {
        val r = TxtRecordParser.parse(POSTEO) as TxtParseResult.Records
        assertEquals(listOf("path=/"), r.strings)
    }

    @Test
    fun `freenet real fixture parses to its carddav path string`() {
        val r = TxtRecordParser.parse(FREENET) as TxtParseResult.Records
        assertEquals(listOf("path=/carddav"), r.strings)
    }

    @Test
    fun `hey com real fixture is a bare boolean attribute with no path`() {
        val r = TxtRecordParser.parse(HEY)
        assertTrue("expected Records, got $r", r is TxtParseResult.Records)
        val strings = (r as TxtParseResult.Records).strings
        assertEquals(listOf("_d8pwu6hq5m6ypgcj3moopthswd7ub6i"), strings)
        assertNull("a boolean token is not a path key", TxtRecordParser.pathValue(strings))
    }

    @Test
    fun `yandex real fixture whose only answer is a CNAME is NoRecords`() {
        // The provider aliases the service name; there is no TXT RR to honour, so
        // the CNAME (type 5) is skipped by rdlength and the result is NoRecords.
        assertEquals(TxtParseResult.NoRecords, TxtRecordParser.parse(YANDEX_CNAME))
    }

    // ---- validation half: decoded real path values → §6.4 extraction --------
    //
    // These lock pathValue() against the exact strings other real providers
    // publish today (captured live, held back from the parse fixtures above), so
    // a regression in the key/value split shows up against real-world data.

    @Test
    fun `real provider path values extract correctly`() {
        // web.de / 1und1.de (shared platform) publish the begenda user path.
        assertEquals("/begenda/dav/users/", TxtRecordParser.pathValue(listOf("path=/begenda/dav/users/")))
        // rambler.ru _caldavs.
        assertEquals("/caldav", TxtRecordParser.pathValue(listOf("path=/caldav")))
        // bell.net publishes distinct card/cal paths.
        assertEquals("/carddav", TxtRecordParser.pathValue(listOf("path=/carddav")))
        assertEquals("/calendars", TxtRecordParser.pathValue(listOf("path=/calendars")))
        // Google's well-known-style path.
        assertEquals("/.well-known/carddav", TxtRecordParser.pathValue(listOf("path=/.well-known/carddav")))
    }

    // ---- RCODE / empty fan-out ----------------------------------------------

    @Test
    fun `empty buffer is Failed`() {
        assertTrue(TxtRecordParser.parse(ByteArray(0)) is TxtParseResult.Failed)
    }

    @Test
    fun `sub-header buffer is Failed`() {
        assertTrue(TxtRecordParser.parse(ByteArray(11)) is TxtParseResult.Failed)
    }

    @Test
    fun `header-only RCODE 0 zero answers is NoRecords`() {
        assertEquals(TxtParseResult.NoRecords, TxtRecordParser.parse(header(rcode = 0, qd = 0, an = 0)))
    }

    @Test
    fun `NXDOMAIN RCODE 3 is NoRecords`() {
        assertEquals(TxtParseResult.NoRecords, TxtRecordParser.parse(header(rcode = 3, qd = 0, an = 0)))
    }

    @Test
    fun `NXDOMAIN with a lying nonzero ANCOUNT still yields NoRecords`() {
        // The name-error RCODE is authoritative: there are no answers regardless
        // of what ANCOUNT claims. A hostile server that sets RCODE 3 but ANCOUNT 1
        // (with no RR bytes following) must NOT make the parser walk past the
        // question into garbage — the RCODE wins and the count is ignored.
        val pkt = header(rcode = 3, qd = 1, an = 1) + QUESTION
        assertEquals(TxtParseResult.NoRecords, TxtRecordParser.parse(pkt))
    }

    @Test
    fun `NXDOMAIN with a malformed question is still NoRecords`() {
        // RCODE 3 is authoritative and the body is not trusted or parsed. A
        // garbled/truncated question on a name-error response must not flip "no TXT
        // for this name" into a parse failure — the RCODE short-circuits the walk.
        val pkt = header(rcode = 3, qd = 1, an = 0) + byteArrayOf(40, 0x61, 0x62, 0x63)
        assertEquals(TxtParseResult.NoRecords, TxtRecordParser.parse(pkt))
    }

    @Test
    fun `SERVFAIL RCODE 2 is Failed and names the RCODE`() {
        val result = TxtRecordParser.parse(header(rcode = 2, qd = 0, an = 0))
        assertTrue(result is TxtParseResult.Failed)
        assertEquals("RCODE=2", (result as TxtParseResult.Failed).reason)
    }

    // ---- answer with no TXT RR ----------------------------------------------

    @Test
    fun `answer holding only a non-TXT RR is NoRecords`() {
        // A single A (type 1) RR, no TXT: a well-formed answer with nothing for us.
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            otherRr(type = 1, rdata = byteArrayOf(1, 2, 3, 4))
        assertEquals(TxtParseResult.NoRecords, TxtRecordParser.parse(pkt))
    }

    @Test
    fun `non-TXT RRs before a TXT RR are skipped by rdlength`() {
        val a = otherRr(type = 1, rdata = byteArrayOf(1, 2, 3, 4))         // A
        val srv = otherRr(type = 33, rdata = u16(0) + u16(0) + u16(443) + encodeName("x.test")) // SRV
        val txt = txtRr("path=/dav/")
        val pkt = header(rcode = 0, qd = 1, an = 3) + QUESTION + a + srv + txt
        val result = TxtRecordParser.parse(pkt)
        assertTrue("expected Records, got $result", result is TxtParseResult.Records)
        assertEquals(listOf("path=/dav/"), (result as TxtParseResult.Records).strings)
    }

    // ---- multiple character-strings in one TXT RR ---------------------------

    @Test
    fun `a TXT RR with several character-strings collects all of them`() {
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + txtRr("path=/dav/", "key=value", "flag")
        val result = TxtRecordParser.parse(pkt) as TxtParseResult.Records
        assertEquals(listOf("path=/dav/", "key=value", "flag"), result.strings)
    }

    @Test
    fun `strings from multiple TXT RRs are aggregated in wire order`() {
        val pkt = header(rcode = 0, qd = 1, an = 2) + QUESTION +
            txtRr("a=1") + txtRr("b=2", "c=3")
        val result = TxtRecordParser.parse(pkt) as TxtParseResult.Records
        assertEquals(listOf("a=1", "b=2", "c=3"), result.strings)
    }

    // ---- character-string / rdlength boundaries -----------------------------

    @Test
    fun `character-string length running past its RR rdlength is Failed`() {
        // rdlength says 3 octets of rdata, but the first length octet claims 10.
        val rdata = byteArrayOf(10, 0x61, 0x62)
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL + u16(rdata.size) + rdata
        assertTrue(TxtRecordParser.parse(pkt) is TxtParseResult.Failed)
    }

    @Test
    fun `a character-string that ends flush with rdlength parses`() {
        // length octet 3 + exactly 3 bytes, rdlength 4: the last string ends
        // precisely at the rdata boundary — the common off-by-one must not fail it.
        val rdata = byteArrayOf(3, 0x61, 0x62, 0x63)
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL + u16(rdata.size) + rdata
        val result = TxtRecordParser.parse(pkt) as TxtParseResult.Records
        assertEquals(listOf("abc"), result.strings)
    }

    @Test
    fun `rdlength zero TXT RR contributes no strings`() {
        // RFC 6763 §6.1: a zero-length TXT record should be read as a single empty
        // string / no record. We contribute nothing, so a lone empty TXT is NoRecords.
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL + u16(0)
        assertEquals(TxtParseResult.NoRecords, TxtRecordParser.parse(pkt))
    }

    @Test
    fun `a single empty character-string parses to one empty string`() {
        // rdlength 1, one length octet of 0: a valid zero-length character-string.
        val rdata = byteArrayOf(0)
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL + u16(rdata.size) + rdata
        val result = TxtRecordParser.parse(pkt) as TxtParseResult.Records
        assertEquals(listOf(""), result.strings)
        assertNull(TxtRecordParser.pathValue(result.strings))
    }

    @Test
    fun `a maximal 255-octet character-string parses`() {
        val rdata = byteArrayOf(255.toByte()) + ByteArray(255) { 0x61 }
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL + u16(rdata.size) + rdata
        val result = TxtRecordParser.parse(pkt) as TxtParseResult.Records
        assertEquals(255, result.strings.single().length)
    }

    @Test
    fun `rdlength pointing past the buffer is Failed`() {
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL + u16(100) + byteArrayOf(3, 0x61)
        assertTrue(TxtRecordParser.parse(pkt) is TxtParseResult.Failed)
    }

    @Test
    fun `ANCOUNT lying high past buffer is Failed`() {
        val pkt = header(rcode = 0, qd = 1, an = 3) + QUESTION + txtRr("path=/dav/")
        assertTrue(TxtRecordParser.parse(pkt) is TxtParseResult.Failed)
    }

    // ---- RFC 6763 §6.4 path-key extraction ----------------------------------

    @Test
    fun `path key match is case-insensitive`() {
        assertEquals("/dav/", TxtRecordParser.pathValue(listOf("PaTh=/dav/")))
    }

    @Test
    fun `first path occurrence wins over later duplicates`() {
        // §6.4: silently ignore all but the first occurrence of a key.
        assertEquals("/first/", TxtRecordParser.pathValue(listOf("path=/first/", "path=/second/")))
    }

    @Test
    fun `a bare path attribute with no equals has no value even before a valued one`() {
        // A boolean "path" (no '=') is the first occurrence of the key, so §6.4
        // makes it win — and a boolean attribute carries no value.
        assertNull(TxtRecordParser.pathValue(listOf("path", "path=/late/")))
    }

    @Test
    fun `path with an empty value is the empty string`() {
        assertEquals("", TxtRecordParser.pathValue(listOf("path=")))
    }

    @Test
    fun `a value containing equals keeps everything after the first equals`() {
        assertEquals("/a=b/", TxtRecordParser.pathValue(listOf("path=/a=b/")))
    }

    @Test
    fun `a key that merely starts with path does not match`() {
        assertNull(TxtRecordParser.pathValue(listOf("pathological=/x/", "pathway=/y/")))
    }

    @Test
    fun `no path key yields null`() {
        assertNull(TxtRecordParser.pathValue(listOf("key=value", "flag")))
    }

    @Test
    fun `path found among other attributes`() {
        assertEquals("/dav/", TxtRecordParser.pathValue(listOf("foo=bar", "path=/dav/", "baz")))
    }

    // ---- helpers -------------------------------------------------------------

    /** The question section for `_carddavs._tcp.example.test`, IN TXT. Starts at offset 12. */
    private val QUESTION = question(qtype = 16)

    /** A TXT answer RR (NAME pointer to question) holding the given character-strings. */
    private fun txtRr(vararg strings: String): ByteArray {
        val rdata = strings.fold(ByteArray(0)) { acc, s ->
            val bytes = s.toByteArray(Charsets.US_ASCII)
            acc + byteArrayOf(bytes.size.toByte()) + bytes
        }
        return byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL + u16(rdata.size) + rdata
    }
}
