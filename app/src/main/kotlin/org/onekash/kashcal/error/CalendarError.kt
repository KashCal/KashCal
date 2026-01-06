package org.onekash.kashcal.error

/**
 * Centralized error types for KashCal.
 *
 * Usage:
 * ```
 * try {
 *     syncEngine.sync()
 * } catch (e: Exception) {
 *     val error = ErrorMapper.fromException(e)
 *     viewModel.showError(error)
 * }
 * ```
 *
 * Design:
 * - Sealed class hierarchy for exhaustive when() expressions
 * - Data classes for errors with parameters
 * - Data objects for singleton errors
 * - Nested sealed classes for organization
 */
sealed class CalendarError {

    /**
     * Authentication errors requiring user action.
     * Always displayed as Dialog.
     */
    sealed class Auth : CalendarError() {
        /** Invalid Apple ID or app-specific password */
        data object InvalidCredentials : Auth()

        /** User entered regular Apple ID password instead of app-specific password */
        data object AppSpecificPasswordRequired : Auth()

        /** OAuth token expired, needs re-authentication */
        data object SessionExpired : Auth()

        /** Apple ID locked due to security (too many attempts, etc.) */
        data object AccountLocked : Auth()
    }

    /**
     * Network connectivity errors.
     * Usually transient, displayed as Snackbar with Retry.
     */
    sealed class Network : CalendarError() {
        /** Device is offline */
        data object Offline : Network()

        /** Connection timed out */
        data object Timeout : Network()

        /** SSL/TLS certificate error */
        data object SslError : Network()

        /** DNS resolution failed */
        data object UnknownHost : Network()

        /** Generic connection failure */
        data class ConnectionFailed(val detail: String? = null) : Network()
    }

    /**
     * Server-side errors.
     * Mix of transient (5xx) and permanent (4xx).
     */
    sealed class Server : CalendarError() {
        /** 5xx - Server temporarily unavailable */
        data object TemporarilyUnavailable : Server()

        /** 429 - Too many requests, rate limited */
        data object RateLimited : Server()

        /** 403 - Access denied */
        data class Forbidden(val resource: String? = null) : Server()

        /** 404 - Resource not found */
        data class NotFound(val resource: String? = null) : Server()

        /** 412 - ETag conflict, event modified elsewhere */
        data class Conflict(val eventTitle: String? = null) : Server()

        /** Sync token expired, need full sync */
        data object SyncTokenExpired : Server()
    }

    /**
     * Event operation errors.
     * Displayed as Snackbar.
     */
    sealed class Event : CalendarError() {
        /** Event ID not found in database */
        data class NotFound(val eventId: Long) : Event()

        /** Calendar ID not found */
        data class CalendarNotFound(val calendarId: Long) : Event()

        /** Cannot modify read-only calendar */
        data class ReadOnlyCalendar(val calendarName: String) : Event()

        /** Invalid event data (e.g., end before start) */
        data class InvalidData(val reason: String) : Event()

        /** RRULE parsing failed */
        data class InvalidRecurrence(val rule: String) : Event()
    }

    /**
     * Import/Export errors.
     * Displayed as Snackbar, may include partial success info.
     */
    sealed class ImportExport : CalendarError() {
        /** File not found or unreadable */
        data object FileNotFound : ImportExport()

        /** ICS file parsing failed */
        data class InvalidIcsFormat(val detail: String? = null) : ImportExport()

        /** Partial import - some events succeeded, some failed */
        data class PartialImport(
            val imported: Int,
            val failed: Int,
            val failedTitles: List<String> = emptyList()
        ) : ImportExport()

        /** Export write operation failed */
        data class ExportFailed(val reason: String? = null) : ImportExport()

        /** Calendar has no events to export */
        data object NoEventsToExport : ImportExport()
    }

    /**
     * Storage/Database errors.
     * Serious errors, displayed as Dialog.
     */
    sealed class Storage : CalendarError() {
        /** Device storage full */
        data object StorageFull : Storage()

        /** SQLite database corrupted */
        data object DatabaseCorruption : Storage()

        /** Room migration failed */
        data class MigrationFailed(
            val fromVersion: Int,
            val toVersion: Int
        ) : Storage()
    }

    /**
     * Android permission errors.
     * Displayed as Dialog with Settings action.
     */
    sealed class Permission : CalendarError() {
        /** Notification permission denied */
        data object NotificationDenied : Permission()

        /** Exact alarm permission denied (Android 12+) */
        data object ExactAlarmDenied : Permission()

        /** Storage permission for import/export */
        data object StorageDenied : Permission()
    }

    /**
     * Sync operation errors.
     * Various presentations based on severity.
     */
    sealed class Sync : CalendarError() {
        /** Sync already in progress */
        data object AlreadySyncing : Sync()

        /** No iCloud account configured */
        data object NoAccountsConfigured : Sync()

        /** Some calendars synced, some failed */
        data class PartialFailure(
            val successCount: Int,
            val failedCount: Int,
            val errors: List<CalendarError> = emptyList()
        ) : Sync()

        /** User cancelled sync */
        data object Cancelled : Sync()
    }

    /**
     * Unknown/unexpected errors.
     * Fallback for unhandled cases.
     */
    data class Unknown(
        val message: String,
        val throwable: Throwable? = null
    ) : CalendarError()
}
