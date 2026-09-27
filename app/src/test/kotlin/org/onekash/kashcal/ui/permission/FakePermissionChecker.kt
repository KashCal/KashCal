package org.onekash.kashcal.ui.permission

/**
 * Fakes [PermissionChecker] with one mutable boolean per permission, `true` by default, that a
 * test flips to simulate a grant or revoke.
 */
class FakePermissionChecker(
    var notifications: Boolean = true,
    var exactAlarm: Boolean = true,
    var readContacts: Boolean = true,
    var writeContacts: Boolean = true,
    var calendarRead: Boolean = true,
    var calendarWrite: Boolean = true,
) : PermissionChecker {
    override fun hasNotificationPermission() = notifications
    override fun hasExactAlarmPermission() = exactAlarm
    override fun hasReadContactsPermission() = readContacts
    override fun hasWriteContactsPermission() = writeContacts
    override fun hasCalendarReadPermission() = calendarRead
    override fun hasCalendarWritePermission() = calendarWrite
}
