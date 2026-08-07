package org.onekash.kashcal.sync.carddav

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.contacts.MAX_PHOTO_SIZE_BYTES
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * MockWebServer exit-gate test for the CardDAV read-path client.
 *
 * Exercises the full discovery walk (well-known → principal → addressbook-home
 * → address book listing with version negotiation), change detection (ctag,
 * sync-collection changed+deleted, 410-invalid signal), the full-listing
 * fallback primitive, and addressbook-multiget body/etag extraction. Both the
 * request wire compliance (method, Depth, XML body) and response handling are
 * checked.
 *
 * The client is built with a plain OkHttpClient pointed at MockWebServer via the
 * pre-authenticated constructor, so this test does not depend on the DI factory.
 */
class CardDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpCardDavClient

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        server = MockWebServer()
        server.start()

        val serverUrl = server.url("/").toString()
        client = OkHttpCardDavClient(DefaultCardDavQuirks(serverUrl), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    private fun <T> assertSuccess(result: CalDavResult<T>): T {
        assertTrue("expected success, got $result", result is CalDavResult.Success)
        return (result as CalDavResult.Success).data
    }

    // ========== Discovery: well-known (RFC 6764) ==========

    @Test
    fun `discoverWellKnown targets well-known carddav with PROPFIND`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(principalBody("/p/alice/")))

        client.discoverWellKnown(server.url("/").toString())

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/.well-known/carddav", request.path)
    }

    // ========== Discovery: principal (RFC 5397) ==========

    @Test
    fun `discoverPrincipal resolves relative principal href against host`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(principalBody("/p/alice/")))

        val principal = assertSuccess(client.discoverPrincipal(server.url("/").toString()))

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        assertTrue(request.body.readUtf8().contains("current-user-principal"))
        assertTrue(principal.endsWith("/p/alice/"))
    }

    // ========== Discovery: addressbook-home-set (RFC 6352 §7.1.1) ==========

    @Test
    fun `discoverAddressBookHome requests addressbook-home-set and resolves hrefs`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(homeSetBody("/ab/alice/")))

        val homes = assertSuccess(client.discoverAddressBookHome(server.url("/p/alice/").toString()))

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertTrue(request.body.readUtf8().contains("addressbook-home-set"))
        assertEquals(1, homes.size)
        assertTrue(homes.single().endsWith("/ab/alice/"))
    }

    // ========== Address book listing + version negotiation (§6.2.2) ==========

    @Test
    fun `listAddressBooks negotiates 4_0 when server offers it`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                addressBooksBody(versions = listOf("3.0", "4.0"))
            )
        )

        val books = assertSuccess(client.listAddressBooks(server.url("/ab/alice/").toString()))

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("supported-address-data"))
        assertTrue(body.contains("addressbook-description"))
        assertEquals(1, books.size)
        assertEquals("Personal", books.single().displayName)
        assertEquals("4.0", books.single().vcardVersion)
        assertEquals("ctag-1", books.single().ctag)
    }

    @Test
    fun `listAddressBooks negotiates 3_0 when only 3_0 offered`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                addressBooksBody(versions = listOf("3.0"))
            )
        )

        val books = assertSuccess(client.listAddressBooks(server.url("/ab/alice/").toString()))
        assertEquals("3.0", books.single().vcardVersion)
    }

    // ========== Change detection: ctag ==========

    @Test
    fun `getCtag extracts collection tag`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                    <d:response>
                        <d:href>/ab/alice/</d:href>
                        <d:propstat><d:prop><cs:getctag>ctag-99</cs:getctag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                </d:multistatus>
                """.trimIndent()
            )
        )

        assertEquals("ctag-99", assertSuccess(client.getCtag(server.url("/ab/alice/").toString())))
    }

    // ========== sync-collection (RFC 6578) ==========

    @Test
    fun `syncCollection returns changed and deleted hrefs plus new token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(syncBody()))

        val report = assertSuccess(client.syncCollection(server.url("/ab/alice/").toString(), "old-token"))

        val request = server.takeRequest()
        assertEquals("REPORT", request.method)
        assertEquals("0", request.getHeader("Depth"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("sync-collection"))
        assertTrue(body.contains("old-token"))
        assertEquals("http://sabre.io/ns/sync/5", report.syncToken)
        assertEquals(listOf("/ab/alice/one.vcf"), report.changed.map { it.href })
        assertEquals("e1", report.changed.single().etag)
        assertEquals(listOf("/ab/alice/gone.vcf"), report.deleted)
    }

    @Test
    fun `syncCollection XML-escapes a sync-token containing entities`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(syncBody()))

        // A token the parser already XML-decoded (raw &, <) must be re-escaped
        // before interpolation, or the request XML is malformed and the server
        // 400s — breaking incremental sync.
        client.syncCollection(server.url("/ab/alice/").toString(), "sync?a=1&b=2<x>")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            "raw token must be escaped in the request body",
            body.contains("<d:sync-token>sync?a=1&amp;b=2&lt;x&gt;</d:sync-token>")
        )
        assertFalse("unescaped ampersand must not appear", body.contains("a=1&b=2"))
    }

    @Test
    fun `fetchContactsByHref XML-escapes an href containing an ampersand`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multigetBody()))

        client.fetchContactsByHref(
            server.url("/ab/alice/").toString(),
            listOf("/ab/alice/a&b.vcf"),
            "3.0"
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            "raw href ampersand must be escaped",
            body.contains("<d:href>/ab/alice/a&amp;b.vcf</d:href>")
        )
    }

    @Test
    fun `syncCollection maps 410 to a non-retryable invalid-token error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410).setBody("Gone"))

        val result = client.syncCollection(server.url("/ab/alice/").toString(), "stale")

        assertTrue(result is CalDavResult.Error)
        val error = result as CalDavResult.Error
        assertEquals(410, error.code)
        assertFalse("invalid sync token must not be retryable", error.isRetryable)
    }

    // ========== full-listing fallback primitive ==========

    @Test
    fun `listAllContactHrefs returns every member via PROPFIND Depth 1`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/ab/alice/</d:href>
                        <d:propstat><d:prop><d:getetag>"col"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                    <d:response>
                        <d:href>/ab/alice/a.vcf</d:href>
                        <d:propstat><d:prop><d:getetag>"ea"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                    <d:response>
                        <d:href>/ab/alice/b.vcf</d:href>
                        <d:propstat><d:prop><d:getetag>"eb"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                </d:multistatus>
                """.trimIndent()
            )
        )

        val hrefs = assertSuccess(client.listAllContactHrefs(server.url("/ab/alice/").toString()))

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        // Collection self-row (trailing slash) is dropped; only members returned.
        assertEquals(listOf("/ab/alice/a.vcf", "/ab/alice/b.vcf"), hrefs.map { it.first })
    }

    @Test
    fun `listAllContactHrefs surfaces an in-body 507 as a retryable error, not a partial list`() = runTest {
        // RFC 6578 §3.6: a server may truncate a large Depth:1 listing and mark it
        // with a 507 <status> on the collection <response> INSIDE an otherwise-207
        // multistatus. If the client returned Success with the partial member set,
        // the caller's orphan sweep would delete every contact truncated off the
        // page — the user's own synced contacts vanishing. The full-listing path
        // must honor the truncation flag exactly as the delta path does.
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response>
                        <d:href>/ab/alice/a.vcf</d:href>
                        <d:propstat><d:prop><d:getetag>"ea"</d:getetag></d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                    <d:response>
                        <d:href>/ab/alice/</d:href>
                        <d:status>HTTP/1.1 507 Insufficient Storage</d:status>
                    </d:response>
                </d:multistatus>
                """.trimIndent()
            )
        )

        val result = client.listAllContactHrefs(server.url("/ab/alice/").toString())

        assertTrue("a truncated listing must not be a Success", result is CalDavResult.Error)
        val error = result as CalDavResult.Error
        assertEquals(507, error.code)
        assertTrue("truncation is transient; the next run must retry", error.isRetryable)
    }

    // ========== addressbook-multiget (§8.7 / §10.4) ==========

    @Test
    fun `fetchContactsByHref requests versioned address-data and returns bodies with etags`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multigetBody()))

        val contacts = assertSuccess(
            client.fetchContactsByHref(
                server.url("/ab/alice/").toString(),
                listOf("/ab/alice/a.vcf"),
                "4.0"
            )
        )

        val request = server.takeRequest()
        assertEquals("REPORT", request.method)
        // RFC 6352 §8.7: addressbook-multiget names its target resources by href in
        // the body, so the request MUST carry Depth: 0. A strict server/proxy can
        // reject the whole batch fetch if it sees Depth: 1.
        assertEquals("0", request.getHeader("Depth"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("addressbook-multiget"))
        assertTrue("multiget must request the negotiated version", body.contains("version=\"4.0\""))
        assertTrue(body.contains("<d:href>/ab/alice/a.vcf</d:href>"))

        assertEquals(1, contacts.size)
        assertEquals("/ab/alice/a.vcf", contacts.single().href)
        assertEquals("ea", contacts.single().etag)
        assertTrue(contacts.single().vcardBody.contains("BEGIN:VCARD"))
        assertTrue(contacts.single().vcardBody.contains("FN:Alice Example"))
    }

    @Test
    fun `fetchContactsByHref drops the collection self-href before the multiget`() = runTest {
        // iCloud's sync-collection REPORT returns the collection self-href WITHOUT a
        // trailing slash and with no resourcetype, so the shared parser's self-row
        // filter misses it. iCloud then 400s the WHOLE multiget if a non-contact
        // collection href is included, so the client must drop any href that
        // resolves to the collection itself before building the request body.
        server.enqueue(MockResponse().setResponseCode(207).setBody(multigetBody()))

        val abUrl = server.url("/ab/alice/").toString()
        val contacts = assertSuccess(
            client.fetchContactsByHref(
                abUrl,
                // self-href in both shapes (no slash, with slash) plus a real member.
                listOf("/ab/alice", "/ab/alice/", "/ab/alice/a.vcf"),
                "4.0"
            )
        )

        val body = server.takeRequest().body.readUtf8()
        assertFalse("self-href (no slash) must not reach the multiget", body.contains("<d:href>/ab/alice</d:href>"))
        assertFalse("self-href (with slash) must not reach the multiget", body.contains("<d:href>/ab/alice/</d:href>"))
        assertTrue("the real member href must remain", body.contains("<d:href>/ab/alice/a.vcf</d:href>"))
        assertEquals(1, contacts.size)
    }

    @Test
    fun `fetchContactsByHref short-circuits when only the self-href is given`() = runTest {
        // After dropping the self-href nothing remains; must not fire an empty multiget.
        val contacts = assertSuccess(
            client.fetchContactsByHref(server.url("/ab/alice/").toString(), listOf("/ab/alice/"), "3.0")
        )
        assertTrue(contacts.isEmpty())
        assertEquals("no round-trip when only the self-href was supplied", 0, server.requestCount)
    }

    @Test
    fun `fetchContactsByHref short-circuits empty hrefs without a request`() = runTest {
        val contacts = assertSuccess(
            client.fetchContactsByHref(server.url("/ab/alice/").toString(), emptyList(), "3.0")
        )
        assertTrue(contacts.isEmpty())
        assertEquals("no network round-trip for empty hrefs", 0, server.requestCount)
    }

    // ========== Cross-host partition home-set (iCloud pNN-contacts.icloud.com) ==========

    /**
     * A single MockWebServer cannot reproduce iCloud's partition redirect, so the
     * base-host derivation is exercised directly: when the home-set lives on a
     * partition host, a relative address book href must resolve against THAT host
     * (not the account root the client was constructed with). This is why
     * [OkHttpCardDavClient.listAddressBooks] derives its base host from the home
     * URL, not the server root.
     */
    @Test
    fun `address book href resolves against the partition home host`() {
        val quirks = DefaultCardDavQuirks("https://contacts.example.test")
        // baseHost is the home URL's scheme+authority (what the client derives via
        // extractBaseHost), NOT the account root the quirks was constructed with.
        val partitionHost = "https://p42-contacts.example.test"

        val resolved = quirks.buildAddressBookUrl("/123/carddavhome/card/", partitionHost)

        assertEquals("https://p42-contacts.example.test/123/carddavhome/card/", resolved)
    }

    @Test
    fun `absolute address book href on partition host is preserved verbatim`() {
        val quirks = DefaultCardDavQuirks("https://contacts.example.test")
        val absolute = "https://p42-contacts.example.test/123/carddavhome/card/"

        assertEquals(absolute, quirks.buildAddressBookUrl(absolute, "https://p42-contacts.example.test"))
    }

    // ========== photo fetch (authenticated GET, same-domain only) ==========

    @Test
    fun `fetchPhoto returns bytes and content type on a 200 image`() = runTest {
        // A body that is NOT valid UTF-8 (JPEG magic + a lone continuation byte):
        // proves the fetch reads binary, not a charset-decoded String.
        val raw = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x80.toByte(), 0x00, 0x41)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/jpeg")
                .setBody(okio.Buffer().write(raw))
        )

        val photo = assertSuccess(client.fetchPhoto(server.url("/photo/1.jpg").toString()))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        org.junit.Assert.assertArrayEquals(raw, photo.bytes)
        assertTrue(photo.contentType.startsWith("image/jpeg"))
    }

    @Test
    fun `fetchPhoto maps 401 to a retryable auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue(result is CalDavResult.Error)
        assertTrue("401 must surface as an auth error", (result as CalDavResult.Error).isAuthError())
        // A genuine credential rotation fails the CardDAV re-read (same account creds)
        // BEFORE the photo GET is ever reached, so a 401 seen here means the account
        // creds are still valid for the collection but the photo gateway rejected this
        // one hop — a transient condition. Retryable so the contact stays pending
        // rather than clearing the flag and permanently losing the photo (the gateway
        // URL is stable, so a cleared flag would never self-re-arm for URL photos).
        assertTrue(
            "a photo-gateway 401 must be retryable, not a permanent give-up",
            result.isRetryable
        )
    }

    @Test
    fun `fetchPhoto marks a 429 rate-limit as retryable`() = runTest {
        // 429 Too Many Requests is transient by definition (RFC 6585): the server is
        // throttling, not refusing forever. A first-sync burst of photo GETs against a
        // gateway can hit it. The photo path issues a bare GET (no executeWithRetry /
        // Retry-After backoff), so classification is the only thing that keeps the
        // contact pending for a later, unthrottled sync.
        server.enqueue(MockResponse().setResponseCode(429))

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("a 429 must surface as an error", result is CalDavResult.Error)
        assertTrue(
            "a 429 rate-limit must be retryable, not permanently abandoned",
            (result as CalDavResult.Error).isRetryable
        )
    }

    @Test
    fun `fetchPhoto marks a 408 request-timeout as retryable`() = runTest {
        // 408 Request Timeout (RFC 7231) is transient — the server timed out waiting,
        // an identical GET plausibly succeeds next sync. Retryable, like a 5xx/429.
        server.enqueue(MockResponse().setResponseCode(408))

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("a 408 must surface as an error", result is CalDavResult.Error)
        assertTrue(
            "a 408 request-timeout must be retryable, not permanently abandoned",
            (result as CalDavResult.Error).isRetryable
        )
    }

    @Test
    fun `fetchPhoto rejects a non-image content type without returning bytes`() = runTest {
        // A server that returns an HTML error page with 200 must not be treated as
        // an image blob (would write garbage into the Photo row).
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html>not a photo</html>")
        )

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("a non-image 200 must be an error, not a blob", result is CalDavResult.Error)
        // The server authoritatively serves non-image content for this URL; the
        // identical GET yields the same wrong type, so retrying can never succeed.
        // Non-retryable so the fetcher gives up rather than looping every sync.
        assertFalse(
            "a non-image content type is permanent, not retryable",
            (result as CalDavResult.Error).isRetryable
        )
    }

    @Test
    fun `fetchPhoto marks a 5xx as retryable`() = runTest {
        // A 5xx is a transient server-side condition (overloaded / down / gateway
        // hiccup); the identical GET plausibly succeeds on a later sync. It must be
        // retryable so the fetcher leaves the contact pending rather than clearing
        // the flag and giving up forever.
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("a 5xx must surface as an error", result is CalDavResult.Error)
        assertTrue(
            "a 5xx photo fetch must be retryable, not permanently abandoned",
            (result as CalDavResult.Error).isRetryable
        )
    }

    @Test
    fun `fetchPhoto marks an unexpected 4xx (not 401 or 404) as permanent`() = runTest {
        // A 403/410/etc. is an authoritative client-side refusal for this URL:
        // retrying the identical request yields the same status, so it is permanent
        // (the fetcher clears the flag; a later vCard change re-arms it).
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("a 403 must surface as an error", result is CalDavResult.Error)
        assertFalse(
            "an unexpected 4xx is permanent, not retryable",
            (result as CalDavResult.Error).isRetryable
        )
    }

    @Test
    fun `fetchPhoto rejects a body over the photo byte cap`() = runTest {
        // Content-Length over the cap trips the cheap header guard before buffering.
        val tooBig = "x".repeat((MAX_PHOTO_SIZE_BYTES + 1).toInt())
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody(tooBig)
        )

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("over-cap body must surface as an error", result is CalDavResult.Error)
        // The body is simply too big — re-downloading it will hit the same cap
        // forever and it could never be written to the Contacts blob. Non-retryable
        // so the fetcher gives up and clears the pending flag.
        assertFalse(
            "an over-cap body must be non-retryable, not looped forever",
            (result as CalDavResult.Error).isRetryable
        )
    }

    @Test
    fun `the photo byte cap stays under the Binder transaction ceiling`() = runTest {
        // The fetched blob is written inside an applyBatch transaction that crosses
        // Binder, whose ceiling is ~1 MB (1024 * 1024). A body near or over that trips
        // TransactionTooLargeException and fails the whole write batch — the provider
        // downscales large photos, but only AFTER receiving the bytes over Binder, so
        // it can't rescue an oversized transaction. This pins the cap under the ceiling
        // so a future bump can't silently reintroduce the crash.
        assertTrue(
            "MAX_PHOTO_SIZE_BYTES ($MAX_PHOTO_SIZE_BYTES) must stay under the ~1MB Binder limit",
            MAX_PHOTO_SIZE_BYTES < 1024L * 1024,
        )
    }

    @Test
    fun `fetchPhoto does not follow a redirect (host revalidation is bypassed otherwise)`() = runTest {
        // A photo GET must NOT follow redirects: the shared client re-attaches
        // preemptive Basic auth on every network request, and OkHttp strips the
        // Authorization header only on a cross-host hop — so a same-host photo URL
        // that 302-redirects to a foreign host would leak the account credentials
        // there. The initial-host guard can't see the redirect target, so the GET
        // itself must refuse to follow. Real gateways serve the image 200 directly.
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/photo/elsewhere.jpg")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody("REDIRECTED")
        )

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("a redirected photo must surface as an error, not be followed", result is CalDavResult.Error)
        assertEquals("the redirect target must never be requested", 1, server.requestCount)
    }

    @Test
    fun `fetchPhoto rejects an empty body without clearing the pending flag`() = runTest {
        // A 200 with an image content type but zero bytes must be an error, not a
        // Success carrying ByteArray(0): a Success writes an empty Photo blob AND
        // clears the pending flag, permanently pinning the contact to a blank photo.
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody("")
        )

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("an empty image body must surface as an error", result is CalDavResult.Error)
        // A 0-byte 200 reads as a transient truncation/glitch, not an authoritative
        // "no photo here" — so it is retryable and the contact stays pending. Pairs
        // with this test's name: the flag must NOT be cleared on an empty body.
        assertTrue(
            "an empty image body must be retryable so the pending flag is not cleared",
            (result as CalDavResult.Error).isRetryable
        )
    }

    @Test
    fun `fetchPhoto rejects an SVG image type (vector XML, not a raster blob)`() = runTest {
        // image/svg+xml passes a naive "image/" prefix check but is an XML document,
        // not the raster the Contacts Photo column expects. Refuse it.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/svg+xml")
                .setBody("<svg xmlns='http://www.w3.org/2000/svg'/>")
        )

        val result = client.fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("an SVG response must surface as an error", result is CalDavResult.Error)
    }

    // ---------- credential-leak guard (pure function, real hostnames) ----------
    //
    // A photo URL is server-controlled and the shared client bakes in preemptive
    // Basic + a DigestAuthenticator, so a GET to a foreign host would harvest the
    // account credentials. The guard permits credentials only when the photo URL
    // shares the CardDAV endpoint's registrable domain. MockWebServer is always
    // loopback (a single host), so the cross-domain branch can only be exercised
    // by testing the pure decision directly with real hostnames.
    //
    // The registrable-domain resolver is INJECTED here: on the okhttp-android
    // artifact this app resolves, the production HttpUrl.topPrivateDomain() loads
    // its public-suffix list from an Android asset via app-startup, which is not
    // present in a JVM unit-test worker (the call throws there). The fake below
    // reproduces the public-suffix + 1 classification for exactly the hosts under
    // test, so these cases exercise the guard's composition logic (host-equality
    // shortcut, null-refuse, same/different-domain comparison) faithfully. The
    // production resolver ([DefaultRegistrableDomainResolver]) is covered
    // separately by its fail-closed contract test below.
    private val fakeRegistrableDomain: RegistrableDomainResolver = { url ->
        when (url.host.lowercase()) {
            "p52-contacts.icloud.com", "gateway.icloud.com" -> "icloud.com"
            "evil-icloud.com" -> "evil-icloud.com"
            "icloud.com.attacker.example" -> "attacker.example"
            "dav.example.test" -> "example.test"
            "malicious.example" -> "malicious.example"
            else -> null
        }
    }

    @Test
    fun `same registrable domain is permitted (iCloud gateway vs partition host)`() {
        // iCloud serves photos from gateway.icloud.com while the CardDAV endpoint
        // lives on pNN-contacts.icloud.com — different hosts, same registrable domain.
        assertTrue(
            shouldAttachCredentials(
                endpointUrl = "https://p52-contacts.icloud.com/123/carddavhome/card/",
                photoUrl = "https://gateway.icloud.com/aaa/bbb/photo.jpg",
                registrableDomainOf = fakeRegistrableDomain,
            )
        )
    }

    @Test
    fun `identical host is permitted`() {
        // Exact-host match short-circuits before the resolver — so it holds even
        // if the public-suffix list were unavailable (resolver returns null).
        assertTrue(
            shouldAttachCredentials(
                endpointUrl = "https://dav.example.test/ab/alice/",
                photoUrl = "https://dav.example.test/ab/alice/photo.jpg",
                registrableDomainOf = { null },
            )
        )
    }

    @Test
    fun `a look-alike sibling domain is refused`() {
        // endsWith("icloud.com") would wrongly allow evil-icloud.com; the
        // registrable-domain check refuses it.
        assertFalse(
            shouldAttachCredentials(
                endpointUrl = "https://p52-contacts.icloud.com/123/carddavhome/card/",
                photoUrl = "https://evil-icloud.com/harvest",
                registrableDomainOf = fakeRegistrableDomain,
            )
        )
    }

    @Test
    fun `a subdomain-suffix trick is refused`() {
        // icloud.com.attacker.example ends with neither the host nor the registrable
        // domain of the endpoint; refuse.
        assertFalse(
            shouldAttachCredentials(
                endpointUrl = "https://p52-contacts.icloud.com/123/carddavhome/card/",
                photoUrl = "https://icloud.com.attacker.example/harvest",
                registrableDomainOf = fakeRegistrableDomain,
            )
        )
    }

    @Test
    fun `an unrelated foreign host is refused`() {
        assertFalse(
            shouldAttachCredentials(
                endpointUrl = "https://dav.example.test/ab/alice/",
                photoUrl = "https://malicious.example/harvest",
                registrableDomainOf = fakeRegistrableDomain,
            )
        )
    }

    @Test
    fun `a malformed photo url is refused`() {
        assertFalse(
            shouldAttachCredentials(
                endpointUrl = "https://dav.example.test/ab/alice/",
                photoUrl = "not a url",
                registrableDomainOf = fakeRegistrableDomain,
            )
        )
    }

    @Test
    fun `guard fails closed to exact-host-only when the public-suffix list is unavailable`() {
        // Simulates the resolver never producing a registrable domain (e.g. the
        // public-suffix asset failed to load): a cross-host fetch is refused rather
        // than crashing, while an identical-host fetch still succeeds. This is the
        // contract DefaultRegistrableDomainResolver honors by mapping its load
        // failure to null.
        val unavailable: RegistrableDomainResolver = { null }
        assertFalse(
            "cross-host must refuse when no registrable domain is resolvable",
            shouldAttachCredentials(
                endpointUrl = "https://p52-contacts.icloud.com/123/carddavhome/card/",
                photoUrl = "https://gateway.icloud.com/aaa/bbb/photo.jpg",
                registrableDomainOf = unavailable,
            )
        )
        assertTrue(
            "identical host must still be permitted with no registrable domain",
            shouldAttachCredentials(
                endpointUrl = "https://gateway.icloud.com/x/",
                photoUrl = "https://gateway.icloud.com/y/",
                registrableDomainOf = unavailable,
            )
        )
    }

    @Test
    fun `default resolver fails closed instead of throwing when the suffix list is absent`() {
        // In this JVM test worker the okhttp-android public-suffix asset is not
        // loaded, so topPrivateDomain() throws. DefaultRegistrableDomainResolver
        // must swallow that and return null (fail closed), never propagate — this
        // is what keeps fetchPhoto from crashing the sync pass on a list failure.
        val resolved = DefaultRegistrableDomainResolver(
            okhttp3.HttpUrl.Builder().scheme("https").host("gateway.icloud.com").build()
        )
        org.junit.Assert.assertNull("must fail closed to null, not throw", resolved)
    }

    @Test
    fun `an https endpoint refuses to send credentials over an http photo (same domain)`() {
        // Same registrable domain AND same host, but the photo is cleartext http.
        // Sending the secure endpoint's Basic/Digest credentials over http would
        // expose them to a passive MITM; refuse the downgrade.
        assertFalse(
            "https -> http credential downgrade must be refused even same-host",
            shouldAttachCredentials(
                endpointUrl = "https://dav.example.test/ab/alice/",
                photoUrl = "http://dav.example.test/ab/alice/photo.jpg",
                registrableDomainOf = fakeRegistrableDomain,
            )
        )
    }

    @Test
    fun `an http endpoint may fetch an http photo (nothing is downgraded)`() {
        // A genuinely-http endpoint (local test server) fetching an http photo is
        // not a downgrade — the credentials were never on a secure channel — so the
        // same-host rule still permits it.
        assertTrue(
            "http -> http on the same host is permitted (no downgrade)",
            shouldAttachCredentials(
                endpointUrl = "http://dav.example.test/ab/alice/",
                photoUrl = "http://dav.example.test/ab/alice/photo.jpg",
                registrableDomainOf = fakeRegistrableDomain,
            )
        )
    }

    // ========== fixtures ==========

    private fun principalBody(href: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/</d:href>
                <d:propstat><d:prop>
                    <d:current-user-principal><d:href>$href</d:href></d:current-user-principal>
                </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun homeSetBody(href: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
            <d:response>
                <d:href>/p/alice/</d:href>
                <d:propstat><d:prop>
                    <card:addressbook-home-set><d:href>$href</d:href></card:addressbook-home-set>
                </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()

    // Flush-left (no trimIndent): the interpolated version rows carry their own
    // newlines at column 0, which would defeat trimIndent's common-indent
    // calculation and leave the <?xml declaration indented (malformed XML).
    private fun addressBooksBody(versions: List<String>): String {
        val types = versions.joinToString("\n") {
            "<card:address-data-type content-type=\"text/vcard\" version=\"$it\"/>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\" " +
            "xmlns:cs=\"http://calendarserver.org/ns/\">\n" +
            "<d:response>\n" +
            "<d:href>/ab/alice/default/</d:href>\n" +
            "<d:propstat><d:prop>\n" +
            "<d:displayname>Personal</d:displayname>\n" +
            "<d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>\n" +
            "<card:addressbook-description>My contacts</card:addressbook-description>\n" +
            "<cs:getctag>ctag-1</cs:getctag>\n" +
            "<card:supported-address-data>\n" +
            types + "\n" +
            "</card:supported-address-data>\n" +
            "<d:current-user-privilege-set>\n" +
            "<d:privilege><d:read/></d:privilege>\n" +
            "<d:privilege><d:write/></d:privilege>\n" +
            "</d:current-user-privilege-set>\n" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>\n" +
            "</d:response>\n" +
            "</d:multistatus>\n"
    }

    private fun syncBody() = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/ab/alice/one.vcf</d:href>
                <d:propstat><d:prop><d:getetag>"e1"</d:getetag></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response>
            <d:response>
                <d:href>/ab/alice/gone.vcf</d:href>
                <d:status>HTTP/1.1 404 Not Found</d:status>
            </d:response>
            <d:sync-token>http://sabre.io/ns/sync/5</d:sync-token>
        </d:multistatus>
    """.trimIndent()

    // Flush-left (no trimIndent): the vCard body carries real newlines with no
    // structural indentation, so the whole document sits at the margin.
    private fun multigetBody() =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
            "<d:response>\n" +
            "<d:href>/ab/alice/a.vcf</d:href>\n" +
            "<d:propstat>\n" +
            "<d:prop>\n" +
            "<d:getetag>\"ea\"</d:getetag>\n" +
            "<card:address-data>BEGIN:VCARD\n" +
            "VERSION:4.0\n" +
            "UID:alice-1\n" +
            "FN:Alice Example\n" +
            "END:VCARD</card:address-data>\n" +
            "</d:prop>\n" +
            "<d:status>HTTP/1.1 200 OK</d:status>\n" +
            "</d:propstat>\n" +
            "</d:response>\n" +
            "</d:multistatus>\n"
}
