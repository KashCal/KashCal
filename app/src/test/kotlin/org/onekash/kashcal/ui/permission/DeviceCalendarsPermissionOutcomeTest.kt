package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [deviceCalendarsPermissionOutcome]: what the Device calendars switch does once Android
 * answers the calendar-permission request.
 */
class DeviceCalendarsPermissionOutcomeTest {

    @Test
    fun `access granted turns device calendars on`() {
        assertEquals(DeviceCalendarsPermissionOutcome.ENABLE, deviceCalendarsPermissionOutcome(true, false))
        assertEquals(DeviceCalendarsPermissionOutcome.ENABLE, deviceCalendarsPermissionOutcome(true, true))
    }

    @Test
    fun `a refusal Android won't ask about again opens the app's settings`() {
        // After "Don't allow" with no rationale due, Android returns denied without a dialog, so
        // only its settings screen can grant access.
        assertEquals(
            DeviceCalendarsPermissionOutcome.OPEN_SETTINGS,
            deviceCalendarsPermissionOutcome(readGranted = false, rationaleAfter = false)
        )
    }

    @Test
    fun `a refusal Android can still ask about again does nothing`() {
        assertEquals(
            DeviceCalendarsPermissionOutcome.NONE,
            deviceCalendarsPermissionOutcome(readGranted = false, rationaleAfter = true)
        )
    }
}
