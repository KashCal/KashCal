package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar

/**
 * Locks in Outcome 4: Device Calendars is a full-page screen with the
 * unified top bar.
 *
 * Verifies the screen exposes the same behavior the bottom sheet did, plus
 * the new chrome contract:
 *  - bar: back arrow + KashCal centered + (refresh in actions iff isEnabled && hasReadPermission)
 *  - body: enable toggle invokes onToggle; row toggle invokes onToggleCalendar;
 *    write-permission banner invokes onRequestWritePermission;
 *    reminders toggle invokes onToggleDeviceCalendarReminders
 *
 * Permission-launcher integration (toggling Enable when no READ permission)
 * is verified at the SettingsActivity host level — this test passes a callback
 * lambda and asserts the lambda is invoked, exactly as the host does.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCalendarsScreenComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appName = context.getString(R.string.app_name)
    private val backCd = context.getString(R.string.cd_back)
    private val refreshCd = context.getString(R.string.cd_refresh_calendars)
    private val enableLabel = context.getString(R.string.action_enable)
    private val pageHeading = context.getString(R.string.label_device_calendars)
    private val grantLabel = context.getString(R.string.device_calendars_grant)

    private fun fakeCalendar(
        id: Long = 1L,
        accountName: String = "phone@example.com",
        displayName: String = "Personal",
        color: Int = 0xFF4285F4.toInt(),
        accessLevel: Int = 700, // OWNER
    ) = DeviceCalendar(
        id = id,
        displayName = displayName,
        color = color,
        accountName = accountName,
        accountType = "com.google",
        visible = true,
        accessLevel = accessLevel,
    )

    @Test
    fun rendersUnifiedTopBar_appNameAndBackArrow() {
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = false,
                    hasReadPermission = false,
                    hasWritePermission = false,
                    deviceCalendars = emptyList(),
                    enabledCalendarIds = emptySet(),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = {},
                )
            }
        }
        rule.onNodeWithText(appName).assertIsDisplayed()
        rule.onNodeWithContentDescription(backCd).assertIsDisplayed()
    }

    @Test
    fun rendersBodyHeading() {
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = false,
                    hasReadPermission = false,
                    hasWritePermission = false,
                    deviceCalendars = emptyList(),
                    enabledCalendarIds = emptySet(),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = {},
                )
            }
        }
        rule.onNodeWithText(pageHeading).assertIsDisplayed()
    }

    @Test
    fun backArrow_invokesOnNavigateBack() {
        var backInvoked = false
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = false,
                    hasReadPermission = false,
                    hasWritePermission = false,
                    deviceCalendars = emptyList(),
                    enabledCalendarIds = emptySet(),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = { backInvoked = true },
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = {},
                )
            }
        }
        rule.onNodeWithContentDescription(backCd).performClick()
        assertTrue("Back arrow should invoke onNavigateBack", backInvoked)
    }

    @Test
    fun refreshIcon_hidden_whenDisabled() {
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = false,
                    hasReadPermission = true,
                    hasWritePermission = true,
                    deviceCalendars = listOf(fakeCalendar()),
                    enabledCalendarIds = emptySet(),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = {},
                )
            }
        }
        rule.onNodeWithContentDescription(refreshCd).assertDoesNotExist()
    }

    @Test
    fun refreshIcon_hidden_whenEnabledButNoReadPermission() {
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = true,
                    hasReadPermission = false,
                    hasWritePermission = false,
                    deviceCalendars = emptyList(),
                    enabledCalendarIds = emptySet(),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = {},
                )
            }
        }
        rule.onNodeWithContentDescription(refreshCd).assertDoesNotExist()
    }

    @Test
    fun refreshIcon_visibleAndClickable_whenEnabledAndHasReadPermission() {
        var refreshInvoked = false
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = true,
                    hasReadPermission = true,
                    hasWritePermission = true,
                    deviceCalendars = listOf(fakeCalendar()),
                    enabledCalendarIds = emptySet(),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = {},
                    onRefresh = { refreshInvoked = true }
                )
            }
        }
        rule.onNodeWithContentDescription(refreshCd).assertIsDisplayed().performClick()
        assertTrue("Refresh icon should invoke onRefresh", refreshInvoked)
    }

    @Test
    fun enableToggle_whenDisabled_invokesOnToggleWithTrue() {
        var toggledTo: Boolean? = null
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = false,
                    hasReadPermission = false,
                    hasWritePermission = false,
                    deviceCalendars = emptyList(),
                    enabledCalendarIds = emptySet(),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = { toggledTo = it },
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = {},
                )
            }
        }
        // The Switch is the only one with the "Enable" label adjacent — tap it.
        rule.onNodeWithText(enableLabel).performClick()
        assertEquals(true, toggledTo)
    }

    @Test
    fun writePermissionBanner_clickInvokesCallback() {
        var requested = false
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = true,
                    hasReadPermission = true,
                    hasWritePermission = false, // shows banner
                    deviceCalendars = listOf(fakeCalendar()),
                    enabledCalendarIds = emptySet(),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = { requested = true },
                )
            }
        }
        rule.onNodeWithText(grantLabel).performClick()
        assertTrue("Write permission banner should invoke onRequestWritePermission", requested)
    }

    @Test
    fun rowSwitch_togglesIndividualCalendar() {
        val cal1 = fakeCalendar(id = 11L, displayName = "Personal")
        val cal2 = fakeCalendar(id = 12L, displayName = "Work")
        var lastToggle: Pair<Long, Boolean>? = null
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = true,
                    hasReadPermission = true,
                    hasWritePermission = true,
                    deviceCalendars = listOf(cal1, cal2),
                    enabledCalendarIds = setOf(11L), // Personal on, Work off
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { id, enabled -> lastToggle = id to enabled },
                    onToggleDeviceCalendarReminders = {},
                    onRequestWritePermission = {},
                )
            }
        }
        // Tap the Work row — currently OFF, should be invoked with (12L, true)
        rule.onNodeWithText("Work").performClick()
        assertEquals(12L to true, lastToggle)
    }

    @Test
    fun deviceCalendarRemindersToggle_invokesCallback() {
        var remindersToggledTo: Boolean? = null
        rule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = true,
                    hasReadPermission = true,
                    hasWritePermission = true,
                    deviceCalendars = listOf(fakeCalendar()),
                    enabledCalendarIds = setOf(1L),
                    deviceCalendarRemindersEnabled = false,
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { _, _ -> },
                    onToggleDeviceCalendarReminders = { remindersToggledTo = it },
                    onRequestWritePermission = {},
                )
            }
        }
        rule.onNodeWithText(context.getString(R.string.device_calendars_reminders)).performClick()
        assertEquals(true, remindersToggledTo)
    }
}
