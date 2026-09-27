package org.onekash.kashcal.domain.reader

import android.provider.CalendarContract
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.DeviceEvent
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.util.DateTimeUtils

/**
 * Tests [DeviceEventReader] over [FakeCalendarProviderRepository]: single events, calendars,
 * attendees, edit loading, quick view by id and export. The reader returns plain provider
 * values; the UI mapping stays in the ViewModels.
 */
class DeviceEventReaderTest {

    private lateinit var repo: FakeCalendarProviderRepository
    private lateinit var reader: DeviceEventReader

    @Before
    fun setup() {
        repo = FakeCalendarProviderRepository()
        reader = DeviceEventReader(repo)
        repo.calendars = listOf(calendar(CAL_ID, owner = OWNER), calendar(OTHER_CAL_ID, owner = ""))
        repo.deviceEvents[MASTER_ID] = event(MASTER_ID, rrule = "FREQ=WEEKLY")
        repo.eventReminders[MASTER_ID] = listOf(10)
        repo.deviceAttendees[MASTER_ID] = listOf(attendee(1L, OWNER), attendee(2L, "guest@example.test"))
    }

    // ---- single event + calendars ----

    @Test
    fun `getDeviceEvent returns the provider row or null`() = runTest {
        assertEquals(MASTER_ID, reader.getDeviceEvent(MASTER_ID)?.id)
        assertNull(reader.getDeviceEvent(999L))
    }

    @Test
    fun `getDeviceCalendars lists every provider calendar`() = runTest {
        assertEquals(listOf(CAL_ID, OTHER_CAL_ID), reader.getDeviceCalendars().map { it.id })
    }

    @Test
    fun `enabled device calendars are empty when the feature is off`() = runTest {
        assertEquals(emptyList<DeviceCalendar>(), reader.getEnabledDeviceCalendars(false, setOf(CAL_ID)))
        assertEquals(0, repo.getDeviceCalendarsCallCount)
    }

    @Test
    fun `enabled device calendars are empty when none are ticked`() = runTest {
        assertEquals(emptyList<DeviceCalendar>(), reader.getEnabledDeviceCalendars(true, emptySet()))
        assertEquals(0, repo.getDeviceCalendarsCallCount)
    }

    @Test
    fun `enabled device calendars keep only the ticked ones`() = runTest {
        assertEquals(listOf(CAL_ID), reader.getEnabledDeviceCalendars(true, setOf(CAL_ID, 77L)).map { it.id })
    }

    @Test
    fun `enabled device calendars fall back to empty when the provider read fails`() = runTest {
        repo.shouldThrowSecurityException = true

        assertEquals(emptyList<DeviceCalendar>(), reader.getEnabledDeviceCalendars(true, setOf(CAL_ID)))
    }

    @Test
    fun `start day code honours all-day events`() = runTest {
        val allDayStart = 1_700_006_400_000L
        repo.deviceEvents[ALL_DAY_ID] = event(ALL_DAY_ID, startTs = allDayStart, isAllDay = true)

        assertEquals(DateTimeUtils.eventTsToDayCode(allDayStart, true), reader.getEventStartDayCode(ALL_DAY_ID))
        assertEquals(
            DateTimeUtils.eventTsToDayCode(START, false),
            reader.getEventStartDayCode(MASTER_ID),
        )
        assertNull(reader.getEventStartDayCode(999L))
    }

    // ---- attendees ----

    @Test
    fun `attendee rows come with the calendar owner email`() = runTest {
        val rows = reader.getAttendeesWithOwner(MASTER_ID, CAL_ID)

        assertEquals(listOf(1L, 2L), rows.attendees.map { it.id })
        assertEquals(OWNER, rows.ownerEmail)
    }

    @Test
    fun `a blank owner or no calendar id gives no owner email`() = runTest {
        assertNull(reader.getAttendeesWithOwner(MASTER_ID, OTHER_CAL_ID).ownerEmail)
        assertNull(reader.getAttendeesWithOwner(MASTER_ID, null).ownerEmail)
    }

    @Test
    fun `an event without attendees returns nothing and skips the owner lookup`() = runTest {
        val rows = reader.getAttendeesWithOwner(ALL_DAY_ID, CAL_ID)

        assertTrue(rows.attendees.isEmpty())
        assertNull(rows.ownerEmail)
    }

    // ---- edit loading ----

    @Test
    fun `edit loading returns the master with its reminders, calendar and attendees`() = runTest {
        val data = reader.getEventForEdit(MASTER_ID, occurrenceTs = null, isAllDay = false)!!

        assertEquals(MASTER_ID, data.event.id)
        assertEquals(listOf(10), data.reminders)
        assertEquals(CAL_ID, data.calendar.id)
        assertEquals(listOf(1L, 2L), data.attendees.map { it.id })
        assertEquals("looks the calendar up by id, not by listing them all", 0, repo.getDeviceCalendarsCallCount)
    }

    @Test
    fun `edit loading an occurrence resolves its exception row`() = runTest {
        repo.deviceEvents[EXCEPTION_ID] = event(EXCEPTION_ID, originalId = MASTER_ID)
        repo.eventReminders[EXCEPTION_ID] = listOf(30)
        repo.exceptionEvents[MASTER_ID to OCCURRENCE_TS] = EXCEPTION_ID

        val data = reader.getEventForEdit(MASTER_ID, occurrenceTs = OCCURRENCE_TS, isAllDay = false)!!

        assertEquals(EXCEPTION_ID, data.event.id)
        assertEquals(listOf(30), data.reminders)
    }

