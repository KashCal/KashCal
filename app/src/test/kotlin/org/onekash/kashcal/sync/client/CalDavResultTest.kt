package org.onekash.kashcal.sync.client

import org.junit.Assert.*
import org.junit.Test
import org.onekash.kashcal.sync.client.model.CalDavException
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Tests for CalDavResult sealed class.
 */
class CalDavResultTest {

    // Success tests

    @Test
    fun `success result isSuccess returns true`() {
        val result = CalDavResult.success("data")
        assertTrue(result.isSuccess())
        assertFalse(result.isError())
    }

    @Test
    fun `success result getOrNull returns value`() {
        val result = CalDavResult.success("data")
        assertEquals("data", result.getOrNull())
    }

    @Test
    fun `success result getOrThrow returns value`() {
        val result = CalDavResult.success("data")
        assertEquals("data", result.getOrThrow())
    }

    @Test
    fun `success result map transforms value`() {
        val result = CalDavResult.success(5)
        val mapped = result.map { it * 2 }
        assertEquals(10, mapped.getOrNull())
    }

    @Test
    fun `success result onSuccess executes action`() {
        var executed = false
        CalDavResult.success("data").onSuccess { executed = true }
        assertTrue(executed)
    }

    @Test
    fun `success result onError does not execute action`() {
        var executed = false
        CalDavResult.success("data").onError { executed = true }
        assertFalse(executed)
    }

    // Error tests

    @Test
    fun `error result isError returns true`() {
        val result = CalDavResult.error(500, "Server error")
        assertTrue(result.isError())
        assertFalse(result.isSuccess())
    }

    @Test
    fun `error result getOrNull returns null`() {
        val result = CalDavResult.error(500, "Server error")
        assertNull(result.getOrNull())
    }

    @Test(expected = CalDavException::class)
    fun `error result getOrThrow throws exception`() {
        val result = CalDavResult.error(500, "Server error")
        result.getOrThrow()
    }

    @Test
    fun `error result map does not transform`() {
        val result: CalDavResult<Int> = CalDavResult.error(500, "error")
        val mapped = result.map { it * 2 }
        assertTrue(mapped.isError())
    }

    @Test
    fun `error result onSuccess does not execute action`() {
        var executed = false
        CalDavResult.error(500, "error").onSuccess { executed = true }
        assertFalse(executed)
    }

    @Test
    fun `error result onError executes action`() {
        var executed = false
        CalDavResult.error(500, "error").onError { executed = true }
        assertTrue(executed)
    }

    @Test
    fun `error result preserves code and message`() {
        val result = CalDavResult.error(404, "Not found")
        val error = result as CalDavResult.Error
        assertEquals(404, error.code)
        assertEquals("Not found", error.message)
    }

    @Test
    fun `error result preserves retryable flag`() {
        val retryable = CalDavResult.error(503, "Service unavailable", isRetryable = true)
        val notRetryable = CalDavResult.error(401, "Unauthorized", isRetryable = false)

        assertTrue((retryable as CalDavResult.Error).isRetryable)
        assertFalse((notRetryable as CalDavResult.Error).isRetryable)
    }

    // Factory method tests

    @Test
    fun `networkError creates retryable error`() {
        val result = CalDavResult.networkError("Connection failed")
        val error = result as CalDavResult.Error
        assertEquals(0, error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `authError creates 401 non-retryable error`() {
        val result = CalDavResult.authError("Invalid credentials")
        val error = result as CalDavResult.Error
        assertEquals(401, error.code)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `conflictError creates 412 non-retryable error`() {
        val result = CalDavResult.conflictError("Etag mismatch")
        val error = result as CalDavResult.Error
        assertEquals(412, error.code)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `notFoundError creates 404 non-retryable error`() {
        val result = CalDavResult.notFoundError("Event deleted")
        val error = result as CalDavResult.Error
        assertEquals(404, error.code)
        assertFalse(error.isRetryable)
    }

    // CalDavException tests

    @Test
    fun `exception contains code and message`() {
        val exception = CalDavException(500, "Server error")
        assertEquals(500, exception.code)
        assertEquals("Server error", exception.message)
    }
}
