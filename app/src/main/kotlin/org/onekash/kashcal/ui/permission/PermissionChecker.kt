package org.onekash.kashcal.ui.permission

/**
 * Point-in-time permission query abstraction.
 *
 * Returns the current grant state at the moment of the call. Callers that need
 * reactive updates re-query on lifecycle events (e.g. `Activity.onResume`).
 *
 * Distinct from [NotificationPermissionManager], which handles the *asking*
 * flow (system dialog, rationale, denial counting). This interface handles
 * only the *querying* of current grant state.
 */
interface PermissionChecker {
    fun hasNotificationPermission(): Boolean
    fun hasExactAlarmPermission(): Boolean
    fun hasReadContactsPermission(): Boolean
    fun hasWriteContactsPermission(): Boolean
    fun hasCalendarReadPermission(): Boolean
    fun hasCalendarWritePermission(): Boolean
}
