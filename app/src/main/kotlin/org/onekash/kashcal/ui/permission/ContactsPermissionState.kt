package org.onekash.kashcal.ui.permission

/**
 * Holds the contacts-permission state behind the attendee picker's inline banner.
 *
 * Shaped like [NotificationPermissionManager.PermissionState] but detects permanent denial from
 * the post-request rationale signal ([classifyAfterRequest]) instead of a denial count. The
 * picker never blocks: manual email entry works in every state, so [PermanentlyDenied] hides the
 * banner instead of sending the user to system settings.
 */
sealed interface ContactsPermissionState {
    /** Granted: contact suggestions are available. */
    data object Granted : ContactsPermissionState

    /**
     * Not granted and no rationale due: never asked, or denied for good before this form opened.
     * Shows the educational banner.
     */
    data object NotRequested : ContactsPermissionState

    /** Denied without "don't ask again": the banner can offer the ask again. */
    data object ShouldShowRationale : ContactsPermissionState

    /** Denied with "don't ask again": the banner hides; manual entry remains. */
    data object PermanentlyDenied : ContactsPermissionState
}

/**
 * Classifies a permission request's outcome from the grant result and the
 * `shouldShowRequestPermissionRationale()` values sampled before and after it.
 *
 * - Granted: [ContactsPermissionState.Granted].
 * - Denied with a rationale afterwards: the user can be asked again
 *   ([ContactsPermissionState.ShouldShowRationale]).
 * - Denied with no rationale afterwards: "don't ask again"
 *   ([ContactsPermissionState.PermanentlyDenied]). This covers the flip (rationale true to
 *   false) and a first-ask denial with the checkbox ticked (false to false).
 *
 * [rationaleBefore] is unused; it documents the flip at the call site. The decision keys on the
 * post-request value, the authoritative Android signal.
 */
fun classifyAfterRequest(
    granted: Boolean,
    rationaleBefore: Boolean,
    rationaleAfter: Boolean,
): ContactsPermissionState = when {
    granted -> ContactsPermissionState.Granted
    rationaleAfter -> ContactsPermissionState.ShouldShowRationale
    else -> ContactsPermissionState.PermanentlyDenied
}

/**
 * Resolves the state from a fresh `checkSelfPermission` and
 * `shouldShowRequestPermissionRationale` reading, taken each time the event form opens so a grant
 * or revoke made in system settings while the app was alive always shows.
 *
 * A steady-state read never returns [ContactsPermissionState.PermanentlyDenied]: "not granted,
 * no rationale" can be never-asked or permanently denied, so it resolves to
 * [ContactsPermissionState.NotRequested]. The banner offers the ask, and a denial there
 * reclassifies through [classifyAfterRequest]. A revoked permission never resolves to Granted.
 */
fun resolveContactsPermissionState(
    granted: Boolean,
    shouldShowRationale: Boolean,
): ContactsPermissionState = when {
    granted -> ContactsPermissionState.Granted
    shouldShowRationale -> ContactsPermissionState.ShouldShowRationale
    else -> ContactsPermissionState.NotRequested
}
