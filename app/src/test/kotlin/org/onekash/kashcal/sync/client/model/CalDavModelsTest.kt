package org.onekash.kashcal.sync.client.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for CalDavResult error factories.
 */
class CalDavModelsTest {

    @Test
    fun `timeoutError returns correct code and isRetryable`() {
        val result = CalDavResult.timeoutError("Socket timeout")

        assertTrue(result is CalDavResult.Error)
        val error = result as CalDavResult.Error
        assertEquals(CalDavResult.CODE_TIMEOUT, error.code)
        assertEquals(-408, error.code)
        assertTrue(error.isRetryable)
        assertTrue(error.message.contains("timeout"))
    }

    @Test
    fun `CODE_TIMEOUT constant is -408`() {
        assertEquals(-408, CalDavResult.CODE_TIMEOUT)
    }

    @Test
    fun `networkError returns code 0 and isRetryable`() {
        val result = CalDavResult.networkError("Connection reset")

        assertTrue(result is CalDavResult.Error)
        val error = result as CalDavResult.Error
        assertEquals(0, error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `authError returns code 401 and not retryable`() {
        val result = CalDavResult.authError("Unauthorized")

        assertTrue(result is CalDavResult.Error)
        val error = result as CalDavResult.Error
        assertEquals(401, error.code)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `conflictError returns code 412 and not retryable`() {
        val result = CalDavResult.conflictError("ETag mismatch")

        assertTrue(result is CalDavResult.Error)
        val error = result as CalDavResult.Error
        assertEquals(412, error.code)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `notFoundError returns code 404 and not retryable`() {
        val result = CalDavResult.notFoundError("Event not found")

        assertTrue(result is CalDavResult.Error)
        val error = result as CalDavResult.Error
        assertEquals(404, error.code)
        assertFalse(error.isRetryable)
    }
}
