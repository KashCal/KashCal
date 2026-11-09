package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that [DeviceEventQuickViewSheet] shows what [EventQuickViewSheet] shows: no alert line,
 * notes, the repeat line ("since" or "starting ... until", "Recurring" for a changed occurrence,
 * none for a one-off), the title as a heading, the TalkBack sheet name, and the whole-series note
 * on an invitation in a series. Run the class alone: Robolectric runs of more than one class hit
 * a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class DeviceEventQuickViewDetailsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val bell = "🔔"
    private val repeatGlyph = "🔁"
    private val seriesNote = "Your reply applies to the whole series."
    private val invitees = listOf(
        AttendeeUiModel("Boss", "boss@example.test", AttendeeStatus.Accepted, isYou = false, isOrganizer = true, sortOrder = 0),
        AttendeeUiModel("Me", "me@example.test", AttendeeStatus.NeedsAction, isYou = true, isOrganizer = false, sortOrder = 1),
    )

    private fun deviceEvent(
        reminders: List<Int>,
        isAllDay: Boolean,
        description: String,
        title: String,
        rrule: String?,
        originalId: Long?,
        originalInstanceTime: Long?,
        eventStartTs: Long,
    ) = DisplayEvent.Device(
        DeviceCalendarInstance(
            instanceId = 1L,
            eventId = 100L,
            title = title,
            description = description,
            location = "",
            startTs = 1_000,
            endTs = 4_600_000,
            startDay = 20260101,
            endDay = 20260101,
            isAllDay = isAllDay,
            hasRrule = !rrule.isNullOrEmpty(),
            rrule = rrule,
            reminders = reminders,
            calendarId = 1L,
            calendarDisplayName = "Device",
            calendarColor = 0xFF4CAF50.toInt(),
            eventColor = null,
            status = 1,
            availability = 0,
            hasAlarm = reminders.isNotEmpty(),
            selfAttendeeStatus = 0,
            isWritable = true,
            originalId = originalId,
            originalInstanceTime = originalInstanceTime,
            timezone = "UTC",
            eventStartTs = eventStartTs,
            categories = emptyList(),
        )
    )

    private fun render(
        reminders: List<Int> = emptyList(),
        isAllDay: Boolean = false,
        description: String = "",
        title: String = "Trip",
        rrule: String? = null,
        originalId: Long? = null,
        originalInstanceTime: Long? = null,
        eventStartTs: Long = 1_000,
        invited: Boolean = false,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                DeviceEventQuickViewSheet(
                    displayEvent = deviceEvent(
                        reminders, isAllDay, description, title, rrule, originalId, originalInstanceTime, eventStartTs,
                    ),
                    onDismiss = {},
                    timeFormat = "12h",
                    hasWritePermission = invited,
                    isWritableCalendar = invited,
                    attendees = if (invited) invitees else emptyList(),
                    isCurrentUserOnList = invited,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun count(text: String): Int =
        composeTestRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun `an event with only an alert shows no alert line`() {
        render(reminders = listOf(15))
        assertEquals(0, count(bell))
        assertEquals(0, count("15 min before"))
    }

    @Test
    fun `an all-day event's alert is not shown`() {
        render(reminders = listOf(-540, 900), isAllDay = true)
        assertEquals(0, count(bell))
        assertEquals(0, count("day of event"))
    }

    @Test
    fun `an event with notes and an alert shows the notes and no alert line`() {
        render(reminders = listOf(15), description = "Room 4")
        assertEquals(1, count("Room 4"))
        assertEquals(0, count(bell))
    }

    @Test
    fun `an event with only notes shows them`() {
        render(reminders = emptyList(), description = "Room 4")
        assertEquals(1, count("Room 4"))
    }

    @Test
    fun `blank notes show no Notes label`() {
        render(reminders = emptyList(), description = "   ")
        assertEquals(0, count("Notes"))
    }

    @Test
    fun `a weekly series shows its rule and the date it started`() {
        val start = 1_678_100_400_000L // Mar 6, 2023
        render(rrule = "FREQ=WEEKLY;BYDAY=MO", eventStartTs = start)
        assertEquals(1, count("since " + formatSeriesStartDateStr(start, isAllDay = false)))
    }

    @Test
    fun `a series with an end shows when it started and until when`() {
        val start = 1_678_100_400_000L // Mar 6, 2023
        render(rrule = "FREQ=WEEKLY;UNTIL=20231231T000000Z", eventStartTs = start)
        assertEquals(1, count("starting " + formatSeriesStartDateStr(start, isAllDay = false)))
        assertEquals(1, count("until"))
    }

    @Test
    fun `a changed occurrence of a series shows Recurring`() {
        render(originalId = 7L)
        assertEquals(1, count("Recurring"))
    }

    @Test
    fun `an occurrence marked only by its original time shows Recurring`() {
        render(originalInstanceTime = 1_000L)
        assertEquals(1, count("Recurring"))
    }

    @Test
    fun `a one-off event shows no repeat line`() {
        render()
        assertEquals(0, count(repeatGlyph))
    }

    @Test
    fun `the title is a heading`() {
        render(title = "Trip")
        composeTestRule.onNode(hasText("Trip") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertExists()
    }

    @Test
    fun `TalkBack names the sheet by its title`() {
        render(title = "Trip")
        composeTestRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Trip"))
            .assertExists()
    }

    @Test
    fun `TalkBack names an untitled sheet Untitled event`() {
        render(title = "")
        composeTestRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Untitled event"))
            .assertExists()
    }

    @Test
    fun `an invitation in a series says the reply applies to the whole series`() {
        render(rrule = "FREQ=WEEKLY", invited = true)
        assertEquals(1, count("Going?"))
        assertEquals(1, count(seriesNote))
    }

    @Test
    fun `a changed occurrence's invitation has no series note`() {
        render(originalId = 7L, invited = true)
        assertEquals(1, count("Going?"))
        assertEquals(0, count(seriesNote))
    }

    @Test
    fun `an occurrence marked only by its original time has no series note`() {
        render(rrule = "FREQ=WEEKLY", originalInstanceTime = 1_000L, invited = true)
        assertEquals(1, count("Going?"))
        assertEquals(0, count(seriesNote))
    }

    @Test
    fun `a one-off invitation has no series note`() {
        render(invited = true)
        assertEquals(1, count("Going?"))
        assertEquals(0, count(seriesNote))
    }
}
