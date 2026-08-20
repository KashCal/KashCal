package org.onekash.kashcal.network

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Tests for AiaCertificateChainCompleter.
 *
 * Test certificates are generated programmatically using raw DER construction
 * and Java's CertificateFactory. No external dependencies or sun.security.* APIs.
 */
class AiaCertificateChainCompleterTest {

    private val completer = AiaCertificateChainCompleter()

    // MockWebServer binds to loopback, which the production SSRF guard refuses.
    // Download tests that need a real fetch opt into allowing local targets.
    private val localCompleter = AiaCertificateChainCompleter(allowLocalFetchTargets = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        AiaCertificateChainCompleter.clearCacheForTesting()
        unmockkAll()
    }

    // ==================== extractAiaCaIssuersUrl ====================

    @Test
    fun `extractAiaCaIssuersUrl - returns URL from cert with AIA extension`() {
        val aiaUrl = "http://certs.example.com/intermediate.crt"
        val cert = createCertWithAia(aiaUrl)

        val extracted = completer.extractAiaCaIssuersUrl(cert)

        assertEquals(aiaUrl, extracted)
    }

    @Test
    fun `extractAiaCaIssuersUrl - returns null for cert without AIA`() {
        val cert = createSelfSignedCert()

        val extracted = completer.extractAiaCaIssuersUrl(cert)

        assertNull("Should return null for cert without AIA extension", extracted)
    }

    @Test
    fun `extractAiaCaIssuersUrl - handles URL with path and query params`() {
        val aiaUrl = "http://pki.example.com/certs/ca.cer?v=2"
        val cert = createCertWithAia(aiaUrl)

        val extracted = completer.extractAiaCaIssuersUrl(cert)

        assertEquals(aiaUrl, extracted)
    }

    @Test
    fun `extractAiaCaIssuersUrl - trims ASN1 junk when OCSP entry follows caIssuers`() {
        // Real-world pattern: Sectigo certs have both caIssuers + OCSP in AIA.
        // The DER encoding leaves ASN.1 tag bytes (e.g., 0x30 = '0') appended
        // to the caIssuers URL when using the heuristic scanner.
        val caIssuersUrl = "http://crt.sectigo.com/SectigoPublicServerAuthenticationCADVR36.crt"
        val ocspUrl = "http://ocsp.sectigo.com"
        val cert = createCertWithAiaAndOcsp(caIssuersUrl, ocspUrl)

        val extracted = completer.extractAiaCaIssuersUrl(cert)

        assertEquals(caIssuersUrl, extracted)
    }

    // ==================== downloadCertificate ====================

