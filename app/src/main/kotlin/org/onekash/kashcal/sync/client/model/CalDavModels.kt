package org.onekash.kashcal.sync.client.model

/**
 * Represents a calendar discovered from CalDAV server.
 */
data class CalDavCalendar(
    val href: String,
    val url: String,
    val displayName: String,
    val color: String?,
    val ctag: String?,
    val isReadOnly: Boolean = false
)

/**
 * Represents an event fetched from CalDAV server.
 */
data class CalDavEvent(
    val href: String,
    val url: String,
    val etag: String?,
    val icalData: String
)

/**
 * Result of a sync-collection REPORT.
 * Contains changed/deleted items and new sync token.
 *
 * @param truncated If true (507 response), server truncated results due to storage constraints.
 *                  Client MUST continue syncing with the new syncToken (RFC 6578 Section 3.6).
 */
data class SyncReport(
    val syncToken: String?,
    val changed: List<SyncItem>,
    val deleted: List<String>,
    val truncated: Boolean = false
)

/**
 * A single item in sync report.
 */
data class SyncItem(
    val href: String,
    val etag: String?,
    val status: SyncItemStatus
)

enum class SyncItemStatus {
    OK,
    NOT_FOUND,
    ERROR
}

/**
 * Result of a CalDAV operation.
 */
sealed class CalDavResult<out T> {
    data class Success<T>(val data: T) : CalDavResult<T>()
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false
    ) : CalDavResult<Nothing>()

    fun isSuccess() = this is Success
    fun isError() = this is Error
    fun isConflict() = this is Error && code == 412
    fun isNotFound() = this is Error && code == 404
    fun isAuthError() = this is Error && code == 401

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw CalDavException(code, message)
    }

    inline fun <R> map(transform: (T) -> R): CalDavResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    inline fun onSuccess(action: (T) -> Unit): CalDavResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (Error) -> Unit): CalDavResult<T> {
        if (this is Error) action(this)
        return this
    }

    companion object {
        /** Error code for SocketTimeoutException (mirrors HTTP 408 but negative to distinguish) */
        const val CODE_TIMEOUT = -408

        fun <T> success(data: T) = Success(data)

        fun error(code: Int, message: String, isRetryable: Boolean = false) =
            Error(code, message, isRetryable)

        fun networkError(message: String) =
            Error(0, message, isRetryable = true)

        fun timeoutError(message: String) =
            Error(CODE_TIMEOUT, message, isRetryable = true)

        fun authError(message: String) =
            Error(401, message, isRetryable = false)

        fun conflictError(message: String) =
            Error(412, message, isRetryable = false)

        fun notFoundError(message: String) =
            Error(404, message, isRetryable = false)
    }
}

/**
 * Exception for CalDAV errors.
 */
class CalDavException(
    val code: Int,
    override val message: String
) : Exception(message)
