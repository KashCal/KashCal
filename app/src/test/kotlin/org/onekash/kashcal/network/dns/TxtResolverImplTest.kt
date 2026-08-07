package org.onekash.kashcal.network.dns

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.TTL
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.header
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.question
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.u16

/**
 * Unit tests for [TxtResolverImpl] — the RFC 6764 §6 step-3 TXT `path=` lookup. Like
 * [SrvResolverImplTest], every case drives a fake [RawDnsChannel] with canned bytes.
 * The contract: build the right query name for TYPE 16, and reduce the decoded TXT
 * strings to Path / NoPath / Error using the parser's `pathValue` semantics.
 */
class TxtResolverImplTest {

    private val QUESTION = question(qtype = 16)

    /** A TXT answer RR (NAME = pointer to the question) carrying one character-string [s]. */
    private fun txtRr(s: String): ByteArray {
        val str = s.toByteArray(Charsets.UTF_8)
        val rdata = byteArrayOf(str.size.toByte()) + str
        return byteArrayOf(0xc0.toByte(), 0x0c) + u16(16) + u16(1) + TTL + u16(rdata.size) + rdata
    }

    private fun txtPacket(s: String) = header(rcode = 0, qd = 1, an = 1) + QUESTION + txtRr(s)

    private class CannedChannel(private val response: ByteArray) : RawDnsChannel {
        var askedFqdn: String? = null
        var askedType: Int = -1
        override suspend fun query(fqdn: String, nsType: Int): ByteArray {
            askedFqdn = fqdn; askedType = nsType
            return response
        }
    }

    // ---- query construction --------------------------------------------------

    @Test
    fun `builds the _service _proto domain name and asks for TYPE 16`() = runTest {
        val channel = CannedChannel(header(rcode = 0, qd = 0, an = 0))
        TxtResolverImpl(channel).resolvePath("carddavs", "tcp", "example.com")
        assertEquals("_carddavs._tcp.example.com", channel.askedFqdn)
        assertEquals(16, channel.askedType)
    }

    // ---- path extraction -----------------------------------------------------

    @Test
    fun `path key yields Path with the value`() = runTest {
        val result = TxtResolverImpl(CannedChannel(txtPacket("path=/dav/"))).resolvePath("carddavs", "tcp", "x.com")
        assertEquals(TxtResult.Path("/dav/"), result)
    }

    @Test
    fun `path key match is case-insensitive`() = runTest {
        val result = TxtResolverImpl(CannedChannel(txtPacket("PATH=/carddav"))).resolvePath("carddavs", "tcp", "x.com")
        assertEquals(TxtResult.Path("/carddav"), result)
    }

    @Test
    fun `present-but-empty path value is still Path`() = runTest {
        // RFC 6763 §6.4 keys on the presence of the key; "path=" with an empty value
        // is a real (if degenerate) context path, distinct from the key being absent.
        val result = TxtResolverImpl(CannedChannel(txtPacket("path="))).resolvePath("carddavs", "tcp", "x.com")
        assertEquals(TxtResult.Path(""), result)
    }

    @Test
    fun `bare boolean path token with no equals is NoPath`() = runTest {
        // A key with no '=' is a boolean attribute carrying no value (§6.4) — no path.
        val result = TxtResolverImpl(CannedChannel(txtPacket("path"))).resolvePath("carddavs", "tcp", "x.com")
        assertEquals(TxtResult.NoPath, result)
    }

    @Test
    fun `TXT record without a path key is NoPath`() = runTest {
        val result = TxtResolverImpl(CannedChannel(txtPacket("txtvers=1"))).resolvePath("carddavs", "tcp", "x.com")
        assertEquals(TxtResult.NoPath, result)
    }

    @Test
    fun `no TXT record is NoPath`() = runTest {
        val result = TxtResolverImpl(CannedChannel(header(rcode = 0, qd = 0, an = 0))).resolvePath("carddavs", "tcp", "x.com")
        assertEquals(TxtResult.NoPath, result)
    }

    @Test
    fun `NXDOMAIN is NoPath`() = runTest {
        val result = TxtResolverImpl(CannedChannel(header(rcode = 3, qd = 0, an = 0))).resolvePath("carddavs", "tcp", "x.com")
        assertEquals(TxtResult.NoPath, result)
    }

    // ---- failures ------------------------------------------------------------

    @Test
    fun `SERVFAIL is Error carrying the RCODE`() = runTest {
        val result = TxtResolverImpl(CannedChannel(header(rcode = 2, qd = 0, an = 0))).resolvePath("carddavs", "tcp", "x.com")
        assertTrue(result is TxtResult.Error)
        assertEquals("RCODE=2", (result as TxtResult.Error).reason)
    }

    @Test
    fun `malformed bytes are Error`() = runTest {
        val result = TxtResolverImpl(CannedChannel(ByteArray(3))).resolvePath("carddavs", "tcp", "x.com")
        assertTrue("expected Error, got $result", result is TxtResult.Error)
    }

    @Test
    fun `channel throwing folds to Error`() = runTest {
        val channel = RawDnsChannel { _, _ -> throw java.net.UnknownHostException("down") }
        val result = TxtResolverImpl(channel).resolvePath("carddavs", "tcp", "x.com")
        assertTrue(result is TxtResult.Error)
        assertEquals("down", (result as TxtResult.Error).reason)
    }
}
