package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the read-only tag row in [DeviceEventQuickViewSheet].
 * Device events route here (not the editor) for read-only viewing, so their
 * tags must surface here the same way Room-event tags do in [EventQuickViewSheet].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class DeviceEventQuickViewSheetTagsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun deviceEvent(categories: List<String>): DisplayEvent.Device {
        val instance = DeviceCalendarInstance(
            instanceId = 1L,
            eventId = 100L,
            title = "Standup",
            description = "",
            location = "",
            startTs = 1_000,
            endTs = 4_600_000,
            startDay = 20260101,
            endDay = 20260101,
            isAllDay = false,
            hasRrule = false,
            rrule = null,
            reminders = emptyList(),
            calendarId = 1L,
            calendarDisplayName = "Device",
            calendarColor = 0xFF4CAF50.toInt(),
            eventColor = null,
            status = 1,
            availability = 0,
            hasAlarm = false,
            selfAttendeeStatus = 0,
            isWritable = true,
            originalId = null,
            originalInstanceTime = null,
            timezone = "UTC",
            eventStartTs = 1_000,
            categories = categories,
        )
        return DisplayEvent.Device(instance)
    }

    private fun render(categories: List<String>) {
        composeTestRule.setContent {
            MaterialTheme {
                DeviceEventQuickViewSheet(
                    displayEvent = deviceEvent(categories),
                    onDismiss = {},
                )
            }
        }
    }

    private fun countWithText(text: String): Int =
        composeTestRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun shows_tag_chips_for_tagged_device_event() {
        render(listOf("Work", "Focus"))
        assertEquals(1, countWithText("Work"))
        assertEquals(1, countWithText("Focus"))
    }

    @Test
    fun no_add_affordance_on_a_tagged_read_only_device_event() {
        render(listOf("Work"))
        // On a tagged event the add-tag affordance is an icon button whose
        // "New tag" label is a contentDescription, not visible text — so this
        // asserts on the description, which surfaces even if readOnly regressed
        // to false. countWithText("New tag") would NOT catch that regression.
        composeTestRule.onNodeWithContentDescription("New tag").assertDoesNotExist()
    }

    @Test
    fun untagged_device_event_renders_no_tag_row() {
        render(emptyList())
        // With no tags the whole tag row is gated out, so neither the read-only
        // chips nor the (edit-only) "New tag" add affordance may appear.
        composeTestRule.onNodeWithContentDescription("New tag").assertDoesNotExist()
    }
}
