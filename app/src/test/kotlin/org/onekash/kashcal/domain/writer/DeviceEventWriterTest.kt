package org.onekash.kashcal.domain.writer

import android.provider.CalendarContract
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.CalendarProviderManager
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceChangeNotifier
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.util.computeDurationString

/**
 * Tests [DeviceEventWriter] over the shared fake provider repository.
 *
 * Covers the per-scope field mapping (a recurring row gets a DURATION and no end time, a one-off
 * row the reverse), the stored tags each write reports, a draft without reminders at every
 * scope, the move (create, then delete the source; a failed delete still succeeds; the
 * organizer row isn't carried), the RSVP self-row match, the ICS import defaults, and the
 * calendar-settings calls. The change signal fires exactly once after each successful write,
 * and never after a failed one, an RSVP that writes nothing, or a calendar-settings call.
 */
class DeviceEventWriterTest {

    private lateinit var repo: FakeCalendarProviderRepository
    private lateinit var manager: CalendarProviderManager
    private lateinit var dataStore: KashCalDataStore
    private lateinit var writer: DeviceEventWriter

    @Before
    fun setup() {
        repo = FakeCalendarProviderRepository()
        manager = deviceChangeNotifier()
        dataStore = mockk {
            coEvery { defaultReminderMinutes } returns MutableStateFlow(20)
            coEvery { defaultAllDayReminder } returns MutableStateFlow(600)
            coEvery { getEnabledDeviceCalendarIds() } returns setOf(CAL_ID, 99L)
            coJustRun { setEnabledDeviceCalendarIds(any()) }
        }
        writer = DeviceEventWriter(repo, manager, dataStore)
    }

    private fun draft(
        rrule: String? = null,
        attendees: List<DeviceAttendee>? = null,
        categories: List<String>? = null,
        calendarId: Long = CAL_ID,
        reminders: List<Int>? = listOf(15, 60),
    ) = DeviceEventDraft(
        calendarId = calendarId,
        title = "Planning",
        description = "agenda",
        location = "Room 4",
        startTs = START,
        endTs = END,
        isAllDay = false,
        rrule = rrule,
        timezone = "America/New_York",
        reminders = reminders,
        availability = 1,
        eventColor = COLOR,
        attendees = attendees,
        categories = categories,
    )

    private fun assertSignalled(times: Int) = verify(exactly = times) { manager.notifyDeviceCalendarChanged() }

    // ---- create ----

    @Test
    fun `creating a one-off event writes its end time and no duration`() = runTest {
        val guests = listOf(guest(0L, "a@example.test"))

        val result = writer.createEvent(draft(attendees = guests, categories = listOf("Work", " ")))

        val created = repo.createdEvents.single()
        assertEquals(created.resultId, result.getOrThrow().eventId)
        assertEquals(CAL_ID, created.calendarId)
        assertEquals("Planning", created.title)
        assertEquals("agenda", created.description)
        assertEquals("Room 4", created.location)
        assertEquals(START, created.startTs)
        assertEquals(END, created.endTs)
        assertNull(created.duration)
        assertNull(created.rrule)
        assertEquals("America/New_York", created.timezone)
        assertEquals(listOf(15, 60), created.reminders)
        assertEquals(1, created.availability)
        assertEquals(COLOR, created.eventColor)
        assertEquals(guests, created.attendees)
        assertEquals(listOf("Work", " "), created.categories)
        assertEquals("the cleaned tag names that were stored", listOf("Work"), result.getOrThrow().savedTags)
        assertSignalled(1)
    }

    @Test
    fun `creating a repeating event writes a duration and no end time`() = runTest {
        writer.createEvent(draft(rrule = "FREQ=DAILY"))

        val created = repo.createdEvents.single()
        assertEquals("FREQ=DAILY", created.rrule)
        assertNull(created.endTs)
        assertEquals(computeDurationString(START, END, false), created.duration)
    }

    @Test
    fun `a failed create returns the failure and does not signal`() = runTest {
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")

        val result = writer.createEvent(draft())

        assertTrue(result.isFailure)
        assertSignalled(0)
    }

    @Test
    fun `creating without tags records no saved tags`() = runTest {
        assertEquals(emptyList<String>(), writer.createEvent(draft()).getOrThrow().savedTags)
    }

    // ---- update ----

