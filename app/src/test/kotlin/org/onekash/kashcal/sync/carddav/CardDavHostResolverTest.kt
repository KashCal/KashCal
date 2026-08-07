package org.onekash.kashcal.sync.carddav

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.network.dns.SrvRecord
import org.onekash.kashcal.network.dns.SrvResolver
import org.onekash.kashcal.network.dns.SrvResult
import org.onekash.kashcal.network.dns.TxtResolver
import org.onekash.kashcal.network.dns.TxtResult

/**
 * Unit tests for [CardDavHostResolver] — the RFC 6764 §6 host-resolution policy.
 *
 * The resolver's contract: run an SRV lookup for the account's email domain and,
 * only when it yields a usable in-domain target, build the seed base URL from that
 * target (+ port, + any TXT `path=`); otherwise return the caller's fallback (the
 * provider bootstrap constant / user-configured host). It leans on the existing
 * [shouldAttachCredentials] credential-domain guard for the security-critical
 * cross-domain-target rejection, so a forged SRV can't redirect credentials off
 * the account's registrable domain.
 *
 * Every case drives fake resolvers with canned results — no DNS, no network. The
 * registrable-domain resolver is faked too (the production one needs the OkHttp
 * public-suffix asset, absent in a JVM worker): here it returns the last two
 * labels, enough to model same-vs-different registrable domain for test hosts.
 */
class CardDavHostResolverTest {

    private val FALLBACK = "https://contacts.icloud.com"

    /** Registrable domain = last two dotted labels (e.g. contacts.icloud.com -> icloud.com). */
    private val twoLabelRegistrable: (okhttp3.HttpUrl) -> String? = { url ->
        url.host.split('.').takeLast(2).joinToString(".").ifBlank { null }
    }

    private fun srv(result: SrvResult) = object : SrvResolver {
        var calls = 0
        override suspend fun resolve(service: String, proto: String, domain: String): SrvResult {
            calls++
            return result
        }
    }

    private fun txt(result: TxtResult) = object : TxtResolver {
        var calls = 0
        override suspend fun resolvePath(service: String, proto: String, domain: String): TxtResult {
            calls++
            return result
        }
    }

    private fun resolver(
        srvResolver: SrvResolver,
        txtResolver: TxtResolver,
    ) = CardDavHostResolver(srvResolver, txtResolver, twoLabelRegistrable)

    private fun found(target: String, port: Int = 443) =
        SrvResult.Found(listOf(SrvRecord(priority = 0, weight = 0, port = port, target = target)))

    // ---- SRV hit -> use the discovered host ----------------------------------

    @Test
    fun `in-domain SRV target becomes the base URL, default port omitted`() = runTest {
        val r = resolver(srv(found("contacts.icloud.com")), txt(TxtResult.NoPath))
        assertEquals("https://contacts.icloud.com", r.resolveBaseUrl("icloud.com", FALLBACK))
    }

    @Test
    fun `a non-443 SRV port is carried onto the base URL`() = runTest {
        val r = resolver(srv(found("dav.example.com", port = 8443)), txt(TxtResult.NoPath))
        assertEquals("https://dav.example.com:8443", r.resolveBaseUrl("example.com", FALLBACK))
    }

    @Test
    fun `the first (already-ordered) SRV record is used when several are returned`() = runTest {
        val many = SrvResult.Found(
            listOf(
                SrvRecord(0, 0, 443, "primary.example.com"),
                SrvRecord(10, 0, 443, "backup.example.com"),
            ),
        )
        val r = resolver(srv(many), txt(TxtResult.NoPath))
        assertEquals("https://primary.example.com", r.resolveBaseUrl("example.com", FALLBACK))
    }

    // ---- TXT path= is appended, only after a successful SRV ------------------

    @Test
    fun `a TXT path is appended to the discovered host as the context path`() = runTest {
        val r = resolver(srv(found("dav.example.com")), txt(TxtResult.Path("/carddav/")))
        assertEquals("https://dav.example.com/carddav/", r.resolveBaseUrl("example.com", FALLBACK))
    }

    @Test
    fun `a TXT path without a leading slash is normalized to one`() = runTest {
        val r = resolver(srv(found("dav.example.com")), txt(TxtResult.Path("dav")))
        assertEquals("https://dav.example.com/dav", r.resolveBaseUrl("example.com", FALLBACK))
    }

