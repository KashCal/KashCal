package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests [OkHttpCalDavClient.syncCollection] against RFC 6578 (sync-collection REPORT).
 *
 * Covers:
 * - §3.2: request format, Depth 0, changed and removed members, the valid-sync-token
 *   precondition
 * - §3.3: the sync-level element
 * - §3.4 and §3.5: the sync-token on an initial and a later sync
 * - §3.6: truncated results (507 Insufficient Storage)
 * - how an invalid or expired sync-token is reported
 *
 * Each test checks the outgoing request (method, headers, XML body) or how the multistatus
 * reply is parsed.
 */
class OkHttpCalDavClientRfc6578SyncTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockWebServer = MockWebServer()
        mockWebServer.start()

        val serverUrl = mockWebServer.url("/").toString()
        val credentials = Credentials(
            username = "testuser",
            password = "testpass",
            serverUrl = serverUrl
        )
        val factory = OkHttpCalDavClientFactory()
        client = factory.createClient(credentials, DefaultQuirks(serverUrl)) as OkHttpCalDavClient
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    // ========== sync-collection request format (RFC 6578 §3.2) ==========

    @Test
    fun `syncCollection sends REPORT method`() = runTest {
        // RFC 6578 §3.2: sync-collection is a REPORT
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        assertEquals("RFC 6578 requires REPORT method for sync-collection", "REPORT", request.method)
    }

    @Test
    fun `syncCollection sends Depth 0 header per RFC 6578 section 3 point 2`() = runTest {
        // RFC 6578 §3.2: "This report is only defined when the Depth header has value '0';
        // other values result in a 400 (Bad Request) error response." The body's
        // <sync-level>1</sync-level> element requests one-level traversal; the Depth header
        // must be 0.
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        assertEquals("RFC 6578 §3.2 requires Depth: 0", "0", request.getHeader("Depth"))
    }

    @Test
    fun `syncCollection sends Content-Type application xml`() = runTest {
        // The request body is XML
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        val contentType = request.getHeader("Content-Type")
        assertNotNull("Content-Type header must be present", contentType)
        assertTrue(
            "Content-Type must be application/xml",
            contentType!!.contains("application/xml")
        )
    }

    @Test
    fun `syncCollection uses sync-collection element in DAV namespace`() = runTest {
        // RFC 6578 §3.2: the request body MUST be a DAV:sync-collection element
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Request must use sync-collection root element",
            body.contains("sync-collection")
        )
        assertTrue(
            "Request must include DAV namespace",
            body.contains("DAV:")
        )
    }

    @Test
    fun `syncCollection includes sync-level element with value 1`() = runTest {
        // RFC 6578 §3.3: the client MUST include sync-level; "1" means immediate members
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Request must include sync-level element",
            body.contains("sync-level")
        )
        assertTrue(
            "sync-level must have value 1",
            body.contains(">1</")
        )
    }

    @Test
    fun `syncCollection requests getetag property only`() = runTest {
        // RFC 6578 §3.2: the request names the properties to return with each change. The
        // client asks only for getetag and fetches event data by multiget later.
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request getetag property", body.contains("getetag"))
    }

    @Test
    fun `syncCollection does not request calendar-data in prop`() = runTest {
        // To save bandwidth the sync-collection returns only hrefs and etags; event data
        // comes from a calendar-multiget (RFC 4791 §7.9).
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertFalse(
            "Must NOT request calendar-data (fetched via multiget later)",
            body.contains("calendar-data")
        )
    }

    // ========== sync-token in the request (RFC 6578 §3.4, §3.5) ==========

    @Test
    fun `syncCollection sends empty sync-token element for initial sync`() = runTest {
        // RFC 6578 §3.4: an initial sync sends an empty sync-token element, and the server
        // returns every member
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_1))

        client.syncCollection(calendarUrl(), null)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Initial sync must include empty sync-token element",
            body.contains("<d:sync-token/>")
        )
    }

    @Test
    fun `syncCollection sends previous sync-token value for subsequent sync`() = runTest {
        // RFC 6578 §3.5: a later sync sends the token from the previous response
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Subsequent sync must include previous sync-token value",
            body.contains("<d:sync-token>$SYNC_TOKEN_1</d:sync-token>")
        )
    }

    @Test
    fun `syncCollection preserves full sync-token URL in request`() = runTest {
        // RFC 6578 §3.2: a sync-token is an opaque URI, so it is sent back unchanged
        val fullTokenUrl = "http://sabre.io/ns/sync/63845d9c3a7b9"
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), fullTokenUrl)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(
            "Full token URL must be preserved in request body",
            body.contains(fullTokenUrl)
        )
    }

    @Test
    fun `syncCollection XML-escapes a sync-token containing entities`() = runTest {
        // The parser XML-decodes the server's sync-token on the way in, so a token carrying
        // a literal &, < or > must be re-escaped before interpolation. Otherwise the request
        // XML is malformed, the server 400s, and delta sync stays stuck on that token forever.
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), "sync?a=1&b=2<x>")

        val body = mockWebServer.takeRequest().body.readUtf8()
        assertTrue(
            "raw token must be escaped in the request body",
            body.contains("<d:sync-token>sync?a=1&amp;b=2&lt;x&gt;</d:sync-token>")
        )
        assertFalse("unescaped ampersand must not appear", body.contains("a=1&b=2"))
    }

    // ========== Changed members (RFC 6578 §3.2) ==========

    @Test
    fun `syncCollection parses changed items with href and etag from 200 propstat`() = runTest {
        // RFC 6578 §3.2: a new or changed member has a propstat, here 200 OK with getetag
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithChanges())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Should parse 2 changed items", 2, report.changed.size)
        assertEquals("/calendars/testuser/personal/event1.ics", report.changed[0].href)
        assertEquals("etag-v2", report.changed[0].etag)
        assertEquals("/calendars/testuser/personal/event4.ics", report.changed[1].href)
        assertEquals("new-event-etag", report.changed[1].etag)
    }

    @Test
    fun `syncCollection parses new sync-token from response`() = runTest {
        // RFC 6578 §3.2: the multistatus MUST contain a new sync-token
        val expectedToken = "http://example.com/ns/sync/token-after-changes"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithToken(expectedToken))
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals(
            "New sync-token must be extracted from response",
            expectedToken,
            report.syncToken
        )
    }

    @Test
    fun `syncCollection returns empty report for empty multistatus`() = runTest {
        // No changes since the last sync: a multistatus with only a new token
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertTrue("No changes should mean empty changed list", report.changed.isEmpty())
        assertTrue("No changes should mean empty deleted list", report.deleted.isEmpty())
        assertNotNull("New sync-token should still be returned", report.syncToken)
    }

    @Test
    fun `syncCollection handles multiple changed items`() = runTest {
        // The response can list many changed members
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithChanges())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Should parse all changed items", 2, report.changed.size)
    }

    @Test
    fun `syncCollection normalizes quoted etag values`() = runTest {
        // RFC 7232 §2.3: an ETag is quoted; the client strips the quotes
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithChanges())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        // The XML carries "etag-v2" quoted; the parser strips the quotes
        val etag = report.changed[0].etag
        assertFalse(
            "ETag should be normalized (quotes stripped)",
            etag?.startsWith("\"") == true
        )
    }

    @Test
    fun `syncCollection skips collection self-row identified by trailing slash`() = runTest {
        // The main signal is href.endsWith("/") (RFC 4918 §5.2 SHOULD). The request doesn't
        // ask for resourcetype, because iCloud answers that with a separate propstat-404 per
        // member resource and the response grows past the read timeout.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithCollectionHref())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Collection self-row should be filtered out", 1, report.changed.size)
        assertEquals(
            "Only the member resource should remain",
            "/calendars/testuser/personal/event1.ics",
            report.changed[0].href
        )
    }

    @Test
    fun `syncCollection uses resourcetype fallback for slashless self-row`() = runTest {
        // Fallback for a server that drops the trailing slash on the collection's own row but
        // volunteers <resourcetype><collection/></...> unprompted. Tests the fallback alone,
        // so removing the resourcetype tracking in ResponseState fails this test.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseSlashlessSelfRowWithResourcetype())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals(
            "Slashless self-row identified via resourcetype fallback must be filtered",
            1, report.changed.size
        )
        assertEquals(
            "/calendars/testuser/personal/event1.ics",
            report.changed[0].href
        )
    }

    // ========== Removed members (RFC 6578 §3.2) ==========

    @Test
    fun `syncCollection identifies deleted items by 404 status at response level`() = runTest {
        // RFC 6578 §3.2: a removed member has a 404 status directly in the response, with no
        // propstat. Nextcloud and Sabre send this shape.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithResponseLevel404())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Should identify 1 deleted item", 1, report.deleted.size)
        assertEquals(
            "/calendars/testuser/personal/deleted-event.ics",
            report.deleted[0]
        )
    }

    @Test
    fun `syncCollection identifies deleted items by 404 status in propstat`() = runTest {
        // Some servers wrap the 404 in a propstat instead, which RFC 6578 §3.2 doesn't allow
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithPropstat404())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Should identify 1 deleted item from propstat 404", 1, report.deleted.size)
        assertEquals(
            "/calendars/testuser/personal/removed.ics",
            report.deleted[0]
        )
    }

    @Test
    fun `syncCollection separates changed and deleted items in same response`() = runTest {
        // One response can mix changed and removed members
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseMixed())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Should have 2 changed items", 2, report.changed.size)
        assertEquals("Should have 1 deleted item", 1, report.deleted.size)
        assertEquals(
            "/calendars/testuser/personal/deleted-event.ics",
            report.deleted[0]
        )
    }

    @Test
    fun `syncCollection includes non-ics deleted hrefs`() = runTest {
        // A removed href is reported whatever its extension. The parser never filters changed
        // or removed members by .ics, since some servers store events at extensionless hrefs.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithNonIcsDeletion())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Should include non-.ics deleted href", 1, report.deleted.size)
        assertEquals(
            "/calendars/testuser/personal/some-resource",
            report.deleted[0]
        )
    }

    // ========== Truncated results, 507 (RFC 6578 §3.6) ==========

    @Test
    fun `syncCollection marks report as truncated on 507`() = runTest {
        // RFC 6578 §3.6 has a server mark truncation with a 507 status for the request-URI
        // inside a 207; the client also accepts a top-level HTTP 507. The returned sync-token
        // continues from the partial set.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(507)
                .setBody(syncResponseWithChanges())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("507 should still be a success result", result.isSuccess())
        val report = result.getOrNull()!!
        assertTrue("Report must be marked as truncated", report.truncated)
    }

    @Test
    fun `syncCollection parses partial results from 507 response`() = runTest {
        // RFC 6578 §3.6: a truncated response still holds the partial changes
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(507)
                .setBody(syncResponseWithChanges())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Should parse partial changed items", 2, report.changed.size)
    }

    @Test
    fun `syncCollection extracts continuation token from 507 response`() = runTest {
        // RFC 6578 §3.6: a truncated response's sync-token MUST represent the partial set,
        // so the client continues from it
        val continuationToken = "http://example.com/sync/page2"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(507)
                .setBody(syncResponseWithToken(continuationToken))
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals(
            "Continuation token must be extracted from 507 response",
            continuationToken,
            report.syncToken
        )
    }

    @Test
    fun `syncCollection normal 207 response is not truncated`() = runTest {
        // A 207 without a truncation marker holds every change
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertFalse("Normal 207 response must NOT be truncated", report.truncated)
    }

    // ========== Errors and invalid sync-tokens ==========

    @Test
    fun `syncCollection returns error on 403 expired token`() = runTest {
        // An invalid sync-token fails the valid-sync-token precondition (RFC 6578 §3.2) and
        // the server answers 403. Some servers (iCloud) send a bare 403 without the error
        // element.
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("403 should be an error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals("Error code should be 403", 403, error.code)
        assertFalse("Expired token error is not retryable", error.isRetryable)
    }

    @Test
    fun `syncCollection returns error on 410 Gone`() = runTest {
        // Some servers answer an expired sync-token with 410 Gone
        mockWebServer.enqueue(MockResponse().setResponseCode(410))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("410 should be an error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals("Error code should be 410", 410, error.code)
        assertFalse("Expired token error is not retryable", error.isRetryable)
    }

    @Test
    fun `syncCollection returns auth error on 401`() = runTest {
        // 401 is an auth error
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("401 should be an auth error", result.isAuthError())
    }

    @Test
    fun `syncCollection detects invalid sync-token via valid-sync-token element on 207`() = runTest {
        // Some servers answer a 207 whose DAV:error holds the valid-sync-token element
        // (RFC 6578 §3.2) instead of a 403 or 410
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithValidSyncTokenError())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("207 with valid-sync-token error should be treated as error", result.isError())
        val error = result as CalDavResult.Error
        assertFalse("Invalid sync-token is not retryable", error.isRetryable)
    }

    @Test
    fun `syncCollection returns generic error on 500`() = runTest {
        // A 500 is a plain error
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("500 should be an error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals("Error code should be 500", 500, error.code)
    }

    @Test
    fun `syncCollection 507 does not check for valid-sync-token error`() = runTest {
        // A top-level 507 with a multistatus body is always truncation, even when the body
        // contains valid-sync-token text. The valid-sync-token check applies only to 207s.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(507)
                .setBody(syncResponseWithValidSyncTokenError())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        // A truncated success, not a sync-token error
        assertTrue("507 should be success even with error body", result.isSuccess())
        val report = result.getOrNull()!!
        assertTrue("Should be marked as truncated", report.truncated)
    }

    // ========== Namespace prefixes and token position ==========

    @Test
    fun `syncCollection parses uppercase DAV namespace prefix`() = runTest {
        // Servers use different namespace prefixes (d:, D:, none); Stalwart uses D:.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseUppercaseNamespace())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Should parse changed item with D: prefix", 1, report.changed.size)
        assertEquals("stalwart-etag-abc", report.changed[0].etag)
        assertEquals("Should parse deleted item with D: prefix", 1, report.deleted.size)
        assertNotNull("Should parse sync-token with D: prefix", report.syncToken)
    }

    @Test
    fun `syncCollection parses sync-token at end of multistatus`() = runTest {
        // The sync-token can follow the responses in the multistatus. Stalwart puts it at
        // the end, other servers at the top.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseTokenAtEnd())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals(
            "Sync-token at end of body should be parsed",
            "http://example.com/sync/token-at-end",
            report.syncToken
        )
    }

    @Test
    fun `syncCollection parses sync-token at start of multistatus`() = runTest {
        // The sync-token can precede the responses. Nextcloud and Sabre put it at the top.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseTokenAtStart())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals(
            "Sync-token at start of body should be parsed",
            "http://example.com/sync/token-at-start",
            report.syncToken
        )
    }

    // ========== Helper Methods ==========

    private fun calendarUrl(): String =
        mockWebServer.url("/calendars/testuser/personal/").toString()

    private fun mockSyncResponse(syncToken: String): MockResponse =
        MockResponse()
            .setResponseCode(207)
            .setBody(
                """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>$syncToken</d:sync-token>
</d:multistatus>"""
            )

    private fun syncResponseWithChanges(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
    <d:sync-token>$SYNC_TOKEN_2</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"etag-v2"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/calendars/testuser/personal/event4.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"new-event-etag"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun syncResponseWithToken(token: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>$token</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"some-etag"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun syncResponseWithCollectionHref(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>$SYNC_TOKEN_2</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal/</d:href>
        <d:propstat>
            <d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"event-etag"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun syncResponseSlashlessSelfRowWithResourcetype(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>$SYNC_TOKEN_2</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal</d:href>
        <d:propstat>
            <d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"event-etag"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun syncResponseWithResponseLevel404(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>$SYNC_TOKEN_2</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal/deleted-event.ics</d:href>
        <d:status>HTTP/1.1 404 Not Found</d:status>
    </d:response>
</d:multistatus>"""

    private fun syncResponseWithPropstat404(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>$SYNC_TOKEN_2</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal/removed.ics</d:href>
        <d:propstat>
            <d:prop/>
            <d:status>HTTP/1.1 404 Not Found</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    private fun syncResponseMixed(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>$SYNC_TOKEN_2</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"etag-updated"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/calendars/testuser/personal/event2.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"etag-new"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/calendars/testuser/personal/deleted-event.ics</d:href>
        <d:status>HTTP/1.1 404 Not Found</d:status>
    </d:response>
</d:multistatus>"""

    private fun syncResponseWithNonIcsDeletion(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>$SYNC_TOKEN_2</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal/some-resource</d:href>
        <d:status>HTTP/1.1 404 Not Found</d:status>
    </d:response>
</d:multistatus>"""

    private fun syncResponseWithValidSyncTokenError(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:response>
        <d:href>/calendars/testuser/personal/</d:href>
        <d:status>HTTP/1.1 403 Forbidden</d:status>
        <d:error>
            <d:valid-sync-token/>
        </d:error>
    </d:response>
</d:multistatus>"""

    private fun syncResponseUppercaseNamespace(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
    <D:response>
        <D:href>/dav/cal/admin/calendar/event-changed.ics</D:href>
        <D:propstat>
            <D:prop>
                <D:getetag>"stalwart-etag-abc"</D:getetag>
            </D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
        </D:propstat>
    </D:response>
    <D:response>
        <D:href>/dav/cal/admin/calendar/event-deleted.ics</D:href>
        <D:status>HTTP/1.1 404 Not Found</D:status>
    </D:response>
    <D:sync-token>http://stalwart.example.com/ns/sync/new-token</D:sync-token>
</D:multistatus>"""

    private fun syncResponseTokenAtEnd(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"etag1"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:sync-token>http://example.com/sync/token-at-end</d:sync-token>
</d:multistatus>"""

    private fun syncResponseTokenAtStart(): String =
        """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:sync-token>http://example.com/sync/token-at-start</d:sync-token>
    <d:response>
        <d:href>/calendars/testuser/personal/event1.ics</d:href>
        <d:propstat>
            <d:prop>
                <d:getetag>"etag1"</d:getetag>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    companion object {
        private const val SYNC_TOKEN_1 = "http://example.com/ns/sync/token-1"
        private const val SYNC_TOKEN_2 = "http://example.com/ns/sync/token-2"
    }
}