    @Test
    fun `updating in place writes the draft to that row`() = runTest {
        val result = writer.updateEvent(EVENT_ID, draft(categories = listOf("Home")))

        val update = repo.updatedEvents.single()
        assertEquals(EVENT_ID, update.eventId)
        assertEquals(EVENT_ID, result.getOrThrow().eventId)
        assertEquals("Planning", update.title)
        assertEquals("agenda", update.description)
        assertEquals("Room 4", update.location)
        assertEquals(START, update.startTs)
        assertEquals(END, update.endTs)
        assertNull(update.duration)
        assertEquals(listOf(15, 60), update.reminders)
        assertEquals("America/New_York", update.timezone)
        assertEquals(listOf("Home"), update.categories)
        assertEquals(listOf("Home"), result.getOrThrow().savedTags)
        assertSignalled(1)
    }

    @Test
    fun `updating a repeating series writes a duration and no end time`() = runTest {
        writer.updateEvent(EVENT_ID, draft(rrule = "FREQ=WEEKLY"))

        val update = repo.updatedEvents.single()
        assertEquals("FREQ=WEEKLY", update.rrule)
        assertNull(update.endTs)
        assertEquals(computeDurationString(START, END, false), update.duration)
    }

    @Test
    fun `an empty stored repeat rule is written as a one-off event`() = runTest {
        // The provider treats an empty RRULE as not recurring, so the row needs its DTEND;
        // a DURATION would be dropped and leave the old end time.
        writer.updateEvent(EVENT_ID, draft(rrule = ""))

        val update = repo.updatedEvents.single()
        assertEquals("", update.rrule)
        assertEquals(END, update.endTs)
        assertNull(update.duration)
    }

    @Test
    fun `leaving guests and tags unmanaged passes null for both`() = runTest {
        writer.updateEvent(EVENT_ID, draft())

        val update = repo.updatedEvents.single()
        assertNull(update.attendees)
        assertNull(update.categories)
    }

    // ---- one occurrence ----

    @Test
    fun `editing an occurrence with an existing exception updates that exception`() = runTest {
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = EXCEPTION_ID

        val result = writer.editSingleOccurrence(MASTER_ID, OCCURRENCE_TS, draft(rrule = "FREQ=WEEKLY", categories = listOf("x")))

        assertTrue(repo.createdExceptions.isEmpty())
        val update = repo.updatedEvents.single()
        assertEquals(EXCEPTION_ID, update.eventId)
        assertNull(update.rrule)
        assertNull(update.duration)
        assertEquals(END, update.endTs)
        assertNull("per-occurrence edits leave guests alone", update.attendees)
        assertNull("per-occurrence edits leave tags alone", update.categories)
        assertEquals(EXCEPTION_ID, result.getOrThrow().eventId)
        assertEquals(emptyList<String>(), result.getOrThrow().savedTags)
        assertSignalled(1)
    }

    @Test
    fun `editing an occurrence without an exception creates one`() = runTest {
        val result = writer.editSingleOccurrence(MASTER_ID, OCCURRENCE_TS, draft(rrule = "FREQ=WEEKLY"))

        assertTrue(repo.updatedEvents.isEmpty())
        val created = repo.createdExceptions.single()
        assertEquals(CAL_ID, created.calendarId)
        assertEquals(MASTER_ID, created.masterEventId)
        assertEquals(OCCURRENCE_TS, created.originalInstanceTime)
        assertEquals(START, created.startTs)
        assertEquals(END, created.endTs)
        assertEquals("Planning", created.title)
        assertEquals("America/New_York", created.timezone)
        assertEquals(listOf(15, 60), created.reminders)
        assertEquals(1, created.availability)
        assertEquals(COLOR, created.eventColor)
        assertEquals(created.resultId, result.getOrThrow().eventId)
        assertSignalled(1)
    }

    @Test
    fun `a failed occurrence edit does not signal`() = runTest {
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")

        assertTrue(writer.editSingleOccurrence(MASTER_ID, OCCURRENCE_TS, draft()).isFailure)
        assertSignalled(0)
    }

    // ---- this and future ----