    @Test
    fun `an empty TXT path leaves the base URL host-only`() = runTest {
        // RFC 6763 keys on the key's presence, but an empty context path adds nothing.
        val r = resolver(srv(found("dav.example.com")), txt(TxtResult.Path("")))
        assertEquals("https://dav.example.com", r.resolveBaseUrl("example.com", FALLBACK))
    }

    @Test
    fun `TXT is not queried when SRV did not resolve a host`() = runTest {
        // RFC 6764 §6 step 3: TXT is queried only after a SUCCESSFUL SRV lookup.
        val txtResolver = txt(TxtResult.Path("/should-not-be-used/"))
        val r = resolver(srv(SrvResult.NoRecords), txtResolver)
        assertEquals(FALLBACK, r.resolveBaseUrl("example.com", FALLBACK))
        assertEquals("TXT must not be queried without a SRV host", 0, txtResolver.calls)
    }

    // ---- security: cross-domain SRV target is rejected -----------------------

    @Test
    fun `a cross-registrable-domain SRV target is rejected and falls back`() = runTest {
        // Forged SRV: fastmail.com -> evil.com would redirect Basic-auth credentials.
        val r = resolver(srv(found("evil.com")), txt(TxtResult.NoPath))
        assertEquals(FALLBACK, r.resolveBaseUrl("fastmail.com", FALLBACK))
    }

    @Test
    fun `a suffix-trick SRV target on a different registrable domain is rejected`() = runTest {
        val r = resolver(srv(found("icloud.com.attacker.example")), txt(TxtResult.NoPath))
        assertEquals(FALLBACK, r.resolveBaseUrl("icloud.com", FALLBACK))
    }

    // ---- non-hit SRV outcomes all fall back ----------------------------------

    @Test
    fun `SRV NotAvailable falls back to the configured host`() = runTest {
        val r = resolver(srv(SrvResult.NotAvailable), txt(TxtResult.NoPath))
        assertEquals(FALLBACK, r.resolveBaseUrl("example.com", FALLBACK))
    }

    @Test
    fun `SRV NoRecords falls back to the configured host`() = runTest {
        val r = resolver(srv(SrvResult.NoRecords), txt(TxtResult.NoPath))
        assertEquals(FALLBACK, r.resolveBaseUrl("example.com", FALLBACK))
    }

    @Test
    fun `SRV Error falls back to the configured host`() = runTest {
        val r = resolver(srv(SrvResult.Error("SERVFAIL")), txt(TxtResult.NoPath))
        assertEquals(FALLBACK, r.resolveBaseUrl("example.com", FALLBACK))
    }

    // ---- internationalized (non-ASCII) email domains -------------------------

    @Test
    fun `an internationalized email domain is punycode-normalized before the SRV lookup`() = runTest {
        // DNS is ASCII-only; "münchen.de" must be queried as its A-label form or it
        // could never resolve. The target comes back within the same A-label domain,
        // so the credential guard (last-two-labels here) still accepts it.
        var queried: String? = null
        val srvResolver = object : SrvResolver {
            override suspend fun resolve(service: String, proto: String, domain: String): SrvResult {
                queried = domain
                return found("contacts.xn--mnchen-3ya.de")
            }
        }
        val r = resolver(srvResolver, txt(TxtResult.NoPath))
        val base = r.resolveBaseUrl("münchen.de", FALLBACK)
        assertEquals("SRV queried with the A-label form", "xn--mnchen-3ya.de", queried)
        assertEquals("https://contacts.xn--mnchen-3ya.de", base)
    }

    @Test
    fun `a malformed domain falls back before any DNS query rather than crashing`() = runTest {
        // An empty label (e.g. "a..b") makes IDN.toASCII throw IllegalArgumentException.
        // The resolver must swallow it and return the fallback WITHOUT ever querying
        // DNS — so the SRV resolver is wired to a hit it must never reach.
        val srvResolver = srv(found("contacts.icloud.com"))
        val r = resolver(srvResolver, txt(TxtResult.NoPath))
        assertEquals(FALLBACK, r.resolveBaseUrl("a..b", FALLBACK))
        assertEquals("a domain that can't be A-labeled skips the SRV query", 0, srvResolver.calls)
    }

    // ---- no domain to query --------------------------------------------------

    @Test
    fun `a blank email domain skips SRV entirely and returns the fallback`() = runTest {
        val srvResolver = srv(found("contacts.icloud.com"))
        val r = resolver(srvResolver, txt(TxtResult.NoPath))
        assertEquals(FALLBACK, r.resolveBaseUrl("", FALLBACK))
        assertEquals("no domain -> no SRV query", 0, srvResolver.calls)
    }

}
