package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the details [EventQuickViewSheet] shows on the rendered sheet: no alert line, notes and
 * the event link (their section shows only for notes or a link, not for guests alone), and the
 * repeat line (a series' "since" date, "Recurring" for a changed occurrence). Run the class
 * alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class EventQuickViewDetailsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val bell = "🔔"

    private fun render(
        reminders: List<String>?,
        description: String? = null,
        url: String? = null,
        isAllDay: Boolean = false,
        rrule: String? = null,
        originalEventId: Long? = null,
        startTs: Long = 1_000,
        attendees: List<AttendeeUiModel> = emptyList(),
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                EventQuickViewSheet(
                    event = Event(
                        uid = "u1",
                        calendarId = 1,
                        title = "Standup",
                        startTs = startTs,
                        endTs = startTs + 3_600_000,
                        timezone = "UTC",
                        isAllDay = isAllDay,
                        description = description,
                        url = url,
                        reminders = reminders,
                        rrule = rrule,
                        originalEventId = originalEventId,
                        dtstamp = 1_000,
                    ),
                    calendarColor = 0xFF4CAF50.toInt(),
                    calendarName = "Local",
                    onDismiss = {},
                    onEdit = {},
                    onDeleteSingle = {},
                    timeFormat = "12h",
                    attendees = attendees,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private val guests = listOf(
        AttendeeUiModel("Boss", "boss@example.test", AttendeeStatus.Accepted, isYou = false, isOrganizer = true, sortOrder = 0),
        AttendeeUiModel("Ana", "ana@example.test", AttendeeStatus.NeedsAction, isYou = false, isOrganizer = false, sortOrder = 1),
    )

    private fun detailsSections(): Int =
        composeTestRule.onAllNodes(hasTestTag(QUICK_VIEW_DETAILS_SECTION_TAG)).fetchSemanticsNodes().size

    @Test
    fun `an event with guests but no notes or link shows no details section under the guests`() {
        render(reminders = null, attendees = guests)
        // The collapsed guest block summarizes the guests and names the organizer.
        assertTrue("the guest block shows", count("Boss") > 0)
        assertEquals(0, detailsSections())
    }

    @Test
    fun `notes open the details section under the guests`() {
        render(reminders = null, description = "Room 4", attendees = guests)
        assertEquals(1, detailsSections())
    }

    @Test
    fun `a valid link alone opens the details section`() {
        render(reminders = null, url = "https://example.test/agenda")
        assertEquals(1, detailsSections())
    }

    private fun count(text: String): Int =
        composeTestRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun `an event with only an alert shows no alert line`() {
        render(reminders = listOf("-PT15M"))
        assertEquals(0, count(bell))
        assertEquals(0, count("15 min before"))
    }

    @Test
    fun `an event with notes and an alert shows the notes and no alert line`() {
        render(reminders = listOf("-PT15M", "-PT15M"), description = "Room 4")
        assertEquals(1, count("Room 4"))
        assertEquals(0, count(bell))
    }

    @Test
    fun `an all-day event's default alert is not shown`() {
        render(reminders = listOf("-PT15H"), isAllDay = true)
        assertEquals(0, count(bell))
        assertEquals(0, count("before"))
    }

    @Test
    fun `an event with a link and an alert shows the link and no alert line`() {
        render(reminders = listOf("-PT15M"), url = "https://example.com/meet")
        assertEquals(1, count("https://example.com/meet"))
        assertEquals(0, count(bell))
    }

    @Test
    fun `an event with only a link shows it`() {
        render(reminders = null, url = "https://example.com/meet")
        assertEquals(1, count("https://example.com/meet"))
    }

    @Test
    fun `an event with only notes shows them`() {
        render(reminders = null, description = "Room 4")
        assertEquals(1, count("Room 4"))
    }

    @Test
    fun `blank notes show no Notes label`() {
        render(reminders = null, description = "   ")
        assertEquals(0, count("Notes"))
    }

    @Test
    fun `a weekly series shows its rule and the date it started`() {
        val start = 1_678_100_400_000L // Mar 6, 2023
        render(reminders = null, rrule = "FREQ=WEEKLY;BYDAY=MO", startTs = start)
        assertEquals(1, count("since " + formatSeriesStartDateStr(start, isAllDay = false)))
    }

    @Test
    fun `a changed occurrence of a series shows Recurring`() {
        render(reminders = null, originalEventId = 7L)
        assertEquals(1, count("Recurring"))
    }
}