    @Test
    fun `editing this and future splits the series with a duration`() = runTest {
        // A later occurrence than the draft's start, so the split point can't be confused with it.
        val splitAt = OCCURRENCE_TS + 7L * 86_400_000L
        val result = writer.editThisAndFuture(MASTER_ID, splitAt, draft(rrule = "FREQ=WEEKLY", categories = listOf("x")))

        val split = repo.editedFutureSeries.single()
        assertEquals(MASTER_ID, split.masterEventId)
        assertEquals(splitAt, split.fromTimeMs)
        assertFalse(split.isAllDay)
        assertEquals(CAL_ID, split.calendarId)
        assertEquals("FREQ=WEEKLY", split.rrule)
        assertEquals(START, split.startTs)
        assertNull(split.endTs)
        assertEquals(computeDurationString(START, END, false), split.duration)
        assertEquals("agenda", split.description)
        assertEquals("Room 4", split.location)
        assertEquals("America/New_York", split.timezone)
        assertEquals(listOf(15, 60), split.reminders)
        assertEquals(split.newEventId, result.getOrThrow().eventId)
        assertEquals("the tags the user edited go to the future series", listOf("x"), split.categories)
        assertEquals(listOf("x"), result.getOrThrow().savedTags)
        assertSignalled(1)
    }

    @Test
    fun `editing this and future without edited tags asks the repository to keep the series tags`() = runTest {
        val splitAt = OCCURRENCE_TS + 7L * 86_400_000L
        val result = writer.editThisAndFuture(MASTER_ID, splitAt, draft(rrule = "FREQ=WEEKLY", categories = null))

        assertNull(repo.editedFutureSeries.single().categories)
        assertEquals(emptyList<String>(), result.getOrThrow().savedTags)
    }

    // ---- a draft without reminders keeps the stored ones ----

    @Test
    fun `a draft without reminders reaches every edit scope as null`() = runTest {
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = EXCEPTION_ID
        writer.updateEvent(EVENT_ID, draft(reminders = null))
        writer.editSingleOccurrence(MASTER_ID, OCCURRENCE_TS, draft(rrule = "FREQ=WEEKLY", reminders = null))
        repo.exceptionEvents.clear()
        writer.editSingleOccurrence(MASTER_ID, OCCURRENCE_TS, draft(rrule = "FREQ=WEEKLY", reminders = null))
        writer.editThisAndFuture(MASTER_ID, OCCURRENCE_TS + 7L * 86_400_000L, draft(rrule = "FREQ=WEEKLY", reminders = null))

        assertEquals(listOf<List<Int>?>(null, null), repo.updatedEvents.map { it.reminders })
        assertNull(repo.createdExceptions.single().reminders)
        assertNull(repo.editedFutureSeries.single().reminders)
    }

    @Test
    fun `a new event from a draft without reminders gets none`() = runTest {
        writer.createEvent(draft(reminders = null))

        assertEquals(emptyList<Int>(), repo.createdEvents.single().reminders)
    }

    @Test
    fun `moving with a draft without reminders gives the target copy none`() = runTest {
        writer.moveEventToCalendar(source(), draft(calendarId = TARGET_CAL_ID, reminders = null))

        assertEquals(emptyList<Int>(), repo.createdEvents.single().reminders)
    }

    // ---- move ----

    @Test
    fun `moving creates in the target, carries guests without the organizer, then deletes the source`() = runTest {
        repo.deviceAttendees[EVENT_ID] = listOf(
            guest(1L, "owner@example.test", CalendarContract.Attendees.RELATIONSHIP_ORGANIZER),
            guest(2L, "guest@example.test"),
        )

        val result = writer.moveEventToCalendar(source(), draft(calendarId = TARGET_CAL_ID))

        val created = repo.createdEvents.single()
        assertEquals(TARGET_CAL_ID, created.calendarId)
        assertEquals(listOf("guest@example.test"), created.attendees!!.map { it.email })
        assertEquals("unedited tags come from the source", listOf("Source tag"), created.categories)
        assertEquals(listOf(EVENT_ID), repo.deletedEventIds)
        assertEquals(created.resultId, result.getOrThrow().eventId)
        assertEquals(listOf("Source tag"), result.getOrThrow().savedTags)
        assertSignalled(1)
    }

