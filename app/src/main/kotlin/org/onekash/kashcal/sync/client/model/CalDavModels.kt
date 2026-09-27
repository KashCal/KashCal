package org.onekash.kashcal.sync.client.model

/** A calendar collection found by a calendar-home listing. */
data class CalDavCalendar(
    val href: String,
    val url: String,
    val displayName: String,
    val color: String?,
    val ctag: String?,
    val isReadOnly: Boolean = false,
    val supportedComponents: Set<String> = emptySet()
)

/**
 * Holds the ctag and calendar properties read by the getCtag PROPFIND.
 *
 * A null field means the server didn't return that property; the caller keeps the stored value.
 */
data class CalendarMetadataProbe(
    val ctag: String,
    val displayName: String?,
    val color: String?,
    val isReadOnly: Boolean?
)

/** A calendar object resource fetched from the server, with its raw iCalendar body. */
data class CalDavEvent(
    val href: String,
    val url: String,
    val etag: String?,
    val icalData: String
)

/**
 * Holds the changed and deleted members of a sync-collection REPORT and its new sync-token.
 *
 * @param truncated the server cut the report short: a top-level 507, a 507 status in the
 *   body, or number-of-matches-within-limits. The client must continue with the new
 *   [syncToken] (RFC 6578 section 3.6).
 */
data class SyncReport(
    val syncToken: String?,
    val changed: List<SyncItem>,
    val deleted: List<String>,
    val truncated: Boolean = false
)

/** One changed member of a [SyncReport]. */
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

/** Holds the outcome of a CalDAV request: [Success] with data, or [Error] with a code. */
sealed class CalDavResult<out T> {
    /**
     * @property finalUrl where the request ended up when the server redirected it,
     *   null otherwise. Set by writes so the stored URL can follow the resource.
     *   [map] and [success] build a new Success without it.
     */
    data class Success<T>(val data: T, val finalUrl: String? = null) : CalDavResult<T>()
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false
    ) : CalDavResult<Nothing>()

    fun isSuccess() = this is Success
    fun isError() = this is Error
    fun isConflict() = this is Error && (code == 412 || code == 409)
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
        /** A socket timeout: HTTP 408's number, negated so it can't be taken for a server reply. */
        const val CODE_TIMEOUT = -408

        /**
         * The client would not send the request: a redirect from https to plain http
         * (other than to the same host), plain http on an account set up with https,
         * or a redirect loop. See [org.onekash.kashcal.network.DavTransportGuard].
         */
        const val CODE_TRANSPORT_REFUSED = -310

        /**
         * A 2xx reply to a PROPFIND or REPORT that is not a WebDAV multistatus (a
         * hotspot login page, a reply cut off): an error, never an empty answer.
         */
        const val CODE_NOT_MULTISTATUS = -207

        /**
         * Statuses that say a probed resource is no longer there for this account.
         * 403 is one: Mailbox (Open-Xchange) answers 403 for a calendar that was deleted
         * (404 for one that never existed), and a calendar the account can no
         * longer read is gone for it too. Keeping it would fail every later sync of it
         * with an auth-style 403 while the password is fine.
         *
         * Only the direct probe of a calendar that a readable listing left out reaches
         * this; a hotspot page answered with 2xx, a refused connection or a network error
         * keeps the calendar. A 403 counts whatever its body, so a front end that answers
         * 403 itself (a firewall, or a portal on an http or trust-all account) is taken
         * for the server, the same exposure a 404 already has. Over https with a
         * verified certificate that front end is the server's or the user's own.
         */
        val RESOURCE_GONE_CODES = setOf(403, 404, 410)

        fun <T> success(data: T) = Success(data)

        fun error(code: Int, message: String, isRetryable: Boolean = false) =
            Error(code, message, isRetryable)

        fun networkError(message: String) =
            Error(0, message, isRetryable = true)

        /**
         * A request the client would not send; [message] holds masked hosts only.
         * Retryable: a pending change waits for the server or network to be fixed.
         */
        fun transportRefused(message: String) =
            Error(CODE_TRANSPORT_REFUSED, message, isRetryable = true)

        /** A 2xx reply that isn't a WebDAV multistatus; retryable (a hotspot page goes away). */
        fun notMultistatus(problem: String) =
            Error(CODE_NOT_MULTISTATUS, "Server reply is not a WebDAV multistatus: $problem", isRetryable = true)

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

/** Thrown by [CalDavResult.getOrThrow] for a [CalDavResult.Error]. */
class CalDavException(
    val code: Int,
    override val message: String
) : Exception(message)
