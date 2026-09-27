package org.onekash.kashcal.ui.viewmodels

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY_CODE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.EVENT_ZONE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.MINUTE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.OWNER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.T0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.occ
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.WEEK
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.domain.mapper.toFormState
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.writer.DeviceEventDraft
import org.onekash.kashcal.testutil.phoneMidnight
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.withAllDay
import org.onekash.kashcal.ui.components.withTimezone
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests device-calendar workflows that start in the ViewModel (the form's load
 * and save, drag to reschedule, the delete routes) through the real
 * HomeViewModel, the real device reader and writer and the real provider
 * repository over [SqliteCalendarProvider], read back from the stored provider
 * rows and the occurrences the grid loads.
 *
 * The form's load step lives in EventFormSheet's load effect, which a unit
 * test can't run; [openForm] repeats what it does with the loaded data (the
 * mapper call, the occurrence time, the guest-picker seed, nothing marked
 * edited), with no calendar groups. Saves are called the way MainActivity
 * calls them: a direct save for a one-off event, a scoped save for a recurring
 * one. The three ignored tests record open-and-save losses the form still has:
 * a tentative event saved as busy, an email reminder saved as an alert, and a
 * seconds-only duration series saved with zero length.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HomeViewModelDeviceRealProviderRoundTripTest {

    private val testDispatcher = StandardTestDispatcher()
    private val f = DeviceRoundTripFixture()
    private lateinit var factory: DeviceHomeViewModelTestFactory
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        f.setUp()
        Dispatchers.setMain(testDispatcher)
        factory = DeviceHomeViewModelTestFactory()
        vm = factory.create(f.reader, f.writer, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        f.tearDown()
    }

    // ---- helpers ----

    private val ana get() = f.guest("ana@example.test", "Ana Lima")

    private suspend fun create(draft: DeviceEventDraft = f.draft()): Long = f.create(draft)

    /** Opens the form on [eventId] at [occurrenceTs], as EventFormSheet's load does. */
    private suspend fun openForm(eventId: Long, occurrenceTs: Long? = null, isAllDay: Boolean = false): EventFormState {
        val data = vm.getDeviceEventForEdit(eventId, occurrenceTs, isAllDay)!!
        val mapped = data.event.toFormState(
            reminders = data.reminders,
            calendarColor = data.calendarColor,
            calendarName = data.calendarName,
            deviceCalendarGroups = emptyList(),
            occurrenceTs = occurrenceTs,
        )
        return mapped.copy(editingOccurrenceTs = occurrenceTs, attendees = deviceGuestsToPickerSeed(data.attendees))
    }

    /**
     * Everything stored for an event: its row (minus the dirty flag), reminders, guests and tags.
     */
    private fun snapshot(eventId: Long): Map<String, Any?> = mapOf(
        "event" to (f.eventRow(eventId)!! - Events.DIRTY),
        "reminders" to f.provider.reminderRows(eventId),
        "guests" to f.provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", eventId.toString())
            .map { it - Attendees._ID }.sortedBy { it[Attendees.ATTENDEE_EMAIL] },
        "tags" to f.provider.rows(SqliteCalendarProvider.EXTENDED_PROPERTIES, "${ExtendedProperties.EVENT_ID} = ?", eventId.toString())
            .map { it[ExtendedProperties.VALUE] },
    )

    private suspend fun march() = f.inMarch()

    private suspend fun createSyncedSeries(rrule: String = "FREQ=WEEKLY;BYDAY=TU;COUNT=4"): Long {
        val id = create(f.draft(title = "Standup", rrule = rrule, startTs = T0, endTs = T0 + HOUR, reminders = listOf(10)))
        f.markSynced(id, "series-$id")
        return id
    }

    /**
     * Waits for work the ViewModel launched: it hops to the repository's IO
     * thread, so the test scheduler alone can go idle before the write lands.
     */
    private suspend fun TestScope.awaitUntil(what: String, condition: suspend () -> Boolean) {
        // Generous: the first test in a cold JVM spends seconds loading classes.
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            advanceUntilIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private suspend fun TestScope.awaitDragResult() =
        awaitUntil("the drag's result") { vm.uiState.value.pendingSnackbarMessage != null || vm.uiState.value.currentError != null }

    // ---- open and save changes nothing ----

    @Test
    fun `opening and saving a one-off event unchanged leaves every stored row as it was`() = runTest {
        val id = create(f.draft(attendees = listOf(ana), categories = listOf("Work")))
        val before = snapshot(id)

        assertTrue(vm.saveDeviceEvent(openForm(id)).isSuccess)

        assertEquals(before, snapshot(id))
    }

    @Ignore("The form keeps only free or busy, so a tentative event is saved back as busy")
    @Test
    fun `opening and saving a tentative event keeps it tentative`() = runTest {
        val id = create(f.draft(availability = Events.AVAILABILITY_TENTATIVE))

        vm.saveDeviceEvent(openForm(id))

        assertEquals(Events.AVAILABILITY_TENTATIVE.toString(), f.eventRow(id)!![Events.AVAILABILITY])
    }

    @Ignore("A form save rewrites every reminder as a pop-up alert, so an email reminder becomes an alert")
    @Test
    fun `opening and saving an event with an email reminder keeps it an email reminder`() = runTest {
        val id = create(f.draft(reminders = emptyList()))
        f.provider.insertRow(SqliteCalendarProvider.REMINDERS, Reminders.EVENT_ID to id, Reminders.MINUTES to 30, Reminders.METHOD to Reminders.METHOD_EMAIL)

        vm.saveDeviceEvent(openForm(id))

        assertEquals(listOf(30 to Reminders.METHOD_EMAIL), f.provider.reminderRows(id))
    }

    @Test
    fun `opening a later occurrence and saving all events unchanged keeps the series where it was`() = runTest {
        val id = createSyncedSeries()
        val before = snapshot(id)

        assertTrue(vm.saveDeviceEvent(openForm(id, occurrenceTs = occ(2)), EditScope.ALL_EVENTS).isSuccess)

        assertEquals(before, snapshot(id))
        assertEquals((0..3).map(::occ), march().map { it.startTs })
    }

    @Test
    fun `renaming a series from a later occurrence renames every occurrence and keeps its dates`() = runTest {
        val id = createSyncedSeries()

        val renamed = openForm(id, occurrenceTs = occ(2)).copy(title = "Daily sync")
        assertTrue(vm.saveDeviceEvent(renamed, EditScope.ALL_EVENTS).isSuccess)

        val shown = f.repository.getInstancesForDayRange(20240301, 20240430, setOf(SYNCED_CAL)).sortedBy { it.startTs }
        assertEquals("the series still starts on its first occurrence", T0.toString(), f.eventRow(id)!![Events.DTSTART])
        assertEquals("the same four occurrences", (0..3).map(::occ), shown.map { it.startTs })
        assertTrue("all renamed", shown.all { it.title == "Daily sync" })
    }

    /** Every occurrence start the series shows over March and April. */
    private suspend fun shownStarts(id: Long) =
        f.repository.getInstancesForDayRange(20240301, 20240430, setOf(SYNCED_CAL))
            .filter { it.eventId == id }.map { it.startTs }.sorted()

    @Test
    fun `changing details from a later occurrence applies them to every occurrence and keeps the dates`() = runTest {
        val id = createSyncedSeries()

        val edited = openForm(id, occurrenceTs = occ(2)).copy(location = "Room 9", description = "New agenda", eventColor = 0xFF112233.toInt())
        assertTrue(vm.saveDeviceEvent(edited, EditScope.ALL_EVENTS).isSuccess)

        val row = f.eventRow(id)!!
        assertEquals(T0.toString(), row[Events.DTSTART])
        assertEquals("Room 9", row[Events.EVENT_LOCATION])
        assertEquals("New agenda", row[Events.DESCRIPTION])
        assertEquals(0xFF112233.toInt().toString(), row[Events.EVENT_COLOR])
        assertEquals((0..3).map(::occ), shownStarts(id))
    }

    @Test
    fun `moving the time from a later occurrence moves every occurrence to it and keeps the dates`() = runTest {
        val id = createSyncedSeries()

        // 10:00 -> 10:30 New York; the series starts before the switch to summer time.
        val later = openForm(id, occurrenceTs = occ(2)).copy(startHour = 10, startMinute = 30, endHour = 11, endMinute = 30)
        assertTrue(vm.saveDeviceEvent(later, EditScope.ALL_EVENTS).isSuccess)

        assertEquals((0..3).map { occ(it) + 30 * MINUTE }, shownStarts(id))
    }

    @Test
    fun `an all-day series edited from a later occurrence keeps its first date and length`() = runTest {
        val first = DAY0
        val id = create(f.draft(title = "Offsite", isAllDay = true, startTs = first, endTs = first + DAY, rrule = "FREQ=WEEKLY;COUNT=4", reminders = listOf(10)))
        f.markSynced(id, "series-$id")

        val renamed = openForm(id, occurrenceTs = first + 2 * WEEK, isAllDay = true).copy(title = "Team offsite")
        assertTrue(vm.saveDeviceEvent(renamed, EditScope.ALL_EVENTS).isSuccess)

        assertEquals(first.toString(), f.eventRow(id)!![Events.DTSTART])
        assertEquals((0..3).map { first + it * WEEK }, shownStarts(id))
        assertEquals("the series keeps its one-day length", "P1D", f.eventRow(id)!![Events.DURATION])
    }

    @Test
    fun `moving the first occurrence to another day with all events moves the series`() = runTest {
        val id = createSyncedSeries()

        val form = openForm(id, occurrenceTs = occ(0))
        val nextDay = form.copy(dateMillis = form.dateMillis + DAY, endDateMillis = form.endDateMillis + DAY)
        assertTrue(vm.saveDeviceEvent(nextDay, EditScope.ALL_EVENTS).isSuccess)

        assertEquals((T0 + DAY).toString(), f.eventRow(id)!![Events.DTSTART])
    }

    @Test
    fun `changing only the timezone from a later occurrence keeps the series' first date`() = runTest {
        // No weekday in the rule, so moving the series to Tokyo can't shift it to another day.
        val id = createSyncedSeries(rrule = "FREQ=WEEKLY;COUNT=4")

        val form = openForm(id, occurrenceTs = occ(2)).withTimezone("Asia/Tokyo")
        assertTrue(vm.saveDeviceEvent(form, EditScope.ALL_EVENTS).isSuccess)

        assertEquals(T0.toString(), f.eventRow(id)!![Events.DTSTART])
        assertEquals("Asia/Tokyo", f.eventRow(id)!![Events.EVENT_TIMEZONE])
        // Tokyo keeps no summer time, so each occurrence is the first one's instant plus
        // whole weeks.
        assertEquals((0..3).map { T0 + it * WEEK }, shownStarts(id))
    }

    @Test
    fun `an unchanged all events save from a later occurrence keeps a start that carries seconds`() = runTest {
        val start = T0 + 17_000L
        val id = create(f.draft(title = "Standup", rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=4", startTs = start, endTs = start + HOUR, reminders = listOf(10)))
        f.markSynced(id, "series-$id")
        val before = snapshot(id)

        assertTrue(vm.saveDeviceEvent(openForm(id, occurrenceTs = occ(2) + 17_000L), EditScope.ALL_EVENTS).isSuccess)

        assertEquals(before, snapshot(id))
    }

    @Test
    fun `an unchanged all events save from a later occurrence keeps a series stored in an unknown zone`() = runTest {
        // Starts 19 Mar and runs past the phone's switch to summer time on 31 Mar.
        val start = T0 + 2 * WEEK
        val id = f.provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to SYNCED_CAL, Events.TITLE to "Weekly sync", Events.DTSTART to start,
            Events.DURATION to "PT1H", Events.RRULE to "FREQ=WEEKLY;COUNT=4", Events.EVENT_TIMEZONE to "Custom/Office",
            // The platform stores these as NOT NULL DEFAULT 0.
            Events.ALL_DAY to 0, Events.AVAILABILITY to 0,
            Events._SYNC_ID to "remote-2",
        )
        val before = snapshot(id)

        assertTrue(vm.saveDeviceEvent(openForm(id, occurrenceTs = shownStarts(id)[2]), EditScope.ALL_EVENTS).isSuccess)

        assertEquals(before, snapshot(id))
    }

    @Test
    fun `turning a series all-day from a later occurrence keeps its first date`() = runTest {
        val id = createSyncedSeries()

        val allDay = openForm(id, occurrenceTs = occ(2)).withAllDay(newIsAllDay = true, defaultReminderTimed = 15, defaultReminderAllDay = 900)
        assertTrue(vm.saveDeviceEvent(allDay, EditScope.ALL_EVENTS).isSuccess)

        val stored = f.eventRow(id)!![Events.DTSTART]!!.toLong()
        assertEquals("the series' own first date", DAY0, stored)
    }

    @Test
    fun `opening the first occurrence and saving all events unchanged leaves the series as it was`() = runTest {
        val id = createSyncedSeries()
        val before = snapshot(id)

        assertTrue(vm.saveDeviceEvent(openForm(id, occurrenceTs = occ(0)), EditScope.ALL_EVENTS).isSuccess)

        assertEquals(before, snapshot(id))
    }

    /** A series the way another sync adapter stores it: seconds-only duration, no status. */
    private fun syncAdapterSeries(duration: String): Long = f.provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to SYNCED_CAL, Events.TITLE to "Weekly sync", Events.DTSTART to T0,
        Events.DURATION to duration, Events.RRULE to "FREQ=WEEKLY;COUNT=4", Events.EVENT_TIMEZONE to EVENT_ZONE,
        Events._SYNC_ID to "remote-1",
    )

    @Test
    fun `saving a sync adapter's standard-duration series unchanged keeps its length, times and status`() = runTest {
        val id = syncAdapterSeries("PT1H")

        assertTrue(vm.saveDeviceEvent(openForm(id, occurrenceTs = occ(0)), EditScope.ALL_EVENTS).isSuccess)

        val row = f.eventRow(id)!!
        assertEquals(T0.toString(), row[Events.DTSTART])
        assertEquals(HOUR, SqliteCalendarProvider.parsePlatformDuration(row[Events.DURATION]!!))
        assertNull("no status is written", row[Events.STATUS])
        assertTrue(march().all { it.endTs - it.startTs == HOUR })
    }

    @Ignore("The form reads a seconds-only duration such as P3600S as zero, so an unchanged save stores a zero-length series")
    @Test
    fun `saving a sync adapter's seconds-only duration series unchanged keeps its length`() = runTest {
        val id = syncAdapterSeries("P3600S")

        assertTrue(vm.saveDeviceEvent(openForm(id, occurrenceTs = occ(0)), EditScope.ALL_EVENTS).isSuccess)

        assertEquals(HOUR, SqliteCalendarProvider.parsePlatformDuration(f.eventRow(id)!![Events.DURATION]!!))
        assertTrue(march().all { it.endTs - it.startTs == HOUR })
    }

    @Test
    fun `opening a modified occurrence and saving it unchanged leaves the exception as it was`() = runTest {
        val id = createSyncedSeries()
        f.writer.editSingleOccurrence(id, occ(1), f.draft(title = "Standup (moved)", startTs = occ(1) + 2 * HOUR, endTs = occ(1) + 3 * HOUR, reminders = listOf(10)))
        val exceptionId = march()[1].eventId
        val before = snapshot(exceptionId)

        assertTrue(vm.saveDeviceEvent(openForm(id, occurrenceTs = occ(1)), EditScope.THIS_EVENT).isSuccess)

        assertEquals(before, snapshot(exceptionId))
        assertEquals(4, march().size)
    }

    // ---- form edits ----

    private suspend fun editOneOff(change: (EventFormState) -> EventFormState): Long {
        val id = create(f.draft(attendees = listOf(ana), categories = listOf("Work")))
        assertTrue(vm.saveDeviceEvent(change(openForm(id))).isSuccess)
        return id
    }

    @Test
    fun `editing text fields in the form stores them`() = runTest {
        val id = editOneOff { it.copy(title = "Design review v2", description = "New agenda", location = "Room 9") }

        val occurrence = f.occurrences(DAY_CODE).single { it.eventId == id }
        assertEquals("Design review v2", occurrence.title)
        assertEquals("New agenda", occurrence.description)
        assertEquals("Room 9", occurrence.location)
    }

    @Test
    fun `clearing the description in the form clears it`() = runTest {
        val id = editOneOff { it.copy(description = "") }

        assertNull(f.eventRow(id)!![Events.DESCRIPTION])
    }

    @Test
    fun `moving the times in the form stores them in the event's zone`() = runTest {
        val id = editOneOff { it.copy(startHour = 11, startMinute = 30, endHour = 12, endMinute = 45) }

        val ny = ZoneId.of(EVENT_ZONE)
        val row = f.eventRow(id)!!
        assertEquals(ZonedDateTime.of(2024, 3, 5, 11, 30, 0, 0, ny).toInstant().toEpochMilli().toString(), row[Events.DTSTART])
        assertEquals(ZonedDateTime.of(2024, 3, 5, 12, 45, 0, 0, ny).toInstant().toEpochMilli().toString(), row[Events.DTEND])
        assertEquals(EVENT_ZONE, row[Events.EVENT_TIMEZONE])
    }

    @Test
    fun `moving the date in the form moves the event to that day`() = runTest {
        val id = editOneOff { it.copy(dateMillis = it.dateMillis + 2 * DAY, endDateMillis = it.endDateMillis + 2 * DAY) }

        assertEquals((T0 + 2 * DAY).toString(), f.eventRow(id)!![Events.DTSTART])
        assertEquals(DAY_CODE + 2, f.occurrences(DAY_CODE + 2).single { it.eventId == id }.startDay)
    }

    @Test
    fun `changing the zone in the form keeps the clock time in the new zone`() = runTest {
        val id = editOneOff { it.copy(timezone = "Asia/Tokyo") }

        val row = f.eventRow(id)!!
        assertEquals("Asia/Tokyo", row[Events.EVENT_TIMEZONE])
        assertEquals(ZonedDateTime.of(2024, 3, 5, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli().toString(), row[Events.DTSTART])
    }

    @Test
    fun `turning all-day on in the form stores an all-day event on that date`() = runTest {
        val id = editOneOff { it.copy(isAllDay = true, timezone = null) }

        val row = f.eventRow(id)!!
        assertEquals("1", row[Events.ALL_DAY])
        assertEquals(DAY0.toString(), row[Events.DTSTART])
        assertEquals((DAY0 + DAY).toString(), row[Events.DTEND])
        assertEquals("UTC", row[Events.EVENT_TIMEZONE])
    }

    @Test
    fun `changing colour, availability and reminders in the form stores them`() = runTest {
        val id = editOneOff { it.copy(eventColor = 0xFF998877.toInt(), transp = "OPAQUE", reminders = listOf(5, 30)) }

        val row = f.eventRow(id)!!
        assertEquals(0xFF998877.toInt().toString(), row[Events.EVENT_COLOR])
        assertEquals(Events.AVAILABILITY_BUSY.toString(), row[Events.AVAILABILITY])
        assertEquals(listOf(5 to Reminders.METHOD_ALERT, 30 to Reminders.METHOD_ALERT), f.provider.reminderRows(id))
    }

    @Test
    fun `adding and removing guests in the form keeps untouched guests' responses`() = runTest {
        val id = create(f.draft(attendees = listOf(ana, ana.copy(email = "bo@example.test", name = null))))
        val anaRowId = f.provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.ATTENDEE_EMAIL} = ?", "ana@example.test").single()[Attendees._ID]!!.toLong()
        f.provider.updateRow(SqliteCalendarProvider.ATTENDEES, anaRowId, Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED)
        val form = openForm(id)
        val edited = form.attendees.filterNot { it.address == "bo@example.test" } +
            Attendee(eventId = 0L, address = "cy@example.test", displayName = "Cy")

        assertTrue(vm.saveDeviceEvent(form.copy(attendees = edited, attendeesEdited = true)).isSuccess)

        val rows = f.provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", id.toString()).associateBy { it[Attendees.ATTENDEE_EMAIL] }
        assertEquals(setOf(OWNER, "ana@example.test", "cy@example.test"), rows.keys)
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED.toString(), rows.getValue("ana@example.test")[Attendees.ATTENDEE_STATUS])
    }

    @Test
    fun `setting and clearing tags in the form stores them`() = runTest {
        val id = create(f.draft(categories = listOf("Work")))

        vm.saveDeviceEvent(openForm(id).copy(categories = listOf("Home", "Errands"), categoriesEdited = true))
        assertEquals(listOf("Home", "Errands"), f.occurrences(DAY_CODE).single().categories)

        vm.saveDeviceEvent(openForm(id).copy(categories = emptyList(), categoriesEdited = true))
        assertTrue(f.occurrences(DAY_CODE).single().categories.isEmpty())
    }

    @Test
    fun `changing the calendar in the form moves the event there`() = runTest {
        val id = create()

        vm.saveDeviceEvent(openForm(id).copy(selectedCalendarId = LOCAL_CAL))

        val occurrence = f.occurrences(DAY_CODE).single()
        assertEquals(LOCAL_CAL, occurrence.calendarId)
        assertEquals("Design review", occurrence.title)
        assertNull("the unsynced source is gone", f.eventRow(id))
    }

    // ---- form create ----

    private fun newEventForm(
        title: String,
        isAllDay: Boolean = false,
        rrule: String? = null,
        timezone: String? = EVENT_ZONE,
        calendarId: Long = SYNCED_CAL,
    ): EventFormState {
        // The form's dates are device-zone midnights (Berlin here).
        val day = phoneMidnight(LocalDate.of(2024, 3, 5))
        return EventFormState(
            title = title, dateMillis = day, endDateMillis = day, startHour = 10, startMinute = 0, endHour = 11, endMinute = 0,
            isAllDay = isAllDay, timezone = if (isAllDay) null else timezone, selectedCalendarId = calendarId,
            reminders = listOf(15), rrule = rrule, isDeviceCalendar = true,
        )
    }

    @Test
    fun `creating a timed event in the form stores it at that clock time in the chosen zone`() = runTest {
        val id = vm.saveDeviceEvent(newEventForm("Kickoff")).getOrThrow()

        val occurrence = f.occurrences(DAY_CODE).single { it.eventId == id }
        assertEquals(T0, occurrence.startTs)
        assertEquals(T0 + HOUR, occurrence.endTs)
        assertEquals(EVENT_ZONE, occurrence.timezone)
        assertEquals(listOf(15), occurrence.reminders)
    }

    @Test
    fun `creating an all-day event in the form stores that day`() = runTest {
        val id = vm.saveDeviceEvent(newEventForm("Holiday", isAllDay = true)).getOrThrow()

        val row = f.eventRow(id)!!
        assertEquals(DAY0.toString(), row[Events.DTSTART])
        assertEquals((DAY0 + DAY).toString(), row[Events.DTEND])
        assertEquals(DAY_CODE, f.occurrences(DAY_CODE).single().startDay)
    }

    @Test
    fun `creating a repeating event in the form stores a series with its occurrences`() = runTest {
        val id = vm.saveDeviceEvent(newEventForm("Standup", rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=4")).getOrThrow()

        assertEquals("FREQ=WEEKLY;BYDAY=TU;COUNT=4", f.eventRow(id)!![Events.RRULE])
        assertEquals((0..3).map(::occ), march().map { it.startTs })
    }

    // ---- form scoped saves ----

    @Test
    fun `saving this event from the form on a plain occurrence adds an exception for it`() = runTest {
        val id = createSyncedSeries()

        val form = openForm(id, occurrenceTs = occ(2))
        assertTrue(vm.saveDeviceEvent(form.copy(title = "Standup (special)"), EditScope.THIS_EVENT).isSuccess)

        val occurrences = march()
        assertEquals((0..3).map(::occ), occurrences.map { it.startTs })
        assertEquals("Standup (special)", occurrences[2].title)
        assertEquals(id, occurrences[2].originalId)
    }

    @Test
    fun `saving one occurrence all-day from the form replaces it, and its form deletes just it`() = runTest {
        val id = createSyncedSeries()

        val form = openForm(id, occurrenceTs = occ(1))
        assertTrue(vm.saveDeviceEvent(form.copy(title = "Offsite", isAllDay = true, timezone = null), EditScope.THIS_EVENT).isSuccess)

        val shown = march()
        assertEquals(4, shown.size)
        val offsite = shown.single { it.startDay == DAY_CODE + 7 }
        assertEquals("Offsite", offsite.title)
        assertTrue(offsite.isAllDay)

        // The form opened on the changed occurrence holds the series id, so its
        // delete asks which occurrences; the exception's own all-day flag rides along.
        val reopened = openForm(id, occurrenceTs = offsite.originalInstanceTime, isAllDay = true)
        assertEquals("Offsite", reopened.title)
        assertTrue(vm.handleDeviceEventFormDelete(reopened).isSuccess)
        vm.confirmDelete(EditScope.THIS_EVENT)
        awaitUntil("the occurrence delete") { march().size == 3 }

        assertEquals(listOf(occ(0), occ(2), occ(3)), march().map { it.startTs })
    }

    @Test
    fun `deleting this and future from an occurrence saved all-day ends a timed series before it`() = runTest {
        val id = createSyncedSeries(rrule = "FREQ=WEEKLY;BYDAY=TU")
        val form = openForm(id, occurrenceTs = occ(1))
        assertTrue(vm.saveDeviceEvent(form.copy(title = "Offsite", isAllDay = true, timezone = null), EditScope.THIS_EVENT).isSuccess)
        val offsite = march().single { it.title == "Offsite" }

        assertTrue(vm.handleDeviceEventFormDelete(openForm(id, occurrenceTs = offsite.originalInstanceTime, isAllDay = true)).isSuccess)
        vm.confirmDelete(EditScope.THIS_AND_FUTURE)
        awaitUntil("the future delete") { march().size == 1 }

        assertEquals(listOf(occ(0)), march().map { it.startTs })
        // A timed series keeps a date-time end: RFC 5545 section 3.3.10 gives UNTIL the
        // value type of DTSTART.
        assertTrue(f.eventRow(id)!![Events.RRULE]!!.matches(Regex(".*UNTIL=\\d{8}T\\d{6}Z.*")))
    }

    @Test
    fun `saving this and future from the form splits the series at that occurrence`() = runTest {
        val id = createSyncedSeries(rrule = "FREQ=WEEKLY;BYDAY=TU")

        val form = openForm(id, occurrenceTs = occ(2))
        assertTrue(vm.saveDeviceEvent(form.copy(title = "Standup v2"), EditScope.THIS_AND_FUTURE).isSuccess)

        assertEquals(listOf("Standup", "Standup", "Standup v2", "Standup v2"), march().map { it.title })
        assertEquals((0..3).map(::occ), march().map { it.startTs })
    }

    // ---- drag ----

    private fun berlin(day: Int, hour: Int): Long =
        ZonedDateTime.of(2024, 3, day, hour, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `dragging a one-off event moves it and keeps every other field`() = runTest {
        val id = create(f.draft(attendees = listOf(ana), categories = listOf("Work")))
        val before = snapshot(id)

        vm.rescheduleEvent(DisplayEvent.Device(f.occurrences(DAY_CODE).single()), LocalDate.of(2024, 3, 7), 18 * 60)
        awaitDragResult()

        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULED_MESSAGE, vm.uiState.value.pendingSnackbarMessage)
        val after = snapshot(id)
        val row = after.getValue("event") as Map<*, *>
        assertEquals(berlin(7, 18).toString(), row[Events.DTSTART])
        assertEquals((berlin(7, 18) + 90 * MINUTE).toString(), row[Events.DTEND])
        val beforeRow = before.getValue("event") as Map<*, *>
        assertEquals(beforeRow - setOf(Events.DTSTART, Events.DTEND), row - setOf(Events.DTSTART, Events.DTEND))
        assertEquals(before - "event", after - "event")
    }

    @Test
    fun `dragging one occurrence moves just it and keeps its fields`() = runTest {
        val id = createSyncedSeries()
        val occurrence = march()[1]

        vm.rescheduleEvent(DisplayEvent.Device(occurrence), LocalDate.of(2024, 3, 14), 9 * 60)
        vm.confirmReschedule(EditScope.THIS_EVENT)
        awaitDragResult()

        val occurrences = march()
        assertEquals(listOf(occ(0), berlin(14, 9), occ(2), occ(3)), occurrences.map { it.startTs })
        assertEquals(id, occurrences[1].originalId)
        assertEquals("Standup", occurrences[1].title)
        assertEquals(listOf(10), occurrences[1].reminders)
    }

    @Test
    fun `dragging a modified occurrence moves that exception`() = runTest {
        val id = createSyncedSeries()
        f.writer.editSingleOccurrence(id, occ(1), f.draft(title = "Standup (moved)", startTs = occ(1) + 2 * HOUR, endTs = occ(1) + 3 * HOUR, reminders = listOf(10)))
        val exception = march()[1]

        vm.rescheduleEvent(DisplayEvent.Device(exception), LocalDate.of(2024, 3, 15), 9 * 60)
        awaitDragResult()

        val moved = march().single { it.eventId == exception.eventId }
        assertEquals(berlin(15, 9), moved.startTs)
        assertEquals("Standup (moved)", moved.title)
        assertEquals(4, march().size)
    }

    @Test
    fun `dragging this and future moves that occurrence and the rest`() = runTest {
        createSyncedSeries(rrule = "FREQ=WEEKLY;BYDAY=TU")
        val occurrence = march()[2]

        vm.rescheduleEvent(DisplayEvent.Device(occurrence), LocalDate.of(2024, 3, 19), 17 * 60)
        vm.confirmReschedule(EditScope.THIS_AND_FUTURE)
        awaitDragResult()

        val starts = march().map { it.startTs }
        assertEquals(listOf(occ(0), occ(1), berlin(19, 17), berlin(26, 17)), starts)
    }

    @Test
    fun `dragging this and future on a counted series keeps the total number of occurrences`() = runTest {
        createSyncedSeries(rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=4")
        val occurrence = march()[2]

        vm.rescheduleEvent(DisplayEvent.Device(occurrence), LocalDate.of(2024, 3, 19), 17 * 60)
        vm.confirmReschedule(EditScope.THIS_AND_FUTURE)
        awaitDragResult()

        // Through April: a new series that got the full count again would add occurrences there.
        val starts = f.inMarchAndApril().map { it.startTs }
        assertEquals(listOf(occ(0), occ(1), berlin(19, 17), berlin(26, 17)), starts)
    }

    // ---- this and future to another day ----

    /**
     * Drops the third occurrence (Tue 19 Mar) on Wed 20 Mar at 17:00 Berlin and
     * waits for the scope check.
     */
    private suspend fun TestScope.dropThirdOnWednesday(): Set<EditScope> {
        val occurrence = march()[2]
        vm.rescheduleEvent(DisplayEvent.Device(occurrence), LocalDate.of(2024, 3, 20), 17 * 60)
        assertNull("the series scopes wait for the check", vm.uiState.value.pendingDragReschedule!!.blockedScopes)
        awaitUntil("the scope check") { vm.uiState.value.pendingDragReschedule?.blockedScopes != null }
        return vm.uiState.value.pendingDragReschedule!!.blockedScopes!!
    }

    private suspend fun TestScope.moveThirdToWednesday() {
        assertTrue(EditScope.THIS_AND_FUTURE !in dropThirdOnWednesday())
        vm.confirmReschedule(EditScope.THIS_AND_FUTURE)
        awaitDragResult()
    }

    @Test
    fun `dragging this and future to another weekday moves every later occurrence to it`() = runTest {
        createSyncedSeries(rrule = "FREQ=WEEKLY;BYDAY=TU")

        moveThirdToWednesday()

        val occurrences = march()
        assertEquals(listOf(occ(0), occ(1), berlin(20, 17), berlin(27, 17)), occurrences.map { it.startTs })
        assertEquals("FREQ=WEEKLY;BYDAY=WE", f.eventRow(occurrences[2].eventId)!![Events.RRULE])
    }

    @Test
    fun `dragging this and future of a counted series to another weekday keeps its total`() = runTest {
        createSyncedSeries(rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=4")

        moveThirdToWednesday()

        val occurrences = f.inMarchAndApril()
        assertEquals(listOf(occ(0), occ(1), berlin(20, 17), berlin(27, 17)), occurrences.map { it.startTs })
    }

    @Test
    fun `dragging this and future on a local calendar to another weekday moves every later occurrence to it`() = runTest {
        create(f.draft(calendarId = LOCAL_CAL, title = "Standup", rrule = "FREQ=WEEKLY;BYDAY=TU", startTs = T0, endTs = T0 + HOUR, reminders = listOf(10)))

        moveThirdToWednesday()

        assertEquals(listOf(occ(0), occ(1), berlin(20, 17), berlin(27, 17)), march().map { it.startTs })
    }

    @Test
    fun `this and future is greyed out for a drag to another day the repeat rule can't follow`() = runTest {
        // A rule fixed to 10 o'clock can't move to the dropped 12 o'clock.
        val id = createSyncedSeries(rrule = "FREQ=WEEKLY;BYDAY=TU;BYHOUR=10")
        val before = snapshot(id)

        assertTrue(EditScope.THIS_AND_FUTURE in dropThirdOnWednesday())
        vm.confirmReschedule(EditScope.THIS_AND_FUTURE)
        awaitDragResult()

        assertEquals(DeviceHomeViewModelTestFactory.RESCHEDULE_FAILED_MESSAGE, vm.uiState.value.pendingSnackbarMessage)
        assertEquals(before, snapshot(id))
        assertEquals(listOf(occ(0), occ(1), occ(2), occ(3)), march().map { it.startTs })
    }

    @Test
    fun `both series scopes stay greyed out when the series can't be read for the check`() = runTest {
        createSyncedSeries(rrule = "FREQ=WEEKLY;BYDAY=TU")
        val occurrence = march()[2]
        f.provider.queryFailures[SqliteCalendarProvider.EVENTS] = SqliteCalendarProvider.QueryFailure.THROW

        vm.rescheduleEvent(DisplayEvent.Device(occurrence), LocalDate.of(2024, 3, 20), 17 * 60)
        awaitUntil("the scope check") { vm.uiState.value.pendingDragReschedule?.blockedScopes != null }

        f.provider.queryFailures.clear()
        assertEquals(setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE), vm.uiState.value.pendingDragReschedule!!.blockedScopes)
    }

    // ---- delete routes ----

    @Test
    fun `deleting from the scope sheet removes the chosen occurrences`() = runTest {
        val id = createSyncedSeries()

        vm.requestDeleteDevice(id, SYNCED_CAL, occ(1), T0, isDetachedException = false, isAllDay = false)
        vm.confirmDelete(EditScope.THIS_EVENT)
        awaitUntil("the single delete") { march().size == 3 }
        assertEquals(listOf(occ(0), occ(2), occ(3)), march().map { it.startTs })

        vm.requestDeleteDevice(id, SYNCED_CAL, occ(2), T0, isDetachedException = false, isAllDay = false)
        vm.confirmDelete(EditScope.THIS_AND_FUTURE)
        awaitUntil("the future delete") { march().size == 1 }
        assertEquals(listOf(occ(0)), march().map { it.startTs })

        vm.requestDeleteDevice(id, SYNCED_CAL, occ(0), T0, isDetachedException = false, isAllDay = false)
        vm.confirmDelete(EditScope.ALL_EVENTS)
        awaitUntil("the series delete") { march().isEmpty() }
    }

    @Test
    fun `deleting a modified occurrence from its form removes just that occurrence once this event is chosen`() = runTest {
        val id = createSyncedSeries()
        f.writer.editSingleOccurrence(id, occ(1), f.draft(title = "Standup (moved)", startTs = occ(1) + 2 * HOUR, endTs = occ(1) + 3 * HOUR, reminders = listOf(10)))

        // The form holds the series id, so the delete asks which occurrences.
        assertTrue(vm.handleDeviceEventFormDelete(openForm(id, occurrenceTs = occ(1))).isSuccess)
        val pending = vm.uiState.value.pendingDelete as PendingDelete.Device
        assertEquals(occ(1), pending.occurrenceTs)
        vm.confirmDelete(EditScope.THIS_EVENT)
        awaitUntil("the occurrence delete") { march().size == 3 }

        assertEquals(listOf(occ(0), occ(2), occ(3)), march().map { it.startTs })
    }

    @Test
    fun `deleting a one-off event from its form removes it`() = runTest {
        val id = create()

        assertTrue(vm.handleDeviceEventFormDelete(openForm(id)).isSuccess)

        assertTrue(f.occurrences(DAY_CODE).isEmpty())
    }

    @Test
    fun `deleting a series from its form asks which occurrences`() = runTest {
        val id = createSyncedSeries()

        assertTrue(vm.handleDeviceEventFormDelete(openForm(id, occurrenceTs = occ(2))).isSuccess)

        val pending = vm.uiState.value.pendingDelete as PendingDelete.Device
        assertEquals(id, pending.masterEventId)
        assertEquals(occ(2), pending.occurrenceTs)
        assertEquals(4, march().size)
    }
}