    @Test
    fun `moving with edited guests and tags uses the edited sets`() = runTest {
        repo.deviceAttendees[EVENT_ID] = listOf(guest(2L, "old@example.test"))
        val edited = listOf(guest(0L, "new@example.test"))

        writer.moveEventToCalendar(source(), draft(calendarId = TARGET_CAL_ID, attendees = edited, categories = listOf("New")))

        val created = repo.createdEvents.single()
        assertEquals(listOf("new@example.test"), created.attendees!!.map { it.email })
        assertEquals(listOf("New"), created.categories)
    }

    @Test
    fun `moving an event whose only row is the organizer carries no guests`() = runTest {
        repo.deviceAttendees[EVENT_ID] =
            listOf(guest(1L, "owner@example.test", CalendarContract.Attendees.RELATIONSHIP_ORGANIZER))

        writer.moveEventToCalendar(source(), draft(calendarId = TARGET_CAL_ID))

        assertNull(repo.createdEvents.single().attendees)
    }

    @Test
    fun `a move whose source delete fails still succeeds`() = runTest {
        repo.failDelete = true

        val result = writer.moveEventToCalendar(source(), draft(calendarId = TARGET_CAL_ID))

        assertTrue(result.isSuccess)
        assertEquals(1, repo.createdEvents.size)
        assertSignalled(1)
    }

    @Test
    fun `a move whose create fails deletes nothing`() = runTest {
        repo.failCreateOnCall = 1

        val result = writer.moveEventToCalendar(source(), draft(calendarId = TARGET_CAL_ID))

        assertTrue(result.isFailure)
        assertTrue(repo.deletedEventIds.isEmpty())
        assertSignalled(0)
    }

    @Test
    fun `moving a repeating event writes a duration and no end time in the target`() = runTest {
        writer.moveEventToCalendar(source(), draft(rrule = "FREQ=DAILY", calendarId = TARGET_CAL_ID))

        val created = repo.createdEvents.single()
        assertEquals("FREQ=DAILY", created.rrule)
        assertNull(created.endTs)
        assertEquals(computeDurationString(START, END, false), created.duration)
    }

    @Test
    fun `a failed update returns the failure and does not signal`() = runTest {
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")

        assertTrue(writer.updateEvent(EVENT_ID, draft()).isFailure)
        assertSignalled(0)
    }

    // ---- deletes ----

    @Test
    fun `deletes delegate per scope and signal once each`() = runTest {
        assertTrue(writer.deleteEvent(EVENT_ID).isSuccess)
        assertTrue(writer.deleteSingleOccurrence(MASTER_ID, OCCURRENCE_TS, isAllDay = true).isSuccess)
        assertTrue(writer.deleteThisAndFuture(MASTER_ID, OCCURRENCE_TS, isAllDay = false).isSuccess)

        assertEquals(listOf(EVENT_ID), repo.deletedEventIds)
        assertEquals(
            listOf(FakeCalendarProviderRepository.DeletedOccurrence(MASTER_ID, OCCURRENCE_TS, true)),
            repo.deletedOccurrences,
        )
        assertEquals(
            listOf(FakeCalendarProviderRepository.DeletedFutureOccurrence(MASTER_ID, OCCURRENCE_TS, false)),
            repo.deletedFutureOccurrences,
        )
        assertSignalled(3)
    }

    @Test
    fun `failed deletes do not signal`() = runTest {
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")

        assertTrue(writer.deleteEvent(EVENT_ID).isFailure)
        assertTrue(writer.deleteSingleOccurrence(MASTER_ID, OCCURRENCE_TS, false).isFailure)
        assertTrue(writer.deleteThisAndFuture(MASTER_ID, OCCURRENCE_TS, false).isFailure)
        assertSignalled(0)
    }

    // ---- RSVP ----

    @Test
    fun `RSVP updates only the owner's row`() = runTest {
        repo.calendars = listOf(calendar(owner = "Me@Example.test"))
        repo.deviceAttendees[EVENT_ID] = listOf(guest(1L, "other@example.test"), guest(2L, "me@example.test"))

        val result = writer.replyRsvp(EVENT_ID, CAL_ID, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED)

        assertEquals(true, result.getOrThrow())
        assertEquals(
            listOf(
                FakeCalendarProviderRepository.SelfRsvpUpdate(
                    EVENT_ID, 2L, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
                )
            ),
            repo.selfRsvpUpdates,
        )
        assertSignalled(1)
    }

