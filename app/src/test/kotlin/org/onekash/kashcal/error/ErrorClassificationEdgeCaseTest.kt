package org.onekash.kashcal.error

import org.junit.Assert.*
import org.junit.Test
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.engine.SyncPhase
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.error.SyncErrorBridge
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.SinglePushResult

/**
 * Edge case tests for error classification and mapping.
 *
 * Tests unusual HTTP codes, message-based classification variations,
 * retryability boundaries, and sync error bridge edge cases.
 *
 * Complements ErrorMapperTest (40 tests) and SyncErrorBridgeTest (24 tests).
 */
class ErrorClassificationEdgeCaseTest {

    // ========== ErrorMapper.fromHttpCode Edge Cases ==========

    @Test
    fun `fromHttpCode 418 maps to Unknown`() {
        val error = ErrorMapper.fromHttpCode(418, "I'm a teapot")
        assertTrue("Non-standard code should map to Unknown", error is CalendarError.Unknown)
    }

    @Test
    fun `fromHttpCode 200 maps to Unknown`() {
        // Success codes shouldn't be passed as errors, but handle gracefully
        val error = ErrorMapper.fromHttpCode(200, "OK")
        assertTrue("Success code as error should map to Unknown", error is CalendarError.Unknown)
    }

    @Test
    fun `fromHttpCode 0 maps to Unknown`() {
        val error = ErrorMapper.fromHttpCode(0, "No response")
        assertTrue("Zero code should map to Unknown", error is CalendarError.Unknown)
    }

    @Test
    fun `fromHttpCode negative maps to Unknown`() {
        val error = ErrorMapper.fromHttpCode(-1, "Internal error")
        assertTrue("Negative code should map to Unknown", error is CalendarError.Unknown)
    }

    // ========== ErrorMapper.fromException Edge Cases ==========

    @Test
    fun `fromException IOException with timeout in message maps to Timeout`() {
        val e = java.io.IOException("Connection timeout while reading response")
        val error = ErrorMapper.fromException(e)
        assertTrue("IOException with 'timeout' should map to Timeout", error is CalendarError.Network.Timeout)
    }

    @Test
    fun `fromException IOException with connection in message maps to ConnectionFailed`() {
        val e = java.io.IOException("Connection reset by peer")
        val error = ErrorMapper.fromException(e)
        assertTrue(
            "IOException with 'connection' should map to ConnectionFailed",
            error is CalendarError.Network.ConnectionFailed
        )
    }

    @Test
    fun `fromException IOException with generic message maps to Unknown`() {
        val e = java.io.IOException("Unexpected end of stream")
        val error = ErrorMapper.fromException(e)
        assertTrue("Generic IOException should map to Unknown", error is CalendarError.Unknown)
    }

    @Test
    fun `fromException RuntimeException maps to Unknown`() {
        val e = RuntimeException("Something went wrong")
        val error = ErrorMapper.fromException(e)
        assertTrue("RuntimeException should map to Unknown", error is CalendarError.Unknown)
    }

    @Test
    fun `fromException preserves original exception in Unknown`() {
        val original = RuntimeException("original cause")
        val error = ErrorMapper.fromException(original)
        assertTrue(error is CalendarError.Unknown)
        assertEquals("original cause", (error as CalendarError.Unknown).message)
    }

    // ========== ErrorMapper.isRetryable ==========

