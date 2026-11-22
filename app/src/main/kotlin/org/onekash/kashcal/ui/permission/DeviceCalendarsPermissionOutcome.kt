package org.onekash.kashcal.ui.permission

/**
 * Names what the Device calendars screen does after a calendar-permission request from its
 * switch or its access note's Allow returns.
 */
enum class DeviceCalendarsPermissionOutcome { ENABLE, OPEN_SETTINGS, NONE }

/**
 * Returns the next step once Android answers the calendar-permission request.
 *
 * Granted read access turns device calendars on (edit access is optional). A refusal that
 * Android won't ask about again opens the app's settings, the same rule as
 * [allowRequestNeedsSettingsFallback], so neither the switch nor Allow is a dead tap. A refusal
 * that can still be asked again leaves the switch off.
 *
 * @param rationaleAfter `shouldShowRequestPermissionRationale(READ_CALENDAR)`, read after the
 *   result
 */
fun deviceCalendarsPermissionOutcome(
    readGranted: Boolean,
    rationaleAfter: Boolean,
): DeviceCalendarsPermissionOutcome = when {
    readGranted -> DeviceCalendarsPermissionOutcome.ENABLE
    allowRequestNeedsSettingsFallback(granted = false, rationaleAfter = rationaleAfter) ->
        DeviceCalendarsPermissionOutcome.OPEN_SETTINGS
    else -> DeviceCalendarsPermissionOutcome.NONE
}
