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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult

/**
 * MockWebServer exit-gate test for the CardDAV write-path verbs
 * ([OkHttpCardDavClient.putContact], [OkHttpCardDavClient.deleteContact]).
 *
 * Covers the full status matrix (create/update success, missing-ETag,
 * precondition-failed, permission-denied, gone) plus the conditional-header wire
 * compliance (If-None-Match:* vs If-Match, Content-Type) and the two server
 * quirks the naming/href policy defends against (Zoho name-policy 401, iCloud
 * verbatim absolute-partition hrefs). Nothing in the app calls these verbs yet;
 * this test is their only acceptance surface.
 */
class CardDavWriteClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpCardDavClient

    private val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:u1\r\nFN:Ada\r\nEND:VCARD\r\n"

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
        client = OkHttpCardDavClient(DefaultCardDavQuirks(server.url("/").toString()), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    private fun url(path: String) = server.url(path).toString()

    // ========== PUT create (If-None-Match: *) ==========

    @Test
    fun `create sends PUT with If-None-Match star and text vcard, captures etag`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"etag-new\""))

        val result = client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfAbsent)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/ab/alice/u1.vcf", request.path)
        assertEquals("*", request.getHeader("If-None-Match"))
        assertEquals("text/vcard; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals(vcard, request.body.readUtf8())
        assertEquals(ContactUploadResult.Success("etag-new"), result)
    }

    @Test
    fun `update success on 200 captures etag`() = runTest {
        // A conditional update PUT may answer 200 OK (not just 204); the shared
        // verb must treat it as success, matching the CalDAV update path.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"etag-200\""))

        val result = client.putContact(
            url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfMatch("etag-v1"),
        )

        assertEquals(ContactUploadResult.Success("etag-200"), result)
    }

    @Test
    fun `create success on 204 captures etag`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"etag-204\""))

        val result = client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfAbsent)

        assertEquals(ContactUploadResult.Success("etag-204"), result)
    }

    @Test
    fun `create normalizes a weak etag`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "W/\"weak-1\""))

        val result = client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfAbsent)

        assertEquals(ContactUploadResult.Success("weak-1"), result)
    }

    @Test
    fun `create with no etag header yields Success null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))

        val result = client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfAbsent)

        assertEquals(ContactUploadResult.Success(null), result)
    }

    // ========== PUT update (If-Match) ==========

    @Test
    fun `update sends If-Match quoted etag`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"etag-v2\""))

        val result = client.putContact(
            url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfMatch("etag-v1"),
        )

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("\"etag-v1\"", request.getHeader("If-Match"))
        assertEquals(null, request.getHeader("If-None-Match"))
        assertEquals(ContactUploadResult.Success("etag-v2"), result)
    }

    // ========== PUT failure outcomes ==========

    @Test
    fun `put 412 is PreconditionFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(412))
        assertEquals(
            ContactUploadResult.PreconditionFailed,
            client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfAbsent),
        )
    }

    @Test
    fun `put 409 is PreconditionFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        assertEquals(
            ContactUploadResult.PreconditionFailed,
            client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfMatch("e")),
        )
    }

    @Test
    fun `put 403 is PermissionDenied`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            ContactUploadResult.PermissionDenied,
            client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfAbsent),
        )
    }

    @Test
    fun `put 404 is Gone`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            ContactUploadResult.Gone,
            client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfMatch("e")),
        )
    }

    @Test
    fun `put 410 is Gone`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410))
        assertEquals(
            ContactUploadResult.Gone,
            client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfMatch("e")),
        )
    }

    @Test
    fun `put 503 is retryable Failed and is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfAbsent)

        assertTrue("expected Failed, got $result", result is ContactUploadResult.Failed)
        result as ContactUploadResult.Failed
        assertEquals(503, result.code)
        assertTrue(result.isRetryable)
        // No-retry contract: a conditional write is issued exactly once, even on a
        // normally-retryable 5xx (a blind retry could misreport a lost-response
        // success as a precondition failure).
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `put transport failure is retryable Failed with code 0`() = runTest {
        server.shutdown() // no one listening -> IOException
        val result = client.putContact(url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfAbsent)
        assertTrue("expected Failed, got $result", result is ContactUploadResult.Failed)
        result as ContactUploadResult.Failed
        assertEquals(0, result.code)
        assertTrue(result.isRetryable)
    }

    // ========== Unusable stored etag (can't build a valid If-Match header) ==========

    @Test
    fun `put with a control-char etag short-circuits to PreconditionFailed without a request`() = runTest {
        // A stored etag carrying a char OkHttp would reject in a header value (here an
        // interior CR) must not throw an uncaught IllegalArgumentException out of the
        // write verb — that would leave the push holding the token and stall the whole
        // account's contact sync. The conditional can't be expressed, so the
        // precondition can't hold: report PreconditionFailed (server-wins next pull).
        val result = client.putContact(
            url("/ab/alice/u1.vcf"), vcard, ContactPrecondition.IfMatch("etag\r\nv1"),
        )

        assertEquals(ContactUploadResult.PreconditionFailed, result)
        assertEquals("no request should reach the server", 0, server.requestCount)
    }

    @Test
    fun `delete with a control-char etag short-circuits to PreconditionFailed without a request`() = runTest {
        val result = client.deleteContact(url("/ab/alice/u1.vcf"), "etag\r\ndel")

        assertEquals(ContactDeleteResult.PreconditionFailed, result)
        assertEquals("no request should reach the server", 0, server.requestCount)
    }

    // ========== Server quirks: Zoho name policy, iCloud verbatim href ==========

    @Test
    fun `uid-derived name is accepted where an arbitrary name would 401`() = runTest {
        // The resource-name policy hands the client a <uid>.vcf href; a server that
        // 401s arbitrary names accepts this one. The client PUTs the href verbatim.
        val name = contactResourceName("zoho-uid-123")
        assertEquals("zoho-uid-123.vcf", name)
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"z1\""))

        val result = client.putContact(url("/caldav/ab/$name"), vcard, ContactPrecondition.IfAbsent)

        assertEquals("/caldav/ab/$name", server.takeRequest().path)
        assertEquals(ContactUploadResult.Success("z1"), result)
    }

    @Test
    fun `arbitrary-name 401 surfaces as Failed 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.putContact(url("/caldav/ab/random-name.vcf"), vcard, ContactPrecondition.IfAbsent)
        assertTrue(result is ContactUploadResult.Failed)
        assertEquals(401, (result as ContactUploadResult.Failed).code)
    }

    @Test
    fun `icloud absolute partition href round-trips verbatim on put`() = runTest {
        val href = url("/1234567890/carddavhome/card/AABBCCDD-EEFF.vcf")
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"ic1\""))

        client.putContact(href, vcard, ContactPrecondition.IfAbsent)

        assertEquals("/1234567890/carddavhome/card/AABBCCDD-EEFF.vcf", server.takeRequest().path)
    }

    // ========== DELETE ==========

    @Test
    fun `delete sends conditional DELETE and succeeds on 204`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.deleteContact(url("/ab/alice/u1.vcf"), "etag-del")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/ab/alice/u1.vcf", request.path)
        assertEquals("\"etag-del\"", request.getHeader("If-Match"))
        assertEquals(ContactDeleteResult.Deleted, result)
    }

    @Test
    fun `delete succeeds on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(ContactDeleteResult.Deleted, client.deleteContact(url("/ab/alice/u1.vcf"), "e"))
    }

    @Test
    fun `delete 412 is PreconditionFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(412))
        assertEquals(
            ContactDeleteResult.PreconditionFailed,
            client.deleteContact(url("/ab/alice/u1.vcf"), "e"),
        )
    }

    @Test
    fun `delete 409 is PreconditionFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        assertEquals(
            ContactDeleteResult.PreconditionFailed,
            client.deleteContact(url("/ab/alice/u1.vcf"), "e"),
        )
    }

    @Test
    fun `delete 404 is AlreadyGone`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            ContactDeleteResult.AlreadyGone,
            client.deleteContact(url("/ab/alice/u1.vcf"), "e"),
        )
    }

    @Test
    fun `delete 410 is AlreadyGone`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410))
        assertEquals(
            ContactDeleteResult.AlreadyGone,
            client.deleteContact(url("/ab/alice/u1.vcf"), "e"),
        )
    }

    @Test
    fun `delete 403 is Failed 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = client.deleteContact(url("/ab/alice/u1.vcf"), "e")
        assertTrue(result is ContactDeleteResult.Failed)
        assertEquals(403, (result as ContactDeleteResult.Failed).code)
    }

    @Test
    fun `delete 503 is retryable Failed and is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.deleteContact(url("/ab/alice/u1.vcf"), "e")

        assertTrue(result is ContactDeleteResult.Failed)
        result as ContactDeleteResult.Failed
        assertEquals(503, result.code)
        assertTrue(result.isRetryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `delete transport failure is retryable Failed with code 0`() = runTest {
        server.shutdown()
        val result = client.deleteContact(url("/ab/alice/u1.vcf"), "e")
        assertTrue(result is ContactDeleteResult.Failed)
        result as ContactDeleteResult.Failed
        assertEquals(0, result.code)
        assertTrue(result.isRetryable)
    }
}
