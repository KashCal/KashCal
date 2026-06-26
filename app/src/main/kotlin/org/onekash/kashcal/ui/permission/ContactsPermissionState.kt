package org.onekash.kashcal.ui.permission

/**
 * Contacts-permission state for the attendee picker's inline banner.
 *
 * Mirrors the shape of the notification-permission state machine but drops the
 * denial-count heuristic in favour of the rationale-flip signal the Android
 * docs recommend (see [classifyAfterRequest]). The picker never blocks: manual
 * email entry works in every state, so [PermanentlyDenied] simply hides the
 * banner rather than redirecting to system settings.
 */
sealed interface ContactsPermissionState {
    /** Permission is granted — contact suggestions are available. */
    data object Granted : ContactsPermissionState

    /** Not yet requested in this picker session — show the educational banner. */
    data object NotRequested : ContactsPermissionState

    /** Denied without "don't ask again" — the banner can offer the ask again. */
    data object ShouldShowRationale : ContactsPermissionState

    /** Denied with "don't ask again" — hide the banner; manual entry remains. */
    data object PermanentlyDenied : ContactsPermissionState
}

/**
 * Classify the outcome of a permission request from the grant result and the
 * `shouldShowRequestPermissionRationale()` value sampled immediately before
 * and after the request.
 *
 * - Granted → [ContactsPermissionState.Granted].
 * - Denied while the system still offers a rationale afterwards → the user can
 *   be asked again ([ContactsPermissionState.ShouldShowRationale]).
 * - Denied with no rationale afterwards → "don't ask again"
 *   ([ContactsPermissionState.PermanentlyDenied]). This covers both the flip
 *   (rationale true→false) and a first-ask denial with the checkbox ticked
 *   (false→false).
 *
 * [rationaleBefore] is accepted for call-site symmetry and documentation of
 * the flip; the decision keys on the post-request state, which is the
 * authoritative Android signal.
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
 * Resolve the current permission state from a fresh `checkSelfPermission` +
 * `shouldShowRequestPermissionRationale` reading — used each time the form
 * opens so a grant or revoke performed in system Settings (while the app was
 * alive) is always reflected.
 *
 * Unlike [classifyAfterRequest] (the post-request rationale-flip), this is the
 * steady-state read and so never returns [ContactsPermissionState.PermanentlyDenied]:
 * "no rationale and not granted" is ambiguous between never-asked and
 * permanently-denied, so it resolves to [ContactsPermissionState.NotRequested]
 * (banner offers the ask; a denial there reclassifies via [classifyAfterRequest]).
 * The key property is that a revoked permission never resolves to Granted.
 */
fun resolveContactsPermissionState(
    granted: Boolean,
    shouldShowRationale: Boolean,
): ContactsPermissionState = when {
    granted -> ContactsPermissionState.Granted
    shouldShowRationale -> ContactsPermissionState.ShouldShowRationale
    else -> ContactsPermissionState.NotRequested
}
