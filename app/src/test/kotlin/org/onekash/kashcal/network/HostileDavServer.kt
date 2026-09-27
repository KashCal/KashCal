package org.onekash.kashcal.network

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress

/**
 * Replies a misbehaving or hostile network hands a DAV client: redirects of every
 * kind, a hotspot login page, a garbled multistatus, a truncated listing, a Digest
 * challenge. Plus an https MockWebServer the test client trusts, and a plain http
 * one, so the https/http rules can be exercised on the wire.
 */
object HostileDavServer {

    private val localhostCertificate: HeldCertificate by lazy {
        HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
    }

    private val serverCertificates: HandshakeCertificates by lazy {
        HandshakeCertificates.Builder().heldCertificate(localhostCertificate).build()
    }

    private val clientCertificates: HandshakeCertificates by lazy {
        HandshakeCertificates.Builder().addTrustedCertificate(localhostCertificate.certificate).build()
    }

    /** An https server on "localhost" whose certificate [trusting] clients accept. */
    fun httpsServer(): MockWebServer = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), tunnelProxy = false)
        start(InetAddress.getByName("localhost"), 0)
    }

    /** A plain http server on "localhost". */
    fun httpServer(): MockWebServer = MockWebServer().apply {
        start(InetAddress.getByName("localhost"), 0)
    }

    /** Makes [builder] trust [httpsServer]'s certificate. */
    fun trusting(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)

    /**
     * Returns a client wired the way the DAV factories wire theirs: the transport guard and
     * Basic auth only where credentials may go, but no Digest authenticator. It trusts
     * [httpsServer].
     */
    fun davClient(allowCleartext: Boolean, username: String = "user", password: String = "secret"): OkHttpClient =
        trusting(OkHttpClient.Builder())
            .installDavTransport(allowCleartext)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val out = if (request.header("Authorization") == null &&
                    DavTransportGuard.mayAttachCredentials(request.url, allowCleartext)
                ) {
                    request.newBuilder()
                        .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                        .build()
                } else {
                    request
                }
                chain.proceed(out)
            }
            .build()

    fun redirect(code: Int, location: String): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Location", location)

    /** What a hotspot or single-sign-on proxy answers to anything: a web page, with 200. */
    fun loginPage(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody("<!DOCTYPE html><html><head><title>Sign in</title></head><body><form>Wi-Fi login</form></body></html>")

    /** A 207 that stops half way through (a proxy cut the connection, a broken server). */
    fun garbledMultistatus(): MockResponse = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/cal/a.ics</d:hr""")

    /** A well-formed 207 listing whose body is [responses] (the `<d:response>` elements). */
    fun multistatus(responses: String): MockResponse = MockResponse()
        .setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">$responses</d:multistatus>""")

    /** One member of a listing: [href] with [etag]. */
    fun member(href: String, etag: String): String =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>"$etag"</d:getetag></d:prop>""" +
            """<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    /**
     * A listing the server cut short (RFC 6578 section 3.6 / RFC 4791 section 7.8):
     * the request-URI answers 507 with DAV:number-of-matches-within-limits, next
     * to the members that did fit.
     */
    fun truncatedListing(requestHref: String, members: String): MockResponse = multistatus(
        members +
            """<d:response><d:href>$requestHref</d:href><d:status>HTTP/1.1 507 Insufficient Storage</d:status>""" +
            """<d:error><d:number-of-matches-within-limits/></d:error></d:response>"""
    )

    fun digestChallenge(): MockResponse = MockResponse()
        .setResponseCode(401)
        .setHeader("WWW-Authenticate", """Digest realm="dav", nonce="abc123", qop="auth", algorithm=MD5""")
}
