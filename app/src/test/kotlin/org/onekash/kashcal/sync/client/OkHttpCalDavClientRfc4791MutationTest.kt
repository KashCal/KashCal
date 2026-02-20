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
 * RFC 4791 compliance tests for CalDAV mutation operations.
 *
 * Tests event CRUD against RFC 4791 requirements:
 * - Section 5.3.1: Creating calendar object resources (PUT)
 * - Section 5.3.2: UID uniqueness constraint (If-None-Match)
 * - Section 5.3.3: Modifying/deleting calendar object resources (If-Match)
 * - Section 5.3.4: ETag retrieval after mutation
 * - Section 5.2.5: max-resource-size (413)
 * - RFC 4918 Section 9.9: MOVE operation
 *
 * Each test verifies BOTH outgoing request compliance (headers, Content-Type)
 * and response handling compliance (status codes, error classification).
 */
class OkHttpCalDavClientRfc4791MutationTest {

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

    // ========== RFC 4791 Section 5.3.1: Creating Calendar Object Resources ==========

    @Test
    fun `createEvent sends PUT to calendar-url slash uid dot ics`() = runTest {
        // RFC 4791 Section 5.3.1: PUT to {calendar-collection}/{uid}.ics
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"created-etag\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.createEvent(calendarUrl, "test-uid-123", testIcal("test-uid-123"))

        val request = mockWebServer.takeRequest()
        assertEquals("Must use PUT method", "PUT", request.method)
        assertTrue(
            "URL must end with {uid}.ics",
            request.path!!.endsWith("/test-uid-123.ics")
        )
    }

    @Test
    fun `createEvent sends If-None-Match star header`() = runTest {
        // RFC 4791 Section 5.3.2: If-None-Match: * prevents overwriting existing resource
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"created-etag\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.createEvent(calendarUrl, "test-uid", testIcal("test-uid"))

        val request = mockWebServer.takeRequest()
        assertEquals(
            "RFC 4791 Section 5.3.2: Must send If-None-Match: * for creation",
            "*",
            request.getHeader("If-None-Match")
        )
    }

    @Test
    fun `createEvent sends Content-Type text calendar`() = runTest {
        // RFC 4791 Section 5.3.1: PUT body is iCalendar data with text/calendar Content-Type
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"created-etag\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        client.createEvent(calendarUrl, "test-uid", testIcal("test-uid"))

        val request = mockWebServer.takeRequest()
        val contentType = request.getHeader("Content-Type")
        assertNotNull("Must have Content-Type header", contentType)
        assertTrue(
            "Content-Type must be text/calendar",
            contentType!!.contains("text/calendar")
        )
    }

    @Test
    fun `createEvent returns url and etag on 201 Created`() = runTest {
        // RFC 4791 Section 5.3.1: 201 Created indicates successful resource creation
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"new-etag-201\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "test-uid", testIcal("test-uid"))

        assertTrue("201 should be success", result.isSuccess())
        val (url, etag) = result.getOrNull()!!
        assertTrue("URL should end with .ics", url.endsWith(".ics"))
        assertEquals("ETag should be extracted from header", "new-etag-201", etag)
    }

    @Test
    fun `createEvent returns url and etag on 204 No Content`() = runTest {
        // RFC 4791: Some servers return 204 instead of 201 for create
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "\"new-etag-204\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "test-uid", testIcal("test-uid"))

        assertTrue("204 should also be success", result.isSuccess())
    }

    @Test
    fun `createEvent extracts etag from response header`() = runTest {
        // RFC 4791 Section 5.3.4: Server SHOULD return ETag in PUT response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"abc123def\"")
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "test-uid", testIcal("test-uid"))

