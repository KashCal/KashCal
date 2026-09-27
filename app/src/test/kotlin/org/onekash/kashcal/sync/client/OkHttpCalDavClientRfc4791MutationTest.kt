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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks

/**
 * Tests [OkHttpCalDavClient] event create, update, delete and move against CalDAV and WebDAV.
 *
 * Covers:
 * - RFC 4791 §5.3.2: creating with PUT and `If-None-Match: *`, changing with `If-Match`
 * - RFC 4791 §5.3.2.1: the no-uid-conflict and max-resource-size preconditions
 * - RFC 4791 §5.3.4: reading the ETag after a write
 * - RFC 4918 §9.6: DELETE
 * - RFC 4918 §9.9: MOVE
 *
 * Each test checks the outgoing request (method, headers, Content-Type) or how a reply's status
 * code is classified.
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

    // ========== Creating calendar object resources (RFC 4791 §5.3.2) ==========

    @Test
    fun `createEvent sends PUT to calendar-url slash uid dot ics`() = runTest {
        // PUT to {calendar-collection}/{uid}.ics. RFC 4791 §5.3.2 makes the name arbitrary;
        // the client derives it from the UID.
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
        // RFC 4791 §5.3.2: If-None-Match: * stops the PUT overwriting an existing resource
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
        // The PUT body is iCalendar data, sent as text/calendar
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
        // 201 Created: the resource was created
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
        // Some servers answer a create with 204 instead of 201
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
        // RFC 4791 §5.3.4: the server SHOULD return an ETag in the PUT response
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
        // RFC 4791 §5.3.4: the server may omit the ETag; the client then fetches it.
        // Nextcloud and Zoho omit it from the PUT response.
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

    // ========== UID uniqueness and create failures (RFC 4791 §5.3.2.1) ==========

    @Test
    fun `createEvent returns conflict on 412 Precondition Failed`() = runTest {
        // 412 when If-None-Match: * fails because a resource exists at that URL
        mockWebServer.enqueue(MockResponse().setResponseCode(412))

        val calendarUrl = mockWebServer.url("/calendars/testuser/personal/").toString()
        val result = client.createEvent(calendarUrl, "existing-uid", testIcal("existing-uid"))

        assertTrue("412 should be conflict error", result.isConflict())
    }

    @Test
    fun `createEvent returns UID conflict on 403 with Location header`() = runTest {
        // A 403 with a Location header: the UID is already used at another URL in the
        // collection (the RFC 4791 §5.3.2.1 no-uid-conflict precondition)
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
        // A 403 without Location is reported as permission denied
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
        // 413: the event exceeds the calendar's max-resource-size (RFC 4791 §5.2.5)
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

    // ========== Modifying calendar object resources (RFC 4791 §5.3.2) ==========

    @Test
    fun `updateEvent sends PUT with If-Match etag header`() = runTest {
        // RFC 4791 §5.3.2: a change carries the current ETag in If-Match (optimistic locking)
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
        // RFC 4791 §5.3.4: the server returns the new ETag after the change
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
        // RFC 4791 §5.3.4: fetch the ETag when the PUT response has none
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
        // RFC 7232 §3.1: 412 Precondition Failed when the ETag doesn't match
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
        // 413: the edited event exceeds max-resource-size (RFC 4791 §5.2.5)
        mockWebServer.enqueue(MockResponse().setResponseCode(413))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.updateEvent(eventUrl, testIcal("test-uid"), "etag")

        assertTrue("413 should be error", result.isError())
    }

    // ========== Deleting calendar object resources (RFC 4918 §9.6) ==========

    @Test
    fun `deleteEvent sends DELETE with If-Match etag header`() = runTest {
        // DELETE carries If-Match for optimistic locking (RFC 7232 §3.1)
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
        // 404 means the event is already gone, so the delete counts as done
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
        // 412: the ETag doesn't match, so the event was modified on the server
        mockWebServer.enqueue(MockResponse().setResponseCode(412))

        val eventUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val result = client.deleteEvent(eventUrl, "stale-etag")

        assertTrue("412 should be conflict", result.isConflict())
    }

    // ========== MOVE (RFC 4918 §9.9) ==========

    @Test
    fun `moveEvent sends MOVE method`() = runTest {
        // RFC 4918 §9.9: MOVE relocates the resource
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
    fun `deleteEvent without an etag sends no If-Match at all`() = runTest {
        // An empty entity-tag never matches (RFC 9110 section 13.1.1), so "delete whatever is
        // there" must omit the header rather than send If-Match: "".
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val result = client.deleteEvent(mockWebServer.url("/calendars/testuser/personal/gone.ics").toString(), null)

        assertTrue(result.isSuccess())
        val request = mockWebServer.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals(null, request.getHeader("If-Match"))
    }

    @Test
    fun `moveEvent keeps the source resource name as the Destination`() = runTest {
        // RFC 4918 §9.9: the Destination header names the target URL. Resource names are
        // opaque, so the name the source was stored under (here chosen by another client,
        // not derived from the UID) is kept in the destination calendar.
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
            "Destination must keep the source name, got $destination",
            destination!!.endsWith("/calendars/testuser/work/event.ics")
        )
    }

    @Test
    fun `moveEvent sends Overwrite F header`() = runTest {
        // RFC 4918 §10.6: Overwrite: F stops the MOVE replacing a resource at the destination
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
        // RFC 4918 §9.9.4: 201 Created when the destination URL wasn't mapped
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
        assertTrue("New URL keeps the source resource name", newUrl.endsWith("/work/event.ics"))
    }

    @Test
    fun `moveEvent returns new url and etag on 204`() = runTest {
        // RFC 4918 §9.9.4: 204 No Content is also a successful MOVE
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
        // The MOVE response may carry no ETag; the client fetches it from the destination
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                // No ETag header
        )
        // ETag fetch on the destination URL
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
        // 404: the source doesn't exist
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("404 should be not found", result.isNotFound())
    }

    @Test
    fun `moveEvent returns conflict on 412`() = runTest {
        // RFC 4918 §9.9.4: 412 when the destination exists and Overwrite: F was set
        mockWebServer.enqueue(MockResponse().setResponseCode(412))

        val sourceUrl = mockWebServer.url("/calendars/testuser/personal/event.ics").toString()
        val destCalUrl = mockWebServer.url("/calendars/testuser/work/").toString()
        val result = client.moveEvent(sourceUrl, destCalUrl, "uid")

        assertTrue("412 should be conflict", result.isConflict())
    }

    @Test
    fun `moveEvent returns error on 403 cross-server`() = runTest {
        // 403: the server forbids the MOVE; the client reports it as a possible cross-server move
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
        // 405: the server doesn't support MOVE on this resource
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
