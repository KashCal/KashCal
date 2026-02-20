package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * RFC 6578 compliance tests for WebDAV Collection Synchronization (sync-collection REPORT).
 *
 * Tests syncCollection() against RFC 6578 requirements:
 * - Section 3: sync-collection REPORT request format
 * - Section 3.2: sync-level element
 * - Section 3.3: sync-token in request (initial vs subsequent)
 * - Section 3.4: Response format (multistatus with changed items and new token)
 * - Section 3.5: Deleted items (404 status)
 * - Section 3.6: Truncated results (507 Insufficient Storage)
 * - Section 3.8: Error handling (invalid/expired sync-token)
 *
 * Each test verifies BOTH outgoing request compliance (method, headers, XML body)
 * and response handling compliance (parsing multistatus responses).
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

    // ========== RFC 6578 Section 3: sync-collection REPORT Request Format ==========

    @Test
    fun `syncCollection sends REPORT method`() = runTest {
        // RFC 6578 Section 3: sync-collection uses REPORT method
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        assertEquals("RFC 6578 requires REPORT method for sync-collection", "REPORT", request.method)
    }

    @Test
    fun `syncCollection sends Depth 1 header`() = runTest {
        // RFC 6578 Section 3: Depth header for sync-collection
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        assertEquals("RFC 6578 requires Depth: 1", "1", request.getHeader("Depth"))
    }

    @Test
    fun `syncCollection sends Content-Type application xml`() = runTest {
        // RFC 6578: Request body is XML
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
        // RFC 6578 Section 3: Root element is DAV:sync-collection
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
        // RFC 6578 Section 3.2: sync-level MUST be present with value "1"
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
        // RFC 6578: Request should include properties to return with changes.
        // KashCal requests only getetag (fetches full data via multiget later).
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertTrue("Must request getetag property", body.contains("getetag"))
    }

    @Test
    fun `syncCollection does not request calendar-data in prop`() = runTest {
        // Bandwidth optimization: sync-collection returns hrefs+etags only.
        // Full event data fetched via calendar-multiget (RFC 4791 Section 7.9).
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertFalse(
            "Must NOT request calendar-data (fetched via multiget later)",
            body.contains("calendar-data")
        )
    }

    // ========== RFC 6578 Section 3.3: Sync-Token in Request ==========

    @Test
    fun `syncCollection sends empty sync-token element for initial sync`() = runTest {
        // RFC 6578 Section 3.3: Initial sync sends empty sync-token element
        // to request all items in the collection
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
        // RFC 6578 Section 3.3: Subsequent sync sends the token from previous response
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
        // RFC 6578: Sync tokens are often URIs — must be preserved exactly
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

    // ========== RFC 6578 Section 3.4: Response Parsing - Changed Items ==========

    @Test
    fun `syncCollection parses changed items with href and etag from 200 propstat`() = runTest {
        // RFC 6578 Section 3.4: Changed/new items have 200 OK status with getetag
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
        // RFC 6578 Section 3.4: Response MUST include a new sync-token
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
        // RFC 6578: No changes since last sync → empty multistatus with new token
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
        // RFC 6578: Response can contain many changed items
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
        // RFC 7232: ETags may be quoted — KashCal normalizes them
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithChanges())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        // ETags in XML are quoted ("etag-v2"), parser should strip quotes
        val etag = report.changed[0].etag
        assertFalse(
            "ETag should be normalized (quotes stripped)",
            etag?.startsWith("\"") == true
        )
    }

    @Test
    fun `syncCollection filters non-ics hrefs from changed items`() = runTest {
        // KashCal filters changed items to .ics resources only.
        // Collection hrefs (directories) should not appear in changed list.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(syncResponseWithCollectionHref())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertEquals("Collection href should be filtered out", 1, report.changed.size)
        assertTrue(
            "Only .ics hrefs should remain",
            report.changed[0].href.endsWith(".ics")
        )
    }

    // ========== RFC 6578 Section 3.5: Deleted Items ==========

    @Test
    fun `syncCollection identifies deleted items by 404 status at response level`() = runTest {
        // RFC 6578 Section 3.5: Deleted items have 404 status directly in response
        // (no propstat wrapper) — this is the Nextcloud/Sabre pattern
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
        // RFC 6578 Section 3.5: Some servers wrap 404 in propstat element
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
        // RFC 6578: Real-world responses mix changed and deleted items
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
        // DEVIATION: extractDeletedHrefs does NOT filter by .ics extension,
        // while extractChangedItems does. This is intentional — deleted resources
        // may have been renamed or the server may not append .ics to deletion reports.
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

    // ========== RFC 6578 Section 3.6: Truncated Results (507) ==========

    @Test
    fun `syncCollection marks report as truncated on 507`() = runTest {
        // RFC 6578 Section 3.6: Server MAY return 507 when results are too large.
        // Client MUST use the new sync-token to continue.
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
        // RFC 6578 Section 3.6: 507 response still contains valid partial results
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
        // RFC 6578 Section 3.6: 507 response MUST include a new sync-token
        // for the client to continue syncing
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
        // RFC 6578: Normal 207 response means all changes are included
        mockWebServer.enqueue(mockSyncResponse(syncToken = SYNC_TOKEN_2))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("Result should be success", result.isSuccess())
        val report = result.getOrNull()!!
        assertFalse("Normal 207 response must NOT be truncated", report.truncated)
    }

    // ========== RFC 6578 Section 3.8: Error Handling ==========

    @Test
    fun `syncCollection returns error on 403 expired token`() = runTest {
        // RFC 6578 Section 3.8: Server returns 403 when sync-token is invalid.
        // Some servers (e.g., iCloud) return bare 403 without error element.
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("403 should be an error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals("Error code should be 403", 403, error.code)
        assertFalse("Expired token error is not retryable", error.isRetryable)
    }

    @Test
    fun `syncCollection returns error on 410 Gone`() = runTest {
        // RFC 6578 Section 3.8: Server returns 410 Gone when sync-token expired
        // (collection has been significantly modified)
        mockWebServer.enqueue(MockResponse().setResponseCode(410))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("410 should be an error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals("Error code should be 410", 410, error.code)
        assertFalse("Expired token error is not retryable", error.isRetryable)
    }

    @Test
    fun `syncCollection returns auth error on 401`() = runTest {
        // RFC 6578: Authentication failure handling
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("401 should be an auth error", result.isAuthError())
    }

    @Test
    fun `syncCollection detects invalid sync-token via valid-sync-token element on 207`() = runTest {
        // RFC 6578 Section 3.8: Some servers return 207 with DAV:error containing
        // valid-sync-token element instead of 403/410
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
        // Server error handling
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        assertTrue("500 should be an error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals("Error code should be 500", 500, error.code)
    }

    @Test
    fun `syncCollection 507 does not check for valid-sync-token error`() = runTest {
        // RFC 6578 Section 3.6: 507 is always treated as truncation, even if
        // body happens to contain valid-sync-token text. The valid-sync-token
        // check only applies to 207 responses.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(507)
                .setBody(syncResponseWithValidSyncTokenError())
        )

        val result = client.syncCollection(calendarUrl(), SYNC_TOKEN_1)

        // 507 is treated as success (truncated), not as a sync-token error
        assertTrue("507 should be success even with error body", result.isSuccess())
        val report = result.getOrNull()!!
        assertTrue("Should be marked as truncated", report.truncated)
    }

    // ========== RFC 6578 Section 3.4: Namespace Handling ==========

    @Test
    fun `syncCollection parses uppercase DAV namespace prefix`() = runTest {
        // Real servers use different namespace prefixes: d:, D:, no prefix, etc.
        // Stalwart uses D: prefix — parser must handle this.
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
        // RFC 6578: sync-token can appear at the end of multistatus (after responses).
        // Some servers (Stalwart) place it at the end, others at the top.
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
        // RFC 6578: sync-token can appear at the start of multistatus (before responses).
        // Nextcloud/Sabre places it at the top.
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
                <d:getetag>"collection-etag"</d:getetag>
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
