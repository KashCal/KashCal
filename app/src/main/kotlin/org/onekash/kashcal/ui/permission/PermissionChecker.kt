package org.onekash.kashcal.ui.permission

/**
 * Reports the current grant state of the app's permissions at the moment of the call.
 *
 * Callers that need updates re-query on lifecycle events, for example `Activity.onResume`. It only
 * queries. For notifications, [NotificationPermissionManager] decides whether to ask (system
 * dialog, rationale, denial count).
 */
interface PermissionChecker {
    fun hasNotificationPermission(): Boolean
    fun hasExactAlarmPermission(): Boolean
    fun hasReadContactsPermission(): Boolean
    fun hasWriteContactsPermission(): Boolean
    fun hasCalendarReadPermission(): Boolean
    fun hasCalendarWritePermission(): Boolean
}
