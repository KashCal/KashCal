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
        assertEquals("1", request.getHeader("Depth"))
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
