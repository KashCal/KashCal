package org.onekash.kashcal.sync.error

import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.SinglePushResult

/**
 * Bridge between sync layer errors and CalendarError system.
 *
 * Converts sync-specific error types to CalendarError for UI presentation.
 * This allows the sync layer to remain unchanged while integrating with
 * the centralized error handling system.
 *
 * Usage:
 * ```
 * val syncResult = syncEngine.syncAccountWithQuirks(account, quirks, client = client)
 * if (syncResult is SyncResult.Error) {
 *     val calendarError = SyncErrorBridge.fromSyncResult(syncResult)
 *     viewModel.showError(calendarError)
 * }
 * ```
 */
object SyncErrorBridge {

    /**
     * Convert SyncResult to CalendarError.
     * Returns null for success results.
     */
    fun fromSyncResult(result: SyncResult): CalendarError? = when (result) {
        is SyncResult.Success -> null
        is SyncResult.PartialSuccess -> {
            // Partial success has errors - return the first significant one
            CalendarError.Sync.PartialFailure(
                successCount = result.calendarsSynced,
                failedCount = result.errors.size,
                errors = result.errors.map { fromSyncError(it) }
            )
        }
        is SyncResult.AuthError -> {
            // Map auth errors based on message content
            when {
                result.message.contains("app-specific", ignoreCase = true) ->
                    CalendarError.Auth.AppSpecificPasswordRequired
                result.message.contains("locked", ignoreCase = true) ->
                    CalendarError.Auth.AccountLocked
                result.message.contains("expired", ignoreCase = true) ->
                    CalendarError.Auth.SessionExpired
                else -> CalendarError.Auth.InvalidCredentials
            }
        }
        is SyncResult.Error -> fromSyncResultError(result)
    }

    /**
     * Convert SyncError (from PartialSuccess) to CalendarError.
     */
    fun fromSyncError(error: SyncError): CalendarError {
        return fromErrorCode(error.code, error.message)
    }

    /**
     * Convert SyncResult.Error to CalendarError.
     */
    private fun fromSyncResultError(error: SyncResult.Error): CalendarError {
        return fromErrorCode(error.code, error.message)
    }

    /**
     * Convert error code and message to CalendarError.
     */
    private fun fromErrorCode(code: Int, message: String): CalendarError {
        return when (code) {
            401 -> CalendarError.Auth.InvalidCredentials
            403 -> CalendarError.Server.Forbidden(message)
            404 -> CalendarError.Server.NotFound(message)
            412 -> CalendarError.Server.Conflict()
            429 -> CalendarError.Server.RateLimited
            in 500..599 -> CalendarError.Server.TemporarilyUnavailable
            -1 -> {
                // Internal error - check message for hints
                when {
                    message.contains("timeout", ignoreCase = true) ->
                        CalendarError.Network.Timeout
                    message.contains("offline", ignoreCase = true) ->
                        CalendarError.Network.Offline
                    message.contains("connection", ignoreCase = true) ->
                        CalendarError.Network.ConnectionFailed(message)
                    message.contains("ssl", ignoreCase = true) ||
                    message.contains("certificate", ignoreCase = true) ->
                        CalendarError.Network.SslError
                    message.contains("host", ignoreCase = true) ||
                    message.contains("dns", ignoreCase = true) ->
                        CalendarError.Network.UnknownHost
                    else -> CalendarError.Unknown(message)
                }
            }
            else -> CalendarError.Unknown("Sync error $code: $message")
        }
    }

    /**
     * Convert PullResult.Error to CalendarError.
     */
    fun fromPullResult(result: PullResult.Error): CalendarError {
        return when (result.code) {
            401 -> CalendarError.Auth.InvalidCredentials
            403 -> CalendarError.Server.Forbidden(result.message)
            404 -> CalendarError.Server.NotFound(result.message)
            410 -> CalendarError.Server.SyncTokenExpired // Gone - sync token expired
            412 -> CalendarError.Server.Conflict()
            429 -> CalendarError.Server.RateLimited
            in 500..599 -> CalendarError.Server.TemporarilyUnavailable
            else -> CalendarError.Unknown("Pull error ${result.code}: ${result.message}")
        }
    }

    /**
     * Convert PushResult.Error to CalendarError.
     */
    fun fromPushResult(result: PushResult.Error): CalendarError {
        return when (result.code) {
            401 -> CalendarError.Auth.InvalidCredentials
            403 -> CalendarError.Server.Forbidden(result.message)
            404 -> CalendarError.Server.NotFound(result.message)
            412 -> CalendarError.Server.Conflict()
            429 -> CalendarError.Server.RateLimited
            in 500..599 -> CalendarError.Server.TemporarilyUnavailable
            else -> CalendarError.Unknown("Push error ${result.code}: ${result.message}")
        }
    }

    /**
     * Convert SinglePushResult to CalendarError.
     * Returns null for success.
     */
    fun fromSinglePushResult(result: SinglePushResult, eventTitle: String? = null): CalendarError? = when (result) {
        is SinglePushResult.Success -> null
        is SinglePushResult.PhaseAdvanced -> null  // MOVE operation advanced to next phase (not an error)
        is SinglePushResult.Conflict -> CalendarError.Server.Conflict(eventTitle)
        is SinglePushResult.Error -> when (result.code) {
            401 -> CalendarError.Auth.InvalidCredentials
            403 -> CalendarError.Server.Forbidden(result.message)
            404 -> CalendarError.Server.NotFound(result.message)
            412 -> CalendarError.Server.Conflict(eventTitle)
            429 -> CalendarError.Server.RateLimited
            in 500..599 -> CalendarError.Server.TemporarilyUnavailable
            else -> CalendarError.Unknown("Push error ${result.code}: ${result.message}")
        }
    }

    /**
     * Check if a CalendarError is retryable (transient).
     * Delegates to ErrorMapper.isRetryable().
     */
    fun isRetryable(error: CalendarError): Boolean {
        return org.onekash.kashcal.error.ErrorMapper.isRetryable(error)
    }
}
