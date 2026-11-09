package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEmail
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests how the event form loads an event's stored alerts for editing: one alert per offset, and
 * a "not shown" notice only for the alarms the form doesn't show. Run the class alone:
 * Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class EventFormReminderLoadTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calendars = listOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.example.test/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt(),
        ),
    )

    private fun open(reminders: List<String>, alarmCount: Int) {
        val event = Event(
            id = 1L,
            uid = "edit-source@test",
            calendarId = 1L,
            title = "Standup",
            startTs = 0L,
            endTs = 3_600_000L,
            timezone = "UTC",
            reminders = reminders,
            alarmCount = alarmCount,
            dtstamp = 0L,
        )
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    eventId = 1L,
                    onLoadEvent = { event },
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    deviceCalendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(event) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Opens the Alerts row, where the "not shown" notice is drawn. */
    private fun expandAlerts() {
        composeTestRule.onAllNodes(hasContentDescription("Alerts"), useUnmergedTree = true)
            .onFirst().performClick()
        composeTestRule.waitForIdle()
    }

    private fun count(text: String): Int =
        composeTestRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun countContaining(text: String): Int =
        composeTestRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    @Test
    fun `a repeated offset opens as one alert`() {
        open(reminders = listOf("-PT15M", "-PT15M", "-PT1H"), alarmCount = 3)
        assertEquals(1, count("15m, 1h"))
        expandAlerts()
        assertEquals(0, countContaining("not shown"))
    }

    @Test
    fun `alarms a pull didn't store are reported once repeats are folded`() {
        open(reminders = listOf("-PT15M", "-PT15M", "-PT1H", "-PT1H", "-P1D"), alarmCount = 7)
        assertEquals(1, count("15m, 1h, 1d"))
        expandAlerts()
        assertEquals(1, count("2 not shown (5 total, max 5 editable)"))
    }

    @Test
    fun `five distinct stored alerts report none hidden`() {
        open(reminders = listOf("-PT15M", "-PT30M", "-PT1H", "-PT2H", "-P1D"), alarmCount = 5)
        expandAlerts()
        assertEquals(0, countContaining("not shown"))
    }

    @Test
    fun `an edited row with a stale alarm count reports the alarms past the first five`() {
        // A form save stores fewer entries but keeps the pulled alarmCount; the two alarms past
        // the first five are still on the server copy.
        open(reminders = listOf("-PT15M", "-PT1H", "-P1D"), alarmCount = 7)
        expandAlerts()
        assertEquals(1, count("2 not shown (5 total, max 5 editable)"))
    }

    @Test
    fun `a locally created event with no alarm count reports none hidden`() {
        open(reminders = listOf("-PT15M", "-PT1H"), alarmCount = 0)
        expandAlerts()
        assertEquals(0, countContaining("not shown"))
    }

    @Test
    fun `an entry that can't be read is left out`() {
        open(reminders = listOf("bogus", "-PT15M"), alarmCount = 2)
        assertEquals(1, count("15m"))
    }
}
