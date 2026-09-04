package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.contacts.ContactEmail
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.PickerCalendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Duplicating an event must carry its tags (RFC 5545 CATEGORIES) into the new
 * event. Renders the wrapper-free [EventFormContent] with a `duplicateFrom`
 * source and asserts (1) the tag chips are seeded and visible before the user
 * saves, and (2) the saved [EventFormState] carries them. Both Room and device
 * duplicates flow through the same `duplicateFrom` branch, so this guards both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class EventFormSheetDuplicateTest {

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

    private val savedEvent = Event(
        id = 2L,
        uid = "dup-saved@test",
        calendarId = 1L,
        title = "Saved",
        startTs = 0L,
        endTs = 0L,
        dtstamp = 0L,
    )

    private fun sourceEvent(categories: List<String>? = null, calendarId: Long = 1L) = Event(
        id = 1L,
        uid = "dup-source@test",
        calendarId = calendarId,
        title = "Team Lunch",
        startTs = 0L,
        endTs = 0L,
        dtstamp = 0L,
        categories = categories,
    )

    private val deviceCalendarGroups = listOf(
        CalendarGroup(
            accountName = "Device Account",
            accountId = -1L,
            calendars = emptyList(),
            pickerCalendars = listOf(
                PickerCalendar.Device(
                    DeviceCalendar(
                        id = 10L,
                        displayName = "Google Cal",
                        color = 0xFF4CAF50.toInt(),
                        accountName = "test@example.com",
                        accountType = "com.google",
                        visible = true,
                        accessLevel = 700, // >= CONTRIBUTOR (500) -> writable
                    )
                )
            ),
            isDeviceSection = true,
        )
    )

    private fun renderDuplicate(
        source: Event,
        duplicateFromDeviceCalendarId: Long? = null,
        deviceGroups: List<CalendarGroup> = emptyList(),
        onCapture: (EventFormState) -> Unit,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    duplicateFrom = source,
                    duplicateFromDeviceCalendarId = duplicateFromDeviceCalendarId,
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    deviceCalendarGroups = deviceGroups,
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { onCapture(it); Result.success(savedEvent) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `duplicate seeds the source event tags and save carries them`() {
        var captured: EventFormState? = null
        renderDuplicate(sourceEvent(listOf("Vacation"))) { captured = it }

        // The tag is pre-populated and visible before the user saves.
        composeTestRule.onNodeWithContentDescription("Tag: Vacation", useUnmergedTree = true)
            .assertExists()

        // ...and saving (without touching the tag row) persists it.
        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()

        val cats = captured?.categories.orEmpty()
        assertTrue("onSave must fire", captured != null)
        assertTrue("duplicate must carry the source tag, got $cats", cats.contains("Vacation"))
    }

    @Test
    fun `duplicate of a tagless event seeds no tags`() {
        var captured: EventFormState? = null
        renderDuplicate(sourceEvent(categories = null)) { captured = it }

        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()

        assertTrue("onSave must fire", captured != null)
        assertTrue(
            "no tags should be seeded when the source has none, got ${captured?.categories}",
            captured?.categories.orEmpty().isEmpty(),
        )
    }

    @Test
    fun `duplicate of a device event defaults to its source device calendar`() {
        var captured: EventFormState? = null
        // A device duplicate zeroes calendarId and carries the source device
        // calendar id on the dedicated channel (as toEventForDuplicate + MainActivity do).
        renderDuplicate(
            source = sourceEvent(calendarId = 0L),
            duplicateFromDeviceCalendarId = 10L,
            deviceGroups = deviceCalendarGroups,
        ) { captured = it }

        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()

        assertTrue("onSave must fire", captured != null)
        assertEquals(10L, captured?.selectedCalendarId)
        assertTrue("device duplicate must route to the device path", captured?.isDeviceCalendar == true)
        assertEquals("Google Cal", captured?.selectedCalendarName)
    }

    @Test
    fun `duplicate of a Room event keeps the Room path`() {
        var captured: EventFormState? = null
        renderDuplicate(source = sourceEvent(calendarId = 1L)) { captured = it }

        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()

        assertTrue("onSave must fire", captured != null)
        assertEquals(1L, captured?.selectedCalendarId)
        assertFalse("Room duplicate must not flip to the device path", captured?.isDeviceCalendar == true)
    }

    @Test
    fun `device duplicate recovers its source calendar when device groups load late`() {
        // Cold-start race: the duplicate form opens before deviceCalendarGroups
        // has loaded, so the source device calendar isn't resolvable yet and the
        // form falls back to the Room default. Once the groups arrive, the
        // reconciliation effect must re-resolve to the source device calendar
        // rather than leaving the user stranded on the Room path.
        var captured: EventFormState? = null
        var reportedChanges: Boolean? = null
        composeTestRule.setContent {
            var groups by remember { mutableStateOf<List<CalendarGroup>>(emptyList()) }
            LaunchedEffect(Unit) {
                // Simulate the async device-calendar load completing after first frame.
                groups = deviceCalendarGroups
            }
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    duplicateFrom = sourceEvent(calendarId = 0L),
                    duplicateFromDeviceCalendarId = 10L,
                    calendars = calendars,
                    calendarGroups = emptyList(),
                    deviceCalendarGroups = groups,
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { captured = it; Result.success(savedEvent) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    onSetTagsAboveNotes = {},
                    onHasChangesChange = { reportedChanges = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        // The async device-source upgrade is not a user edit, so it must fold into
        // the baseline: a form the user never touched must report no unsaved changes
        // (otherwise closing it triggers a spurious discard confirmation).
        assertEquals(
            "late device-source upgrade must re-baseline, not read as an edit",
            false,
            reportedChanges,
        )

        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()

        assertTrue("onSave must fire", captured != null)
        assertEquals(10L, captured?.selectedCalendarId)
        assertTrue("late-loading device groups must recover the device path", captured?.isDeviceCalendar == true)
        assertEquals("Google Cal", captured?.selectedCalendarName)
    }

    @Test
    fun `device duplicate falls back to default when the source device calendar is gone`() {
        var captured: EventFormState? = null
        // Source device calendar id 99 is absent from the (empty) device groups.
        renderDuplicate(
            source = sourceEvent(calendarId = 0L),
            duplicateFromDeviceCalendarId = 99L,
            deviceGroups = emptyList(),
        ) { captured = it }

        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()

        assertTrue("onSave must fire", captured != null)
        // Falls back to the resolved default (Room calendar 1L), not the device path.
        assertEquals(1L, captured?.selectedCalendarId)
        assertFalse("gone source must not resolve as device", captured?.isDeviceCalendar == true)
    }
}