        assertTrue(result.isSuccess())
        val (_, etag) = result.getOrNull()!!
        assertEquals("ETag should be normalized (quotes stripped)", "abc123def", etag)
    }

    @Test
    fun `createEvent falls back to PROPFIND when etag header missing`() = runTest {
        // RFC 4791 Section 5.3.4: Server MAY not return ETag; client should fetch via PROPFIND
        // Real-world: Nextcloud, Zoho omit ETag from PUT response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                // No ETag header
        )
        // PROPFIND fallback response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindEtagResponse("fallback-etag"))
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "test-uid", testIcal("test-uid"))

        assertTrue("Result should be success even with PROPFIND fallback", result.isSuccess())
        assertTrue(
            "Should make 2 requests: PUT + PROPFIND fallback",
            mockWebServer.requestCount >= 2
        )
    }

    // ========== RFC 4791 Section 5.3.2: UID Uniqueness ==========

    @Test
    fun `createEvent returns conflict on 412 Precondition Failed`() = runTest {
        // RFC 4791 Section 5.3.2: 412 when If-None-Match: * fails (resource exists)
        mockWebServer.enqueue(MockResponse().setResponseCode(412))

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "existing-uid", testIcal("existing-uid"))

        assertTrue("412 should be conflict error", result.isConflict())
    }

    @Test
    fun `createEvent returns UID conflict on 403 with Location header`() = runTest {
        // RFC 4791 Section 5.3.2: 403 with Location header indicates UID already used
        // at a different URL in the calendar collection
        val existingUrl = "/calendars/testuser/personal/other-file.ics"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Location", existingUrl)
        )

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "duplicate-uid", testIcal("duplicate-uid"))

        assertTrue("Should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals("Should be 403", 403, error.code)
        assertTrue(
            "Error message should mention UID conflict",
            error.message.contains("UID conflict")
        )
    }

    @Test
    fun `createEvent returns permission denied on 403 without Location`() = runTest {
        // RFC 4791: 403 without Location is generic permission denied
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "test-uid", testIcal("test-uid"))

        assertTrue("Should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals(403, error.code)
        assertTrue(
            "Error message should mention permission",
            error.message.contains("Permission denied", ignoreCase = true) ||
                error.message.contains("denied", ignoreCase = true)
        )
    }

    @Test
    fun `createEvent returns error on 413 Request Entity Too Large`() = runTest {
        // RFC 4791 Section 5.2.5: max-resource-size exceeded
        mockWebServer.enqueue(MockResponse().setResponseCode(413))

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "big-uid", testIcal("big-uid"))

        assertTrue("413 should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals(413, error.code)
    }

    @Test
    fun `createEvent returns auth error on 401`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "test-uid", testIcal("test-uid"))

        assertTrue("401 should be auth error", result.isAuthError())
    }

    // ========== RFC 4791 Section 5.3.3: Modifying Calendar Object Resources ==========

    @Test
    fun `updateEvent sends PUT with If-Match etag header`() = runTest {
        // RFC 4791 Section 5.3.3: If-Match with current ETag for optimistic locking
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "\"new-etag\"")
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        client.updateEvent(eventUrl, testIcal("test-uid"), "current-etag")

        val request = mockWebServer.takeRequest()
        assertEquals("Must use PUT method", "PUT", request.method)
        assertEquals(
            "RFC 4791 Section 5.3.3: Must send If-Match with quoted ETag",
            "\"current-etag\"",
            request.getHeader("If-Match")
        )
    }

    @Test
    fun `updateEvent sends Content-Type text calendar`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "\"new-etag\"")
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        client.updateEvent(eventUrl, testIcal("test-uid"), "etag")

        val request = mockWebServer.takeRequest()
        val contentType = request.getHeader("Content-Type")
        assertNotNull("Must have Content-Type", contentType)
        assertTrue("Must be text/calendar", contentType!!.contains("text/calendar"))
    }

    @Test
    fun `updateEvent returns new etag on success`() = runTest {
        // RFC 4791 Section 5.3.4: Server returns new ETag after modification
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "\"updated-etag\"")
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.updateEvent(eventUrl, testIcal("test-uid"), "old-etag")

        assertTrue("Should be success", result.isSuccess())
        assertEquals("Should return new ETag", "updated-etag", result.getOrNull())
    }

    @Test
    fun `updateEvent falls back to PROPFIND when etag header missing`() = runTest {
        // RFC 4791 Section 5.3.4: Fallback for servers that don't return ETag in PUT response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                // No ETag header
        )
        // PROPFIND fallback
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindEtagResponse("propfind-etag"))
        )

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.updateEvent(eventUrl, testIcal("test-uid"), "old-etag")

        assertTrue("Should succeed with PROPFIND fallback", result.isSuccess())
        assertTrue(
            "Should make at least 2 requests: PUT + PROPFIND",
            mockWebServer.requestCount >= 2
        )
    }

    @Test
    fun `updateEvent returns conflict on 412`() = runTest {
        // RFC 4791 Section 5.3.3: 412 Precondition Failed when ETag doesn't match
        mockWebServer.enqueue(MockResponse().setResponseCode(412))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.updateEvent(eventUrl, testIcal("test-uid"), "stale-etag")

        assertTrue("412 should be conflict", result.isConflict())
    }

    @Test
    fun `updateEvent returns not found on 404`() = runTest {
        // Event deleted between fetch and update
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.updateEvent(eventUrl, testIcal("test-uid"), "etag")

        assertTrue("404 should be not found", result.isNotFound())
    }

    @Test
    fun `updateEvent returns error on 413`() = runTest {
        // RFC 4791 Section 5.2.5: Event exceeds max-resource-size after edit
        mockWebServer.enqueue(MockResponse().setResponseCode(413))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.updateEvent(eventUrl, testIcal("test-uid"), "etag")

        assertTrue("413 should be error", result.isError())
    }

    // ========== RFC 4791 Section 5.3.3: Deleting Calendar Object Resources ==========

    @Test
    fun `deleteEvent sends DELETE with If-Match etag header`() = runTest {
        // RFC 4791 Section 5.3.3: DELETE with If-Match for optimistic locking
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        client.deleteEvent(eventUrl, "delete-etag")

        val request = mockWebServer.takeRequest()
        assertEquals("Must use DELETE method", "DELETE", request.method)
        assertEquals(
            "Must send If-Match with quoted ETag",
            "\"delete-etag\"",
            request.getHeader("If-Match")
        )
    }

    @Test
    fun `deleteEvent returns success on 200`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.deleteEvent(eventUrl, "etag")

        assertTrue("200 should be success", result.isSuccess())
    }

    @Test
    fun `deleteEvent returns success on 204`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.deleteEvent(eventUrl, "etag")

        assertTrue("204 should be success", result.isSuccess())
    }

    @Test
    fun `deleteEvent returns success on 404`() = runTest {
        // RFC 4918: DELETE is idempotent. 404 means already deleted elsewhere.
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.deleteEvent(eventUrl, "etag")

        assertTrue(
            "404 on DELETE should be treated as success (idempotent, already deleted)",
            result.isSuccess()
        )
    }

    @Test
    fun `deleteEvent returns conflict on 412`() = runTest {
        // RFC 4791 Section 5.3.3: ETag mismatch means event was modified
        mockWebServer.enqueue(MockResponse().setResponseCode(412))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.deleteEvent(eventUrl, "stale-etag")

        assertTrue("412 should be conflict", result.isConflict())
    }

    // ========== RFC 4918 Section 9.9: MOVE Operation ==========

    @Test
    fun `moveEvent sends MOVE method`() = runTest {
        // RFC 4918 Section 9.9: MOVE method for relocating resources
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"moved-etag\"")
        )

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        client.moveEvent(sourceUrl, destCalUrl, "event-uid")

        val request = mockWebServer.takeRequest()
        assertEquals("Must use MOVE method", "MOVE", request.method)
    }

    @Test
    fun `moveEvent sends Destination header with uid dot ics`() = runTest {
        // RFC 4918 Section 9.9: Destination header specifies target URL
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"moved-etag\"")
        )

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        client.moveEvent(sourceUrl, destCalUrl, "my-event-uid")

        val request = mockWebServer.takeRequest()
        val destination = request.getHeader("Destination")
        assertNotNull("Must have Destination header", destination)
        assertTrue(
            "Destination must end with {uid}.ics",
            destination!!.endsWith("/my-event-uid.ics")
        )
    }

    @Test
    fun `moveEvent sends Overwrite F header`() = runTest {
        // RFC 4918 Section 9.9: Overwrite: F prevents clobbering existing resource at destination
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"moved-etag\"")
        )

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        client.moveEvent(sourceUrl, destCalUrl, "uid")

        val request = mockWebServer.takeRequest()
        assertEquals(
            "RFC 4918: Overwrite: F prevents clobbering",
            "F",
            request.getHeader("Overwrite")
        )
    }

    @Test
    fun `moveEvent returns new url and etag on 201`() = runTest {
        // RFC 4918: 201 Created when destination didn't exist
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"moved-etag-201\"")
        )

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("201 should be success", result.isSuccess())
        val (newUrl, etag) = result.getOrNull()!!
        assertTrue("New URL should contain destination calendar", newUrl.contains("/work/"))
        assertTrue("New URL should end with uid.ics", newUrl.endsWith("/uid.ics"))
    }

    @Test
    fun `moveEvent returns new url and etag on 204`() = runTest {
        // RFC 4918: 204 No Content is also success for MOVE
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
                .setHeader("ETag", "\"moved-etag-204\"")
        )

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("204 should be success", result.isSuccess())
    }

    @Test
    fun `moveEvent falls back to PROPFIND when etag missing`() = runTest {
        // RFC 4791 Section 5.3.4: ETag may not be in MOVE response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                // No ETag header
        )
        // PROPFIND fallback on destination URL
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(propfindEtagResponse("propfind-etag-after-move"))
        )

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("Should succeed with PROPFIND fallback", result.isSuccess())
    }

    @Test
    fun `moveEvent returns not found on 404`() = runTest {
        // RFC 4918: Source doesn't exist
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("404 should be not found", result.isNotFound())
    }

    @Test
    fun `moveEvent returns conflict on 412`() = runTest {
        // RFC 4918: Destination exists and Overwrite: F was set
        mockWebServer.enqueue(MockResponse().setResponseCode(412))

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("412 should be conflict", result.isConflict())
    }

    @Test
    fun `moveEvent returns error on 403 cross-server`() = runTest {
        // RFC 4918: Cross-server MOVE is forbidden
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("403 should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals(403, error.code)
    }

    @Test
    fun `moveEvent returns error on 405 not supported`() = runTest {
        // RFC 4918: Server doesn't support MOVE on this resource
        mockWebServer.enqueue(MockResponse().setResponseCode(405))

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("405 should be error", result.isError())
        val error = result as CalDavResult.Error
        assertEquals(405, error.code)
    }

    // ========== Helper Methods ==========

    private fun testIcal(uid: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//KashCal//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260115T000000Z
        DTSTART:20260201T100000Z
        DTEND:20260201T110000Z
        SUMMARY:Test Event
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun propfindEtagResponse(etag: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/calendars/testuser/personal/event.ics</d:href>
                <d:propstat>
                    <d:prop>
                        <d:getetag>"$etag"</d:getetag>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
    """.trimIndent()
}
