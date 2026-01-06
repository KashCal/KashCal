package org.onekash.kashcal.sync.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.engine.SyncPhase
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.SinglePushResult

/**
 * Tests for SyncErrorBridge.
 *
 * Verifies conversion from sync layer errors to CalendarError.
 */
class SyncErrorBridgeTest {

    // ==================== SyncResult Mapping ====================

    @Test
    fun `Success returns null`() {
        val result = SyncResult.Success(
            calendarsSynced = 2,
            durationMs = 1000
        )
        assertNull(SyncErrorBridge.fromSyncResult(result))
    }

    @Test
    fun `PartialSuccess returns PartialFailure`() {
        val result = SyncResult.PartialSuccess(
            calendarsSynced = 2,
            errors = listOf(
                SyncError(SyncPhase.PULL, code = 500, message = "Server error")
            ),
            durationMs = 1000
        )
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Sync.PartialFailure)
        assertEquals(2, (error as CalendarError.Sync.PartialFailure).successCount)
        assertEquals(1, error.failedCount)
    }

    @Test
    fun `AuthError maps to InvalidCredentials`() {
        val result = SyncResult.AuthError("Invalid credentials")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    @Test
    fun `AuthError with app-specific maps to AppSpecificPasswordRequired`() {
        val result = SyncResult.AuthError("Please use an app-specific password")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Auth.AppSpecificPasswordRequired)
    }

    @Test
    fun `AuthError with locked maps to AccountLocked`() {
        val result = SyncResult.AuthError("Account is locked")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Auth.AccountLocked)
    }

    @Test
    fun `AuthError with expired maps to SessionExpired`() {
        val result = SyncResult.AuthError("Session expired")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Auth.SessionExpired)
    }

    @Test
    fun `Error 401 maps to InvalidCredentials`() {
        val result = SyncResult.Error(401, "Unauthorized")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    @Test
    fun `Error 403 maps to Forbidden`() {
        val result = SyncResult.Error(403, "Forbidden")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Server.Forbidden)
    }

    @Test
    fun `Error 404 maps to NotFound`() {
        val result = SyncResult.Error(404, "Not found")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Server.NotFound)
    }

    @Test
    fun `Error 412 maps to Conflict`() {
        val result = SyncResult.Error(412, "Precondition failed")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Server.Conflict)
    }

    @Test
    fun `Error 429 maps to RateLimited`() {
        val result = SyncResult.Error(429, "Too many requests")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Server.RateLimited)
    }

    @Test
    fun `Error 500 maps to TemporarilyUnavailable`() {
        val result = SyncResult.Error(500, "Internal server error")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `Error -1 with timeout maps to Network Timeout`() {
        val result = SyncResult.Error(-1, "Request timeout")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Network.Timeout)
    }

    @Test
    fun `Error -1 with offline maps to Network Offline`() {
        val result = SyncResult.Error(-1, "Device is offline")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Network.Offline)
    }

    @Test
    fun `Error -1 with ssl maps to Network SslError`() {
        val result = SyncResult.Error(-1, "SSL handshake failed")
        val error = SyncErrorBridge.fromSyncResult(result)
        assertTrue(error is CalendarError.Network.SslError)
    }

    // ==================== PullResult Mapping ====================

    @Test
    fun `PullResult Error 410 maps to SyncTokenExpired`() {
        val result = PullResult.Error(410, "Gone - sync token expired")
        val error = SyncErrorBridge.fromPullResult(result)
        assertTrue(error is CalendarError.Server.SyncTokenExpired)
    }

    @Test
    fun `PullResult Error 401 maps to InvalidCredentials`() {
        val result = PullResult.Error(401, "Unauthorized")
        val error = SyncErrorBridge.fromPullResult(result)
        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    // ==================== PushResult Mapping ====================

    @Test
    fun `PushResult Error 412 maps to Conflict`() {
        val result = PushResult.Error(412, "Precondition failed")
        val error = SyncErrorBridge.fromPushResult(result)
        assertTrue(error is CalendarError.Server.Conflict)
    }

    // ==================== SinglePushResult Mapping ====================

    @Test
    fun `SinglePushResult Success returns null`() {
        val result = SinglePushResult.Success()
        assertNull(SyncErrorBridge.fromSinglePushResult(result))
    }

    @Test
    fun `SinglePushResult Conflict maps to Conflict with title`() {
        val result = SinglePushResult.Conflict()
        val error = SyncErrorBridge.fromSinglePushResult(result, "Team Meeting")
        assertTrue(error is CalendarError.Server.Conflict)
        assertEquals("Team Meeting", (error as CalendarError.Server.Conflict).eventTitle)
    }

    @Test
    fun `SinglePushResult Error 401 maps to InvalidCredentials`() {
        val result = SinglePushResult.Error(401, "Unauthorized")
        val error = SyncErrorBridge.fromSinglePushResult(result)
        assertTrue(error is CalendarError.Auth.InvalidCredentials)
    }

    // ==================== SyncError Mapping ====================

    @Test
    fun `SyncError 500 maps to TemporarilyUnavailable`() {
        val error = SyncError(SyncPhase.PULL, code = 500, message = "Server error")
        val calendarError = SyncErrorBridge.fromSyncError(error)
        assertTrue(calendarError is CalendarError.Server.TemporarilyUnavailable)
    }

    @Test
    fun `SyncError with timeout in message maps to Network Timeout`() {
        val error = SyncError(SyncPhase.PUSH, code = -1, message = "Connection timeout")
        val calendarError = SyncErrorBridge.fromSyncError(error)
        assertTrue(calendarError is CalendarError.Network.Timeout)
    }

    // ==================== Retryable ====================

    @Test
    fun `isRetryable delegates to ErrorMapper`() {
        assertTrue(SyncErrorBridge.isRetryable(CalendarError.Network.Timeout))
        assertTrue(SyncErrorBridge.isRetryable(CalendarError.Server.TemporarilyUnavailable))
        assertTrue(SyncErrorBridge.isRetryable(CalendarError.Server.RateLimited))
    }
}
