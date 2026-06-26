package org.onekash.kashcal.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Event

class DeviceCalendarImporterTest {

    private lateinit var fakeRepo: FakeCalendarProviderRepository

    @Before
    fun setup() {
        fakeRepo = FakeCalendarProviderRepository()
    }

    private fun makeEvent(
        title: String = "Test Event",
        description: String? = null,
        location: String? = null,
        startTs: Long = 1704067200000L, // Jan 1, 2024 00:00 UTC
        endTs: Long = 1704070800000L,   // Jan 1, 2024 01:00 UTC
        isAllDay: Boolean = false,
        rrule: String? = null,
        duration: String? = null,
        timezone: String? = null,
        reminders: List<String>? = null
    ): Event = Event(
        id = 0,
        uid = "test-uid",
        calendarId = 1L,
        title = title,
        description = description,
        location = location,
        startTs = startTs,
        endTs = endTs,
        isAllDay = isAllDay,
        rrule = rrule,
        duration = duration,
        timezone = timezone,
        reminders = reminders,
        dtstamp = startTs
    )

    @Test
    fun `imports single event successfully`() = runTest {
        val events = listOf(makeEvent(title = "Meeting"))

        val count = importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals(1, count)
        assertEquals(1, fakeRepo.createdEvents.size)
        assertEquals(5L, fakeRepo.createdEvents[0].calendarId)
        assertEquals("Meeting", fakeRepo.createdEvents[0].title)
    }

    @Test
    fun `imports multiple events and returns count`() = runTest {
        val events = listOf(
            makeEvent(title = "Event 1"),
            makeEvent(title = "Event 2"),
            makeEvent(title = "Event 3")
        )

        val count = importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals(3, count)
        assertEquals(3, fakeRepo.createdEvents.size)
    }

    @Test
    fun `failed event is skipped and continues to next`() = runTest {
        val events = listOf(
            makeEvent(title = "Event 1"),
            makeEvent(title = "Event 2"),
            makeEvent(title = "Event 3")
        )

        fakeRepo.failCreateOnCall = 2

        val count = importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals(2, count)
    }

    @Test
    fun `recurring event passes duration and null endTs`() = runTest {
        val events = listOf(
            makeEvent(rrule = "FREQ=WEEKLY;BYDAY=MO", duration = "PT1H")
        )

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        val created = fakeRepo.createdEvents.single()
        assertEquals(null, created.endTs)
        assertEquals("PT1H", created.duration)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", created.rrule)
    }

    @Test
    fun `recurring event without explicit duration computes it`() = runTest {
        val events = listOf(
            makeEvent(
                rrule = "FREQ=DAILY",
                duration = null,
                startTs = 1704067200000L,
                endTs = 1704067200000L + 90 * 60 * 1000 // 1h30m later
            )
        )

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals("PT1H30M", fakeRepo.createdEvents.single().duration)
    }

    @Test
    fun `non-recurring event passes endTs and null duration`() = runTest {
        val events = listOf(makeEvent(rrule = null, duration = null))

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        val created = fakeRepo.createdEvents.single()
        assertEquals(1704070800000L, created.endTs)
        assertEquals(null, created.duration)
    }

    @Test
    fun `reminders converted via isoRemindersToMinutes`() = runTest {
        val events = listOf(
            makeEvent(reminders = listOf("-PT15M", "-PT1H"))
        )

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals(listOf(15, 60), fakeRepo.createdEvents.single().reminders)
    }

    @Test
    fun `event without reminders passes empty list when no default configured`() = runTest {
        // Default args (REMINDER_OFF) → no fallback applied. Caller didn't
        // pass user's preference so behavior matches the old contract.
        val events = listOf(makeEvent(reminders = null))

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals(emptyList<Int>(), fakeRepo.createdEvents.single().reminders)
    }

    @Test
    fun `timed event without reminders applies user's default timed reminder`() = runTest {
        // Caller (a ViewModel) passed the user's configured default in.
        val events = listOf(makeEvent(isAllDay = false, reminders = null))

        importEventsToDeviceCalendar(
            events = events,
            calendarId = 5L,
            repo = fakeRepo,
            defaultTimedReminderMinutes = 15,
            defaultAllDayReminderMinutes = 540
        )

        assertEquals(listOf(15), fakeRepo.createdEvents.single().reminders)
    }

    @Test
    fun `all-day event without reminders applies user's default all-day reminder`() = runTest {
        val events = listOf(makeEvent(isAllDay = true, reminders = null))

        importEventsToDeviceCalendar(
            events = events,
            calendarId = 5L,
            repo = fakeRepo,
            defaultTimedReminderMinutes = 15,
            defaultAllDayReminderMinutes = 540
        )

        assertEquals(listOf(540), fakeRepo.createdEvents.single().reminders)
    }

    @Test
    fun `event with ICS reminders preserves them and ignores default`() = runTest {
        val events = listOf(
            makeEvent(reminders = listOf("-PT30M", "-PT1H"))
        )

        importEventsToDeviceCalendar(
            events = events,
            calendarId = 5L,
            repo = fakeRepo,
            defaultTimedReminderMinutes = 15,
            defaultAllDayReminderMinutes = 540
        )

        // The ICS file's VALARMs win — default is not appended.
        assertEquals(listOf(30, 60), fakeRepo.createdEvents.single().reminders)
    }

    @Test
    fun `event without reminders skips default when user set REMINDER_OFF`() = runTest {
        val events = listOf(makeEvent(reminders = null))

        importEventsToDeviceCalendar(
            events = events,
            calendarId = 5L,
            repo = fakeRepo,
            defaultTimedReminderMinutes =
                org.onekash.kashcal.data.preferences.KashCalDataStore.REMINDER_OFF,
            defaultAllDayReminderMinutes =
                org.onekash.kashcal.data.preferences.KashCalDataStore.REMINDER_OFF
        )

        assertEquals(emptyList<Int>(), fakeRepo.createdEvents.single().reminders)
    }

    @Test
    fun `timezone falls back to system default`() = runTest {
        val events = listOf(makeEvent(timezone = null))

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals(java.util.TimeZone.getDefault().id, fakeRepo.createdEvents.single().timezone)
    }

    @Test
    fun `event timezone is used when present`() = runTest {
        val events = listOf(makeEvent(timezone = "America/New_York"))

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals("America/New_York", fakeRepo.createdEvents.single().timezone)
    }

    @Test
    fun `all-day event passes isAllDay true`() = runTest {
        val events = listOf(makeEvent(isAllDay = true))

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        assertEquals(true, fakeRepo.createdEvents.single().isAllDay)
    }

    @Test
    fun `empty events list returns zero`() = runTest {
        val count = importEventsToDeviceCalendar(emptyList(), 5L, fakeRepo)

        assertEquals(0, count)
        assertEquals(0, fakeRepo.createdEvents.size)
    }

    @Test
    fun `blank description and location passed as null`() = runTest {
        val events = listOf(makeEvent(description = "  ", location = ""))

        importEventsToDeviceCalendar(events, 5L, fakeRepo)

        val created = fakeRepo.createdEvents.single()
        assertEquals(null, created.description)
        assertEquals(null, created.location)
    }
}
