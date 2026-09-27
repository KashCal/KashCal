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
 * Pins the shared response size limit that [OkHttpCalDavClient] inherits by reading its response
 * bodies through `readBoundedBody`. The reader itself (limit enforcement, charsets, chunked bodies)
 * is covered by `HttpResponseBodyReaderTest`.
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
        // The cap keeps a malicious or malformed server from running the app out of memory.
        assertEquals(
            "CalDAV inherits the shared 50MB response limit",
            50L * 1024 * 1024,
            MAX_HTTP_RESPONSE_SIZE_BYTES
        )
    }
}
