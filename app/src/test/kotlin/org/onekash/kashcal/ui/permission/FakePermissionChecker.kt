package org.onekash.kashcal.ui.permission

/**
 * Test fake for [PermissionChecker]. Each permission is a mutable boolean
 * (default `true`) that callers can flip to simulate grant state transitions.
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
