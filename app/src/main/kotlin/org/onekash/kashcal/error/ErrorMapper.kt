package org.onekash.kashcal.error

import org.onekash.kashcal.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Maps CalendarError to user-friendly ErrorPresentation.
 *
 * Central location for all error -> UI mapping logic.
 * Ensures consistent error handling across the app.
 *
 * Usage:
 * ```
 * val error = CalendarError.Network.Timeout
 * val presentation = ErrorMapper.toPresentation(error)
 * // presentation is ErrorPresentation.Snackbar with retry action
 * ```
 */
object ErrorMapper {

    /**
     * Convert CalendarError to ErrorPresentation.
     * Determines which UI component should display the error.
     */
    fun toPresentation(error: CalendarError): ErrorPresentation = when (error) {

        // ==================== AUTH ERRORS -> DIALOG ====================

        is CalendarError.Auth.InvalidCredentials -> ErrorPresentation.Dialog(
            titleResId = R.string.error_auth_title,
            messageResId = R.string.error_auth_invalid_credentials,
            primaryAction = DialogAction(
                labelResId = R.string.action_try_again,
                callback = ErrorActionCallback.ReAuthenticate
            ),
            secondaryAction = DialogAction(
                labelResId = R.string.action_cancel,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            )
        )

        is CalendarError.Auth.AppSpecificPasswordRequired -> ErrorPresentation.Dialog(
            titleResId = R.string.error_auth_title,
            messageResId = R.string.error_auth_app_specific_password_required,
            primaryAction = DialogAction(
                labelResId = R.string.action_learn_more,
                callback = ErrorActionCallback.OpenAppleIdWebsite
            ),
            secondaryAction = DialogAction(
                labelResId = R.string.action_close,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            )
        )

        is CalendarError.Auth.SessionExpired -> ErrorPresentation.Dialog(
            titleResId = R.string.error_auth_session_expired_title,
            messageResId = R.string.error_auth_session_expired,
            primaryAction = DialogAction(
                labelResId = R.string.action_sign_in,
                callback = ErrorActionCallback.ReAuthenticate
            ),
            dismissible = false // Must sign in to continue
        )

        is CalendarError.Auth.AccountLocked -> ErrorPresentation.Dialog(
            titleResId = R.string.error_auth_title,
            messageResId = R.string.error_auth_account_locked,
            primaryAction = DialogAction(
                labelResId = R.string.action_open_apple_id,
                callback = ErrorActionCallback.OpenAppleIdWebsite
            ),
            secondaryAction = DialogAction(
                labelResId = R.string.action_close,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            )
        )

        // ==================== NETWORK ERRORS -> SNACKBAR ====================

        is CalendarError.Network.Offline -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_network_offline,
            action = SnackbarAction(
                labelResId = R.string.action_retry,
                callback = ErrorActionCallback.Retry
            ),
            duration = ErrorPresentation.Snackbar.SnackbarDuration.Long
        )

