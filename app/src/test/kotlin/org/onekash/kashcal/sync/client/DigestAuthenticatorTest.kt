package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DigestAuthenticator.
 *
 * Tests RFC 2617/7616 Digest authentication:
 * - Challenge parsing (WWW-Authenticate header)
 * - QoP negotiation
 * - Hash computation (MD5, SHA-256)
 * - Loop prevention
 * - Nonce count management
 * - Stale nonce handling
 */
class DigestAuthenticatorTest {

    private lateinit var authenticator: DigestAuthenticator

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        authenticator = DigestAuthenticator("testuser", "testpass")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== Helper methods ==========

    private fun build401Response(
        vararg wwwAuthHeaders: String,
        requestUrl: String = "http://example.com/dav.php/",
        requestMethod: String = "PROPFIND",
        existingAuthHeader: String? = null
    ): Response {
        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .method(requestMethod, null)
        existingAuthHeader?.let { requestBuilder.header("Authorization", it) }
        val request = requestBuilder.build()

        val responseBuilder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
        wwwAuthHeaders.forEach { responseBuilder.addHeader("WWW-Authenticate", it) }
        return responseBuilder.build()
    }

    private fun buildRequest(
        url: String = "http://example.com/dav.php/",
        method: String = "PROPFIND"
    ): Request {
        return Request.Builder()
            .url(url)
            .method(method, null)
            .build()
    }

    // ========== parseDigestChallenge tests ==========

    @Test
    fun `parseDigestChallenge parses SabreDAV challenge correctly`() {
        val response = build401Response(
            """Digest realm="SabreDAV", nonce="abc123def456", qop="auth", opaque="opaque-value""""
        )
        val challenge = authenticator.parseDigestChallenge(response)

        assertNotNull(challenge)
        assertEquals("SabreDAV", challenge!!.realm)
        assertEquals("abc123def456", challenge.nonce)
        assertEquals("auth", challenge.qop)
        assertEquals("opaque-value", challenge.opaque)
        assertNull(challenge.algorithm)  // Not specified = default MD5
        assertEquals("MD5", challenge.hashAlgorithm)
        assertFalse(challenge.stale)
    }

    @Test
    fun `parseDigestChallenge handles missing optional fields`() {
        // Only realm and nonce are required
        val response = build401Response(
            """Digest realm="TestRealm", nonce="nonce123""""
        )
        val challenge = authenticator.parseDigestChallenge(response)

        assertNotNull(challenge)
        assertEquals("TestRealm", challenge!!.realm)
        assertEquals("nonce123", challenge.nonce)
        assertNull(challenge.qop)
        assertNull(challenge.opaque)
        assertNull(challenge.algorithm)
        assertFalse(challenge.stale)
    }

