package org.onekash.kashcal.sync.error

import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.sync.engine.SyncError
import org.onekash.kashcal.sync.engine.SyncResult
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PushResult
import org.onekash.kashcal.sync.strategy.SinglePushResult

/**
 * Converts sync-layer results and errors to [CalendarError] for the UI, so the sync types stay
 * independent of the UI error hierarchy. Only tests call it today.
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

    /** Converts [result] to a [CalendarError], or null for [SyncResult.Success]. */
    fun fromSyncResult(result: SyncResult): CalendarError? = when (result) {
        is SyncResult.Success -> null
        is SyncResult.PartialSuccess -> {
            // Carries every calendar's error, each converted.
            CalendarError.Sync.PartialFailure(
                successCount = result.calendarsSynced,
                failedCount = result.errors.size,
                errors = result.errors.map { fromSyncError(it) }
            )
        }
        is SyncResult.AuthError -> {
            // The auth error kind is only in the message text.
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

    /** Converts one calendar's [SyncError] from a [SyncResult.PartialSuccess]. */
    fun fromSyncError(error: SyncError): CalendarError {
        return fromErrorCode(error.code, error.message)
    }

    /** Converts a [SyncResult.Error] through [fromErrorCode]. */
    private fun fromSyncResultError(error: SyncResult.Error): CalendarError {
        return fromErrorCode(error.code, error.message)
    }

    /** Maps an HTTP status; for -1, an internal error, the message decides the kind. */
    private fun fromErrorCode(code: Int, message: String): CalendarError {
        return when (code) {
            401 -> CalendarError.Auth.InvalidCredentials
            403 -> CalendarError.Server.Forbidden(message)
            404 -> CalendarError.Server.NotFound(message)
            412 -> CalendarError.Server.Conflict()
            429 -> CalendarError.Server.RateLimited
            in 500..599 -> CalendarError.Server.TemporarilyUnavailable
            -1 -> {
                // An internal error names its cause only in the message.
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

    /** Converts a pull error; 410 means the sync-token expired. */
    fun fromPullResult(result: PullResult.Error): CalendarError {
        return when (result.code) {
            401 -> CalendarError.Auth.InvalidCredentials
            403 -> CalendarError.Server.Forbidden(result.message)
            404 -> CalendarError.Server.NotFound(result.message)
            410 -> CalendarError.Server.SyncTokenExpired
            412 -> CalendarError.Server.Conflict()
            429 -> CalendarError.Server.RateLimited
            in 500..599 -> CalendarError.Server.TemporarilyUnavailable
            else -> CalendarError.Unknown("Pull error ${result.code}: ${result.message}")
        }
    }

    /** Converts a push error. */
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
     * Converts [result] to a [CalendarError], or null for a success or an advanced MOVE phase.
     * [eventTitle] names the event in a conflict.
     */
    fun fromSinglePushResult(result: SinglePushResult, eventTitle: String? = null): CalendarError? = when (result) {
        is SinglePushResult.Success -> null
        is SinglePushResult.PhaseAdvanced -> null
        is SinglePushResult.Conflict -> CalendarError.Server.Conflict(eventTitle)
        is SinglePushResult.RsvpModified -> CalendarError.Server.Conflict(result.eventTitle)
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

    /** Returns whether [error] is transient, as decided by `ErrorMapper.isRetryable`. */
    fun isRetryable(error: CalendarError): Boolean {
        return org.onekash.kashcal.error.ErrorMapper.isRetryable(error)
    }
}