    @Test
    fun `RSVP without an owner address, calendar or self row writes nothing`() = runTest {
        repo.deviceAttendees[EVENT_ID] = listOf(guest(1L, "other@example.test"))

        assertEquals(false, writer.replyRsvp(EVENT_ID, CAL_ID, 1).getOrThrow())
        repo.calendars = listOf(calendar(owner = ""))
        assertEquals(false, writer.replyRsvp(EVENT_ID, CAL_ID, 1).getOrThrow())
        repo.calendars = listOf(calendar(owner = "me@example.test"))
        assertEquals(false, writer.replyRsvp(EVENT_ID, CAL_ID, 1).getOrThrow())

        assertTrue(repo.selfRsvpUpdates.isEmpty())
        assertSignalled(0)
    }

    @Test
    fun `a failed RSVP write returns the failure and does not signal`() = runTest {
        repo.calendars = listOf(calendar(owner = "me@example.test"))
        repo.deviceAttendees[EVENT_ID] = listOf(guest(2L, "me@example.test"))
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")

        assertTrue(writer.replyRsvp(EVENT_ID, CAL_ID, 1).isFailure)
        assertSignalled(0)
    }

    // ---- ICS import ----

    @Test
    fun `ICS import applies the stored default reminders and signals once`() = runTest {
        val count = writer.importIcsEvents(
            listOf(imported("timed"), imported("all-day", isAllDay = true)),
            calendarId = CAL_ID,
        )

        assertEquals(2, count)
        assertEquals(listOf(listOf(20), listOf(600)), repo.createdEvents.map { it.reminders })
        assertSignalled(1)
    }

    @Test
    fun `an ICS import that creates nothing does not signal`() = runTest {
        repo.writeFailure = CalendarError.DeviceCalendar.WriteFailed("boom")

        assertEquals(0, writer.importIcsEvents(listOf(imported("x")), CAL_ID))
        assertSignalled(0)
    }

    // ---- calendar settings ----

    @Test
    fun `pruning stale calendar ids rewrites the stored set without signalling`() = runTest {
        repo.calendars = listOf(calendar(owner = ""))

        writer.pruneStaleCalendarIds()

        coVerify { dataStore.setEnabledDeviceCalendarIds(setOf(CAL_ID)) }
        assertSignalled(0)
    }

    @Test
    fun `making a calendar visible delegates without signalling`() = runTest {
        writer.ensureCalendarVisible(CAL_ID)

        assertEquals(listOf(CAL_ID), repo.ensureCalendarVisibleCalls)
        assertSignalled(0)
    }

    private companion object {
        const val CAL_ID = 5L
        const val TARGET_CAL_ID = 6L
        const val EVENT_ID = 20L
        const val MASTER_ID = 10L
        const val EXCEPTION_ID = 11L
        const val START = 1_700_038_800_000L
        const val END = 1_700_044_200_000L
        const val OCCURRENCE_TS = 1_700_038_800_000L
        const val COLOR = 0xFF445566.toInt()

        fun guest(
            id: Long,
            email: String,
            relationship: Int = CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
        ) = DeviceAttendee(
            id = id,
            name = null,
            email = email,
            relationship = relationship,
            status = CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
        )

        fun calendar(owner: String) = DeviceCalendar(
            id = CAL_ID,
            displayName = "Cal",
            color = 0,
            accountName = "acct",
            accountType = "LOCAL",
            visible = true,
            accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER,
            ownerAccount = owner,
        )

        fun source() = DeviceEvent(
            id = EVENT_ID,
            calendarId = CAL_ID,
            title = "Planning",
            description = null,
            location = null,
            startTs = START,
            endTs = END,
            duration = null,
            isAllDay = false,
            rrule = null,
            rdate = null,
            exdate = null,
            exrule = null,
            timezone = "UTC",
            originalId = null,
            originalInstanceTime = null,
            status = CalendarContract.Events.STATUS_CONFIRMED,
            availability = 0,
            accessLevel = 0,
            calendarColor = null,
            eventColor = null,
            categories = listOf("Source tag"),
        )

        fun imported(uid: String, isAllDay: Boolean = false) = Event(
            id = 0L,
            calendarId = 0L,
            uid = uid,
            title = uid,
            startTs = 0L,
            endTs = 3_600_000L,
            dtstamp = 0L,
            isAllDay = isAllDay,
        )
    }
}
