package org.onekash.kashcal.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ErrorActionCallback types.
 */
class ErrorActionCallbackTest {

    @Test
    fun `OpenUrl stores url correctly`() {
        val url = "https://github.com/KashCal/KashCal/issues"
        val callback = ErrorActionCallback.OpenUrl(url)

        assertEquals(url, callback.url)
    }

    @Test
    fun `OpenUrl with different urls are not equal`() {
        val callback1 = ErrorActionCallback.OpenUrl("https://example.com/1")
        val callback2 = ErrorActionCallback.OpenUrl("https://example.com/2")

        assertTrue(callback1 != callback2)
    }

    @Test
    fun `OpenUrl with same url are equal`() {
        val url = "https://github.com/KashCal/KashCal/issues"
        val callback1 = ErrorActionCallback.OpenUrl(url)
        val callback2 = ErrorActionCallback.OpenUrl(url)

        assertEquals(callback1, callback2)
    }

    @Test
    fun `all callback types are distinct`() {
        val callbacks: List<ErrorActionCallback> = listOf(
            ErrorActionCallback.Retry,
            ErrorActionCallback.OpenSettings,
            ErrorActionCallback.OpenAppSettings,
            ErrorActionCallback.OpenAppleIdWebsite,
            ErrorActionCallback.ReAuthenticate,
            ErrorActionCallback.ForceFullSync,
            ErrorActionCallback.ViewSyncDetails,
            ErrorActionCallback.Dismiss,
            ErrorActionCallback.OpenUrl("https://example.com"),
            ErrorActionCallback.Custom { }
        )

        // All should be different types
        val uniqueTypes = callbacks.map { it::class }.toSet()
        assertEquals(callbacks.size, uniqueTypes.size)
    }
}
