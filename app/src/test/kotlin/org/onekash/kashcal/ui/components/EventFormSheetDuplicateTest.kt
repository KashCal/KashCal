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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.contacts.ContactEmail
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.testutil.phoneLocalDate
import org.onekash.kashcal.testutil.withDeviceTimeZone
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.PickerCalendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Renders [EventFormContent] with a `duplicateFrom` source and checks what the duplicate
 * carries into the saved [EventFormState].
 *
 * Tags (RFC 5545 CATEGORIES): the source's tag chips show before save and the save carries
 * them; a tagless source seeds none. Room and device duplicates take the same `duplicateFrom`
 * branch. Calendar: a device duplicate opens on its source device calendar, also when the
 * device groups load after the first frame (without reporting unsaved changes); a Room
 * duplicate keeps the Room path; a gone source device calendar falls back to the Room
 * default. Repeat: a series' rule shows in the Repeat row and saves unless cleared; a changed
 * occurrence saves without one. Time: a timed copy shows its own zone's wall clock with the
 * phone in another zone and keeps the zone; an unknown zone shows the phone zone and keeps
 * the original ID as `sourceTimezoneId`; an all-day copy keeps its local date.
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

    private fun sourceEvent(
        categories: List<String>? = null,
        calendarId: Long = 1L,
        startTs: Long = 0L,
        endTs: Long = 0L,
        timezone: String? = null,
        rrule: String? = null,
        originalEventId: Long? = null,
    ) = Event(
        id = 1L,
        uid = "dup-source@test",
        calendarId = calendarId,
        title = "Team Lunch",
        startTs = startTs,
        endTs = endTs,
        timezone = timezone,
        rrule = rrule,
        originalEventId = originalEventId,
        dtstamp = 0L,
        categories = categories,
    )

    // Monday 2026-01-19 09:00 to 10:00 in New York, 14:00 to 15:00 in London.
    private val nyStart = ZonedDateTime.of(2026, 1, 19, 9, 0, 0, 0, ZoneId.of("America/New_York"))
        .toInstant().toEpochMilli()
    private val nyEnd = nyStart + 3_600_000L

    private fun saveAndCapture(): EventFormState {
        composeTestRule.onNodeWithText("Save Event").performClick()
        composeTestRule.waitForIdle()
        return checkNotNull(lastCaptured) { "onSave must fire" }
    }

    private var lastCaptured: EventFormState? = null
    private var lastReportedChanges: Boolean? = null

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
                        accessLevel = 700, // At least CONTRIBUTOR (500), so writable.
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
        onCapture: (EventFormState) -> Unit = { lastCaptured = it },
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    onHasChangesChange = { lastReportedChanges = it },
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
        // A device duplicate zeroes calendarId and passes the source device calendar
        // id separately, as toEventForDuplicate and MainActivity do.
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
        // Cold start: the form opens before deviceCalendarGroups loads, so the
        // source device calendar isn't resolvable and the form falls back to the
        // Room default. Once the groups arrive, the form must re-resolve to the
        // source device calendar, or the user stays on the Room path.
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

        // The late switch to the device source isn't a user edit, so it goes into
        // the baseline: an untouched form reports no unsaved changes, or closing it
        // asks to discard.
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

    @Test
    fun `duplicate of a series shows its rule in Repeat and save carries it`() {
        renderDuplicate(sourceEvent(rrule = "FREQ=WEEKLY;BYDAY=MO"))

        composeTestRule.onNodeWithText("Weekly on Mon").assertExists()
        assertEquals("FREQ=WEEKLY;BYDAY=MO", saveAndCapture().rrule)
    }

    @Test
    fun `duplicate of a changed occurrence saves as a one-off`() {
        renderDuplicate(
            sourceEvent(rrule = "FREQ=WEEKLY;BYDAY=MO", originalEventId = 7L)
        )

        assertNull(saveAndCapture().rrule)
    }

    @Test
    fun `duplicate repeat can be cleared before save`() {
        renderDuplicate(sourceEvent(rrule = "FREQ=WEEKLY;BYDAY=MO"))

        composeTestRule.onNodeWithText("Weekly on Mon").performClick()
        composeTestRule.waitForIdle()
        // The frequency row comes before the end-condition row, which also has a "Never".
        composeTestRule.onAllNodesWithText("Never").onFirst().performClick()
        composeTestRule.waitForIdle()

        assertNull(saveAndCapture().rrule)
    }

    @Test
    fun `timed duplicate shows the source zone wall clock and keeps the zone`() {
        val state = withDeviceTimeZone("Europe/London") {
            renderDuplicate(sourceEvent(startTs = nyStart, endTs = nyEnd, timezone = "America/New_York"))
            saveAndCapture()
        }
        assertEquals("America/New_York", state.timezone)
        assertEquals(9, state.startHour)
        assertEquals(0, state.startMinute)
        assertEquals(10, state.endHour)
        assertEquals(nyStart to nyEnd, state.toStartEndTs())
    }

    @Test
    fun `duplicate with an unknown zone shows the phone zone and keeps the original id`() {
        val state = withDeviceTimeZone("Europe/London") {
            renderDuplicate(sourceEvent(startTs = nyStart, endTs = nyEnd, timezone = "Mars/Olympus_Mons"))
            saveAndCapture()
        }
        assertNull(state.timezone)
        assertEquals("Mars/Olympus_Mons", state.sourceTimezoneId)
        assertEquals(14, state.startHour)
        assertEquals(nyStart to nyEnd, state.toStartEndTs())
    }

    @Test
    fun `all-day duplicate opens on the same local date`() {
        val utcMidnight = Instant.parse("2026-03-16T00:00:00Z").toEpochMilli()
        withDeviceTimeZone("America/Los_Angeles") {
            renderDuplicate(
                sourceEvent(startTs = utcMidnight, endTs = utcMidnight + 86_400_000L - 1).copy(isAllDay = true)
            )
            val state = saveAndCapture()
            assertTrue(state.isAllDay)
            assertEquals(LocalDate.of(2026, 3, 16), phoneLocalDate(state.dateMillis))
        }
    }

    @Test
    fun `duplicate of a series opens without unsaved changes and carries the other fields`() {
        val source = sourceEvent(rrule = "FREQ=WEEKLY;BYDAY=MO", startTs = nyStart, endTs = nyEnd, timezone = "America/New_York")
            .copy(
                location = "Room 4",
                description = "notes",
                reminders = listOf("-PT30M"),
                transp = "TRANSPARENT",
                color = 0xFF00AA00.toInt(),
            )
        renderDuplicate(source)

        assertEquals(false, lastReportedChanges)
        val state = saveAndCapture()
        assertEquals("Room 4", state.location)
        assertEquals("notes", state.description)
        assertEquals(listOf(30), state.reminders)
        assertEquals("TRANSPARENT", state.transp)
        assertEquals(0xFF00AA00.toInt(), state.eventColor)
    }

    @Test
    fun `duplicate keeps an unknown zone id only for a timed event with a non-blank zone`() {
        val timed = EventFormState().withDuplicateOf(sourceEvent(startTs = nyStart, endTs = nyEnd, timezone = "Mars/Olympus_Mons"))
        val blank = EventFormState().withDuplicateOf(sourceEvent(startTs = nyStart, endTs = nyEnd, timezone = ""))
        val allDay = EventFormState().withDuplicateOf(
            sourceEvent(startTs = 0L, endTs = 86_400_000L - 1, timezone = "Mars/Olympus_Mons").copy(isAllDay = true)
        )

        assertEquals("Mars/Olympus_Mons", timed.sourceTimezoneId)
        assertNull(blank.sourceTimezoneId)
        assertNull(blank.timezone)
        assertNull(allDay.sourceTimezoneId)
        assertNull(allDay.timezone)
    }
}