        is CalendarError.Network.Timeout -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_network_timeout,
            action = SnackbarAction(
                labelResId = R.string.action_retry,
                callback = ErrorActionCallback.Retry
            )
        )

        is CalendarError.Network.SslError -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_network_ssl
        )

        is CalendarError.Network.UnknownHost -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_network_unknown_host,
            action = SnackbarAction(
                labelResId = R.string.action_retry,
                callback = ErrorActionCallback.Retry
            )
        )

        is CalendarError.Network.ConnectionFailed -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_network_connection_failed,
            action = SnackbarAction(
                labelResId = R.string.action_retry,
                callback = ErrorActionCallback.Retry
            )
        )

        // ==================== SERVER ERRORS ====================

        is CalendarError.Server.TemporarilyUnavailable -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_server_unavailable,
            action = SnackbarAction(
                labelResId = R.string.action_retry,
                callback = ErrorActionCallback.Retry
            ),
            duration = ErrorPresentation.Snackbar.SnackbarDuration.Long
        )

        is CalendarError.Server.RateLimited -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_server_rate_limited,
            duration = ErrorPresentation.Snackbar.SnackbarDuration.Long
        )

        is CalendarError.Server.Forbidden -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_server_forbidden
        )

        is CalendarError.Server.NotFound -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_server_not_found
        )

        is CalendarError.Server.Conflict -> ErrorPresentation.Dialog(
            titleResId = R.string.error_conflict_title,
            messageResId = if (error.eventTitle != null) {
                R.string.error_conflict_with_event
            } else {
                R.string.error_conflict_generic
            },
            messageArgs = if (error.eventTitle != null) {
                arrayOf(error.eventTitle)
            } else {
                emptyArray()
            },
            primaryAction = DialogAction(
                labelResId = R.string.action_force_sync,
                callback = ErrorActionCallback.ForceFullSync
            ),
            secondaryAction = DialogAction(
                labelResId = R.string.action_cancel,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            )
        )

        is CalendarError.Server.SyncTokenExpired -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_sync_token_expired,
            action = SnackbarAction(
                labelResId = R.string.action_sync_now,
                callback = ErrorActionCallback.ForceFullSync
            )
        )

        // ==================== EVENT ERRORS -> SNACKBAR ====================

        is CalendarError.Event.NotFound -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_event_not_found
        )

        is CalendarError.Event.CalendarNotFound -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_calendar_not_found
        )

        is CalendarError.Event.ReadOnlyCalendar -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_calendar_read_only,
            messageArgs = arrayOf(error.calendarName)
        )

        is CalendarError.Event.InvalidData -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_event_invalid_data
        )

        is CalendarError.Event.InvalidRecurrence -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_event_invalid_recurrence
        )

        // ==================== IMPORT/EXPORT ERRORS ====================

        is CalendarError.ImportExport.FileNotFound -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_import_file_not_found
        )

        is CalendarError.ImportExport.InvalidIcsFormat -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_import_invalid_format,
            duration = ErrorPresentation.Snackbar.SnackbarDuration.Long
        )

        is CalendarError.ImportExport.PartialImport -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_import_partial,
            messageArgs = arrayOf(error.imported, error.failed),
            duration = ErrorPresentation.Snackbar.SnackbarDuration.Long
        )

        is CalendarError.ImportExport.ExportFailed -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_export_failed
        )

        is CalendarError.ImportExport.NoEventsToExport -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_export_no_events
        )

        // ==================== STORAGE ERRORS -> DIALOG ====================

        is CalendarError.Storage.StorageFull -> ErrorPresentation.Dialog(
            titleResId = R.string.error_storage_full_title,
            messageResId = R.string.error_storage_full,
            primaryAction = DialogAction(
                labelResId = R.string.action_open_settings,
                callback = ErrorActionCallback.OpenAppSettings
            ),
            secondaryAction = DialogAction(
                labelResId = R.string.action_close,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            )
        )

        is CalendarError.Storage.DatabaseCorruption -> ErrorPresentation.Dialog(
            titleResId = R.string.error_database_corruption_title,
            messageResId = R.string.error_database_corruption,
            primaryAction = DialogAction(
                labelResId = R.string.action_report_issue,
                callback = ErrorActionCallback.OpenUrl("https://github.com/KashCal/KashCal/issues")
            ),
            secondaryAction = DialogAction(
                labelResId = R.string.action_close,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            ),
            dismissible = true
        )

        is CalendarError.Storage.MigrationFailed -> ErrorPresentation.Dialog(
            titleResId = R.string.error_migration_title,
            messageResId = R.string.error_migration_failed,
            primaryAction = DialogAction(
                labelResId = R.string.action_ok,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            ),
            dismissible = false
        )

        // ==================== PERMISSION ERRORS -> DIALOG ====================

        is CalendarError.Permission.NotificationDenied -> ErrorPresentation.Dialog(
            titleResId = R.string.error_permission_notification_title,
            messageResId = R.string.error_permission_notification,
            primaryAction = DialogAction(
                labelResId = R.string.action_open_settings,
                callback = ErrorActionCallback.OpenAppSettings
            ),
            secondaryAction = DialogAction(
                labelResId = R.string.action_not_now,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            )
        )

        is CalendarError.Permission.ExactAlarmDenied -> ErrorPresentation.Dialog(
            titleResId = R.string.error_permission_alarm_title,
            messageResId = R.string.error_permission_alarm,
            primaryAction = DialogAction(
                labelResId = R.string.action_open_settings,
                callback = ErrorActionCallback.OpenAppSettings
            ),
            secondaryAction = DialogAction(
                labelResId = R.string.action_not_now,
                callback = ErrorActionCallback.Dismiss,
                isDismissAction = true
            )
        )

        is CalendarError.Permission.StorageDenied -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_permission_storage
        )

        // ==================== SYNC ERRORS ====================

        is CalendarError.Sync.AlreadySyncing -> ErrorPresentation.Silent(
            logMessage = "Sync already in progress, ignoring request"
        )

        is CalendarError.Sync.NoAccountsConfigured -> ErrorPresentation.Banner(
            messageResId = R.string.error_sync_no_accounts,
            type = ErrorPresentation.Banner.BannerType.Info,
            action = BannerAction(
                labelResId = R.string.action_set_up,
                callback = ErrorActionCallback.OpenSettings
            )
        )

        is CalendarError.Sync.PartialFailure -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_sync_partial,
            messageArgs = arrayOf(error.successCount, error.failedCount),
            action = SnackbarAction(
                labelResId = R.string.action_details,
                callback = ErrorActionCallback.ViewSyncDetails
            ),
            duration = ErrorPresentation.Snackbar.SnackbarDuration.Long
        )

        is CalendarError.Sync.Cancelled -> ErrorPresentation.Silent(
            logMessage = "Sync cancelled by user"
        )

        // ==================== UNKNOWN ====================

        is CalendarError.Unknown -> ErrorPresentation.Snackbar(
            messageResId = R.string.error_unknown
        )
    }

    /**
     * Convert HTTP status code to CalendarError.
     *
     * @param code HTTP status code
     * @param message Optional context message (e.g., resource name, event title)
     */
    fun fromHttpCode(code: Int, message: String? = null): CalendarError = when (code) {
        401 -> CalendarError.Auth.InvalidCredentials
        403 -> CalendarError.Server.Forbidden(message)
        404 -> CalendarError.Server.NotFound(message)
        412 -> CalendarError.Server.Conflict(message)
        429 -> CalendarError.Server.RateLimited
        in 500..599 -> CalendarError.Server.TemporarilyUnavailable
        else -> CalendarError.Unknown("HTTP $code: ${message ?: "Unknown error"}")
    }

    /**
     * Convert Exception to CalendarError.
     *
     * @param e The exception to convert
     */
    fun fromException(e: Throwable): CalendarError = when (e) {
        is SocketTimeoutException -> CalendarError.Network.Timeout
        is UnknownHostException -> CalendarError.Network.UnknownHost
        is SSLHandshakeException -> CalendarError.Network.SslError
        is ConnectException -> CalendarError.Network.ConnectionFailed(e.message)
        is java.io.IOException -> {
            // Check for common IO error patterns
            when {
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    CalendarError.Network.Timeout
                e.message?.contains("connection", ignoreCase = true) == true ->
                    CalendarError.Network.ConnectionFailed(e.message)
                else -> CalendarError.Unknown(e.message ?: "I/O error", e)
            }
        }
        is android.database.sqlite.SQLiteFullException ->
            CalendarError.Storage.StorageFull
        is android.database.sqlite.SQLiteDatabaseCorruptException ->
            CalendarError.Storage.DatabaseCorruption
        else -> CalendarError.Unknown(e.message ?: "Unknown error", e)
    }

    /**
     * Check if an error is retryable (transient).
     */
    fun isRetryable(error: CalendarError): Boolean = when (error) {
        is CalendarError.Network.Offline,
        is CalendarError.Network.Timeout,
        is CalendarError.Network.UnknownHost,
        is CalendarError.Network.ConnectionFailed,
        is CalendarError.Server.TemporarilyUnavailable,
        is CalendarError.Server.RateLimited,
        is CalendarError.Server.SyncTokenExpired -> true
        else -> false
    }
}
