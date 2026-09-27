package org.onekash.kashcal.network.dns

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.TTL
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.encodeName
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.header
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.question
import org.onekash.kashcal.network.dns.DnsWireTestFixtures.u16

/**
 * Tests [SrvResolverImpl] over a fake [RawDnsChannel] that returns canned bytes or a canned throw,
 * so no network is touched. Covers the query name and TYPE, the mapping of each [SrvParseResult] to
 * its [SrvResult] (a short body fails in the parser and maps to Error), a throwing channel mapped
 * to Error with its message or exception type, and Found records ordered through [SrvSelection].
 */
class SrvResolverImplTest {

    private val QUESTION = question(qtype = 33)

    /** An SRV answer RR whose NAME is a 0xc00c pointer to the question. */
    private fun srvRr(priority: Int, weight: Int, port: Int, target: ByteArray): ByteArray {
        val rdata = u16(priority) + u16(weight) + u16(port) + target
        return byteArrayOf(0xc0.toByte(), 0x0c) + u16(33) + u16(1) + TTL + u16(rdata.size) + rdata
    }

    /** Returns the same canned response for any query, recording the fqdn and type asked. */
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
    fun `builds the _service _proto domain name and asks for TYPE 33`() = runTest {
        val channel = CannedChannel(header(rcode = 0, qd = 0, an = 0))
        SrvResolverImpl(channel).resolve("carddavs", "tcp", "example.com")
        assertEquals("_carddavs._tcp.example.com", channel.askedFqdn)
        assertEquals(33, channel.askedType)
    }

    // ---- parse-result -> SrvResult mapping -----------------------------------

    @Test
    fun `records map to Found`() = runTest {
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION +
            srvRr(0, 0, 443, encodeName("dav.example.com"))
        val result = SrvResolverImpl(CannedChannel(pkt)).resolve("carddavs", "tcp", "example.com")
        assertTrue("expected Found, got $result", result is SrvResult.Found)
        assertEquals(
            listOf(SrvRecord(0, 0, 443, "dav.example.com")),
            (result as SrvResult.Found).records,
        )
    }

    @Test
    fun `NXDOMAIN maps to NoRecords`() = runTest {
        val pkt = header(rcode = 3, qd = 0, an = 0)
        assertEquals(SrvResult.NoRecords, SrvResolverImpl(CannedChannel(pkt)).resolve("carddavs", "tcp", "x.com"))
    }

    @Test
    fun `empty NOERROR answer maps to NoRecords`() = runTest {
        val pkt = header(rcode = 0, qd = 0, an = 0)
        assertEquals(SrvResult.NoRecords, SrvResolverImpl(CannedChannel(pkt)).resolve("carddavs", "tcp", "x.com"))
    }

    @Test
    fun `lone root target maps to NotAvailable`() = runTest {
        val pkt = header(rcode = 0, qd = 1, an = 1) + QUESTION + srvRr(0, 0, 0, byteArrayOf(0))
        assertEquals(SrvResult.NotAvailable, SrvResolverImpl(CannedChannel(pkt)).resolve("carddavs", "tcp", "x.com"))
    }

    @Test
    fun `SERVFAIL maps to Error carrying the RCODE`() = runTest {
        val result = SrvResolverImpl(CannedChannel(header(rcode = 2, qd = 0, an = 0)))
            .resolve("carddavs", "tcp", "x.com")
        assertTrue(result is SrvResult.Error)
        assertEquals("RCODE=2", (result as SrvResult.Error).reason)
    }

    @Test
    fun `malformed bytes map to Error`() = runTest {
        val result = SrvResolverImpl(CannedChannel(ByteArray(3)))  // shorter than a header
            .resolve("carddavs", "tcp", "x.com")
        assertTrue("expected Error, got $result", result is SrvResult.Error)
    }

    // ---- channel transport failures ------------------------------------------

    @Test
    fun `channel throwing folds to Error with the exception message`() = runTest {
        val channel = RawDnsChannel { _, _ -> throw java.net.UnknownHostException("no route") }
        val result = SrvResolverImpl(channel).resolve("carddavs", "tcp", "x.com")
        assertTrue(result is SrvResult.Error)
        assertEquals("no route", (result as SrvResult.Error).reason)
    }

    @Test
    fun `channel throwing with no message folds to Error naming the exception type`() = runTest {
        val channel = RawDnsChannel { _, _ -> throw java.util.concurrent.TimeoutException() }
        val result = SrvResolverImpl(channel).resolve("carddavs", "tcp", "x.com")
        assertTrue(result is SrvResult.Error)
        assertEquals("TimeoutException", (result as SrvResult.Error).reason)
    }

    // ---- RFC 2782 ordering is delegated to SrvSelection ----------------------

    @Test
    fun `Found records come back ordered by ascending priority`() = runTest {
        // Wire order is priority 20 then 10; Found must come back priority 10 first, as
        // SrvSelection.order returns it. Each priority holds one record, so the fixed rng is
        // never drawn.
        val pkt = header(rcode = 0, qd = 1, an = 2) + QUESTION +
            // A higher priority number means lower preference.
            srvRr(20, 0, 443, encodeName("low.example.com")) +
            srvRr(10, 0, 443, encodeName("high.example.com"))
        val result = SrvResolverImpl(CannedChannel(pkt), rng = { 0.0 })
            .resolve("carddavs", "tcp", "example.com")
        assertTrue(result is SrvResult.Found)
        assertEquals(
            listOf("high.example.com", "low.example.com"),
            (result as SrvResult.Found).records.map { it.target },
        )
    }
}
