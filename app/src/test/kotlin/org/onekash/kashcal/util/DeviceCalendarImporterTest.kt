package org.onekash.kashcal.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.db.entity.Event

class DeviceCalendarImporterTest {

    private lateinit var fakeRepo: FakeCalendarProviderRepository

    /**
     * Captures all createEvent parameters for assertion.
     * Delegates read operations to a no-op stub since only writes are tested.
     */
    private class CapturingRepo : CalendarProviderRepository {
        var callCount = 0
        var failOnCall: Int = -1 // -1 = never fail
        private var nextId = 100L

        // Captured params from the last call
        var calendarId: Long = 0L
        var title: String = ""
        var description: String? = null
        var location: String? = null
        var startTs: Long = 0L
        var endTs: Long? = null
        var isAllDay: Boolean = false
        var rrule: String? = null
        var duration: String? = null
        var timezone: String = ""
        var reminders: List<Int> = emptyList()

        override suspend fun createEvent(
            calendarId: Long, title: String, description: String?,
            location: String?, startTs: Long, endTs: Long?,
            isAllDay: Boolean, rrule: String?, duration: String?,
            timezone: String, reminders: List<Int>,
            availability: Int, eventColor: Int?
        ): Result<Long> {
            callCount++
            if (callCount == failOnCall) {
                return Result.failure(RuntimeException("Write failed"))
            }
            this.calendarId = calendarId
            this.title = title
            this.description = description
            this.location = location
            this.startTs = startTs
            this.endTs = endTs
            this.isAllDay = isAllDay
            this.rrule = rrule
            this.duration = duration
            this.timezone = timezone
            this.reminders = reminders
            return Result.success(nextId++)
        }

        // Stub out unused interface methods
        override suspend fun getDeviceCalendars(): List<DeviceCalendar> = emptyList()
        override suspend fun getInstancesForDayRange(startDayCode: Int, endDayCode: Int, enabledCalendarIds: Set<Long>, hideDeclined: Boolean): List<DeviceCalendarInstance> = emptyList()
        override suspend fun searchInstances(query: String, startDayCode: Int, endDayCode: Int, enabledCalendarIds: Set<Long>, hideDeclined: Boolean): List<DeviceCalendarInstance> = emptyList()
        override suspend fun pruneStaleCalendarIds(dataStore: org.onekash.kashcal.data.preferences.KashCalDataStore) {}
        override suspend fun ensureCalendarVisible(calendarId: Long) {}
        override suspend fun updateEvent(eventId: Long, title: String, description: String?, location: String?, startTs: Long, endTs: Long?, isAllDay: Boolean, rrule: String?, duration: String?, timezone: String, reminders: List<Int>, availability: Int, eventColor: Int?): Result<Unit> = Result.success(Unit)
        override suspend fun deleteEvent(eventId: Long): Result<Unit> = Result.success(Unit)
        override suspend fun createException(calendarId: Long, masterEventId: Long, originalInstanceTime: Long, title: String, description: String?, location: String?, startTs: Long, endTs: Long, isAllDay: Boolean, timezone: String, reminders: List<Int>, availability: Int, eventColor: Int?): Result<Long> = Result.success(0L)
        override suspend fun deleteSingleOccurrence(masterEventId: Long, originalInstanceTime: Long, isAllDay: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun deleteThisAndFuture(masterEventId: Long, fromTimeMs: Long, isAllDay: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun moveEventToCalendar(eventId: Long, newCalendarId: Long): Result<Unit> = Result.success(Unit)
        override suspend fun getDeviceEvent(eventId: Long) = null
        override suspend fun getReminders(eventId: Long): List<Int> = emptyList()
        override suspend fun findExceptionEventId(masterEventId: Long, originalInstanceTime: Long, isAllDay: Boolean): Long? = null
        override suspend fun getMaxReminders(calendarId: Long): Int = 5
        override suspend fun getNextUpcomingReminder(enabledCalendarIds: Set<Long>, afterMs: Long) = null
    }

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

        val repo = CapturingRepo().apply { failOnCall = 2 }

        val count = importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals(2, count)
    }

    @Test
    fun `recurring event passes duration and null endTs`() = runTest {
        val events = listOf(
            makeEvent(rrule = "FREQ=WEEKLY;BYDAY=MO", duration = "PT1H")
        )

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals(null, repo.endTs)
        assertEquals("PT1H", repo.duration)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", repo.rrule)
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

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals("PT1H30M", repo.duration)
    }

    @Test
    fun `non-recurring event passes endTs and null duration`() = runTest {
        val events = listOf(makeEvent(rrule = null, duration = null))

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals(1704070800000L, repo.endTs)
        assertEquals(null, repo.duration)
    }

    @Test
    fun `reminders converted via isoRemindersToMinutes`() = runTest {
        val events = listOf(
            makeEvent(reminders = listOf("-PT15M", "-PT1H"))
        )

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals(listOf(15, 60), repo.reminders)
    }

    @Test
    fun `event without reminders passes empty list`() = runTest {
        val events = listOf(makeEvent(reminders = null))

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals(emptyList<Int>(), repo.reminders)
    }

    @Test
    fun `timezone falls back to system default`() = runTest {
        val events = listOf(makeEvent(timezone = null))

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals(java.util.TimeZone.getDefault().id, repo.timezone)
    }

    @Test
    fun `event timezone is used when present`() = runTest {
        val events = listOf(makeEvent(timezone = "America/New_York"))

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals("America/New_York", repo.timezone)
    }

    @Test
    fun `all-day event passes isAllDay true`() = runTest {
        val events = listOf(makeEvent(isAllDay = true))

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals(true, repo.isAllDay)
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

        val repo = CapturingRepo()
        importEventsToDeviceCalendar(events, 5L, repo)

        assertEquals(null, repo.description)
        assertEquals(null, repo.location)
    }
}
