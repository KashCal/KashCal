package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the Device calendars screen: its copy, the main switch's state, the calendar-access note
 * shown without read access, the hint until a calendar is picked, and the calendar list (tapping
 * a calendar routes its id and new value; no enabled count and no reminders switch, since device
 * reminders are switched in the Calendar notifications sheet). Run the class alone: Robolectric
 * runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class DeviceCalendarsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calendars = listOf(
        DeviceCalendar(
            id = 100, displayName = "Calendar", color = 0xFF2196F3.toInt(), accountName = "alex@gmail.example",
            accountType = "org.example", visible = true, accessLevel = 700
        ),
        DeviceCalendar(
            id = 101, displayName = "Phone", color = 0xFF607D8B.toInt(), accountName = "",
            accountType = "LOCAL", visible = true, accessLevel = 700
        ),
    )

    private val toggled = mutableListOf<Pair<Long, Boolean>>()
    private val switchTaps = mutableListOf<Boolean>()
    private var allowTaps = 0

    private fun show(
        isEnabled: Boolean = true,
        hasReadPermission: Boolean = true,
        deviceCalendars: List<DeviceCalendar> = calendars,
        enabledCalendarIds: Set<Long> = setOf(100L),
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = isEnabled,
                    hasReadPermission = hasReadPermission,
                    deviceCalendars = deviceCalendars,
                    enabledCalendarIds = enabledCalendarIds,
                    onNavigateBack = {},
                    onToggle = { switchTaps += it },
                    onToggleCalendar = { id, enabled -> toggled += id to enabled },
                    onAllowCalendarAccess = { allowTaps++ },
                )
            }
        }
    }

    @Test
    fun `the list shows the calendars without an enabled count or a reminders switch`() {
        show()

        composeTestRule.onNodeWithText("Calendar").assertExists()
        composeTestRule.onNodeWithText("Phone").assertExists()
        composeTestRule.onNodeWithText("1 / 2 enabled").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reminders").assertDoesNotExist()
        composeTestRule.onNodeWithText("Get notified for device calendar events").assertDoesNotExist()
    }

    @Test
    fun `tapping a calendar routes its id and new value`() {
        show()

        composeTestRule.onNodeWithText("Phone").performClick()

        assertEquals(listOf(101L to true), toggled)
    }

    @Test
    fun `the screen names the sources and the switch says what it shows`() {
        show()

        composeTestRule.onNodeWithText("Device calendars").assertExists()
        composeTestRule.onNodeWithText(
            "Show calendars from Google, Outlook, and sync apps on this device."
        ).assertExists()
        composeTestRule.onNodeWithText("Show device calendars").assertExists()
        composeTestRule.onNodeWithTag(DEVICE_CALENDARS_SWITCH_TAG).assertIsOn()
    }

    @Test
    fun `the switch shows off while calendar access is missing`() {
        show(isEnabled = true, hasReadPermission = false)

        composeTestRule.onNodeWithTag(DEVICE_CALENDARS_SWITCH_TAG).assertIsOff()
        composeTestRule.onNodeWithText("Calendar permission required. Tap Enable to grant access.")
            .assertDoesNotExist()
    }

    @Test
    fun `the pick hint shows until a calendar is picked`() {
        show(enabledCalendarIds = emptySet())

        composeTestRule.onNodeWithText("Pick calendars to show.").assertExists()
    }

    @Test
    fun `the pick hint is gone once a calendar is picked`() {
        show(enabledCalendarIds = setOf(101L))

        composeTestRule.onNodeWithText("Pick calendars to show.").assertDoesNotExist()
    }

    @Test
    fun `an empty list says nothing was found`() {
        show(deviceCalendars = emptyList(), enabledCalendarIds = emptySet())

        composeTestRule.onNodeWithText("No calendars found.").assertExists()
        composeTestRule.onNodeWithText("Pick calendars to show.").assertDoesNotExist()
    }

    @Test
    fun `there is no view-only row and no permission read-only label`() {
        show()

        composeTestRule.onNodeWithText("Read-only access").assertDoesNotExist()
        composeTestRule.onNodeWithText("Read only (no write permission)").assertDoesNotExist()
    }

    @Test
    fun `a read-only calendar is labelled as such`() {
        val readOnly = calendars[0].copy(accessLevel = 200)
        show(deviceCalendars = listOf(readOnly))

        composeTestRule.onNodeWithText("Read only (calendar is read-only)").assertExists()
    }

    @Test
    fun `the access note and Allow show without read access while the feature is on`() {
        show(isEnabled = true, hasReadPermission = false)

        composeTestRule.onNodeWithText(ACCESS_NOTE).assertExists()
        composeTestRule.onNodeWithText(ALLOW).assertExists()
    }

    @Test
    fun `the access note and Allow show without read access while the feature is off`() {
        show(isEnabled = false, hasReadPermission = false)

        composeTestRule.onNodeWithText(ACCESS_NOTE).assertExists()
        composeTestRule.onNodeWithText(ALLOW).assertExists()
    }

    @Test
    fun `the access note is absent with read access whether the feature is on or off`() {
        var enabled by mutableStateOf(true)
        composeTestRule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = enabled,
                    hasReadPermission = true,
                    deviceCalendars = calendars,
                    enabledCalendarIds = setOf(100L),
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onAllowCalendarAccess = {},
                )
            }
        }

        composeTestRule.onNodeWithText(ACCESS_NOTE).assertDoesNotExist()
        enabled = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(ACCESS_NOTE).assertDoesNotExist()
        composeTestRule.onNodeWithText(ALLOW).assertDoesNotExist()
    }

    @Test
    fun `the access note sits between the intro and the switch`() {
        show(isEnabled = false, hasReadPermission = false)

        val intro = composeTestRule.onNodeWithText(INTRO).getBoundsInRoot()
        val note = composeTestRule.onNodeWithText(ACCESS_NOTE).getBoundsInRoot()
        val switch = composeTestRule.onNodeWithTag(DEVICE_CALENDARS_SWITCH_TAG).getBoundsInRoot()
        assertTrue("note below the intro", note.top >= intro.bottom)
        assertTrue("note above the switch", note.bottom <= switch.top)
    }

    @Test
    fun `tapping Allow asks for access once and leaves the switch alone`() {
        show(isEnabled = false, hasReadPermission = false)

        composeTestRule.onNodeWithText(ALLOW).performClick()

        assertEquals(1, allowTaps)
        assertEquals(emptyList<Boolean>(), switchTaps)
    }

    @Test
    fun `tapping the switch without read access still turns it on through onToggle`() {
        show(isEnabled = false, hasReadPermission = false)

        composeTestRule.onNodeWithTag(DEVICE_CALENDARS_SWITCH_TAG).performClick()

        assertEquals(listOf(true), switchTaps)
        assertEquals(0, allowTaps)
    }

    @Test
    fun `with read access the switch keeps one column gap below the intro`() {
        show(isEnabled = true, hasReadPermission = true)

        val intro = composeTestRule.onNodeWithText(INTRO).getBoundsInRoot()
        val switch = composeTestRule.onNodeWithTag(DEVICE_CALENDARS_SWITCH_TAG).getBoundsInRoot()
        // The column spaces its children 16dp apart; a hidden note must not add a second gap.
        assertEquals(16f, (switch.top - intro.bottom).value, 0.5f)
    }

    private companion object {
        const val ACCESS_NOTE = "Calendar access is off for KashCal."
        const val ALLOW = "Allow"
        const val INTRO = "Show calendars from Google, Outlook, and sync apps on this device."
    }
}
