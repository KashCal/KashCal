package org.onekash.kashcal.sync

import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.ErrorMapper
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.engine.SyncPhase
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.error.SyncErrorBridge
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.SinglePushResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Network resilience tests for sync operations.
 *
 * Tests verify that the sync layer handles network failures gracefully:
 * - HTTP error codes (401, 403, 404, 429, 500-599)
 * - Timeout handling
 * - SSL/TLS errors
 * - Retry classification
 *
 * These tests focus on error classification and retry behavior,
 * not actual network calls (which are tested in integration tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NetworkResilienceTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== HTTP 401 Authentication Tests ====================

    @Test
    fun `401 error maps to InvalidCredentials`() {
        val result = SyncResult.Error(401, "Unauthorized")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    @Test
    fun `401 error from pull maps to InvalidCredentials`() {
        val result = PullResult.Error(401, "Unauthorized")
        val error = SyncErrorBridge.fromPullResult(result)

        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    @Test
    fun `401 error from push maps to InvalidCredentials`() {
        val result = PushResult.Error(401, "Unauthorized")
        val error = SyncErrorBridge.fromPushResult(result)

        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    @Test
    fun `auth error with app-specific password hint maps correctly`() {
        val result = SyncResult.AuthError("Please use an app-specific password")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Auth.AppSpecificPasswordRequired)
    }

    // ==================== HTTP 403 Forbidden Tests ====================

    @Test
    fun `403 error maps to Forbidden`() {
        val result = SyncResult.Error(403, "Calendar is read-only")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.Forbidden)
    }

    @Test
    fun `403 from single push returns Forbidden error`() {
        val result = SinglePushResult.Error(403, "Write access denied")
        val error = SyncErrorBridge.fromSinglePushResult(result, "Team Meeting")

        assertTrue(error is CalendarError.Server.Forbidden)
    }

    // ==================== HTTP 404 Not Found Tests ====================

    @Test
    fun `404 error maps to NotFound`() {
        val result = SyncResult.Error(404, "Calendar not found")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.NotFound)
    }

    @Test
    fun `404 from pull indicates deleted calendar`() {
        val result = PullResult.Error(404, "Resource has been deleted")
        val error = SyncErrorBridge.fromPullResult(result)

        assertTrue(error is CalendarError.Server.NotFound)
    }

    // ==================== HTTP 410 Gone (Sync Token Expired) Tests ====================

    @Test
    fun `410 error maps to SyncTokenExpired`() {
        val result = PullResult.Error(410, "Sync token expired")
        val error = SyncErrorBridge.fromPullResult(result)

        assertTrue(error is CalendarError.Server.SyncTokenExpired)
    }

    // ==================== HTTP 412 Conflict Tests ====================

    @Test
    fun `412 precondition failed maps to Conflict`() {
        val result = SyncResult.Error(412, "ETag mismatch")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.Conflict)
    }

    @Test
    fun `412 from single push includes event title`() {
        val result = SinglePushResult.Error(412, "Precondition failed")
        val error = SyncErrorBridge.fromSinglePushResult(result, "Team Meeting")

        assertTrue(error is CalendarError.Server.Conflict)
        assertEquals("Team Meeting", (error as CalendarError.Server.Conflict).eventTitle)
    }

    @Test
    fun `SinglePushResult Conflict type maps correctly`() {
        val result = SinglePushResult.Conflict("new-etag-123")
        val error = SyncErrorBridge.fromSinglePushResult(result, "Standup")

        assertTrue(error is CalendarError.Server.Conflict)
    }

    // ==================== HTTP 429 Rate Limited Tests ====================

    @Test
    fun `429 error maps to RateLimited`() {
        val result = SyncResult.Error(429, "Too many requests")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.RateLimited)
    }

    @Test
    fun `429 is retryable`() {
        val error = CalendarError.Server.RateLimited
        assertTrue(SyncErrorBridge.isRetryable(error))
    }

    // ==================== HTTP 5xx Server Error Tests ====================

    @Test
    fun `500 error maps to TemporarilyUnavailable`() {
        val result = SyncResult.Error(500, "Internal server error")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `502 bad gateway maps to TemporarilyUnavailable`() {
        val result = SyncResult.Error(502, "Bad Gateway")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `503 service unavailable maps to TemporarilyUnavailable`() {
        val result = SyncResult.Error(503, "Service Unavailable")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `504 gateway timeout maps to TemporarilyUnavailable`() {
        val result = SyncResult.Error(504, "Gateway Timeout")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `5xx errors are retryable`() {
        val error = CalendarError.Server.TemporarilyUnavailable
        assertTrue(SyncErrorBridge.isRetryable(error))
    }

    // ==================== Network Error Tests ====================

    @Test
    fun `timeout error maps to Network Timeout`() {
        val result = SyncResult.Error(-1, "Connection timeout")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Network.Timeout)
    }

    @Test
    fun `offline error maps to Network Offline`() {
        val result = SyncResult.Error(-1, "Device is offline")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Network.Offline)
    }

    @Test
    fun `connection failed error maps correctly`() {
        val result = SyncResult.Error(-1, "Connection refused")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Network.ConnectionFailed)
    }

    @Test
    fun `SSL error maps to SslError`() {
        val result = SyncResult.Error(-1, "SSL handshake failed")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Network.SslError)
    }

    @Test
    fun `certificate error maps to SslError`() {
        val result = SyncResult.Error(-1, "Certificate verification failed")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Network.SslError)
    }

    @Test
    fun `DNS error maps to UnknownHost`() {
        val result = SyncResult.Error(-1, "DNS lookup failed for host")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Network.UnknownHost)
    }

    @Test
    fun `network timeout is retryable`() {
        val error = CalendarError.Network.Timeout
        assertTrue(SyncErrorBridge.isRetryable(error))
    }

    @Test
    fun `network offline is retryable`() {
        val error = CalendarError.Network.Offline
        assertTrue(SyncErrorBridge.isRetryable(error))
    }

    // ==================== Partial Success Tests ====================

    @Test
    fun `partial success contains both success and error counts`() {
        val errors = listOf(
            SyncError(SyncPhase.PULL, calendarId = 1L, code = 500, message = "Server error"),
            SyncError(SyncPhase.PUSH, calendarId = 2L, code = 403, message = "Forbidden")
        )
        val result = SyncResult.PartialSuccess(
            calendarsSynced = 3,
            errors = errors,
            durationMs = 1500L
        )

        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Sync.PartialFailure)
        val partialError = error as CalendarError.Sync.PartialFailure
        assertEquals(3, partialError.successCount)
        assertEquals(2, partialError.failedCount)
        assertEquals(2, partialError.errors.size)
    }

    @Test
    fun `partial success with single error extracts correctly`() {
        val errors = listOf(
            SyncError(SyncPhase.AUTH, calendarId = 1L, code = 401, message = "Auth failed")
        )
        val result = SyncResult.PartialSuccess(
            calendarsSynced = 5,
            errors = errors,
            durationMs = 2000L
        )

        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Sync.PartialFailure)
        val partialError = error as CalendarError.Sync.PartialFailure
        assertEquals(1, partialError.errors.size)
        assertTrue(partialError.errors[0] is CalendarError.Auth.InvalidCredentials)
    }

    // ==================== Success Cases ====================

    @Test
    fun `success result returns null error`() {
        val result = SyncResult.Success(
            calendarsSynced = 5,
            durationMs = 1000L
        )

        val error = SyncErrorBridge.fromSyncResult(result)
        assertNull(error)
    }

    @Test
    fun `single push success returns null error`() {
        val result = SinglePushResult.Success("new-etag", "caldav://url")
        val error = SyncErrorBridge.fromSinglePushResult(result, "Event")
        assertNull(error)
    }

    // ==================== Retryability Tests ====================

    @Test
    fun `auth errors are not retryable`() {
        assertFalse(SyncErrorBridge.isRetryable(CalendarError.Auth.InvalidCredentials))
        assertFalse(SyncErrorBridge.isRetryable(CalendarError.Auth.AppSpecificPasswordRequired))
        assertFalse(SyncErrorBridge.isRetryable(CalendarError.Auth.AccountLocked))
    }

    @Test
    fun `forbidden errors are not retryable`() {
        val error = CalendarError.Server.Forbidden("Read-only calendar")
        assertFalse(SyncErrorBridge.isRetryable(error))
    }

    @Test
    fun `not found errors are not retryable`() {
        val error = CalendarError.Server.NotFound("Deleted")
        assertFalse(SyncErrorBridge.isRetryable(error))
    }

    @Test
    fun `sync token expired is retryable`() {
        val error = CalendarError.Server.SyncTokenExpired
        assertTrue(SyncErrorBridge.isRetryable(error))
    }

    // ==================== ErrorMapper Tests ====================

    @Test
    fun `ErrorMapper fromHttpCode handles 401`() {
        val error = ErrorMapper.fromHttpCode(401)
        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    @Test
    fun `ErrorMapper fromHttpCode handles 403`() {
        val error = ErrorMapper.fromHttpCode(403, "calendar-123")
        assertTrue(error is CalendarError.Server.Forbidden)
    }

    @Test
    fun `ErrorMapper fromHttpCode handles 5xx range`() {
        for (code in listOf(500, 502, 503, 504)) {
            val error = ErrorMapper.fromHttpCode(code)
            assertTrue("$code should map to TemporarilyUnavailable",
                       error is CalendarError.Server.TemporarilyUnavailable)
        }
    }

    @Test
    fun `ErrorMapper fromException handles SocketTimeoutException`() {
        val exception = java.net.SocketTimeoutException("Read timed out")
        val error = ErrorMapper.fromException(exception)
        assertTrue(error is CalendarError.Network.Timeout)
    }

    @Test
    fun `ErrorMapper fromException handles UnknownHostException`() {
        val exception = java.net.UnknownHostException("caldav.icloud.com")
        val error = ErrorMapper.fromException(exception)
        assertTrue(error is CalendarError.Network.UnknownHost)
    }

    @Test
    fun `ErrorMapper fromException handles SSLHandshakeException`() {
        val exception = javax.net.ssl.SSLHandshakeException("Certificate error")
        val error = ErrorMapper.fromException(exception)
        assertTrue(error is CalendarError.Network.SslError)
    }

    @Test
    fun `ErrorMapper fromException handles ConnectException`() {
        val exception = java.net.ConnectException("Connection refused")
        val error = ErrorMapper.fromException(exception)
        assertTrue(error is CalendarError.Network.ConnectionFailed)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `unknown error code maps to Unknown`() {
        val result = SyncResult.Error(999, "Unknown server response")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Unknown)
    }

    @Test
    fun `internal error without recognized pattern maps to Unknown`() {
        val result = SyncResult.Error(-1, "Something weird happened")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Unknown)
    }

    @Test
    fun `empty error message is handled gracefully`() {
        val result = SyncResult.Error(500, "")
        val error = SyncErrorBridge.fromSyncResult(result)

        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `ErrorMapper isRetryable returns correct values`() {
        // Retryable errors
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Offline))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Timeout))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.UnknownHost))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.TemporarilyUnavailable))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.RateLimited))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.SyncTokenExpired))

        // Non-retryable errors
        assertFalse(ErrorMapper.isRetryable(CalendarError.Auth.InvalidCredentials))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Server.Forbidden()))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Server.NotFound()))
    }
}
