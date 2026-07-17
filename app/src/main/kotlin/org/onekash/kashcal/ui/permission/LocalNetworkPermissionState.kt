package org.onekash.kashcal.ui.permission

/**
 * Local-network-permission state for the CalDAV sign-in sheet's inline banner.
 *
 * Mirrors [ContactsPermissionState]: the banner never blocks the primary task
 * (manual server entry works in every state), and permanent denial is detected
 * via the rationale-flip signal the Android docs recommend rather than a
 * denial-count heuristic.
 *
 * Adds [NotRequired] for OS versions below Android 17 (API 37), where apps with
 * INTERNET implicitly retain local-network access and there is no runtime
 * prompt — the banner is never shown in that case.
 */
sealed interface LocalNetworkPermissionState {
    /** Permission granted — LAN sync will work. */
    data object Granted : LocalNetworkPermissionState

    /** Pre-Android 17 — no runtime permission needed. */
    data object NotRequired : LocalNetworkPermissionState

    /** Not yet requested — show the educational banner for LAN servers. */
    data object NotRequested : LocalNetworkPermissionState

    /** Denied without "don't ask again" — the banner can offer the ask again. */
    data object ShouldShowRationale : LocalNetworkPermissionState

    /** Denied with "don't ask again" — hide the banner; manual entry remains. */
    data object PermanentlyDenied : LocalNetworkPermissionState
}

/**
 * Classify the outcome of a permission request from the grant result and the
 * `shouldShowRequestPermissionRationale()` value sampled before and after.
 * See [classifyAfterRequest] in the contacts variant for the rationale-flip
 * reasoning; semantics are identical.
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
 * Resolve the current permission state from a fresh reading, used each time the
 * sign-in sheet opens so a grant/revoke performed in system Settings is
 * reflected. Like the contacts variant, the ambiguous "not granted, no
 * rationale" resolves to [LocalNetworkPermissionState.NotRequested] (never
 * PermanentlyDenied), and a revoked permission never resolves to Granted.
 *
 * @param permissionRequired false on API < 37 → [LocalNetworkPermissionState.NotRequired].
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
 * Whether the proactive local-network banner should be shown: only for a LAN
 * server whose permission is still actionable. Hidden for public hosts, when
 * already granted, when not required (old OS), and when permanently denied
 * (nagging adds nothing — manual entry is unaffected and the reactive hint
 * still fires if a blocked sync is attempted).
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
 * Whether to append the "allow local network access" hint after a CalDAV
 * connection failure. Unlike [shouldShowLanBanner], this is deliberately NOT
 * gated on [isLanHost]: on Android 17 only local-network sockets are
 * permission-blocked, so a connection failure while the permission is required
 * and ungranted is itself the signal — and this must serve bare-hostname /
 * custom-domain LAN servers that string classification cannot detect. The hint
 * is additive (kept alongside the server's real error), so a genuinely-down
 * public server is not mislabeled.
 */
fun shouldShowLanHintOnFailure(
    permissionRequired: Boolean,
    granted: Boolean,
): Boolean = permissionRequired && !granted

/**
 * Reconcile the stored permission state with a fresh live read taken on resume
 * (e.g. after the user may have changed it in system Settings). Upgrade-only:
 *
 * A live read via [resolveLocalNetworkPermissionState] can never return
 * [LocalNetworkPermissionState.PermanentlyDenied] — that is produced only by
 * [classifyLocalNetworkAfterRequest]'s rationale-flip after an in-app request.
 * So blindly overwriting with the live read would downgrade a PermanentlyDenied
 * to a banner-showing state and the banner would nag on every resume. This
 * applies a newly-detected grant (or the pre-37 NotRequired), and otherwise
 * only clears a now-stale [LocalNetworkPermissionState.Granted]; it never
 * overwrites PermanentlyDenied.
 */
fun reconcileOnResume(
    current: LocalNetworkPermissionState,
    resolved: LocalNetworkPermissionState,
): LocalNetworkPermissionState = when (resolved) {
    LocalNetworkPermissionState.Granted,
    LocalNetworkPermissionState.NotRequired -> resolved
    else -> if (current == LocalNetworkPermissionState.Granted) resolved else current
}
