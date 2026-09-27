package org.onekash.kashcal.error

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Lists the errors the app can show; [ErrorMapper.toPresentation] decides how each is displayed.
 *
 * ```
 * try {
 *     syncEngine.sync()
 * } catch (e: Exception) {
 *     val error = ErrorMapper.fromException(e)
 *     viewModel.showError(error)
 * }
 * ```
 */
@Immutable
sealed class CalendarError {

    /** Authentication errors that need the user to act; always shown as a dialog. */
    @Immutable
    sealed class Auth : CalendarError() {
        /** Credentials rejected, for example an HTTP 401 ([ErrorMapper.fromHttpCode]). */
        data object InvalidCredentials : Auth()

        /** The user entered the regular Apple ID password instead of an app-specific password. */
        data object AppSpecificPasswordRequired : Auth()

        /** The iCloud session expired; the user must sign in again. */
        data object SessionExpired : Auth()

        /** The Apple ID is locked for security, for example after too many attempts. */
        data object AccountLocked : Auth()
    }

    /**
     * Network connectivity errors, shown as a snackbar. All but [SslError] are retryable
     * ([ErrorMapper.isRetryable]) and offer Retry.
     */
    @Immutable
    sealed class Network : CalendarError() {
        /** The device is offline. */
        data object Offline : Network()

        /** The connection timed out. */
        data object Timeout : Network()

        /** The TLS handshake failed. */
        data object SslError : Network()

        /** DNS resolution failed. */
        data object UnknownHost : Network()

        /** Any other connection failure. */
        data class ConnectionFailed(val detail: String? = null) : Network()
    }

    /**
     * Server-side errors. 5xx, 429 and an expired sync-token are retryable
     * ([ErrorMapper.isRetryable]); 403, 404 and 412 are not.
     */
    @Immutable
    sealed class Server : CalendarError() {
        /** 5xx: the server is temporarily unavailable. */
        data object TemporarilyUnavailable : Server()

        /** 429: rate limited. */
        data object RateLimited : Server()

        /** 403: access denied. */
        data class Forbidden(val resource: String? = null) : Server()

        /** 404: resource not found. */
        data class NotFound(val resource: String? = null) : Server()

        /** 412: ETag conflict; the event was modified elsewhere. */
        data class Conflict(val eventTitle: String? = null) : Server()

        /** The sync-token expired; a full sync is needed. */
        data object SyncTokenExpired : Server()
    }

    /** Event operation errors, shown as a snackbar. */
    @Immutable
    sealed class Event : CalendarError() {
        /** No event with this ID in the database. */
        data class NotFound(val eventId: Long) : Event()

        /** No calendar with this ID. */
        data class CalendarNotFound(val calendarId: Long) : Event()

        /** The calendar is read-only. */
        data class ReadOnlyCalendar(val calendarName: String) : Event()

        /** Invalid event data, for example an end before the start. */
        data class InvalidData(val reason: String) : Event()

        /** The RRULE couldn't be parsed. */
        data class InvalidRecurrence(val rule: String) : Event()
    }

    /** Import and export errors, shown as a snackbar; [PartialImport] carries the counts. */
    @Immutable
    sealed class ImportExport : CalendarError() {
        /** The file is missing or unreadable. */
        data object FileNotFound : ImportExport()

        /** The ICS file couldn't be parsed. */
        data class InvalidIcsFormat(val detail: String? = null) : ImportExport()

        /** Some events imported and some failed. */
        data class PartialImport(
            val imported: Int,
            val failed: Int,
            val failedTitles: List<String> = emptyList()
        ) : ImportExport()

        /** Writing the export failed. */
        data class ExportFailed(val reason: String? = null) : ImportExport()

        /** The calendar has no events to export. */
        data object NoEventsToExport : ImportExport()
    }

    /** Storage and database errors, shown as a dialog. */
    @Immutable
    sealed class Storage : CalendarError() {
        /** Device storage is full. */
        data object StorageFull : Storage()

        /** The SQLite database is corrupt. */
        data object DatabaseCorruption : Storage()

        /** A Room migration failed. */
        data class MigrationFailed(
            val fromVersion: Int,
            val toVersion: Int
        ) : Storage()
    }

    /**
     * Android permission errors. Notification and exact-alarm denials show a dialog that opens
     * the app's settings; [StorageDenied] shows a snackbar.
     */
    @Immutable
    sealed class Permission : CalendarError() {
        /** Notification permission denied. */
        data object NotificationDenied : Permission()

        /** Exact alarm permission denied (Android 12+). */
        data object ExactAlarmDenied : Permission()

        /** Storage permission, needed for import and export, denied. */
        data object StorageDenied : Permission()
    }

    /**
     * Device calendar (CalendarProvider) write errors. [WriteFailed] shows a dialog with Retry,
     * [PermissionDenied] a dialog that opens the app's settings, and the rest a snackbar.
     */
    @Immutable
    sealed class DeviceCalendar : CalendarError() {
        /** The write failed. */
        data class WriteFailed(val message: String) : DeviceCalendar()

        /** WRITE_CALENDAR permission denied or revoked. */
        data object PermissionDenied : DeviceCalendar()

        /** The target calendar isn't in CalendarProvider. */
        data object CalendarNotFound : DeviceCalendar()

        /** The event, or the row of it a write targets, isn't in CalendarProvider. */
        data object EventNotFound : DeviceCalendar()

        /** The calendar is read-only (`CAL_ACCESS_READ` or `CAL_ACCESS_FREEBUSY`). */
        data object ReadOnlyCalendar : DeviceCalendar()
    }

    /**
     * Sync errors. [AlreadySyncing] and [Cancelled] are only logged, [NoAccountsConfigured] shows
     * a banner and [PartialFailure] a snackbar.
     */
    @Stable
    sealed class Sync : CalendarError() {
        /** A sync is already running. */
        data object AlreadySyncing : Sync()

        /** No iCloud account is configured. */
        data object NoAccountsConfigured : Sync()

        /** Some calendars synced and some failed. */
        data class PartialFailure(
            val successCount: Int,
            val failedCount: Int,
            val errors: List<CalendarError> = emptyList()
        ) : Sync()

        /** The user cancelled the sync. */
        data object Cancelled : Sync()
    }

    /** Any error no other type covers. */
    data class Unknown(
        val message: String,
        val throwable: Throwable? = null
    ) : CalendarError()
}

/**
 * Wraps a [CalendarError] as the [Throwable] that [Result.failure] requires.
 *
 * ```
 * Result.failure(CalendarErrorException(CalendarError.DeviceCalendar.PermissionDenied))
 * ```
 *
 * Unwrap:
 * ```
 * result.exceptionOrNull()?.let { exception ->
 *     val error = (exception as? CalendarErrorException)?.error
 *     // Handle error
 * }
 * ```
 */
class CalendarErrorException(val error: CalendarError) : Exception(error.toString())
