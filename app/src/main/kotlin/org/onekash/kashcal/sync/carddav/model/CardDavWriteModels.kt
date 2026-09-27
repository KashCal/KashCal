package org.onekash.kashcal.sync.carddav.model

/**
 * Data shapes for the CardDAV write path: conditional PUT and DELETE of one contact resource
 * (RFC 6352 §6.3, RFC 4918).
 *
 * `ContactPushStrategy` is the caller. Outcomes are sealed types, not status codes, so it can
 * branch on each one: a stale-version precondition failure is an expected, non-fatal outcome
 * that server wins absorbs, not a generic error.
 */

/**
 * The conditional-request precondition for a contact PUT.
 *
 * RFC 4918 §10.4: a conditional PUT states intent atomically against the server's current
 * state, avoiding a lost-update race.
 */
sealed interface ContactPrecondition {
    /**
     * `If-None-Match: *`: creates only if no resource exists at the target href. Used for a
     * net-new contact so a name collision fails with 412 instead of silently overwriting an
     * unrelated resource.
     */
    data object IfAbsent : ContactPrecondition

    /**
     * `If-Match: "<etag>"`: updates only if the resource still matches [etag], the version the
     * client last saw. A mismatch means the server copy changed and the PUT fails with 412.
     *
     * @property etag the normalized (unquoted) entity tag; the client re-wraps it in quotes.
     */
    data class IfMatch(val etag: String) : ContactPrecondition
}

/**
 * Outcome of uploading a contact vCard via PUT.
 *
 * Each distinguishable outcome is its own variant so the caller never interprets status codes.
 * A transport failure (unreachable host, reset) is [Failed] with `code = 0`, like the result
 * envelope's `networkError`; a request the transport guard refused to send is [Failed] with
 * `CalDavResult.CODE_TRANSPORT_REFUSED`.
 */
sealed interface ContactUploadResult {
    /**
     * 200 OK, 201 Created or 204 No Content: the vCard landed.
     *
     * @property etag the response `ETag`, or null when the server omitted it (RFC 6352/4791
     *   permit this). A null etag is not an error: the next pull re-reads and reconciles.
     * @property finalUrl where the vCard landed when the server redirected the PUT, null
     *   otherwise. The caller stores it as the contact's href.
     */
    data class Success(val etag: String?, val finalUrl: String? = null) : ContactUploadResult

    /**
     * 412 Precondition Failed or 409 Conflict: the name is taken on a create, or the known
     * version is stale on an update. Also returned without a request when the etag can't be
     * placed in a header. Non-fatal: server wins overwrites the local copy on the next pull.
     */
    data object PreconditionFailed : ContactUploadResult

    /** 403 Forbidden: the account lacks write privilege for this resource. */
    data object PermissionDenied : ContactUploadResult

    /** 404 Not Found or 410 Gone: the target resource no longer exists. */
    data object Gone : ContactUploadResult

    /**
     * Any other HTTP status, or a transport failure.
     *
     * @property code the HTTP status, 0 for a transport failure, or
     *   `CalDavResult.CODE_TRANSPORT_REFUSED` for a request the guard refused.
     * @property isRetryable true for 5xx, 429, transport failures and guard refusals. The client
     *   never retries a conditional write itself.
     */
    data class Failed(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false,
    ) : ContactUploadResult
}

/** Outcome of deleting a contact resource via a conditional DELETE (`If-Match`). */
sealed interface ContactDeleteResult {
    /** 200 OK or 204 No Content: the resource was removed. */
    data object Deleted : ContactDeleteResult

    /**
     * 404 Not Found or 410 Gone: already removed, perhaps by another client. The intent is
     * satisfied, so this is not an error.
     */
    data object AlreadyGone : ContactDeleteResult

    /**
     * 412 Precondition Failed or 409 Conflict: the resource changed since the known version.
     * Also returned without a request when the etag can't be placed in a header. Non-fatal:
     * the next pull re-downloads the server copy.
     */
    data object PreconditionFailed : ContactDeleteResult

    /**
     * Any other HTTP status, or a transport failure. [code] and [isRetryable] follow
     * [ContactUploadResult.Failed].
     */
    data class Failed(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false,
    ) : ContactDeleteResult
}