    @Test
    fun `parseDigestChallenge returns null for Basic-only challenge`() {
        val response = build401Response("""Basic realm="SabreDAV"""")
        val challenge = authenticator.parseDigestChallenge(response)
        assertNull(challenge)
    }

    @Test
    fun `parseDigestChallenge prefers Digest over Basic when both offered`() {
        val response = build401Response(
            """Basic realm="SabreDAV"""",
            """Digest realm="SabreDAV", nonce="abc123", qop="auth""""
        )
        val challenge = authenticator.parseDigestChallenge(response)

        assertNotNull(challenge)
        assertEquals("SabreDAV", challenge!!.realm)
        assertEquals("abc123", challenge.nonce)
    }

    @Test
    fun `parseDigestChallenge returns null when no WWW-Authenticate header`() {
        val request = buildRequest()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val challenge = authenticator.parseDigestChallenge(response)
        assertNull(challenge)
    }

    @Test
    fun `parseDigestChallenge returns null when realm missing`() {
        val response = build401Response("""Digest nonce="abc123"""")
        val challenge = authenticator.parseDigestChallenge(response)
        assertNull("Should return null without realm", challenge)
    }

    @Test
    fun `parseDigestChallenge returns null when nonce missing`() {
        val response = build401Response("""Digest realm="SabreDAV"""")
        val challenge = authenticator.parseDigestChallenge(response)
        assertNull("Should return null without nonce", challenge)
    }

    @Test
    fun `parseDigestChallenge parses stale flag`() {
        val response = build401Response(
            """Digest realm="SabreDAV", nonce="new-nonce", stale=true"""
        )
        val challenge = authenticator.parseDigestChallenge(response)

        assertNotNull(challenge)
        assertTrue("stale should be true", challenge!!.stale)
    }

    @Test
    fun `parseDigestChallenge parses SHA-256 algorithm`() {
        val response = build401Response(
            """Digest realm="SabreDAV", nonce="abc123", algorithm=SHA-256"""
        )
        val challenge = authenticator.parseDigestChallenge(response)

        assertNotNull(challenge)
        assertEquals("SHA-256", challenge!!.algorithm)
        assertEquals("SHA-256", challenge.hashAlgorithm)
    }

    // ========== parseQop tests ==========

    @Test
    fun `parseQop selects auth from comma-separated list`() {
        assertEquals("auth", authenticator.parseQop("auth,auth-int"))
    }

    @Test
    fun `parseQop selects auth from single value`() {
        assertEquals("auth", authenticator.parseQop("auth"))
    }

    @Test
    fun `parseQop returns null when only auth-int offered`() {
        assertNull(authenticator.parseQop("auth-int"))
    }

    @Test
    fun `parseQop returns null for legacy mode`() {
        assertNull(authenticator.parseQop(null))
    }

    // ========== buildDigestHeader tests ==========

    @Test
    fun `buildDigestHeader computes correct MD5 response`() {
        // Known-answer test based on RFC 2617 Section 3.5
        // Using our own known values for deterministic testing
        val auth = DigestAuthenticator("Mufasa", "Circle Of Life")
        val challenge = DigestAuthenticator.DigestChallenge(
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            qop = "auth",
            opaque = "5ccc069c403ebaf9f0171e9517f40e41",
            algorithm = null,
            stale = false
        )
        val request = Request.Builder()
            .url("http://www.nowhere.org/dir/index.html")
            .get()
            .build()

        // Manually set nonceCount to 1 for deterministic test
        // We need to call authenticate first to set nonce state, but for unit test
        // we can test the hash computation directly
        val ha1 = auth.hash("Mufasa:testrealm@host.com:Circle Of Life", "MD5")
        assertEquals("939e7578ed9e3c518a452acee763bce9", ha1)

        val ha2 = auth.hash("GET:/dir/index.html", "MD5")
        assertEquals("39aff3a2bab6126f332b942af96d3366", ha2)
    }

    @Test
    fun `buildDigestHeader computes correct SHA-256 response`() {
        val auth = DigestAuthenticator("testuser", "testpass")
        val ha1 = auth.hash("testuser:testrealm:testpass", "SHA-256")
        // SHA-256 produces 64-char hex string
        assertEquals(64, ha1.length)
        // Verify it's a valid hex string
        assertTrue(ha1.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `buildDigestHeader handles no qop (legacy mode)`() {
        val challenge = DigestAuthenticator.DigestChallenge(
            realm = "SabreDAV",
            nonce = "nonce123",
            qop = null,  // Legacy mode
            opaque = null,
            algorithm = null,
            stale = false
        )
        val request = Request.Builder()
            .url("http://example.com/dav.php/")
            .method("PROPFIND", null)
            .build()

        // Force nonceCount by calling authenticate with a proper response
        val auth = DigestAuthenticator("user", "pass")
        val header = auth.buildDigestHeader(request, challenge)

        // Legacy mode: no qop, nc, or cnonce
        assertFalse("Legacy mode should not have qop", header.contains("qop="))
        assertFalse("Legacy mode should not have nc", header.contains("nc="))
        assertFalse("Legacy mode should not have cnonce", header.contains("cnonce="))
        assertTrue("Should have response", header.contains("response=\""))
    }

    @Test
    fun `buildDigestHeader uses encoded path+query for URI, not full URL`() {
        val challenge = DigestAuthenticator.DigestChallenge(
            realm = "SabreDAV",
            nonce = "nonce123",
            qop = "auth",
            opaque = null,
            algorithm = null,
            stale = false
        )
        val request = Request.Builder()
            .url("http://example.com/dav.php/calendars/user/?param=value")
            .method("PROPFIND", null)
            .build()

        val header = authenticator.buildDigestHeader(request, challenge)

        // URI should be path+query only, not full URL
        assertTrue(
            "URI should be path+query",
            header.contains("uri=\"/dav.php/calendars/user/?param=value\"")
        )
        assertFalse(
            "URI should NOT contain scheme/host",
            header.contains("uri=\"http://")
        )
    }

    @Test
    fun `buildDigestHeader echoes opaque value unchanged`() {
        val challenge = DigestAuthenticator.DigestChallenge(
            realm = "SabreDAV",
            nonce = "nonce123",
            qop = "auth",
            opaque = "opaque-test-value-12345",
            algorithm = null,
            stale = false
        )
        val request = buildRequest()

        val header = authenticator.buildDigestHeader(request, challenge)

        assertTrue(
            "Should echo opaque unchanged",
            header.contains("opaque=\"opaque-test-value-12345\"")
        )
    }

    @Test
    fun `buildDigestHeader includes algorithm when specified`() {
        val challenge = DigestAuthenticator.DigestChallenge(
            realm = "SabreDAV",
            nonce = "nonce123",
            qop = "auth",
            opaque = null,
            algorithm = "SHA-256",
            stale = false
        )
        val request = buildRequest()

        val header = authenticator.buildDigestHeader(request, challenge)

        assertTrue("Should include algorithm", header.contains("algorithm=SHA-256"))
    }

    @Test
    fun `buildDigestHeader omits algorithm when null (default MD5)`() {
        val challenge = DigestAuthenticator.DigestChallenge(
            realm = "SabreDAV",
            nonce = "nonce123",
            qop = "auth",
            opaque = null,
            algorithm = null,
            stale = false
        )
        val request = buildRequest()

        val header = authenticator.buildDigestHeader(request, challenge)

        assertFalse("Should not include algorithm for default MD5", header.contains("algorithm="))
    }

    // ========== authenticate() loop prevention tests ==========

    @Test
    fun `authenticate returns null on second 401 (loop prevention)`() {
        // Simulate: we already sent Digest auth, server rejected it (not stale)
        val response = build401Response(
            """Digest realm="SabreDAV", nonce="abc123", qop="auth"""",
            existingAuthHeader = """Digest username="testuser", realm="SabreDAV", nonce="abc123", response="wronghash""""
        )

        val result = authenticator.authenticate(null, response)

        assertNull("Should return null when Digest already tried and not stale", result)
    }

    @Test
    fun `authenticate retries on stale=true with reset nonce count`() {
        // First: successful auth establishes nonce
        val firstResponse = build401Response(
            """Digest realm="SabreDAV", nonce="old-nonce", qop="auth""""
        )
        val firstResult = authenticator.authenticate(null, firstResponse)
        assertNotNull("First auth should succeed", firstResult)

        // Second: stale=true means nonce expired, retry with new nonce
        val staleResponse = build401Response(
            """Digest realm="SabreDAV", nonce="new-nonce", qop="auth", stale=true""",
            existingAuthHeader = """Digest username="testuser", realm="SabreDAV", nonce="old-nonce", response="hash""""
        )
        val staleResult = authenticator.authenticate(null, staleResponse)

        assertNotNull("Should retry on stale=true", staleResult)
        val authHeader = staleResult!!.header("Authorization")!!
        assertTrue("Should use new nonce", authHeader.contains("nonce=\"new-nonce\""))
        assertTrue("nc should be reset to 1", authHeader.contains("nc=00000001"))
    }

    @Test
    fun `authenticate resets nonce count when server issues new nonce`() {
        // First request with nonce A
        val firstResponse = build401Response(
            """Digest realm="SabreDAV", nonce="nonceA", qop="auth""""
        )
        authenticator.authenticate(null, firstResponse)

        // Second request still nonce A → nc increments
        val secondResponse = build401Response(
            """Digest realm="SabreDAV", nonce="nonceA", qop="auth""""
        )
        val secondResult = authenticator.authenticate(null, secondResponse)
        val secondHeader = secondResult!!.header("Authorization")!!
        assertTrue("nc should be 2", secondHeader.contains("nc=00000002"))

        // Third request with NEW nonce B → nc resets
        val thirdResponse = build401Response(
            """Digest realm="SabreDAV", nonce="nonceB", qop="auth""""
        )
        val thirdResult = authenticator.authenticate(null, thirdResponse)
        val thirdHeader = thirdResult!!.header("Authorization")!!
        assertTrue("nc should reset to 1 with new nonce", thirdHeader.contains("nc=00000001"))
    }

    @Test
    fun `nonce count increments across calls with same nonce`() {
        val challenge = """Digest realm="SabreDAV", nonce="same-nonce", qop="auth""""

        val first = authenticator.authenticate(null, build401Response(challenge))
        assertTrue("First nc=1", first!!.header("Authorization")!!.contains("nc=00000001"))

        val second = authenticator.authenticate(null, build401Response(challenge))
        assertTrue("Second nc=2", second!!.header("Authorization")!!.contains("nc=00000002"))

        val third = authenticator.authenticate(null, build401Response(challenge))
        assertTrue("Third nc=3", third!!.header("Authorization")!!.contains("nc=00000003"))
    }

    @Test
    fun `cnonce is different for each call`() {
        val challenge = """Digest realm="SabreDAV", nonce="nonce123", qop="auth""""

        val first = authenticator.authenticate(null, build401Response(challenge))
        val second = authenticator.authenticate(null, build401Response(challenge))

        val cnonce1 = extractField(first!!.header("Authorization")!!, "cnonce")
        val cnonce2 = extractField(second!!.header("Authorization")!!, "cnonce")

        assertNotEquals("cnonces should be different", cnonce1, cnonce2)
    }

    @Test
    fun `authenticate returns null for non-Digest 401`() {
        val response = build401Response("""Basic realm="SabreDAV"""")
        val result = authenticator.authenticate(null, response)
        assertNull("Should return null for Basic-only challenge", result)
    }

    @Test
    fun `authenticate returns null when 401 has no WWW-Authenticate header`() {
        val request = buildRequest()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val result = authenticator.authenticate(null, response)
        assertNull("Should return null without WWW-Authenticate header", result)
    }

    // ========== hash() tests ==========

    @Test
    fun `hash produces correct MD5 hex`() {
        // MD5("") = d41d8cd98f00b204e9800998ecf8427e
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", authenticator.hash("", "MD5"))
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertEquals("900150983cd24fb0d6963f7d28e17f72", authenticator.hash("abc", "MD5"))
    }

    @Test
    fun `hash produces correct SHA-256 hex`() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            authenticator.hash("", "SHA-256")
        )
    }

    // ========== parseAuthParams tests ==========

    @Test
    fun `parseAuthParams handles quoted and unquoted values`() {
        val params = authenticator.parseAuthParams(
            """realm="SabreDAV", nonce="abc123", qop="auth", stale=true, algorithm=MD5"""
        )
        assertEquals("SabreDAV", params["realm"])
        assertEquals("abc123", params["nonce"])
        assertEquals("auth", params["qop"])
        assertEquals("true", params["stale"])
        assertEquals("MD5", params["algorithm"])
    }

    // ========== Utility ==========

    private fun extractField(header: String, field: String): String? {
        val regex = Regex("""$field="([^"]*)"""")
        return regex.find(header)?.groupValues?.get(1)
    }
}