    @Test
    fun `isRetryable returns false for Auth errors`() {
        assertFalse(ErrorMapper.isRetryable(CalendarError.Auth.InvalidCredentials))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Auth.AppSpecificPasswordRequired))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Auth.SessionExpired))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Auth.AccountLocked))
    }

    @Test
    fun `isRetryable returns false for Event errors`() {
        assertFalse(ErrorMapper.isRetryable(CalendarError.Event.NotFound(1L)))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Event.CalendarNotFound(1L)))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Event.InvalidData("bad data")))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Event.InvalidRecurrence("bad rule")))
    }

    @Test
    fun `isRetryable returns true for all Network subtypes`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Offline))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Timeout))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.UnknownHost))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.ConnectionFailed()))
    }

    @Test
    fun `isRetryable returns true for Server transient errors`() {
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.TemporarilyUnavailable))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.RateLimited))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.SyncTokenExpired))
    }

    @Test
    fun `isRetryable returns false for Server permanent errors`() {
        assertFalse(ErrorMapper.isRetryable(CalendarError.Server.Forbidden()))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Server.NotFound()))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Server.Conflict()))
    }

    // ========== ErrorMapper.toPresentation ==========

    @Test
    fun `toPresentation silent errors do not produce Snackbar or Dialog`() {
        val alreadySyncing = ErrorMapper.toPresentation(CalendarError.Sync.AlreadySyncing)
        assertTrue("AlreadySyncing should be Silent", alreadySyncing is ErrorPresentation.Silent)

        val cancelled = ErrorMapper.toPresentation(CalendarError.Sync.Cancelled)
        assertTrue("Cancelled should be Silent", cancelled is ErrorPresentation.Silent)
    }

    @Test
    fun `toPresentation Conflict with event title uses event-specific message`() {
        val withTitle = ErrorMapper.toPresentation(CalendarError.Server.Conflict("Meeting"))
        assertTrue(withTitle is ErrorPresentation.Dialog)
        val dialog = withTitle as ErrorPresentation.Dialog
        assertNotNull("Should have messageArgs with event title", dialog.messageArgs)
        assertTrue("messageArgs should contain event title", dialog.messageArgs!!.contains("Meeting"))
    }

    @Test
    fun `toPresentation Conflict without event title uses generic message`() {
        val noTitle = ErrorMapper.toPresentation(CalendarError.Server.Conflict(null))
        assertTrue(noTitle is ErrorPresentation.Dialog)
        val dialog = noTitle as ErrorPresentation.Dialog
        assertTrue(
            "Should have empty messageArgs for generic conflict",
            dialog.messageArgs == null || dialog.messageArgs!!.isEmpty()
        )
    }

    // ========== SyncErrorBridge.fromSyncResult Auth Message Parsing ==========

    @Test
    fun `fromSyncResult AuthError with app-specific message`() {
        val result = SyncResult.AuthError("Authentication failed: app-specific password required")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(
            "Should detect app-specific keyword",
            error is CalendarError.Auth.AppSpecificPasswordRequired
        )
    }

    @Test
    fun `fromSyncResult AuthError with locked message`() {
        val result = SyncResult.AuthError("Account locked for security reasons")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue("Should detect locked keyword", error is CalendarError.Auth.AccountLocked)
    }

    @Test
    fun `fromSyncResult AuthError with expired message`() {
        val result = SyncResult.AuthError("Session expired, please re-authenticate")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue("Should detect expired keyword", error is CalendarError.Auth.SessionExpired)
    }

    @Test
    fun `fromSyncResult AuthError with unknown message falls back to InvalidCredentials`() {
        val result = SyncResult.AuthError("HTTP 401 Unauthorized")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue("Should fall back to InvalidCredentials", error is CalendarError.Auth.InvalidCredentials)
    }

    @Test
    fun `fromSyncResult AuthError case insensitive matching`() {
        val result = SyncResult.AuthError("APP-SPECIFIC password needed")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(
            "Should match case-insensitively",
            error is CalendarError.Auth.AppSpecificPasswordRequired
        )
    }

    // ========== SyncErrorBridge.fromErrorCode Internal Error (-1) ==========

    @Test
    fun `fromErrorCode -1 with timeout maps to Timeout`() {
        val error = SyncErrorBridge.fromSyncError(
            SyncError(phase = SyncPhase.PULL, code = -1, message = "Connection timeout")
        )
        assertTrue("Internal error with timeout hint should map to Timeout", error is CalendarError.Network.Timeout)
    }

    @Test
    fun `fromErrorCode -1 with ssl maps to SslError`() {
        val error = SyncErrorBridge.fromSyncError(
            SyncError(phase = SyncPhase.PULL, code = -1, message = "SSL handshake failed")
        )
        assertTrue("Internal error with ssl hint should map to SslError", error is CalendarError.Network.SslError)
    }

    @Test
    fun `fromErrorCode -1 with certificate maps to SslError`() {
        val error = SyncErrorBridge.fromSyncError(
            SyncError(phase = SyncPhase.PULL, code = -1, message = "Certificate verification failed")
        )
        assertTrue("Internal error with certificate hint should map to SslError", error is CalendarError.Network.SslError)
    }

    @Test
    fun `fromErrorCode -1 with dns maps to UnknownHost`() {
        val error = SyncErrorBridge.fromSyncError(
            SyncError(phase = SyncPhase.PULL, code = -1, message = "DNS resolution failed")
        )
        assertTrue("Internal error with dns hint should map to UnknownHost", error is CalendarError.Network.UnknownHost)
    }

    @Test
    fun `fromErrorCode -1 with host maps to UnknownHost`() {
        val error = SyncErrorBridge.fromSyncError(
            SyncError(phase = SyncPhase.PULL, code = -1, message = "Unknown host: caldav.example.com")
        )
        assertTrue("Internal error with host hint should map to UnknownHost", error is CalendarError.Network.UnknownHost)
    }

    @Test
    fun `fromErrorCode -1 with unknown message maps to Unknown`() {
        val error = SyncErrorBridge.fromSyncError(
            SyncError(phase = SyncPhase.PULL, code = -1, message = "Something unexpected happened")
        )
        assertTrue("Unknown internal error should map to Unknown", error is CalendarError.Unknown)
    }

    // ========== SyncErrorBridge.fromPullResult ==========

    @Test
    fun `fromPullResult 410 maps to SyncTokenExpired`() {
        val error = SyncErrorBridge.fromPullResult(PullResult.Error(410, "Gone"))
        assertTrue("410 Gone should map to SyncTokenExpired", error is CalendarError.Server.SyncTokenExpired)
    }

    @Test
    fun `fromPullResult 401 maps to InvalidCredentials`() {
        val error = SyncErrorBridge.fromPullResult(PullResult.Error(401, "Unauthorized"))
        assertTrue("401 should map to InvalidCredentials", error is CalendarError.Auth.InvalidCredentials)
    }

    // ========== SyncErrorBridge.fromSinglePushResult ==========

    @Test
    fun `fromSinglePushResult Success returns null`() {
        val error = SyncErrorBridge.fromSinglePushResult(SinglePushResult.Success("url", "etag"))
        assertNull("Success should return null", error)
    }

    @Test
    fun `fromSinglePushResult Conflict includes event title`() {
        val error = SyncErrorBridge.fromSinglePushResult(
            SinglePushResult.Conflict(), eventTitle = "Team Meeting"
        )
        assertNotNull(error)
        assertTrue("Should be Server.Conflict", error is CalendarError.Server.Conflict)
        assertEquals("Team Meeting", (error as CalendarError.Server.Conflict).eventTitle)
    }

    // ========== SyncErrorBridge.fromSyncResult Success/PartialSuccess ==========

    @Test
    fun `fromSyncResult Success returns null`() {
        val result = SyncResult.Success(calendarsSynced = 3, durationMs = 1000)
        assertNull("Success should return null", SyncErrorBridge.fromSyncResult(result))
    }

    @Test
    fun `fromSyncResult PartialSuccess returns PartialFailure with nested errors`() {
        val errors = listOf(
            SyncError(phase = SyncPhase.PULL, code = 401, message = "Unauthorized"),
            SyncError(phase = SyncPhase.PUSH, code = 500, message = "Server Error")
        )
        val result = SyncResult.PartialSuccess(
            calendarsSynced = 2, errors = errors, durationMs = 1000
        )
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue("Should be PartialFailure", error is CalendarError.Sync.PartialFailure)
        val partial = error as CalendarError.Sync.PartialFailure
        assertEquals("Should have 2 nested errors", 2, partial.errors.size)
        assertEquals("Success count should be 2", 2, partial.successCount)
        assertEquals("Failed count should be 2", 2, partial.failedCount)
    }
}