    @Test
    fun `edit loading an occurrence without an exception returns the master`() = runTest {
        val data = reader.getEventForEdit(MASTER_ID, occurrenceTs = OCCURRENCE_TS, isAllDay = false)!!

        assertEquals(MASTER_ID, data.event.id)
    }

    @Test
    fun `edit loading returns null for a missing event or calendar`() = runTest {
        assertNull(reader.getEventForEdit(999L, null, false))
        repo.calendars = emptyList()
        assertNull(reader.getEventForEdit(MASTER_ID, null, false))
    }

    // ---- quick view by id ----

    @Test
    fun `quick view of a one-off event opens at its start`() = runTest {
        repo.deviceEvents[ALL_DAY_ID] = event(ALL_DAY_ID, startTs = 5_000L)

        assertEquals(5_000L, reader.resolveQuickViewOccurrenceStart(ALL_DAY_ID, nowMs = 9_999_999L))
    }

    @Test
    fun `quick view of a series opens at the next instance from now`() = runTest {
        val now = START + 10 * DAY_MS
        repo.instances = listOf(
            instance(MASTER_ID, START),
            instance(MASTER_ID, now + DAY_MS),
            instance(MASTER_ID, now + 8 * DAY_MS),
        )

        assertEquals(now + DAY_MS, reader.resolveQuickViewOccurrenceStart(MASTER_ID, now))
    }

    @Test
    fun `quick view of an ended series or a missing event resolves nothing`() = runTest {
        repo.instances = listOf(instance(MASTER_ID, START))

        assertNull(reader.resolveQuickViewOccurrenceStart(MASTER_ID, START + 30 * DAY_MS))
        assertNull(reader.resolveQuickViewOccurrenceStart(999L, START))
    }

    @Test
    fun `quick view treats an empty stored repeat rule as a one-off event`() = runTest {
        repo.deviceEvents[ALL_DAY_ID] = event(ALL_DAY_ID, startTs = 5_000L, rrule = "")

        assertEquals(5_000L, reader.resolveQuickViewOccurrenceStart(ALL_DAY_ID, nowMs = 9_999_999L))
    }

    // ---- export ----

    @Test
    fun `export read returns master, exceptions and reminders by id`() = runTest {
        repo.deviceEvents[EXCEPTION_ID] = event(EXCEPTION_ID, originalId = MASTER_ID)
        repo.eventReminders[EXCEPTION_ID] = listOf(30)

        val export = reader.getEventWithExceptionsForExport(MASTER_ID)!!

        assertEquals(MASTER_ID, export.master.id)
        assertEquals(listOf(EXCEPTION_ID), export.exceptions.map { it.id })
        assertEquals(mapOf(MASTER_ID to listOf(10), EXCEPTION_ID to listOf(30)), export.remindersById)
        assertNull(reader.getEventWithExceptionsForExport(999L))
    }

    private companion object {
        const val MASTER_ID = 10L
        const val EXCEPTION_ID = 11L
        const val ALL_DAY_ID = 12L
        const val CAL_ID = 5L
        const val OTHER_CAL_ID = 6L
        const val OWNER = "me@example.test"
        const val START = 1_699_999_000_000L
        const val DAY_MS = 86_400_000L
        const val OCCURRENCE_TS = 1_700_603_800_000L

        fun calendar(id: Long, owner: String) = DeviceCalendar(
            id = id,
            displayName = "Cal $id",
            color = 0,
            accountName = "acct",
            accountType = "LOCAL",
            visible = true,
            accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER,
            ownerAccount = owner,
        )

        fun attendee(id: Long, email: String) = DeviceAttendee(
            id = id,
            name = null,
            email = email,
            relationship = CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
            status = CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
        )

        fun event(
            id: Long,
            rrule: String? = null,
            startTs: Long = START,
            isAllDay: Boolean = false,
            originalId: Long? = null,
        ) = DeviceEvent(
            id = id,
            calendarId = CAL_ID,
            title = "Event $id",
            description = null,
            location = null,
            startTs = startTs,
            endTs = startTs + 3_600_000L,
            duration = null,
            isAllDay = isAllDay,
            rrule = rrule,
            rdate = null,
            exdate = null,
            exrule = null,
            timezone = "UTC",
            originalId = originalId,
            originalInstanceTime = if (originalId != null) OCCURRENCE_TS else null,
            status = CalendarContract.Events.STATUS_CONFIRMED,
            availability = 0,
            accessLevel = 0,
            calendarColor = null,
            eventColor = null,
        )

        fun instance(eventId: Long, startTs: Long) = DeviceCalendarInstance(
            instanceId = startTs,
            eventId = eventId,
            title = "Event $eventId",
            description = "",
            location = "",
            startTs = startTs,
            endTs = startTs + 3_600_000L,
            startDay = 0,
            endDay = 0,
            isAllDay = false,
            hasRrule = true,
            rrule = "FREQ=WEEKLY",
            reminders = emptyList(),
            calendarId = CAL_ID,
            calendarDisplayName = "Cal",
            calendarColor = 0,
            eventColor = null,
            status = 1,
            availability = 0,
            hasAlarm = false,
            selfAttendeeStatus = 0,
            isWritable = true,
            originalId = null,
            originalInstanceTime = null,
            timezone = "UTC",
            eventStartTs = START,
        )
    }
}