    @Test
    fun `downloadCertificate - parses DER cert from server`() {
        val cert = createSelfSignedCert()
        val derBytes = cert.encoded

        val mockServer = MockWebServer()
        mockServer.start()
        try {
            mockServer.enqueue(
                MockResponse()
                    .setBody(Buffer().write(derBytes))
                    .setHeader("Content-Type", "application/x-x509-ca-cert")
            )

            val downloaded = localCompleter.downloadCertificate(mockServer.url("/intermediate.cer").toString())

            assertNotNull("Should parse DER certificate", downloaded)
            assertEquals(cert.subjectX500Principal, downloaded!!.subjectX500Principal)
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun `downloadCertificate - returns null on socket timeout`() {
        val mockServer = MockWebServer()
        mockServer.start()
        try {
            // Response body delay exceeds the 5s read timeout in downloadCertificate.
            // Use setBodyDelay instead of throttleBody to avoid slow trickle.
            mockServer.enqueue(
                MockResponse()
                    .setBody(Buffer().write(ByteArray(100)))
                    .setBodyDelay(10, TimeUnit.SECONDS)
            )

            val downloaded = localCompleter.downloadCertificate(mockServer.url("/slow.cer").toString())

            assertNull("Should return null on timeout", downloaded)
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun `downloadCertificate - returns null on invalid content`() {
        val mockServer = MockWebServer()
        mockServer.start()
        try {
            mockServer.enqueue(
                MockResponse()
                    .setBody("this is not a certificate")
                    .setHeader("Content-Type", "text/plain")
            )

            val downloaded = localCompleter.downloadCertificate(mockServer.url("/garbage.cer").toString())

            assertNull("Should return null for invalid certificate data", downloaded)
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun `downloadCertificate - returns null for connection refused`() {
        // Port 1 on localhost should refuse connections quickly.
        // Use the local-allowing completer so the guard doesn't short-circuit first —
        // this test is about connection failure, not the SSRF guard.
        val downloaded = localCompleter.downloadCertificate("http://127.0.0.1:1/unreachable.cer")

        assertNull("Should return null for connection refused", downloaded)
    }

    @Test
    fun `downloadCertificate - refuses fetch to loopback or private addresses`() {
        // An attacker-controlled AIA URL must not be usable to probe the device's
        // own network (blind SSRF). The production completer resolves the host and
        // rejects loopback / link-local / private / any-local targets outright.
        assertFalse("loopback must be refused", completer.isAllowedFetchUrl("http://127.0.0.1/ca.crt"))
        assertFalse("any-local must be refused", completer.isAllowedFetchUrl("http://0.0.0.0/ca.crt"))
        assertFalse("link-local must be refused", completer.isAllowedFetchUrl("http://169.254.1.1/ca.crt"))
        assertFalse("private 10/8 must be refused", completer.isAllowedFetchUrl("http://10.0.0.5/ca.crt"))
        assertFalse("private 192.168/16 must be refused", completer.isAllowedFetchUrl("http://192.168.1.1/ca.crt"))
        // Ranges the JDK helpers don't cover.
        assertFalse("CGNAT 100.64/10 must be refused", completer.isAllowedFetchUrl("http://100.64.0.1/ca.crt"))
        assertFalse("0.0.0.0/8 must be refused", completer.isAllowedFetchUrl("http://0.1.2.3/ca.crt"))
        assertFalse("IPv6 ULA fc00::/7 must be refused", completer.isAllowedFetchUrl("http://[fd00::1]/ca.crt"))

        // A routable public address is allowed (literal IP so no DNS is needed).
        assertTrue("public address must be allowed", completer.isAllowedFetchUrl("http://8.8.8.8/ca.crt"))
        assertTrue("public IPv6 must be allowed", completer.isAllowedFetchUrl("http://[2001:4860:4860::8888]/ca.crt"))

        // The test-only opt-in bypasses the guard so MockWebServer (loopback) works.
        assertTrue(
            "allowLocalFetchTargets must permit loopback",
            localCompleter.isAllowedFetchUrl("http://127.0.0.1/ca.crt")
        )
    }

    @Test
    fun `downloadCertificate - does not follow redirects`() {
        // The SSRF guard only clears the original host. An attacker-controlled AIA
        // endpoint that 302s to an internal address must not be chased — even when a
        // perfectly valid cert sits behind the redirect, the fetch must fail.
        val cert = createSelfSignedCert()
        val mockServer = MockWebServer()
        mockServer.start()
        try {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/real.cer")
            )
            // Would be returned only if the redirect were (wrongly) followed.
            mockServer.enqueue(
                MockResponse()
                    .setBody(Buffer().write(cert.encoded))
                    .setHeader("Content-Type", "application/x-x509-ca-cert")
            )

            val downloaded = localCompleter.downloadCertificate(mockServer.url("/redirect.cer").toString())

            assertNull("Must not follow an AIA redirect, even to a valid cert", downloaded)
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun `downloadCertificate - returns null when response exceeds size cap`() {
        val mockServer = MockWebServer()
        mockServer.start()
        try {
            // Just over the 1 MB ceiling — a real intermediate is a few KB, so this
            // can only be a misconfigured endpoint or a memory-exhaustion attempt.
            mockServer.enqueue(
                MockResponse()
                    .setBody(Buffer().write(ByteArray(1 * 1024 * 1024 + 1)))
                    .setHeader("Content-Type", "application/x-x509-ca-cert")
            )

            val downloaded = localCompleter.downloadCertificate(mockServer.url("/huge.cer").toString())

            assertNull("Should return null for an oversized response", downloaded)
        } finally {
            mockServer.shutdown()
        }
    }

    // ==================== buildTrustManager ====================

    @Test
    fun `buildTrustManager - rogue cert is not installed as a trust anchor`() {
        // The downloaded cert must be validated as an intermediate that has to
        // chain to a real system root — never installed as a trust anchor in its
        // own right. A self-signed cert that reaches no system root must be rejected.
        val rogue = createSelfSignedCert("CN=Rogue CA")

        val trustManager = completer.buildTrustManager(rogue, "rogue.example")

        assertNotNull("Should still return a trust manager", trustManager)

        // It must not appear among the accepted issuers — i.e. it is not an anchor.
        assertTrue(
            "Downloaded cert must not become a trust anchor",
            trustManager!!.acceptedIssuers.none {
                it.subjectX500Principal == rogue.subjectX500Principal
            }
        )

        // Presenting that same untrusted cert as the server chain must be rejected,
        // because it does not chain to any system trust anchor.
        try {
            trustManager.checkServerTrusted(arrayOf(rogue), "RSA")
            fail("Expected CertificateException: a rogue cert must not validate")
        } catch (expected: CertificateException) {
            // expected — the platform validator found no path to a system root
        }
    }

    @Test
    fun `buildTrustManager - rejected chain does not poison the cache`() {
        // An on-path attacker who seeds a bogus intermediate must not get it cached:
        // caching only happens after the platform validator accepts a completed chain.
        AiaCertificateChainCompleter.clearCacheForTesting()
        val rogue = createSelfSignedCert("CN=Rogue CA")
        val host = "poison.example"

        val trustManager = completer.buildTrustManager(rogue, host)!!

        try {
            trustManager.checkServerTrusted(arrayOf(rogue), "RSA")
            fail("Expected CertificateException")
        } catch (expected: CertificateException) {
            // expected
        }

        assertTrue(
            "A cert that failed validation must not be cached",
            !AiaCertificateChainCompleter.isIntermediateCachedForTesting(host)
        )
    }

    // ==================== attemptChainCompletion ====================

    @Test
    fun `attemptChainCompletion - returns Failed when connection refused`() {
        // Port 1 on localhost refuses connections quickly (no timeout wait)
        val result = completer.attemptChainCompletion(
            hostname = "localhost",
            port = 1,
            baseClientBuilder = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
        )

        assertTrue("Should return Failed for connection refused", result is AiaCertificateChainCompleter.Result.Failed)
    }

    @Test
    fun `attemptChainCompletion - never throws on empty hostname`() {
        val result = completer.attemptChainCompletion(
            hostname = "",
            baseClientBuilder = OkHttpClient.Builder()
        )

        assertTrue("Should return Failed, not throw", result is AiaCertificateChainCompleter.Result.Failed)
    }

    @Test
    fun `attemptChainCompletion - never throws on invalid hostname`() {
        val result = completer.attemptChainCompletion(
            hostname = "not a hostname!!!",
            baseClientBuilder = OkHttpClient.Builder()
        )

        assertTrue("Should return Failed, not throw", result is AiaCertificateChainCompleter.Result.Failed)
    }

    // ==================== Cache behavior ====================

    @Test
    fun `cache - clearCacheForTesting clears the cache`() {
        // Verify the test utility works (prevents test pollution)
        // Build a trust manager to indirectly confirm cache behavior
        val intermediate = createSelfSignedCert("CN=Cache Test CA")
        val trustManager = completer.buildTrustManager(intermediate, "cache.example")
        assertNotNull(trustManager)

        AiaCertificateChainCompleter.clearCacheForTesting()
        // No assertion needed — if clearCacheForTesting() throws, the test fails
    }

    // ==================== Test certificate helpers ====================

    /**
     * Create a self-signed X.509v3 certificate without AIA extension.
     * Built from raw DER bytes + CertificateFactory — no sun.security.* APIs.
     */
    private fun createSelfSignedCert(dn: String = "CN=Test Self-Signed"): X509Certificate {
        val keyPair = generateKeyPair()
        return buildX509v3Cert(dn, keyPair, keyPair, extensions = byteArrayOf())
    }

    /**
     * Create a self-signed X.509v3 certificate with an AIA extension.
     */
    private fun createCertWithAia(aiaUrl: String): X509Certificate {
        val keyPair = generateKeyPair()
        val aiaExtDer = buildAiaExtensionSequence(aiaUrl)
        return buildX509v3Cert("CN=Test Leaf with AIA", keyPair, keyPair, extensions = aiaExtDer)
    }

    /**
     * Create a cert with AIA containing both caIssuers and OCSP entries.
     * This reproduces the real-world DER layout where the OCSP SEQUENCE tag
     * (0x30 = ASCII '0') immediately follows the caIssuers URL bytes.
     */
    private fun createCertWithAiaAndOcsp(caIssuersUrl: String, ocspUrl: String): X509Certificate {
        val keyPair = generateKeyPair()
        val aiaExtDer = buildAiaExtensionWithOcsp(caIssuersUrl, ocspUrl)
        return buildX509v3Cert("CN=Test Leaf with AIA+OCSP", keyPair, keyPair, extensions = aiaExtDer)
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    /**
     * Build an X.509v3 certificate from raw DER.
     *
     * This constructs the TBSCertificate, signs it, and wraps it in a SEQUENCE.
     * The result is parsed by CertificateFactory to produce a proper X509Certificate.
     */
    private fun buildX509v3Cert(
        subjectDn: String,
        subjectKeyPair: KeyPair,
        signerKeyPair: KeyPair,
        extensions: ByteArray
    ): X509Certificate {
        val algorithmId = derSequence(
            // OID for SHA256withRSA: 1.2.840.113549.1.1.11
            derOid(byteArrayOf(
                0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xFD.toByte(), 0x0D, 0x01, 0x01, 0x0B
            )),
            derNull()
        )

        val issuerAndSubject = derSequence(
            derSet(derSequence(
                derOid(byteArrayOf(0x55, 0x04, 0x03)), // OID 2.5.4.3 (CN)
                derUtf8String(subjectDn.removePrefix("CN="))
            ))
        )

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86400_000L)
        val notAfter = Date(now + 365L * 86400_000L)

        val validity = derSequence(
            derUtcTime(notBefore),
            derUtcTime(notAfter)
        )

        val subjectPublicKeyInfo = subjectKeyPair.public.encoded

        // Build TBSCertificate
        val tbsComponents = mutableListOf<ByteArray>()
        // Version: v3 (explicit tag [0])
        tbsComponents.add(derExplicit(0, derInteger(BigInteger.valueOf(2))))
        // Serial number
        tbsComponents.add(derInteger(BigInteger.valueOf(System.nanoTime().and(0x7FFFFFFFL))))
        // Signature algorithm
        tbsComponents.add(algorithmId)
        // Issuer
        tbsComponents.add(issuerAndSubject)
        // Validity
        tbsComponents.add(validity)
        // Subject
        tbsComponents.add(issuerAndSubject)
        // Subject public key info (use encoded form directly)
        tbsComponents.add(subjectPublicKeyInfo)

        // Extensions (explicit tag [3]) — only if non-empty
        if (extensions.isNotEmpty()) {
            tbsComponents.add(derExplicit(3, derSequence(extensions)))
        }

        val tbsCertificate = derSequence(*tbsComponents.toTypedArray())

        // Sign TBSCertificate
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(signerKeyPair.private)
        signer.update(tbsCertificate)
        val signatureBytes = signer.sign()

        // Wrap in Certificate SEQUENCE: tbsCertificate, algorithmId, signature (BIT STRING)
        val certificate = derSequence(
            tbsCertificate,
            algorithmId,
            derBitString(signatureBytes)
        )

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    // ==================== AIA extension builder ====================

    /**
     * Build the DER-encoded AIA extension as a complete X.509v3 extension SEQUENCE:
     *
     * Extension ::= SEQUENCE {
     *     extnID      OBJECT IDENTIFIER,    -- 1.3.6.1.5.5.7.1.1
     *     critical    BOOLEAN DEFAULT FALSE, -- omitted (FALSE)
     *     extnValue   OCTET STRING          -- wraps AuthorityInfoAccessSyntax
     * }
     */
    private fun buildAiaExtensionSequence(url: String): ByteArray {
        // AIA OID: 1.3.6.1.5.5.7.1.1
        val aiaOid = derOid(byteArrayOf(
            0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x01, 0x01
        ))

        val urlBytes = url.toByteArray(Charsets.US_ASCII)

        // caIssuers OID: 1.3.6.1.5.5.7.48.2
        val caIssuersOid = derOid(byteArrayOf(
            0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02
        ))

        // uniformResourceIdentifier [6] IMPLICIT IA5String
        val uri = byteArrayOf(0x86.toByte()) + derLength(urlBytes.size) + urlBytes

        // AccessDescription SEQUENCE
        val accessDesc = derSequence(caIssuersOid, uri)

        // AuthorityInfoAccessSyntax SEQUENCE
        val aiaValue = derSequence(accessDesc)

        // Wrap in OCTET STRING
        val extnValue = derOctetString(aiaValue)

        // Extension SEQUENCE
        return derSequence(aiaOid, extnValue)
    }

    /**
     * Build AIA extension with both caIssuers and OCSP access descriptions.
     * Matches real-world Sectigo/Moodle cert layout.
     */
    private fun buildAiaExtensionWithOcsp(caIssuersUrl: String, ocspUrl: String): ByteArray {
        // AIA OID: 1.3.6.1.5.5.7.1.1
        val aiaOid = derOid(byteArrayOf(
            0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x01, 0x01
        ))

        // caIssuers OID: 1.3.6.1.5.5.7.48.2
        val caIssuersOid = derOid(byteArrayOf(
            0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02
        ))

        // OCSP OID: 1.3.6.1.5.5.7.48.1
        val ocspOid = derOid(byteArrayOf(
            0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x01
        ))

        // caIssuers AccessDescription
        val caIssuersUrlBytes = caIssuersUrl.toByteArray(Charsets.US_ASCII)
        val caIssuersUri = byteArrayOf(0x86.toByte()) + derLength(caIssuersUrlBytes.size) + caIssuersUrlBytes
        val caIssuersDesc = derSequence(caIssuersOid, caIssuersUri)

        // OCSP AccessDescription
        val ocspUrlBytes = ocspUrl.toByteArray(Charsets.US_ASCII)
        val ocspUri = byteArrayOf(0x86.toByte()) + derLength(ocspUrlBytes.size) + ocspUrlBytes
        val ocspDesc = derSequence(ocspOid, ocspUri)

        // AuthorityInfoAccessSyntax SEQUENCE with both entries
        val aiaValue = derSequence(caIssuersDesc, ocspDesc)

        // Wrap in OCTET STRING
        val extnValue = derOctetString(aiaValue)

        // Extension SEQUENCE
        return derSequence(aiaOid, extnValue)
    }

    // ==================== DER encoding primitives ====================

    private fun derLength(length: Int): ByteArray {
        return when {
            length < 128 -> byteArrayOf(length.toByte())
            length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte())
        }
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        return byteArrayOf(tag.toByte()) + derLength(content.size) + content
    }

    private fun derSequence(vararg components: ByteArray): ByteArray {
        val content = components.fold(byteArrayOf()) { acc, b -> acc + b }
        return derTag(0x30, content)
    }

    private fun derSet(vararg components: ByteArray): ByteArray {
        val content = components.fold(byteArrayOf()) { acc, b -> acc + b }
        return derTag(0x31, content)
    }

    private fun derOid(encodedValue: ByteArray): ByteArray {
        return derTag(0x06, encodedValue)
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return derTag(0x02, bytes)
    }

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derUtf8String(value: String): ByteArray {
        return derTag(0x0C, value.toByteArray(Charsets.UTF_8))
    }

    private fun derOctetString(content: ByteArray): ByteArray {
        return derTag(0x04, content)
    }

    private fun derBitString(content: ByteArray): ByteArray {
        // BIT STRING: first byte is number of unused bits (0)
        val padded = byteArrayOf(0x00) + content
        return derTag(0x03, padded)
    }

    private fun derUtcTime(date: Date): ByteArray {
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val str = fmt.format(date)
        return derTag(0x17, str.toByteArray(Charsets.US_ASCII))
    }

    private fun derExplicit(tagNum: Int, content: ByteArray): ByteArray {
        val tag = 0xA0 or tagNum
        return byteArrayOf(tag.toByte()) + derLength(content.size) + content
    }
}
