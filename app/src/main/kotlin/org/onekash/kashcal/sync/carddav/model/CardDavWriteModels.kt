package org.onekash.kashcal.sync.carddav.model

/**
 * Data shapes for the CardDAV write path (RFC 6352 §6.3, RFC 4918 conditional
 * PUT/DELETE).
 *
 * These describe uploading and deleting a single contact resource. Nothing in the
 * app calls the write verbs yet — they exist so a later sync path can push local
 * contact edits. The outcomes are modelled as sealed types rather than folded
 * into HTTP status codes so the eventual caller can branch on each business
 * outcome explicitly (a stale-version precondition failure is an expected,
 * non-fatal event that server-wins consumes — not a generic "error").
 */

/**
 * The conditional-request precondition for a contact PUT.
 *
 * RFC 4918 §10.4: a conditional PUT lets the client express intent atomically
 * against the server's current state, avoiding a lost-update race.
 */
sealed interface ContactPrecondition {
    /**
     * `If-None-Match: *` — create only if no resource exists at the target href.
     * Used for a brand-new contact so a name collision fails loudly (412) instead
     * of silently clobbering an unrelated resource.
     */
    data object IfAbsent : ContactPrecondition

    /**
     * `If-Match: "<etag>"` — update only if the resource's current version still
     * matches [etag] (the version the client last saw). A mismatch means the
     * server copy changed underneath us and the PUT fails with 412.
     *
     * @property etag the normalized (unquoted) entity tag; the client re-wraps it
     *   in quotes for the header.
     */
    data class IfMatch(val etag: String) : ContactPrecondition
}

/**
 * Outcome of uploading a contact vCard via PUT.
 *
 * Success and every distinguishable failure are separate variants so the caller
 * never has to interpret raw status codes. Transport failures (unreachable host,
 * reset) surface as [Failed] with `code = 0`, mirroring the generic result
 * envelope's `networkError` convention.
 */
sealed interface ContactUploadResult {
    /**
     * 201 Created or 204 No Content — the vCard landed.
     *
     * @property etag the new version identifier from the response `ETag` header,
     *   or null when the server omitted it (RFC 6352/4791 permit this). A null
     *   etag is not an error: the next pull re-reads the resource and reconciles.
     */
    data class Success(val etag: String?) : ContactUploadResult

    /**
     * 412 Precondition Failed or 409 Conflict — the precondition did not hold
     * (the name is already taken on a create, or the known version is stale on an
     * update). Non-fatal by design: server-wins will overwrite the local copy on
     * the next pull.
     */
    data object PreconditionFailed : ContactUploadResult

    /** 403 Forbidden — the account lacks write privilege for this resource. */
    data object PermissionDenied : ContactUploadResult

    /** 404 Not Found or 410 Gone — the target resource no longer exists. */
    data object Gone : ContactUploadResult

    /**
     * Any other HTTP status, or a transport failure.
     *
     * @property code the HTTP status, or 0 for a transport-layer failure.
     * @property isRetryable follows the shared result envelope's convention (5xx,
     *   429, and network failures are retryable).
     */
    data class Failed(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false,
    ) : ContactUploadResult
}

/**
 * Outcome of deleting a contact resource via a conditional DELETE.
 */
sealed interface ContactDeleteResult {
    /** 200 OK or 204 No Content — the resource was removed. */
    data object Deleted : ContactDeleteResult

    /**
     * 404 Not Found or 410 Gone — already removed (perhaps by another client). The
     * caller's intent (make it not exist) is satisfied, so this is not an error.
     */
    data object AlreadyGone : ContactDeleteResult

    /**
     * 412 Precondition Failed or 409 Conflict — the resource changed since the
     * known version. Swallow-able: the next pull re-downloads the server copy.
     */
    data object PreconditionFailed : ContactDeleteResult

    /**
     * Any other HTTP status, or a transport failure ([code] == 0).
     */
    data class Failed(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false,
    ) : ContactDeleteResult
}
