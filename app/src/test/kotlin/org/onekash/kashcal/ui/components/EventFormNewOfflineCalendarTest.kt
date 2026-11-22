package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Creating an offline calendar from the event form's calendar picker: the new calendar becomes
 * the form's calendar through the same path as a pick (so the attendee context follows it), the
 * title typed before stays, and Save carries the new calendar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class EventFormNewOfflineCalendarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private val local = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = LocalCalendarInitializer.LOCAL_CALENDAR_URL,
        displayName = LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME,
        color = 0xFF9E9E9E.toInt(),
    )
    private val offline = CalendarGroup(
        accountName = "Offline",
        accountId = 1L,
        calendars = listOf(local),
        provider = AccountProvider.LOCAL,
    )
    private val savedEvent = Event(id = 9L, uid = "form@test", calendarId = 5L, title = "Dentist", startTs = 0L, endTs = 0L, dtstamp = 0L)

    @Test
    fun `a calendar created from the picker becomes the event's calendar and the title stays`() {
        val created = mutableListOf<String>()
        val selected = mutableListOf<Long>()
        var saved: EventFormState? = null
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = listOf(local),
                    calendarGroups = listOf(offline),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { saved = it; Result.success(savedEvent) },
                    onCalendarSelected = { selected += it },
                    onCreateOfflineCalendar = { name, _ -> created += name; Result.success(5L) },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Event title").performTextInput("Dentist")
        composeTestRule.onNodeWithContentDescription("Calendar", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("New offline calendar").performClick()
        composeTestRule.onNodeWithText("Name").performTextInput(" Trips ")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("Trips"), created)
        assertEquals("the pick path ran for the new calendar", listOf(5L), selected)
        composeTestRule.onNodeWithText("Trips").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dentist").assertIsDisplayed()

        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()
        assertEquals(5L, saved?.selectedCalendarId)
        assertEquals("Trips", saved?.selectedCalendarName)
        assertEquals("Dentist", saved?.title)
    }

    @Test
    fun `without the host's create callback the picker has no New offline calendar`() {
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    calendars = listOf(local),
                    calendarGroups = listOf(offline),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(savedEvent) },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Calendar", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("New offline calendar").assertDoesNotExist()
    }
}
