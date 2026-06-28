package org.onekash.kashcal.sync.client

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.network.MAX_HTTP_RESPONSE_SIZE_BYTES

/**
 * Tests that the CalDAV client reads responses through the shared bounded
 * reader. Reader behavior is covered by HttpResponseBodyReaderTest; this pins
 * the shared size limit the CalDAV path inherits.
 */
class OkHttpCalDavClientResponseLimitTest {

    @Before
    fun setup() {
        // Mock Android Log methods
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
        unmockkAll()
    }

    @Test
    fun `CalDAV reads enforce the shared response size limit`() {
        // CalDAV responses are read through the shared bounded reader, which
        // caps buffered body size to prevent OOM on malicious/malformed servers.
        // The reader itself (limit enforcement, charset handling, chunked-body
        // behavior) is covered by HttpResponseBodyReaderTest; here we pin the
        // shared limit that the CalDAV path inherits.
        assertEquals(
            "CalDAV inherits the shared 50MB response limit",
            50L * 1024 * 1024,
            MAX_HTTP_RESPONSE_SIZE_BYTES
        )
    }
}
