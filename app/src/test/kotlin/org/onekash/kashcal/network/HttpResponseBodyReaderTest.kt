package org.onekash.kashcal.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Tests for the shared bounded response-body reader.
 *
 * The reader caps how much of an HTTP response body is buffered into memory,
 * preventing OOM when a server returns a pathologically large (or malicious)
 * body. Both the CalDAV client and the ICS fetch paths read through it.
 */
class HttpResponseBodyReaderTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(): Response {
        val request = Request.Builder().url(server.url("/")).build()
        return client.newCall(request).execute()
    }

    @Test
    fun `returns full content for a body under the limit`() {
        val payload = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

        val result = get().readBoundedBody(maxBytes = 1024)

        assertEquals(payload, result)
    }

    @Test
    fun `throws when Content-Length exceeds the limit`() {
        // setBody gives MockWebServer a known Content-Length, exercising the
        // cheap header check before any bytes are buffered.
        val tooBig = "x".repeat(2048)
        server.enqueue(MockResponse().setResponseCode(200).setBody(tooBig))

        assertThrows(IOException::class.java) {
            get().readBoundedBody(maxBytes = 1024)
        }
    }

    @Test
    fun `throws when a chunked body exceeds the limit without Content-Length`() {
        // Chunked transfer has no Content-Length (-1), so the guard must fall
        // through to the buffered-size check.
        val tooBig = "y".repeat(4096)
        server.enqueue(
            MockResponse().setResponseCode(200).setChunkedBody(tooBig, 256)
        )

        assertThrows(IOException::class.java) {
            get().readBoundedBody(maxBytes = 1024)
        }
    }

    @Test
    fun `decodes using the body's declared charset`() {
        // A non-UTF-8 charset must be honored; the old CalDAV reader hardcoded
        // UTF-8, which would corrupt an ISO-8859-1 ICS feed.
        val text = "Città" // "Città" — é-class char differs across charsets
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/calendar; charset=ISO-8859-1")
                .setBody(okio.Buffer().writeString(text, Charsets.ISO_8859_1))
        )

        val result = get().readBoundedBody(maxBytes = 1024)

        assertEquals(text, result)
    }

    @Test
    fun `default limit is 50 MB`() {
        assertEquals(50L * 1024 * 1024, MAX_HTTP_RESPONSE_SIZE_BYTES)
    }

    @Test
    fun `empty body decodes to empty string`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        assertEquals("", get().readBoundedBody(maxBytes = 1024))
    }

    @Test
    fun `body of exactly the limit is accepted`() {
        // Boundary: contentLength == maxBytes must pass (the guard is strict >).
        val payload = "z".repeat(1024)
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        assertEquals(payload, get().readBoundedBody(maxBytes = 1024))
    }

    @Test
    fun `over-limit body throws the specific ResponseTooLargeException`() {
        val tooBig = "x".repeat(2048)
        server.enqueue(MockResponse().setResponseCode(200).setBody(tooBig))
        assertThrows(ResponseTooLargeException::class.java) {
            get().readBoundedBody(maxBytes = 1024)
        }
    }

    @Test
    fun `body with no Content-Type falls back to UTF-8`() {
        // No Content-Type header -> charset() is null -> UTF-8 default. A
        // multibyte char round-trips only if UTF-8 is actually used.
        val text = "café €"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().writeString(text, Charsets.UTF_8))
        )
        assertEquals(text, get().readBoundedBody(maxBytes = 1024))
    }

    // ========== readBoundedBytes (binary, no charset decode) ==========

    @Test
    fun `readBoundedBytes returns the raw bytes verbatim`() {
        // A byte sequence that is NOT valid UTF-8: 0xFF 0xD8 (JPEG SOI) followed by
        // a lone 0x80 continuation byte. Reading it through a String reader would
        // replace the invalid bytes with U+FFFD and corrupt the image; the binary
        // reader must return every byte untouched.
        val raw = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x80.toByte(), 0x00, 0x41)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(okio.Buffer().write(raw))
        )

        val result = get().readBoundedBytes(maxBytes = 1024)

        assertArrayEquals(raw, result)
    }

    @Test
    fun `readBoundedBytes returns empty array for a null body`() {
        // A 204 No Content has no body; must decode to an empty array, not throw.
        server.enqueue(MockResponse().setResponseCode(204))
        assertArrayEquals(ByteArray(0), get().readBoundedBytes(maxBytes = 1024))
    }

    @Test
    fun `readBoundedBytes throws when Content-Length exceeds the limit`() {
        val tooBig = "x".repeat(2048)
        server.enqueue(MockResponse().setResponseCode(200).setBody(tooBig))
        assertThrows(ResponseTooLargeException::class.java) {
            get().readBoundedBytes(maxBytes = 1024)
        }
    }

    @Test
    fun `readBoundedBytes throws when a chunked body exceeds the limit`() {
        val tooBig = "y".repeat(4096)
        server.enqueue(
            MockResponse().setResponseCode(200).setChunkedBody(tooBig, 256)
        )
        assertThrows(ResponseTooLargeException::class.java) {
            get().readBoundedBytes(maxBytes = 1024)
        }
    }

    @Test
    fun `readBoundedBytes accepts a body of exactly the limit`() {
        val payload = ByteArray(1024) { 0x5A }
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))
        assertArrayEquals(payload, get().readBoundedBytes(maxBytes = 1024))
    }
}
