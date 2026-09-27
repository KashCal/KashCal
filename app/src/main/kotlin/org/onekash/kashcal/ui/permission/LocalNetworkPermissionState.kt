package org.onekash.kashcal.ui.permission

/**
 * Holds the local-network permission state behind the inline banners of CalDAV sign-in and the
 * add-subscription dialog.
 *
 * Mirrors [ContactsPermissionState]: the banner never blocks the primary task (manual server
 * entry works in every state), and permanent denial is detected from the post-request rationale
 * signal, not a denial count.
 *
 * Adds [NotRequired] below Android 17 (API 37), where apps with INTERNET keep implicit
 * local-network access and there is no runtime prompt; the banner never shows then.
 */
sealed interface LocalNetworkPermissionState {
    /** Granted: LAN sync works. */
    data object Granted : LocalNetworkPermissionState

    /** Below Android 17: no runtime permission needed. */
    data object NotRequired : LocalNetworkPermissionState

    /** Not granted and no rationale due: shows the educational banner for LAN servers. */
    data object NotRequested : LocalNetworkPermissionState

    /** Denied without "don't ask again": the banner can offer the ask again. */
    data object ShouldShowRationale : LocalNetworkPermissionState

    /** Denied with "don't ask again": the banner hides; manual entry remains. */
    data object PermanentlyDenied : LocalNetworkPermissionState
}

/**
 * Classifies a permission request's outcome the same way [classifyAfterRequest] does for
 * contacts.
 */
fun classifyLocalNetworkAfterRequest(
    granted: Boolean,
    rationaleBefore: Boolean,
    rationaleAfter: Boolean,
): LocalNetworkPermissionState = when {
    granted -> LocalNetworkPermissionState.Granted
    rationaleAfter -> LocalNetworkPermissionState.ShouldShowRationale
    else -> LocalNetworkPermissionState.PermanentlyDenied
}

/**
 * Resolves the state from a fresh reading, so a grant or revoke made in system settings shows.
 *
 * Like [resolveContactsPermissionState], "not granted, no rationale" resolves to
 * [LocalNetworkPermissionState.NotRequested], never PermanentlyDenied, and a revoked permission
 * never resolves to Granted.
 *
 * @param permissionRequired false below API 37, which resolves to
 *   [LocalNetworkPermissionState.NotRequired]
 */
fun resolveLocalNetworkPermissionState(
    permissionRequired: Boolean,
    granted: Boolean,
    shouldShowRationale: Boolean,
): LocalNetworkPermissionState = when {
    !permissionRequired -> LocalNetworkPermissionState.NotRequired
    granted -> LocalNetworkPermissionState.Granted
    shouldShowRationale -> LocalNetworkPermissionState.ShouldShowRationale
    else -> LocalNetworkPermissionState.NotRequested
}

/**
 * Returns whether the local-network banner shows: only when [isLan] and the permission can still
 * be asked for.
 *
 * Hidden when not [isLan], granted, not required (old OS) or permanently denied. Nagging then
 * adds nothing: manual entry is unaffected and the failure hint still fires if a blocked
 * connection is attempted.
 */
fun shouldShowLanBanner(
    isLan: Boolean,
    state: LocalNetworkPermissionState,
): Boolean = isLan && when (state) {
    LocalNetworkPermissionState.NotRequested,
    LocalNetworkPermissionState.ShouldShowRationale -> true
    LocalNetworkPermissionState.Granted,
    LocalNetworkPermissionState.NotRequired,
    LocalNetworkPermissionState.PermanentlyDenied -> false
}

/**
 * Returns whether to append the "allow local network access" hint after a connection failure.
 *
 * Unlike the banner, this is deliberately not gated on [org.onekash.kashcal.util.isLanHost]: on
 * Android 17 only local-network sockets are permission-blocked, so a failure while the permission
 * is required and not granted is itself the signal. It must cover bare-hostname and custom-domain
 * LAN servers string classification can't detect. The hint is added next to the server's own error,
 * so a public server that is down isn't mislabeled.
 */
fun shouldShowLanHintOnFailure(
    permissionRequired: Boolean,
    granted: Boolean,
): Boolean = permissionRequired && !granted

/**
 * Returns whether a failed request looks like a blocked local-network socket in this state: the
 * permission is required (API 37 and later) but not granted.
 *
 * Holds the state to [shouldShowLanHintOnFailure] mapping so its callers, CalDAV discovery and
 * the ICS subscription fetch, don't re-encode which states mean required and granted.
 */
fun LocalNetworkPermissionState.failureIndicatesBlockedLan(): Boolean =
    shouldShowLanHintOnFailure(
        permissionRequired = this != LocalNetworkPermissionState.NotRequired,
        granted = this == LocalNetworkPermissionState.Granted,
    )

/**
 * Merges the stored state with a live read taken on resume, for example after a change in
 * system settings.
 *
 * A live read ([resolveLocalNetworkPermissionState]) never returns
 * [LocalNetworkPermissionState.PermanentlyDenied]; only [classifyLocalNetworkAfterRequest]
 * produces it, after an in-app request. Overwriting with the live read would turn a
 * PermanentlyDenied back into a banner-showing state that nags on every resume. So a live
 * Granted or NotRequired always applies; any other live read replaces only a stale
 * [LocalNetworkPermissionState.Granted], so PermanentlyDenied stays until a live grant.
 */
fun reconcileOnResume(
    current: LocalNetworkPermissionState,
    resolved: LocalNetworkPermissionState,
): LocalNetworkPermissionState = when (resolved) {
    LocalNetworkPermissionState.Granted,
    LocalNetworkPermissionState.NotRequired -> resolved
    else -> if (current == LocalNetworkPermissionState.Granted) resolved else current
}
